package com.example.codemap.CodeMap.data

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import java.io.File
import com.intellij.openapi.ui.Messages
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// --- Структуры данных ---

data class ParameterNode(val name: String, val type: String, val defaultValue: String?)

data class CallNode(
    val targetName: String,
    val targetFile: String,
    val targetId: String,
    var targetType: String,
    val callOrder: Int,
    val lineNumber: Int,
    var nestedCalls: List<CallNode> = emptyList(),
    var branches: List<BranchNode> = emptyList(),
    val isRecursive: Boolean = false,
    val isSystemCall: Boolean = false,
    val isImplicit: Boolean = false
)

data class IntentLink(val from: String, val to: String, val type: String, val lineNumber: Int)
data class InboundCallNode(val fileName: String, val functionName: String, val functionId: String)

data class BranchVariant(
    val id: String? = null,
    val case: String? = null,
    var nestedCalls: List<CallNode> = emptyList(),
    var branches: List<BranchNode> = emptyList()
)

data class BranchNode(val type: String, val condition: String?, val lineNumber: Int, val branches: List<BranchVariant>)

data class FunctionNode(
    val id: String,
    val name: String,
    var type: String,
    val signature: String,
    val parameters: List<ParameterNode>,
    val hasReturn: Boolean,
    val returnType: String?,
    val isEntryPoint: Boolean,
    val lineStart: Int,
    val lineEnd: Int,
    val calls: MutableList<CallNode>,
    val branches: MutableList<BranchNode>,
    val inboundCalls: MutableList<InboundCallNode> = mutableListOf()
)

data class FileNode(val fileName: String, val filePath: String, val packageName: String, val functions: List<FunctionNode>)

data class Metadata(val parsedAt: String, val project: String, val totalFiles: Int, val totalFunctions: Int, val totalCalls: Int)

data class PSIData(
    val metadata: Metadata,
    val files: List<FileNode>,
    val intentLinks: List<IntentLink> = emptyList()
)

class CodeMapCore(private val project: Project) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val allFunctionsMap = mutableMapOf<String, FunctionNode>()
    private val classImplMap = mutableMapOf<String, String>()
    private val reactivePoints = mutableMapOf<String, MutableList<ReactivePoint>>()
    private val intentLinks = mutableListOf<IntentLink>()
    private var globalCallCount = 0

    data class ReactivePoint(val fileName: String, val functionName: String, val isEmitter: Boolean, val streamName: String)
    data class IntentInfo(val targetClass: String? = null, val action: String? = null)

    fun refreshDatabase(onProgress: (Int) -> Unit, onFinished: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val allFiles = mutableListOf<VirtualFile>()
            ApplicationManager.getApplication().runReadAction {
                val basePath = project.basePath ?: return@runReadAction
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                if (baseDir != null) collectFiles(baseDir, allFiles)
            }

            allFunctionsMap.clear()
            classImplMap.clear()
            reactivePoints.clear()
            intentLinks.clear()
            globalCallCount = 0
            val fileNodes = mutableListOf<FileNode>()

            if (allFiles.isEmpty()) {
                onProgress(100)
                ApplicationManager.getApplication().invokeLater { onFinished() }
                return@executeOnPooledThread
            }

            allFiles.forEachIndexed { index, vFile ->
                ApplicationManager.getApplication().runReadAction {
                    preScanImplicitLinks(vFile)
                    val node = scanFile(vFile)
                    if (node != null) {
                        fileNodes.add(node)
                        node.functions.forEach { allFunctionsMap[it.id] = it }
                    }
                }
                onProgress(((index + 1).toFloat() / allFiles.size * 40).toInt())
            }

            fileNodes.forEachIndexed { index, fileNode ->
                fileNode.functions.forEach { fn ->
                    injectReactiveCalls(fn)
                    val hasAsync = fn.calls.any { it.targetType == "ASYNC" }
                    val hasUI = fn.calls.any { it.targetType == "UI" }
                    fn.type = when {
                        fn.isEntryPoint -> "ACTION"
                        hasAsync -> "ASYNC"
                        hasUI -> "UI"
                        fn.hasReturn -> "REQUEST"
                        else -> "DATA_FLOW"
                    }
                    linkRecursive(fn)
                }
                onProgress(40 + ((index + 1).toFloat() / fileNodes.size * 50).toInt())
            }

            fileNodes.forEach { fileNode ->
                fileNode.functions.forEach { fn ->
                    fn.calls.forEach { call ->
                        allFunctionsMap[call.targetId]?.inboundCalls?.add(
                            InboundCallNode(fileNode.fileName, fn.name, fn.id)
                        )
                    }
                }
            }

            val dataFolder = File(project.basePath, ".codemap")
            if (!dataFolder.exists()) dataFolder.mkdirs()
            val finalOutput = PSIData(
                metadata = Metadata(ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT), project.name, fileNodes.size, allFunctionsMap.size, globalCallCount),
                files = fileNodes,
                intentLinks = intentLinks
            )
            File(dataFolder, "PSI.json").writeText(gson.toJson(finalOutput))

            onProgress(100)
            ApplicationManager.getApplication().invokeLater { onFinished() }
        }
    }

    private fun preScanImplicitLinks(vFile: VirtualFile) {
        val psi = PsiManager.getInstance(project).findFile(vFile) as? KtFile ?: return
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                classOrObject.superTypeListEntries.forEach { entry ->
                    val interfaceName = entry.typeReference?.text ?: ""
                    if (interfaceName.isNotEmpty()) classImplMap[interfaceName] = classOrObject.name ?: ""
                }
                super.visitClassOrObject(classOrObject)
            }
        })
    }

    private fun injectReactiveCalls(fn: FunctionNode) {
        reactivePoints.forEach { (_, points) ->
            val isCurrentEmitter = points.any { it.isEmitter && it.functionName == fn.name && it.fileName == fn.id.substringBefore(".") }
            if (isCurrentEmitter) {
                points.filter { !it.isEmitter }.forEach { collector ->
                    fn.calls.add(CallNode(collector.functionName, collector.fileName, "${collector.fileName}.${collector.functionName}", "STATE", 0, 0, isImplicit = true))
                    globalCallCount++
                }
            }
        }
    }

    private fun linkRecursive(fn: FunctionNode) {
        fn.calls.forEach { linkCallDetails(it, 0, fn.id) }
        fn.branches.forEach { linkBranchRecursive(it, fn.id) }
    }

    private fun linkBranchRecursive(branch: BranchNode, parentId: String) {
        branch.branches.forEach { variant ->
            variant.nestedCalls.forEach { linkCallDetails(it, 0, parentId) }
            variant.branches.forEach { linkBranchRecursive(it, parentId) }
        }
    }

    private fun linkCallDetails(call: CallNode, depth: Int, parentId: String) {
        val targetId = if (allFunctionsMap.containsKey(call.targetId)) call.targetId else {
            val implName = classImplMap[call.targetName]
            if (implName != null) "${call.targetFile.substringBeforeLast(".")}.$implName" else call.targetId
        }
        val target = allFunctionsMap[targetId]
        if (target != null) {
            if (call.targetType == "INTERNAL") call.targetType = target.type
            if (depth < 1 && targetId != parentId && call.nestedCalls.isEmpty()) {
                call.nestedCalls = target.calls.map { it.copy() }
                call.branches = target.branches.map { it.copy() }
            }
        }
        call.nestedCalls.forEach { linkCallDetails(it, depth + 1, parentId) }
        call.branches.forEach { linkBranchRecursive(it, parentId) }
    }

    private fun collectFiles(v: VirtualFile, res: MutableList<VirtualFile>) {
        if (v.isDirectory) {
            if (!listOf("build", "test", "androidTest").contains(v.name) && !v.name.startsWith(".")) v.children.forEach { collectFiles(it, res) }
        } else if (v.extension in listOf("kt", "java")) res.add(v)
    }

    private fun scanFile(vFile: VirtualFile): FileNode? {
        val psi = PsiManager.getInstance(project).findFile(vFile) ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)
        val functions = mutableListOf<FunctionNode>()
        if (psi is KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(f: KtNamedFunction) { functions.add(parseKtCallable(f, vFile.name, doc)) }
            })
        }
        return FileNode(vFile.name, vFile.path, (psi as? KtFile)?.packageFqName?.asString() ?: "", functions)
    }

    private fun parseKtCallable(element: KtNamedFunction, fileName: String, doc: Document?): FunctionNode {
        val name = element.name ?: "anon"
        val id = "$fileName.$name"
        val hasReturn = element.typeReference != null && element.typeReference?.text != "Unit"
        val isEntry = listOf("onCreate", "onStart", "onReceive", "onResume").any { name.contains(it) }
        val bodyData = parseBody(element.bodyExpression, fileName, doc, id, element)
        return FunctionNode(id, name, "UNKNOWN", element.text.substringBefore("{").trim(), element.valueParameters.map { ParameterNode(it.name ?: "", it.typeReference?.text ?: "Any", it.defaultValue?.text) }, hasReturn, element.typeReference?.text, isEntry, doc?.getLineNumber(element.textRange.startOffset) ?: 0, doc?.getLineNumber(element.textRange.endOffset) ?: 0, bodyData.first, bodyData.second)
    }

    private fun parseBody(container: KtElement?, fileName: String, doc: Document?, parentId: String, parentFunc: KtNamedFunction): Pair<MutableList<CallNode>, MutableList<BranchNode>> {
        val calls = mutableListOf<CallNode>()
        val branches = mutableListOf<BranchNode>()
        if (container == null) return calls to branches
        var order = 0
        container.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val call = parseKtCall(expression, fileName, ++order, doc, parentId, parentFunc)
                calls.add(call)
                globalCallCount++
                if (call.targetName in listOf("startActivity", "startService", "sendBroadcast")) {
                    val intentArg = expression.valueArguments.firstOrNull()?.getArgumentExpression()
                    val info = resolveIntentInfo(intentArg)
                    if (info?.targetClass != null) {
                        intentLinks.add(IntentLink(parentId, info.targetClass, if (call.targetName == "startService") "service" else "activity", doc?.getLineNumber(expression.textRange.startOffset) ?: 0))
                    }
                }
                if (call.targetName in listOf("emit", "collect", "observe", "postValue")) {
                    val streamName = (expression.parent as? KtDotQualifiedExpression)?.receiverExpression?.text ?: "this"
                    reactivePoints.getOrPut(streamName) { mutableListOf() }.add(ReactivePoint(fileName, parentFunc.name ?: "anon", call.targetName == "emit", streamName))
                }
            }
            override fun visitProperty(property: KtProperty) {
                if (property.initializer != null) {
                    val pName = property.name ?: "temp"
                    calls.add(CallNode("val $pName =", "Local", "local.$pName", "DATA_FLOW", ++order, doc?.getLineNumber(property.textRange.startOffset) ?: 0))
                }
                super.visitProperty(property)
            }
            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                if (expression.operationReference.getReferencedName() == "=") {
                    val left = expression.left?.text ?: ""
                    val isUI = listOf(".text", ".visibility", ".adapter", ".color").any { left.contains(it) }
                    calls.add(CallNode("$left =", "UI", "ui.$left", if (isUI) "UI" else "DATA_FLOW", ++order, doc?.getLineNumber(expression.textRange.startOffset) ?: 0, isSystemCall = isUI))
                }
                super.visitBinaryExpression(expression)
            }
            override fun visitIfExpression(expression: KtIfExpression) {
                expression.condition?.accept(this)
                val condition = expression.condition?.text ?: "if"
                val variants = mutableListOf<BranchVariant>()
                val thenBody = parseBody(expression.then, fileName, doc, parentId, parentFunc)
                variants.add(BranchVariant(id = "then", case = "true", nestedCalls = thenBody.first, branches = thenBody.second))
                if (expression.`else` != null) {
                    val elseBody = parseBody(expression.`else`, fileName, doc, parentId, parentFunc)
                    variants.add(BranchVariant(id = "else", case = "false", nestedCalls = elseBody.first, branches = elseBody.second))
                }
                branches.add(BranchNode("IF", condition, doc?.getLineNumber(expression.textRange.startOffset) ?: 0, variants))
            }
            override fun visitWhenExpression(expression: KtWhenExpression) {
                expression.subjectExpression?.accept(this)
                val condition = expression.subjectExpression?.text ?: "when"
                val variants = expression.entries.map { entry ->
                    val body = parseBody(entry.expression, fileName, doc, parentId, parentFunc)
                    BranchVariant(id = "case", case = entry.conditions.joinToString { it.text } + (if (entry.isElse) "else" else ""), nestedCalls = body.first, branches = body.second)
                }
                branches.add(BranchNode("WHEN", condition, doc?.getLineNumber(expression.textRange.startOffset) ?: 0, variants))
            }
            override fun visitTryExpression(expression: KtTryExpression) {
                val tryBody = parseBody(expression.tryBlock, fileName, doc, parentId, parentFunc)
                val variants = mutableListOf<BranchVariant>()
                variants.add(BranchVariant(id = "try", case = "try", nestedCalls = tryBody.first, branches = tryBody.second))
                expression.catchClauses.forEach { 
                    val catchBody = parseBody(it.catchBody, fileName, doc, parentId, parentFunc)
                    variants.add(BranchVariant(id = "catch", case = it.catchParameter?.text ?: "catch", nestedCalls = catchBody.first, branches = catchBody.second))
                }
                branches.add(BranchNode("ERROR", "try-catch", doc?.getLineNumber(expression.textRange.startOffset) ?: 0, variants))
            }
        })
        return calls to branches
    }

    private fun parseKtCall(expr: KtCallExpression, fileName: String, order: Int, doc: Document?, parentId: String, parentFunc: KtNamedFunction): CallNode {
        val targetName = expr.calleeExpression?.text ?: "unknown"
        val res = expr.calleeExpression?.references?.firstOrNull()?.resolve()
        val isSys = res == null || (res.containingFile?.virtualFile?.let { !ProjectRootManager.getInstance(project).fileIndex.isInContent(it) } ?: true)
        val tFile = if (isSys) "System" else res?.containingFile?.name ?: "Unknown"
        val category = determineCategory(targetName, isSys)
        val nested = mutableListOf<CallNode>()
        val nestedBranches = mutableListOf<BranchNode>()
        expr.lambdaArguments.forEach { 
             val body = parseBody(it.getLambdaExpression()?.bodyExpression, fileName, doc, parentId, parentFunc)
             nested.addAll(body.first)
             nestedBranches.addAll(body.second)
        }
        return CallNode(targetName, tFile, "$tFile.$targetName", category, order, doc?.getLineNumber(expr.textRange.startOffset) ?: 0, nestedCalls = nested, branches = nestedBranches, isSystemCall = isSys, isRecursive = "$tFile.$targetName" == parentId)
    }

    private fun determineCategory(name: String, isSystem: Boolean): String {
        if (!isSystem) return "INTERNAL"
        return when {
            name in listOf("launch", "async", "withContext", "runBlocking", "delay") -> "ASYNC"
            name in listOf("setText", "setVisibility", "setBackgroundColor", "findViewById", "inflate", "show", "dismiss", "animate") -> "UI"
            name in listOf("Log", "d", "e", "w", "i", "println") -> "LOG"
            name in listOf("getSharedPreferences", "getString", "putString", "edit", "apply", "insert", "query", "delete") -> "STORAGE"
            name in listOf("startActivity", "startService", "sendBroadcast", "Intent") -> "ANDROID"
            name in listOf("observe", "collect", "emit", "postValue") -> "STATE"
            name in listOf("runCatching", "onFailure", "onSuccess") -> "ERROR"
            name in listOf("fetch", "get", "post", "execute") -> "NETWORK"
            else -> "ACTION"
        }
    }

    private fun resolveIntentInfo(expr: KtExpression?): IntentInfo? {
        if (expr is KtCallExpression && expr.calleeExpression?.text == "Intent") {
            val args = expr.valueArguments
            if (args.size >= 2) {
                val second = args[1].getArgumentExpression()
                if (second is KtClassLiteralExpression) return IntentInfo(targetClass = second.receiverExpression?.text)
            }
        }
        return null
    }
}
