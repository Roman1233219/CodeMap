package com.example.codemap.CodeMap.data

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import java.io.File
import com.intellij.openapi.ui.Messages
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- Структуры данных V11.1 (Compose, Themes & Property Usage) ---

data class CallNode(
    val targetName: String,
    val targetId: String?,
    val targetLocation: String?,
    val type: String,               
    val text: String,
    val callOrder: Int,
    val isConditional: Boolean,
    val hasDataCallback: Boolean = false,
    val isSystem: Boolean = false,
    val navTargetFile: String? = null,
    val isImplicit: Boolean = false
)

data class MethodNode(
    val id: String,
    val name: String,
    val isEntryPoint: Boolean,
    val calls: List<CallNode>
)

data class ClassNode(
    val name: String,
    val fqName: String?,
    val type: String,
    val methods: List<MethodNode>
)

data class FileNode(
    val fileName: String,
    val path: String,
    val subBlock: String,
    val classes: List<ClassNode>,
    var importanceScore: Int = 0,
    val isResource: Boolean = false,
    var isUnused: Boolean = false,
    var calculatedType: String = ""
)

data class CodeMapData(
    val blocks: Map<String, MutableMap<String, MutableList<FileNode>>> = linkedMapOf(
        "Presentation" to mutableMapOf(),
        "Domain" to mutableMapOf(),
        "Data" to mutableMapOf(),
        "Infrastructure" to mutableMapOf(),
        "DI" to mutableMapOf(),
        "Common / Utils" to mutableMapOf(),
        "Resources" to mutableMapOf()
    )
)

data class ConnectionNode(
    val from: String,
    val fromFile: String,
    val fromFunction: String,
    val fromCallType: String,
    val to: String,
    val toFile: String,
    val toFunction: String,
    val targetCallType: String,
    val callOrder: Int,
    val isConditional: Boolean,
    val isImplicit: Boolean,
    val sourceFileType: String,
    val isSystemLink: Boolean = false,
    val navTargetFile: String? = null,
    val _comment: String
)

data class AnalysisResult(
    val level1_blocks: LevelConnections = LevelConnections(),
    val level3_files: LevelConnections = LevelConnections(),
    val level4_functions: LevelConnections = LevelConnections()
)

data class LevelConnections(val internal: MutableList<ConnectionNode> = mutableListOf(), val external: MutableList<ConnectionNode> = mutableListOf())

data class FinalOutput(
    val metadata: Map<String, Any>,
    val fileTypes: Map<String, String>,
    val blocks: Map<String, Map<String, List<FileNode>>>,
    val analysis: AnalysisResult
)

class CodeMapCore(private val project: Project) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val methodMap = mutableMapOf<String, MethodInfo>()
    private val classImplMap = mutableMapOf<String, String>() 
    private val reactivePoints = mutableMapOf<String, MutableList<ReactivePoint>>()
    private val propertyUsages = mutableSetOf<String>() // Для отслеживания использования Color.kt, Type.kt

    data class MethodInfo(
        val methodId: String, val methodName: String, val className: String,
        val filePath: String, val fileName: String, val block: String,
        val subBlock: String, var fileType: String, val calls: List<CallNode>
    )

    data class ReactivePoint(val fileName: String, val isEmitter: Boolean, val streamName: String)

    fun refreshDatabase(onProgress: (Int) -> Unit, onFinished: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val smartData = CodeMapData()
            val allFiles = mutableListOf<VirtualFile>()
            
            ApplicationManager.getApplication().runReadAction {
                val basePath = project.basePath ?: return@runReadAction
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                if (baseDir != null) collectFiles(baseDir, allFiles)
                propertyUsages.clear()
                allFiles.forEach { preScanImplicitLinks(it) }
            }

            allFiles.forEachIndexed { index, vFile ->
                ApplicationManager.getApplication().runReadAction { scanSingleFile(vFile, smartData) }
                onProgress(((index + 1).toFloat() / allFiles.size * 100).toInt())
            }
            
            val finalOutput = runNativeParser(smartData)
            val exportFile = File(System.getProperty("user.home") + File.separator + "Desktop", "${project.name}_codemap_deep.json")
            exportFile.writeText(gson.toJson(finalOutput))
            
            ApplicationManager.getApplication().invokeLater { 
                Messages.showInfoMessage(project, "Анализ V11.1 (Theme & Property Support) завершен!", "CodeMap")
                onFinished() 
            }
        }
    }

    private fun preScanImplicitLinks(vFile: VirtualFile) {
        val psi = PsiManager.getInstance(project).findFile(vFile) ?: return
        if (psi is KtFile) {
            // 1. DI & Interfaces
            psi.getChildrenOfType<KtClassOrObject>().forEach { klass ->
                klass.superTypeListEntries.forEach { entry ->
                    val interfaceName = entry.typeReference?.text ?: ""
                    if (interfaceName.isNotEmpty()) classImplMap[interfaceName] = klass.name ?: ""
                }
            }
            // 2. Reactive & Properties (Color/Type/Theme usage)
            PsiTreeUtil.findChildrenOfType(psi, KtSimpleNameExpression::class.java).forEach { nameExpr ->
                val resolved = nameExpr.references.firstOrNull()?.resolve()
                if (resolved is PsiNamedElement) {
                    val targetFile = resolved.containingFile?.name ?: ""
                    if (targetFile.isNotEmpty() && targetFile != vFile.name) {
                        propertyUsages.add(targetFile)
                    }
                }
            }
            // 3. Flow Tracer
            PsiTreeUtil.findChildrenOfType(psi, KtCallExpression::class.java).forEach { call ->
                val name = call.calleeExpression?.text ?: ""
                val stream = call.getParentOfType<KtDotQualifiedExpression>(false)?.receiverExpression?.text ?: ""
                if (stream.isNotEmpty()) {
                    if (listOf("emit", "postValue", "send").contains(name)) {
                        reactivePoints.getOrPut(stream) { mutableListOf() }.add(ReactivePoint(vFile.name, true, stream))
                    } else if (listOf("collect", "observe", "onEach").contains(name)) {
                        reactivePoints.getOrPut(stream) { mutableListOf() }.add(ReactivePoint(vFile.name, false, stream))
                    }
                }
            }
        }
    }

    private fun runNativeParser(data: CodeMapData): FinalOutput {
        buildMaps(data)
        val res = AnalysisResult()
        val activeFiles = mutableSetOf<String>()

        val fileBlockCount = mutableMapOf<String, MutableSet<String>>()
        data.blocks.forEach { (b, subs) -> subs.forEach { (_, files) -> files.forEach { f -> fileBlockCount.getOrPut(f.fileName) { mutableSetOf() }.add(b) } } }

        // 1. Active by Function Calls
        methodMap.values.forEach { src ->
            src.calls.forEach { call ->
                val isRes = call.targetLocation?.startsWith("res/") == true
                var target = findTargetInfo(call.targetId, call.targetLocation)
                if (target == null && !call.isSystem && !isRes && call.navTargetFile == null) {
                    target = methodMap.values.find { it.methodName == call.targetName }
                }
                if (target == null && classImplMap.containsKey(call.targetName)) {
                    target = methodMap.values.find { it.className == classImplMap[call.targetName] }
                }

                if (target == null && !call.isSystem && call.navTargetFile == null && !isRes) return@forEach
                
                val tBlock = target?.block ?: if (isRes) "Resources" else "External"
                val tFile = target?.fileName ?: call.navTargetFile ?: "System"
                val tFunc = target?.methodName ?: call.targetName

                res.level4_functions.external.add(ConnectionNode(
                    src.block, src.fileName, src.methodName, call.type,
                    tBlock, tFile, tFunc, "TARGET", call.callOrder, call.isConditional,
                    call.isImplicit || target?.className == classImplMap[call.targetName], 
                    src.fileType, call.isSystem, call.navTargetFile, "?? $tFunc"
                ))
                activeFiles.add(src.fileName); activeFiles.add(tFile)
            }
        }

        // 2. Active by Property Usage (Color.kt, Type.kt, etc.)
        activeFiles.addAll(propertyUsages)

        // 3. Entry Points & Composables
        data.blocks.values.forEach { it.values.forEach { it.forEach { f ->
            if (f.classes.any { c -> c.methods.any { m -> m.isEntryPoint } }) activeFiles.add(f.fileName)
        } } }

        // 4. Distribution
        val unusedFiles = mutableListOf<FileNode>()
        val filteredBlocks = linkedMapOf<String, Map<String, List<FileNode>>>()
        val finalFileTypes = mutableMapOf<String, String>()

        data.blocks.forEach { (blockName, subBlocks) ->
            val finalSubBlocks = mutableMapOf<String, List<FileNode>>()
            subBlocks.forEach { (subName, fileList) ->
                val activeInSub = mutableListOf<FileNode>()
                fileList.forEach { file ->
                    val isMixed = (fileBlockCount[file.fileName]?.size ?: 0) > 1
                    file.calculatedType = if (isMixed) "MIXED" else blockName
                    
                    if (activeFiles.contains(file.fileName) || file.isResource) {
                        activeInSub.add(file)
                        finalFileTypes[file.fileName] = file.calculatedType
                    } else {
                        file.isUnused = true; unusedFiles.add(file)
                        finalFileTypes[file.fileName] = "Unused"
                    }
                }
                if (activeInSub.isNotEmpty()) finalSubBlocks[subName] = activeInSub
            }
            if (finalSubBlocks.isNotEmpty()) filteredBlocks[blockName] = finalSubBlocks
        }

        if (unusedFiles.isNotEmpty()) filteredBlocks["Unused"] = mapOf("Trash" to unusedFiles)

        return FinalOutput(
            metadata = mapOf("parsedAt" to LocalDateTime.now().toString(), "project" to project.name, "active" to activeFiles.size, "unused" to unusedFiles.size),
            fileTypes = finalFileTypes, blocks = filteredBlocks, analysis = res
        )
    }

    private fun buildMaps(data: CodeMapData) {
        methodMap.clear()
        data.blocks.forEach { (b, subs) -> subs.forEach { (sb, files) -> files.forEach { f ->
            f.classes.forEach { c -> c.methods.forEach { m ->
                methodMap[m.id] = MethodInfo(m.id, m.name, c.name, f.path, f.fileName, b, sb, b, m.calls)
            } }
        } } }
    }

    private fun findTargetInfo(id: String?, loc: String?): MethodInfo? {
        if (id != null) methodMap[id]?.let { return it }
        if (loc != null && loc != "External/Library") {
            val parts = loc.split(" > ")
            if (parts.size >= 2) return methodMap.values.find { it.fileName == parts[0] && it.methodName == parts.last() }
        }
        return null
    }

    private fun collectFiles(v: VirtualFile, res: MutableList<VirtualFile>) {
        if (v.isDirectory) {
            if (!listOf("build", "test", "androidTest").contains(v.name) && !v.name.startsWith(".")) v.children.forEach { collectFiles(it, res) }
        } else if (v.extension in listOf("kt", "java", "xml")) res.add(v)
    }

    private fun scanSingleFile(v: VirtualFile, data: CodeMapData) {
        val psi = PsiManager.getInstance(project).findFile(v) ?: return
        if (psi is XmlFile) {
            data.blocks["Resources"]?.getOrPut("layout") { mutableListOf() }?.add(FileNode(v.name, v.path, "layout", emptyList(), 0, true))
            return
        }
        val path = v.path; val text = psi.text
        val cat = when {
            path.contains("/ui/") || path.contains("/screens/") || text.contains("@Composable") || text.contains("ViewModel") || text.contains("Activity") -> "Presentation"
            path.contains("/domain/") || text.contains("UseCase") -> "Domain"
            path.contains("/data/") || path.contains("/db/") || text.contains("@Dao") || v.name.contains("Manager") -> "Data"
            v.name.contains("Receiver") || v.name.contains("Service") || v.name.contains("Ble") -> "Infrastructure"
            else -> "Common / Utils"
        }
        val sub = if (path.contains("/features/")) path.split("/features/")[1].split("/").first() else v.parent?.name ?: "main"
        data.blocks[cat]?.getOrPut(sub) { mutableListOf() }?.add(parseFile(psi, sub))
    }

    private fun parseFile(f: PsiFile, sub: String): FileNode {
        val classes = mutableListOf<ClassNode>()
        if (f is KtFile) {
            f.getChildrenOfType<KtClassOrObject>().forEach { classes.add(parseKtClass(it)) }
            val topLevelFunctions = f.declarations.filterIsInstance<KtFunction>()
            if (topLevelFunctions.isNotEmpty()) {
                classes.add(ClassNode("TopLevel", "${f.name}.TopLevel", "VirtualClass", topLevelFunctions.map { parseKtFunction(it, "TopLevel") }))
            }
        } else if (f is PsiJavaFile) f.classes.forEach { classes.add(parseJavaClass(it)) }
        return FileNode(f.name, f.virtualFile.path, sub, classes)
    }

    private fun parseKtClass(kt: KtClassOrObject): ClassNode {
        val methods = kt.declarations.filterIsInstance<KtFunction>().map { parseKtFunction(it, kt.name ?: "Unknown") }
        return ClassNode(kt.name ?: "Anon", kt.fqName?.asString(), "Class", methods)
    }

    private fun parseKtFunction(fn: KtFunction, className: String): MethodNode {
        var order = 0
        val calls = PsiTreeUtil.findChildrenOfType(fn, KtCallExpression::class.java).map { call ->
            order++
            val res = call.calleeExpression?.references?.firstOrNull()?.resolve()
            val targetLoc = when (res) {
                is KtFunction -> "${res.containingKtFile.name} > ${res.name}"
                is PsiMethod -> "${res.containingFile.name} > ${res.name}"
                else -> "External/Library"
            }
            val isUsed = call.parent is KtProperty || call.parent is KtReturnExpression
            CallNode(call.calleeExpression?.text ?: "unknown", null, targetLoc, if (isUsed) "REQUEST" else "ACTION", call.text, order, false)
        }
        val isEntry = listOf("onCreate", "onReceive", "doWork", "onViewCreated", "init").contains(fn.name) || fn.annotationEntries.any { it.shortName?.asString() == "Composable" }
        return MethodNode("${className}.${fn.name}", fn.name ?: "anon", isEntry, calls)
    }

    private fun parseJavaClass(jc: PsiClass): ClassNode {
        val methods = jc.methods.map { m ->
            var orderCounter = 0
            val calls = PsiTreeUtil.findChildrenOfType(m, PsiMethodCallExpression::class.java).map { call ->
                orderCounter++
                CallNode(call.methodExpression.referenceName ?: "unknown", null, null, "ACTION", call.text, orderCounter, false)
            }
            MethodNode("${jc.name}.${m.name}", m.name, listOf("onCreate", "init").contains(m.name), calls)
        }
        return ClassNode(jc.name ?: "Anon", jc.qualifiedName, "Class", methods)
    }
}
