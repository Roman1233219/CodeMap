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

data class IntentLink(val from: String, val to: String, val type: String, val lineNumber: Int)
data class BroadcastLink(val from: String, val to: String, val action: String, val lineNumber: Int)
data class InboundCallNode(val fileName: String, val functionName: String, val functionId: String)

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
    val branches: List<BranchNode>,
    val inboundCalls: MutableList<InboundCallNode> = mutableListOf(),
    @Transient val hasParams: Boolean = false
)

data class FileNode(val fileName: String, val filePath: String, val packageName: String, val functions: List<FunctionNode>)

data class Metadata(val parsedAt: String, val project: String, val totalFiles: Int, val totalFunctions: Int, val totalCalls: Int)

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
                    val callsSystemAction = fn.calls.any { it.isSystemCall && it.targetType == "ACTION" }
                    fn.type = determineStructuralType(fn.hasParams, fn.hasReturn, fn.isEntryPoint, callsSystemAction)
                    fn.calls.forEach { linkCallDetails(it, 0, fn.id) }
                }
                onProgress(40 + ((index + 1).toFloat() / fileNodes.size * 50).toInt())
            }

            // Inbound calls
            fileNodes.forEach { fileNode ->
                fileNode.functions.forEach { fn ->
                    fn.calls.forEach { call ->
                        allFunctionsMap[call.targetId]?.inboundCalls?.add(
                            InboundCallNode(fileNode.fileName, fn.name, fn.id)
                        )
                    }
                }
            }
            onProgress(95)
            
            val finalOutput = PSIData(
                metadata = Metadata(
                    parsedAt = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT),
                    project = project.name,
                    totalFiles = fileNodes.size,
                    totalFunctions = allFunctionsMap.size,
                    totalCalls = globalCallCount
                ),
                files = fileNodes,
                intentLinks = intentLinks
            )

            // СОХРАНЯЕМ В КОРЕНЬ ПРОЕКТА
            val dataFolder = File(project.basePath, ".codemap")
            if (!dataFolder.exists()) dataFolder.mkdirs()
            File(dataFolder, "PSI.json").writeText(gson.toJson(finalOutput))

            onProgress(100)
            ApplicationManager.getApplication().invokeLater {
                onFinished()
            }
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
            if (depth < 1 && targetId != parentId) {
                call.nestedCalls = target.calls.map { it.copy() }
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
            })
        }
        return FileNode(vFile.name, vFile.path, (psi as? KtFile)?.packageFqName?.asString() ?: "", functions)
    }

    private fun parseKtCallable(element: KtNamedFunction, fileName: String, doc: Document?): FunctionNode {
        val name = element.name ?: "anon"
        val id = "$fileName.$name"
        val hasReturn = element.typeReference != null && element.typeReference?.text != "Unit"
        val hasParams = element.valueParameters.isNotEmpty()
        val isEntry = listOf("onCreate", "onStart", "onReceive").any { name.contains(it) }

        val calls = mutableListOf<CallNode>()
        element.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val call = parseKtCall(expression, fileName, doc, id, element)
                calls.add(call); globalCallCount++
                super.visitCallExpression(expression)
            }
        })

        return FunctionNode(
            id, name, "UNKNOWN", element.text.substringBefore("{").trim(),
            element.valueParameters.map { ParameterNode(it.name ?: "", it.typeReference?.text ?: "Any", it.defaultValue?.text) },
            hasReturn, element.typeReference?.text, isEntry,
            doc?.getLineNumber(element.textRange.startOffset) ?: 0, doc?.getLineNumber(element.textRange.endOffset) ?: 0,
            calls, emptyList()
        )
    }

    private fun parseKtCall(expr: KtCallExpression, fileName: String, doc: Document?, parentId: String, parentFunc: KtNamedFunction): CallNode {
        val targetName = expr.calleeExpression?.text ?: "unknown"
        val res = expr.calleeExpression?.references?.firstOrNull()?.resolve()
        val isSys = res == null || (res.containingFile?.virtualFile?.let { !ProjectRootManager.getInstance(project).fileIndex.isInContent(it) } ?: true)
        val tFile = if (isSys) "System" else res?.containingFile?.name ?: "Unknown"
        
        return CallNode(
            targetName, tFile, "$tFile.$targetName", determineStructuralType(false, false, isSystem = isSys),
            0, doc?.getLineNumber(expr.textRange.startOffset) ?: 0, false, null,
            isSystemCall = isSys
        )
    }

    private fun determineStructuralType(hasIn: Boolean, hasOut: Boolean, isEntry: Boolean = false, hasSysAction: Boolean = false, isSystem: Boolean = false): String {
        if (isEntry) return "ACTION"
        if (hasOut) return "REQUEST"
        return if (isSystem) "ACTION" else "DATA_FLOW"
    }
}
