package com.example.codemap.CodeMap.data

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import java.io.File
import com.intellij.openapi.ui.Messages

// --- Структура JSON (3 уровня + Ранжирование) ---

data class CallNode(
    val targetName: String,
    val targetId: String?,
    val targetLocation: String?,
    val type: String,         // ACTION или REQUEST
    val text: String
)

data class MethodNode(
    val id: String,
    val name: String,
    val tooltip: String,
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
    var importanceScore: Int = 0
)

data class CodeMapData(
    val blocks: Map<String, MutableMap<String, MutableList<FileNode>>> = mapOf(
        "Presentation" to mutableMapOf(),
        "Domain" to mutableMapOf(),
        "Data" to mutableMapOf(),
        "Infrastructure" to mutableMapOf(),
        "DI" to mutableMapOf(),
        "Common/Utils" to mutableMapOf()
    )
)

class CodeMapCore(private val project: Project) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dbPath = "${project.basePath}/.idea/codemap_smart_db.json"

    fun refreshDatabase(onFinished: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val smartData = CodeMapData()
            ApplicationManager.getApplication().runReadAction {
                val basePath = project.basePath ?: return@runReadAction
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                if (baseDir != null) scanProject(baseDir, smartData)
            }
            rankAndSortFiles(smartData)
            File(dbPath).writeText(gson.toJson(smartData))
            ApplicationManager.getApplication().invokeLater { onFinished() }
        }
    }

    private fun rankAndSortFiles(data: CodeMapData) {
        data.blocks.values.forEach { subBlocks ->
            subBlocks.values.forEach { fileList ->
                fileList.forEach { it.importanceScore = calculateImportance(it) }
                fileList.sortByDescending { it.importanceScore }
            }
        }
    }

    private fun calculateImportance(file: FileNode): Int {
        var score = 0
        val name = file.fileName
        val allCalls = file.classes.flatMap { it.methods }.flatMap { it.calls }
        when {
            name.contains("MainActivity") -> score += 2000
            name.contains("Activity") -> score += 1500
            name.contains("Fragment") || name.contains("Screen") -> score += 1000
            name.contains("ViewModel") -> score += 800
            name.contains("Repository") -> score += 600
            name.contains("Service") || name.contains("Manager") -> score += 500
        }
        score += allCalls.size * 10
        return score
    }

    private fun scanProject(vFile: VirtualFile, data: CodeMapData) {
        if (vFile.isDirectory) {
            val name = vFile.name
            if (name == "build" || name.startsWith(".") || name == "test" || name == "androidTest") return
            vFile.children.forEach { scanProject(it, data) }
            return
        }
        val path = vFile.path.replace("\\", "/")
        if (path.contains("/src/test/") || path.contains("/src/androidTest/")) return
        val ext = vFile.extension?.lowercase() ?: ""
        if (ext != "kt" && ext != "java") return

        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return
        val (category, subBlock) = resolveCategoryAndSubBlock(psiFile)
        
        val fileNode = parseFile(psiFile, subBlock)
        data.blocks[category]?.getOrPut(subBlock) { mutableListOf() }?.add(fileNode)
    }

    private fun resolveCategoryAndSubBlock(file: PsiFile): Pair<String, String> {
        val path = file.virtualFile.path.replace("\\", "/")
        val fileName = file.name
        val text = file.text

        // 1. ЖЕСТКИЕ ИСКЛЮЧЕНИЯ (Приоритет 1)
        if (path.contains("/on-device-server/") || fileName == "LabelResolver.java" || path.contains("LogkatTracer")) {
            return Pair("Common/Utils", "system-tools")
        }

        // 2. ИЕРАРХИЯ ПО ПУТИ (Приоритет 2)
        val category = when {
            // Infrastructure (Сенсоры, Сервисы, Ресиверы)
            path.contains("/core/bluetooth/") || path.contains("/core/services/") || 
            path.contains("/receiver/") || fileName == "StepManager.kt" || 
            fileName == "StepResetReceiver.kt" || fileName == "BleManager.kt" -> "Infrastructure"

            // Presentation (UI)
            path.contains("/ui/") || path.contains("/screens/") || 
            path.contains("/components/") || fileName == "MainActivity.kt" -> "Presentation"

            // Domain / Data / DI
            path.contains("/domain/") -> "Domain"
            path.contains("/data/") || path.contains("/db/") || path.contains("/repository/") -> "Data"
            path.contains("/di/") -> "DI"

            else -> "Common/Utils"
        }

        // 3. УМНОЕ ОПРЕДЕЛЕНИЕ ПОДБЛОКА
        val subBlock = when {
            // Feature-based: features/search/ui -> search (ui)
            path.contains("/features/") -> {
                val parts = path.split("/features/")
                val featurePath = parts[1].split("/")
                val featureName = featurePath.firstOrNull() ?: "common"
                val layer = if (path.contains("/ui/")) "ui" else if (path.contains("/data/")) "data" else "logic"
                "$featureName ($layer)"
            }
            // Группировка по смыслу папок
            path.contains("/ui/screens/") -> "screens"
            path.contains("/ui/theme/") -> "theme"
            path.contains("/ui/components/") -> "components"
            path.contains("/core/ui/") -> "ui-core"
            path.contains("/data/local/") -> "local-data"
            path.contains("/data/remote/") -> "remote-data"
            // По умолчанию - имя родительской папки
            else -> file.virtualFile.parent?.name ?: "main"
        }

        return Pair(category, subBlock)
    }

    private fun parseFile(file: PsiFile, subBlock: String): FileNode {
        val classes = mutableListOf<ClassNode>()
        if (file is KtFile) {
            file.getChildrenOfType<KtClassOrObject>().forEach { classes.add(parseKtClass(it)) }
        } else if (file is PsiJavaFile) {
            file.classes.forEach { classes.add(parseJavaClass(it)) }
        }
        return FileNode(file.name, file.virtualFile.path, subBlock, classes)
    }

    private fun parseKtClass(ktClass: KtClassOrObject): ClassNode {
        val fqName = ktClass.fqName?.asString()
        val methods = ktClass.declarations.filterIsInstance<KtFunction>().map { parseKtFunction(it, ktClass.name ?: "Unknown") }
        return ClassNode(ktClass.name ?: "Anonymous", fqName, if (ktClass is KtClass && ktClass.isInterface()) "Interface" else "Class", methods)
    }

    private fun parseKtFunction(function: KtFunction, className: String): MethodNode {
        val params = function.valueParameters.joinToString(",") { it.typeReference?.text ?: "any" }
        val fqName = (function.fqName?.asString() ?: "${className}.${function.name}") + "($params)"
        val calls = PsiTreeUtil.findChildrenOfType(function, KtCallExpression::class.java).map { call ->
            val resolved = call.calleeExpression?.references?.firstOrNull()?.resolve()
            
            val targetId = when (resolved) {
                is KtFunction -> {
                    val p = resolved.valueParameters.joinToString(",") { it.typeReference?.text ?: "any" }
                    (resolved.fqName?.asString() ?: resolved.name) + "($p)"
                }
                is PsiMethod -> {
                    val p = resolved.parameterList.parameters.joinToString(",") { it.type.presentableText }
                    (resolved.containingClass?.qualifiedName + "." + resolved.name) + "($p)"
                }
                else -> null
            }
            
            val targetLoc = when (resolved) {
                is KtFunction -> "${resolved.containingKtFile.name} > ${resolved.name}"
                is PsiMethod -> "${resolved.containingFile.name} > ${resolved.containingClass?.name ?: "Unknown"} > ${resolved.name}"
                else -> "External/Library"
            }

            CallNode(call.calleeExpression?.text ?: "unknown", targetId, targetLoc, if (isExpressionUsed(call)) "REQUEST" else "ACTION", call.text)
        }
        return MethodNode(fqName, function.name ?: "anonymous", "Function: ${function.name}\nClass: $className", calls)
    }

    private fun isExpressionUsed(expr: KtExpression): Boolean {
        val parent = expr.parent
        return when (parent) {
            is KtProperty, is KtBinaryExpression, is KtReturnExpression, is KtValueArgument, 
            is KtIfExpression, is KtWhenCondition, is KtArrayAccessExpression, 
            is KtPostfixExpression, is KtPrefixExpression -> true
            is KtDotQualifiedExpression -> if (parent.receiverExpression == expr) true else isExpressionUsed(parent)
            is KtSafeQualifiedExpression -> if (parent.receiverExpression == expr) true else isExpressionUsed(parent)
            else -> false
        }
    }

    private fun parseJavaClass(psiClass: PsiClass): ClassNode {
        val methods = psiClass.methods.map { method ->
            val params = method.parameterList.parameters.joinToString(",") { it.type.presentableText }
            val fqName = (psiClass.qualifiedName ?: "Unknown") + "." + method.name + "($params)"
            val calls = PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression::class.java).map { call ->
                val resolved = call.resolveMethod()
                val targetParams = resolved?.parameterList?.parameters?.joinToString(",") { it.type.presentableText } ?: ""
                CallNode(call.methodExpression.referenceName ?: "unknown", resolved?.let { (it.containingClass?.qualifiedName ?: "Unknown") + "." + it.name + "($targetParams)" }, resolved?.let { "${it.containingFile.name} > ${it.containingClass?.name ?: "Unknown"} > ${it.name}" }, "UNKNOWN_JAVA", call.text)
            }
            MethodNode(fqName, method.name, "Method: ${method.name}\nClass: ${psiClass.name}", calls)
        }
        return ClassNode(psiClass.name ?: "Anonymous", psiClass.qualifiedName, if (psiClass.isInterface) "Interface" else "Class", methods)
    }

    fun buildMapOnTheFly(): CodeMapData {
        val dbFile = File(dbPath)
        if (!dbFile.exists()) return CodeMapData()
        return try {
            com.google.gson.Gson().fromJson(dbFile.readText(), CodeMapData::class.java)
        } catch (e: Exception) { CodeMapData() }
    }

    fun exportToDesktop() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val smartData = buildMapOnTheFly()
            val desktopPath = System.getProperty("user.home") + File.separator + "Desktop"
            val file = File(desktopPath, "codemap_export.json")
            try {
                file.writeText(gson.toJson(smartData))
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage("Иерархия исправлена! LabelResolver в Utils, StepManager в Infrastructure.", "Успех")
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog("Ошибка экспорта: ${e.message}", "Ошибка")
                }
            }
        }
    }

    fun openProjectFile(p: String) {
        val f = File(p)
        if (f.exists()) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
            vf?.let { ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(it, true)
            } }
        }
    }
}
