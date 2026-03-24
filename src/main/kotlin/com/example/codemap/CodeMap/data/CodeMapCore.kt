package com.example.codemap.CodeMap.data

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import java.io.File
import com.intellij.openapi.ui.Messages

// --- Структура JSON (3 уровня + Ранжирование + Шины + Навигация) ---

data class CallNode(
    val targetName: String,
    val targetId: String?,
    val targetLocation: String?,
    val type: String,
    val text: String,
    val hasDataCallback: Boolean = false
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
    val blocks: Map<String, MutableMap<String, MutableList<FileNode>>> = linkedMapOf(
        "Presentation" to mutableMapOf(),
        "Domain" to mutableMapOf(),
        "Data" to mutableMapOf(),
        "Infrastructure" to mutableMapOf(),
        "DI" to mutableMapOf(),
        "Common / Utils" to mutableMapOf()
    )
)

class CodeMapCore(private val project: Project) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dbPath = "${project.basePath}/codemap_data.json"

    fun refreshDatabase(onProgress: (Int) -> Unit, onFinished: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val smartData = CodeMapData()
            val allFiles = mutableListOf<VirtualFile>()
            
            ApplicationManager.getApplication().runReadAction {
                val basePath = project.basePath ?: return@runReadAction
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                if (baseDir != null) collectFiles(baseDir, allFiles)
            }

            val totalFiles = allFiles.size
            if (totalFiles > 0) {
                allFiles.forEachIndexed { index, vFile ->
                    ApplicationManager.getApplication().runReadAction {
                        scanSingleFile(vFile, smartData)
                    }
                    onProgress(((index + 1).toFloat() / totalFiles * 100).toInt())
                }
            }
            
            rankAndSortFiles(smartData)

            val file = File(dbPath)
            if (file.exists()) file.delete()
            file.writeText(gson.toJson(smartData))
            
            ApplicationManager.getApplication().invokeLater { onFinished() }
        }
    }

    private fun collectFiles(vFile: VirtualFile, result: MutableList<VirtualFile>) {
        if (vFile.isDirectory) {
            val name = vFile.name
            if (name == "build" || name.startsWith(".") || name == "test" || name == "androidTest") return
            vFile.children.forEach { collectFiles(it, result) }
        } else {
            val ext = vFile.extension?.lowercase() ?: ""
            val path = vFile.path.replace("\\", "/")
            if ((ext == "kt" || ext == "java") && !path.contains("/src/test/") && !path.contains("/src/androidTest/")) {
                result.add(vFile)
            }
        }
    }

    private fun scanSingleFile(vFile: VirtualFile, data: CodeMapData) {
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return
        val (category, subBlock) = resolveCategoryAndSubBlock(psiFile)
        val fileNode = parseFile(psiFile, subBlock)
        data.blocks[category]?.getOrPut(subBlock) { mutableListOf() }?.add(fileNode)
    }

    private fun resolveCategoryAndSubBlock(file: PsiFile): Pair<String, String> {
        val path = file.virtualFile.path.replace("\\", "/")
        val fileName = file.name
        val text = file.text

        if (path.contains("/on-device-server/") || fileName == "LabelResolver.java" || path.contains("LogkatTracer")) {
            return Pair("Common / Utils", "system-tools")
        }

        var category = when {
            path.contains("/core/bluetooth/") || path.contains("/core/services/") || 
            path.contains("/receiver/") || fileName == "StepManager.kt" || 
            fileName == "StepResetReceiver.kt" || fileName == "BleManager.kt" -> "Infrastructure"
            path.contains("/ui/") || path.contains("/screens/") || 
            path.contains("/components/") || fileName == "MainActivity.kt" -> "Presentation"
            path.contains("/domain/") -> "Domain"
            path.contains("/data/") || path.contains("/db/") || path.contains("/repository/") -> "Data"
            path.contains("/di/") -> "DI"
            else -> null
        }

        if (category == null) {
            category = when {
                text.contains("SensorEventListener") || text.contains("SensorManager") ||
                text.contains("BluetoothDevice") || text.contains("Worker") || 
                text.contains("Service") || text.contains("Notification") -> "Infrastructure"
                text.contains("@Composable") || text.contains("ViewModel") || 
                text.contains("Activity") || text.contains("Fragment") -> "Presentation"
                text.contains("@Module") || text.contains("@Provides") || text.contains("module {") -> "DI"
                text.contains("RepositoryImpl") || text.contains("@Dao") || text.contains("@Database") || 
                text.contains("@GET") || text.contains("@POST") -> "Data"
                text.contains("UseCase") || text.contains("Interactor") -> "Domain"
                else -> "Common / Utils"
            }
        }

        val subBlock = when {
            path.contains("/features/") -> {
                val parts = path.split("/features/")
                val featurePath = parts[1].split("/")
                val featureName = featurePath.firstOrNull() ?: "common"
                val layer = if (path.contains("/ui/")) "ui" else if (path.contains("/data/")) "data" else "logic"
                "$featureName ($layer)"
            }
            path.contains("/ui/screens/") -> "screens"
            path.contains("/ui/theme/") -> "theme"
            path.contains("/ui/components/") -> "components"
            path.contains("/core/ui/") -> "ui-core"
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

            val typeResult = resolveKtCallType(call)
            CallNode(
                targetName = call.calleeExpression?.text ?: "unknown",
                targetId = targetId,
                targetLocation = targetLoc,
                type = typeResult.first,
                text = call.text,
                hasDataCallback = typeResult.second
            )
        }
        return MethodNode(fqName, function.name ?: "anonymous", "Function: ${function.name}\nClass: $className", calls)
    }

    private fun resolveKtCallType(call: KtCallExpression): Pair<String, Boolean> {
        val name = call.calleeExpression?.text?.lowercase() ?: ""

        val lambdas = (call.lambdaArguments.map { it.getArgumentExpression() } +
                call.valueArguments.map { it.getArgumentExpression() })
            .filterIsInstance<KtLambdaExpression>()

        val hasCallback = lambdas.isNotEmpty()
        val hasDataCallback = lambdas.any { it.valueParameters.isNotEmpty() }

        // Методы, которые возвращают данные (REQUEST)
        val dataRequestPrefixes = listOf("get", "fetch", "load", "observe", "find", "query", "request")
        val isDataRequest = dataRequestPrefixes.any { name.startsWith(it) }

        // Методы, которые только запускают процесс (ACTION)
        val actionPrefixes = listOf("start", "stop", "run", "execute", "launch", "schedule", "set", "enable", "clear")
        val isAction = actionPrefixes.any { name.startsWith(it) }

        val type = when {
            hasCallback -> "REQUEST"
            isDataRequest -> "REQUEST"
            isAction -> "ACTION"
            isExpressionUsed(call) -> "REQUEST"
            else -> "ACTION"
        }

        return Pair(type, hasDataCallback)
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
                
                val name = call.methodExpression.referenceName?.lowercase() ?: ""
                val isRequest = (resolved?.returnType?.presentableText != "void") || 
                                name.startsWith("get") || name.startsWith("fetch") || name.startsWith("load")

                CallNode(
                    targetName = call.methodExpression.referenceName ?: "unknown",
                    targetId = resolved?.let { (it.containingClass?.qualifiedName ?: "Unknown") + "." + it.name + "($targetParams)" },
                    targetLocation = resolved?.let { "${it.containingFile.name} > ${it.containingClass?.name ?: "Unknown"} > ${it.name}" },
                    type = if (isRequest) "REQUEST" else "ACTION", 
                    text = call.text,
                    hasDataCallback = false
                )
            }
            MethodNode(fqName, method.name, "Method: ${method.name}\nClass: ${psiClass.name}", calls)
        }
        return ClassNode(psiClass.name ?: "Anonymous", psiClass.qualifiedName, if (psiClass.isInterface) "Interface" else "Class", methods)
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
            name.contains("Dao") || name.contains("Database") -> score += 400
        }
        score += allCalls.size * 10
        score += allCalls.count { it.targetId != null } * 5
        return score
    }

    fun getJsonData(): String {
        val file = File(dbPath)
        return if (file.exists()) file.readText() else "{}"
    }

    fun exportToDesktop() {
        val jsonData = getJsonData()
        if (jsonData == "{}") {
            Messages.showErrorDialog(project, "Нет данных для экспорта. Сначала выполните парсинг.", "Ошибка")
            return
        }
        val desktopPath = System.getProperty("user.home") + File.separator + "Desktop"
        val exportFile = File(desktopPath, "PSI.json")
        try {
            exportFile.writeText(jsonData)
            Messages.showInfoMessage(project, "Файл успешно экспортирован на рабочий стол: ${exportFile.absolutePath}", "Экспорт завершен")
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Ошибка при экспорте: ${e.message}", "Ошибка")
        }
    }

    fun openProjectFile(p: String) {
        val f = File(p)
        if (f.exists()) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
            vf?.let { ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(it, true)
            } }
        }
    }
}
