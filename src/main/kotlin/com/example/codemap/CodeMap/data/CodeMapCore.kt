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

// --- Структуры данных согласно ТЗ PSI.json ---

data class ParameterNode(
    val name: String,
    val type: String,
    val defaultValue: String?
)

data class CallNode(
    val targetName: String,
    val targetFile: String,
    val targetId: String,
    var targetType: String,
    val callOrder: Int,
    val lineNumber: Int,
    val isConditional: Boolean,
    val conditionBranch: String?,
    var nestedCalls: List<CallNode> = emptyList(),
    val isRecursive: Boolean = false,
    val isSystemCall: Boolean = false,
    val isCallback: Boolean = false,
    val callbackParameter: String? = null,
    val isImplicit: Boolean = false,
    val listenerLinks: List<String> = emptyList()
)

data class IntentLink(
    val from: String,
    val to: String,
    val type: String,
    val lineNumber: Int
)

data class BroadcastLink(
    val from: String,
    val to: String,
    val action: String,
    val lineNumber: Int
)

data class InboundCallNode(
    val fileName: String,
    val functionName: String,
    val functionId: String
)

data class BranchVariant(
    val id: String? = null,
    val case: String? = null,
    val targetName: String? = null,
    val targetFile: String? = null,
    val targetId: String? = null,
    val targetType: String? = null,
    val lineNumber: Int? = null,
    val nestedCalls: List<CallNode> = emptyList(),
    val branches: List<BranchNode> = emptyList()
)

data class BranchNode(
    val type: String,
    val condition: String?,
    val lineNumber: Int,
    val branches: List<BranchVariant>
)

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
    val branches: List<BranchNode>,
    val inboundCalls: MutableList<InboundCallNode> = mutableListOf(),
    @Transient val hasParams: Boolean = false
)

data class FileNode(
    val fileName: String,
    val filePath: String,
    val packageName: String,
    val functions: List<FunctionNode>
)

data class Metadata(
    val parsedAt: String,
    val project: String,
    val totalFiles: Int,
    val totalFunctions: Int,
    val totalCalls: Int
)

data class PSIData(
    val metadata: Metadata,
    val files: List<FileNode>,
    val intentLinks: List<IntentLink> = emptyList(),
    val broadcastLinks: List<BroadcastLink> = emptyList()
)

class CodeMapCore(private val project: Project) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val allFunctionsMap = mutableMapOf<String, FunctionNode>()
    private val classImplMap = mutableMapOf<String, String>()
    private val reactivePoints = mutableMapOf<String, MutableList<ReactivePoint>>()
    private val intentLinks = mutableListOf<IntentLink>()
    private val broadcastSenders = mutableListOf<PendingBroadcast>()
    private val broadcastReceivers = mutableListOf<PendingBroadcast>()
    private var globalCallCount = 0

    data class ReactivePoint(val fileName: String, val functionName: String, val isEmitter: Boolean, val streamName: String)
    data class PendingBroadcast(val functionId: String, val action: String, val lineNumber: Int)
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
            broadcastSenders.clear()
            broadcastReceivers.clear()
            globalCallCount = 0
            val fileNodes = mutableListOf<FileNode>()

            // Pass 1: Извлечение структуры и имплицитных связей
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

            // Pass 2: Глубокий анализ связей и типов
            fileNodes.forEachIndexed { index, fileNode ->
                fileNode.functions.forEach { fn ->
                    injectReactiveCalls(fn)
                    val callsSystemAction = fn.calls.any { it.isSystemCall && it.targetType == "ACTION" }
                    fn.type = determineStructuralType(fn.hasParams, fn.hasReturn, fn.isEntryPoint, callsSystemAction)

                    fn.calls.forEach { linkCallDetails(it, 0, fn.id) }
                    fn.branches.forEach { br ->
                        br.branches.forEach { v -> 
                            v.nestedCalls.forEach { linkCallDetails(it, 0, fn.id) }
                            v.branches.forEach { recursiveLink(it, fn.id) }
                        }
                    }
                }
                onProgress(40 + ((index + 1).toFloat() / fileNodes.size * 50).toInt())
            }

            // Pass 3: Сбор входящих вызовов (Inbound Calls) и связывание Broadcast
            fileNodes.forEach { fileNode ->
                fileNode.functions.forEach { fn ->
                    fn.calls.forEach { call ->
                        allFunctionsMap[call.targetId]?.inboundCalls?.add(
                            InboundCallNode(fileNode.fileName, fn.name, fn.id)
                        )
                    }
                }
            }
            
            // Link Broadcasts
            val finalBroadcastLinks = mutableListOf<BroadcastLink>()
            broadcastSenders.forEach { sender ->
                broadcastReceivers.filter { it.action == sender.action }.forEach { receiver ->
                    finalBroadcastLinks.add(BroadcastLink(sender.functionId, receiver.functionId, sender.action, sender.lineNumber))
                }
            }
            
            onProgress(100)

            val finalOutput = PSIData(
                metadata = Metadata(
                    parsedAt = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT),
                    project = project.name,
                    totalFiles = fileNodes.size,
                    totalFunctions = allFunctionsMap.size,
                    totalCalls = globalCallCount
                ),
                files = fileNodes,
                intentLinks = intentLinks,
                broadcastLinks = finalBroadcastLinks
            )

            val exportFile = File(System.getProperty("user.home") + File.separator + "Desktop", "PSI.json")
            exportFile.writeText(gson.toJson(finalOutput))

            ApplicationManager.getApplication().invokeLater {
                Messages.showInfoMessage(project, "Анализ PSI V11.7 завершен! Входящие связи и Intent-связи добавлены.", "CodeMap")
                onFinished()
            }
        }
    }

    private fun recursiveLink(branch: BranchNode, parentId: String) {
        branch.branches.forEach { variant ->
            variant.nestedCalls.forEach { linkCallDetails(it, 0, parentId) }
            variant.branches.forEach { recursiveLink(it, parentId) }
        }
    }

    private fun preScanImplicitLinks(vFile: VirtualFile) {
        val psi = PsiManager.getInstance(project).findFile(vFile) ?: return
        if (psi is KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                    classOrObject.superTypeListEntries.forEach { entry ->
                        val interfaceName = entry.typeReference?.text ?: ""
                        if (interfaceName.isNotEmpty()) classImplMap[interfaceName] = classOrObject.name ?: ""
                    }
                    super.visitClassOrObject(classOrObject)
                }
                override fun visitCallExpression(expression: KtCallExpression) {
                    val name = expression.calleeExpression?.text ?: ""
                    val stream = expression.getParentOfType<KtDotQualifiedExpression>(false)?.receiverExpression?.text ?: ""
                    if (stream.isNotEmpty()) {
                        val isEmitter = listOf("emit", "postValue", "send", "tryEmit").contains(name)
                        val isCollector = listOf("collect", "observe", "onEach").contains(name)
                        if (isEmitter || isCollector) {
                            val parentFunc = expression.getParentOfType<KtNamedFunction>(true)
                            reactivePoints.getOrPut(stream) { mutableListOf() }.add(ReactivePoint(vFile.name, parentFunc?.name ?: "init", isEmitter, stream))
                        }
                    }
                    
                    // Broadcast Receiver detection
                    if (name == "registerReceiver") {
                        val filter = expression.valueArguments.getOrNull(1)?.getArgumentExpression()
                        val actions = extractActionsFromFilter(filter)
                        val parentFunc = expression.getParentOfType<KtNamedFunction>(true)
                        val funcId = "${vFile.name}.${parentFunc?.name ?: "init"}"
                        actions.forEach { action ->
                            broadcastReceivers.add(PendingBroadcast(funcId, action, 0))
                        }
                    }
                    
                    super.visitCallExpression(expression)
                }
            })
        }
    }

    private fun extractActionsFromFilter(expr: KtExpression?): List<String> {
        if (expr is KtCallExpression && expr.calleeExpression?.text == "IntentFilter") {
            return expr.valueArguments.mapNotNull { extractActionString(it.getArgumentExpression()) }
        }
        return emptyList()
    }

    private fun extractActionString(expr: KtExpression?): String? {
        if (expr == null) return null
        if (expr is KtStringTemplateExpression) {
            return expr.entries.joinToString("") { it.text }
        }
        if (expr is KtSimpleNameExpression) {
            val resolved = expr.references.firstOrNull()?.resolve()
            if (resolved is KtProperty) {
                val initializer = resolved.initializer
                if (initializer is KtStringTemplateExpression) {
                    return initializer.entries.joinToString("") { it.text }
                }
            }
        }
        return null
    }

    private fun injectReactiveCalls(fn: FunctionNode) {
        reactivePoints.forEach { (_, points) ->
            val isCurrentEmitter = points.any { it.isEmitter && it.functionName == fn.name && it.fileName == fn.id.substringBefore(".") }
            if (isCurrentEmitter) {
                points.filter { !it.isEmitter }.forEach { collector ->
                    fn.calls.add(CallNode(collector.functionName, collector.fileName, "${collector.fileName}.${collector.functionName}", "DATA_FLOW", 0, 0, false, null, isImplicit = true))
                    globalCallCount++
                }
            }
        }
    }

    private fun linkCallDetails(call: CallNode, depth: Int, parentId: String) {
        val targetId = if (allFunctionsMap.containsKey(call.targetId)) call.targetId else {
            val implName = classImplMap[call.targetName]
            if (implName != null) "${call.targetFile.substringBeforeLast(".")}.$implName" else call.targetId
        }
        val target = allFunctionsMap[targetId]
        if (target != null) {
            call.targetType = target.type
            if (depth < 2 && targetId != parentId) {
                call.nestedCalls = target.calls.map { it.copy() }
                call.nestedCalls.forEach { linkCallDetails(it, depth + 1, parentId) }
            }
        }
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
                override fun visitNamedFunction(f: KtNamedFunction) { functions.add(parseKtCallable(f, vFile.name, doc)); super.visitNamedFunction(f) }
                override fun visitPrimaryConstructor(c: KtPrimaryConstructor) { functions.add(parseKtCallable(c, vFile.name, doc)); super.visitPrimaryConstructor(c) }
                override fun visitSecondaryConstructor(c: KtSecondaryConstructor) { functions.add(parseKtCallable(c, vFile.name, doc)); super.visitSecondaryConstructor(c) }
                override fun visitAnonymousInitializer(i: KtAnonymousInitializer) { functions.add(parseKtCallable(i, vFile.name, doc)); super.visitAnonymousInitializer(i) }
                override fun visitPropertyAccessor(a: KtPropertyAccessor) { functions.add(parseKtCallable(a, vFile.name, doc)); super.visitPropertyAccessor(a) }
            })
        } else if (psi is PsiJavaFile) {
            psi.accept(object : JavaRecursiveElementWalkingVisitor() {
                override fun visitMethod(m: PsiMethod) { functions.add(parseJavaMethod(m, vFile.name, doc)); super.visitMethod(m) }
            })
        }
        return FileNode(vFile.name, vFile.path, (psi as? KtFile)?.packageFqName?.asString() ?: (psi as? PsiJavaFile)?.packageName ?: "", functions)
    }

    private fun parseKtCallable(element: KtElement, fileName: String, doc: Document?): FunctionNode {
        val name = when (element) {
            is KtNamedFunction -> element.name ?: "anon"
            is KtConstructor<*> -> "constructor"
            is KtAnonymousInitializer -> "init"
            is KtPropertyAccessor -> (if (element.isGetter) "get_" else "set_") + (element.getParentOfType<KtProperty>(true)?.name ?: "prop")
            else -> "anon"
        }
        val id = "$fileName.$name"
        val hasReturn = element is KtNamedFunction && element.typeReference != null && element.typeReference?.text != "Unit"
        val hasParams = (element as? KtFunction)?.valueParameters?.isNotEmpty() ?: false
        val isEntry = element is KtAnonymousInitializer || (element is KtNamedFunction && listOf("onCreate", "onStart", "init").any { name.contains(it) }) || (element is KtAnnotated && element.annotationEntries.any { it.shortName?.asString() == "Composable" })

        val calls = mutableListOf<CallNode>(); val branches = mutableListOf<BranchNode>(); var order = 0
        element.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                if (expression.getParentOfType<KtFunction>(true) == element || expression.getParentOfType<KtAnonymousInitializer>(true) == element) {
                    val call = parseKtCall(expression, fileName, ++order, doc, id, element as? KtFunction, null)
                    calls.add(call)
                    globalCallCount++
                    
                    // Intent-related link detection
                    if (call.targetName in listOf("startActivity", "startService", "sendBroadcast")) {
                        val intentArg = expression.valueArguments.firstOrNull()?.getArgumentExpression()
                        val intentInfo = resolveIntentInfo(intentArg)
                        if (intentInfo != null) {
                            if (call.targetName == "sendBroadcast") {
                                intentInfo.action?.let { action ->
                                    broadcastSenders.add(PendingBroadcast(id, action, call.lineNumber))
                                }
                            } else {
                                intentInfo.targetClass?.let { targetClass ->
                                    intentLinks.add(IntentLink(id, targetClass, if (call.targetName == "startService") "service" else "activity", call.lineNumber))
                                }
                            }
                        }
                    }
                }
                super.visitCallExpression(expression)
            }
            override fun visitIfExpression(expression: KtIfExpression) {
                if (expression.getParentOfType<KtFunction>(true) == element) branches.add(parseKtIf(expression, fileName, doc, id, element as? KtFunction))
                super.visitIfExpression(expression)
            }
            override fun visitWhenExpression(expression: KtWhenExpression) {
                if (expression.getParentOfType<KtFunction>(true) == element) branches.add(parseKtWhen(expression, fileName, doc, id, element as? KtFunction))
                super.visitWhenExpression(expression)
            }
        })

        return FunctionNode(
            id, name, "UNKNOWN", element.text.substringBefore("{").trim(),
            (element as? KtFunction)?.valueParameters?.map { ParameterNode(it.name ?: "", it.typeReference?.text ?: "Any", it.defaultValue?.text) } ?: emptyList(),
            hasReturn, (element as? KtNamedFunction)?.typeReference?.text ?: "Unit", isEntry,
            doc?.getLineNumber(element.textRange.startOffset) ?: 0, doc?.getLineNumber(element.textRange.endOffset) ?: 0,
            calls, branches, mutableListOf(), hasParams
        )
    }

    private fun resolveIntentInfo(expr: KtExpression?): IntentInfo? {
        if (expr == null) return null
        if (expr is KtCallExpression && expr.calleeExpression?.text == "Intent") {
            return parseIntentConstructor(expr)
        }
        if (expr is KtSimpleNameExpression) {
            val resolved = expr.references.firstOrNull()?.resolve()
            if (resolved is KtProperty) {
                val initializer = resolved.initializer
                if (initializer is KtCallExpression && initializer.calleeExpression?.text == "Intent") {
                    return parseIntentConstructor(initializer)
                }
            }
        }
        return null
    }

    private fun parseIntentConstructor(call: KtCallExpression): IntentInfo {
        val args = call.valueArguments
        if (args.isEmpty()) return IntentInfo()
        val firstArg = args[0].getArgumentExpression()
        val secondArg = args.getOrNull(1)?.getArgumentExpression()
        if (secondArg != null) {
            val targetName = extractTargetClassName(secondArg)
            if (targetName != null) return IntentInfo(targetClass = targetName)
        }
        if (firstArg != null) {
            val action = extractActionString(firstArg)
            if (action != null) return IntentInfo(action = action)
        }
        return IntentInfo()
    }

    private fun extractTargetClassName(expr: KtExpression): String? {
        if (expr is KtClassLiteralExpression) return expr.receiverExpression?.text
        if (expr is KtDotQualifiedExpression && expr.receiverExpression is KtClassLiteralExpression) {
            return (expr.receiverExpression as KtClassLiteralExpression).receiverExpression?.text
        }
        return null
    }

    private fun parseKtCall(expr: KtCallExpression, fileName: String, order: Int, doc: Document?, parentId: String, parentFunc: KtFunction?, branchLabel: String?): CallNode {
        val targetName = expr.calleeExpression?.text ?: "unknown"
        val res = expr.calleeExpression?.references?.firstOrNull()?.resolve()
        val isSys = res == null || (res.containingFile?.virtualFile?.let { !ProjectRootManager.getInstance(project).fileIndex.isInContent(it) } ?: true)
        val tFile = if (isSys) "System" else res?.containingFile?.name ?: "Unknown"
        val tId = "$tFile.$targetName"
        val isUsed = expr.parent !is KtBlockExpression && expr.parent !is KtContainerNodeForControlStructureBody
        val hasArgs = expr.valueArgumentList?.arguments?.isNotEmpty() ?: false || expr.lambdaArguments.isNotEmpty()

        val nested = mutableListOf<CallNode>()
        expr.lambdaArguments.forEach { lambdaArg ->
            lambdaArg.getLambdaExpression()?.bodyExpression?.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    nested.add(parseKtCall(expression, fileName, 0, doc, parentId, parentFunc, "lambda"))
                    super.visitCallExpression(expression)
                }
            })
        }

        val listeners = mutableListOf<String>()
        expr.valueArguments.forEach { arg ->
            val argExpr = arg.getArgumentExpression()
            if (argExpr is KtObjectLiteralExpression) {
                argExpr.objectDeclaration.declarations.filterIsInstance<KtNamedFunction>().forEach {
                    listeners.add(it.name ?: "anon")
                }
            }
        }

        return CallNode(
            targetName, tFile, tId, determineStructuralType(hasArgs, isUsed, isSystem = isSys), 
            order, doc?.getLineNumber(expr.textRange.startOffset) ?: 0, branchLabel != null, branchLabel, 
            nestedCalls = nested,
            isRecursive = tId == parentId, isSystemCall = isSys, 
            isCallback = parentFunc?.valueParameters?.any { it.name == targetName } == true, 
            callbackParameter = if (parentFunc?.valueParameters?.any { it.name == targetName } == true) targetName else null,
            listenerLinks = listeners
        )
    }

    private fun parseKtIf(expr: KtIfExpression, fileName: String, doc: Document?, parentId: String, parentFunc: KtFunction?): BranchNode {
        val variants = mutableListOf<BranchVariant>()
        listOf(expr.then to "then", expr.`else` to "else").forEach { (branch, label) ->
            if (branch != null) {
                val nestedCalls = mutableListOf<CallNode>(); val nestedBranches = mutableListOf<BranchNode>()
                branch.children.forEach { child ->
                    when (child) {
                        is KtCallExpression -> nestedCalls.add(parseKtCall(child, fileName, 0, doc, parentId, parentFunc, label))
                        is KtIfExpression -> nestedBranches.add(parseKtIf(child, fileName, doc, parentId, parentFunc))
                        is KtWhenExpression -> nestedBranches.add(parseKtWhen(child, fileName, doc, parentId, parentFunc))
                        is KtBlockExpression -> {
                            child.statements.forEach { stmt ->
                                if (stmt is KtCallExpression) nestedCalls.add(parseKtCall(stmt, fileName, 0, doc, parentId, parentFunc, label))
                                if (stmt is KtIfExpression) nestedBranches.add(parseKtIf(stmt, fileName, doc, parentId, parentFunc))
                                if (stmt is KtWhenExpression) nestedBranches.add(parseKtWhen(stmt as KtWhenExpression, fileName, doc, parentId, parentFunc))
                            }
                        }
                    }
                }
                variants.add(BranchVariant(id = label, lineNumber = doc?.getLineNumber(branch.textRange.startOffset), nestedCalls = nestedCalls, branches = nestedBranches))
            } else if (label == "else") variants.add(BranchVariant(id = "else"))
        }
        return BranchNode("if", expr.condition?.text, doc?.getLineNumber(expr.textRange.startOffset) ?: 0, variants)
    }

    private fun parseKtWhen(expr: KtWhenExpression, fileName: String, doc: Document?, parentId: String, parentFunc: KtFunction?): BranchNode {
        val variants = mutableListOf<BranchVariant>()
        expr.entries.forEach { entry ->
            val label = if (entry.isElse) "else" else entry.conditions.joinToString { it.text }
            val nestedCalls = mutableListOf<CallNode>(); val nestedBranches = mutableListOf<BranchNode>()
            entry.expression?.let { branchExpr ->
                branchExpr.children.forEach { child ->
                    when (child) {
                        is KtCallExpression -> nestedCalls.add(parseKtCall(child, fileName, 0, doc, parentId, parentFunc, label))
                        is KtIfExpression -> nestedBranches.add(parseKtIf(child, fileName, doc, parentId, parentFunc))
                        is KtWhenExpression -> nestedBranches.add(parseKtWhen(child, fileName, doc, parentId, parentFunc))
                        is KtBlockExpression -> {
                            child.statements.forEach { stmt ->
                                if (stmt is KtCallExpression) nestedCalls.add(parseKtCall(stmt, fileName, 0, doc, parentId, parentFunc, label))
                                if (stmt is KtIfExpression) nestedBranches.add(parseKtIf(stmt, fileName, doc, parentId, parentFunc))
                                if (stmt is KtWhenExpression) nestedBranches.add(parseKtWhen(stmt as KtWhenExpression, fileName, doc, parentId, parentFunc))
                            }
                        }
                    }
                }
            }
            variants.add(BranchVariant(case = label, lineNumber = doc?.getLineNumber(entry.textRange.startOffset), nestedCalls = nestedCalls, branches = nestedBranches))
        }
        return BranchNode("when", expr.subjectExpression?.text, doc?.getLineNumber(expr.textRange.startOffset) ?: 0, variants)
    }

    private fun parseJavaMethod(m: PsiMethod, fileName: String, doc: Document?): FunctionNode {
        val name = if (m.isConstructor) "constructor" else m.name; val hasReturn = m.returnType != null && m.returnType != PsiTypes.voidType(); val hasParams = m.parameterList.parameters.isNotEmpty()
        val calls = mutableListOf<CallNode>(); var order = 0
        PsiTreeUtil.findChildrenOfType(m, PsiMethodCallExpression::class.java).forEach {
            val tName = it.methodExpression.referenceName ?: "unknown"
            val isSys = it.methodExpression.resolve() == null || (it.methodExpression.resolve()?.containingFile?.virtualFile?.let { !ProjectRootManager.getInstance(project).fileIndex.isInContent(it) } ?: true)
            calls.add(CallNode(tName, if (isSys) "System" else "Internal", "System.$tName", "UNKNOWN", ++order, doc?.getLineNumber(it.textRange.startOffset) ?: 0, false, null, isSystemCall = isSys))
            globalCallCount++
        }
        return FunctionNode("$fileName.$name", name, determineStructuralType(hasParams, hasReturn, name == "onCreate"), m.hierarchicalMethodSignature.toString(), m.parameterList.parameters.map { ParameterNode(it.name, it.type.presentableText, null) }, hasReturn, m.returnType?.presentableText, name == "onCreate", doc?.getLineNumber(m.textRange.startOffset) ?: 0, doc?.getLineNumber(m.textRange.endOffset) ?: 0, calls, emptyList(), mutableListOf(), hasParams)
    }

    private fun determineStructuralType(hasIn: Boolean, hasOut: Boolean, isEntry: Boolean = false, hasSysAction: Boolean = false, isSystem: Boolean = false): String {
        if (isEntry) return "ACTION"; if (hasOut) return "REQUEST"; if (hasSysAction || (isSystem && !hasOut)) return "ACTION"
        return if (!hasIn) "ACTION" else "DATA_FLOW"
    }
}
