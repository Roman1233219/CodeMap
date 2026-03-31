package com.example.codemap.CodeMap.data

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import java.io.File

// --- Структуры данных для правил архитектуры ---
data class LayerRule(
    val name: String, 
    val mustNotDependOn: List<String>? = null, 
    val mustDependOn: List<String>? = null, 
    val description: String
)

data class ArchitectureRule(
    val name: String, 
    val layers: List<LayerRule>
)

data class ArchitectureRulesConfig(val architectures: List<ArchitectureRule>)

// --- Результат проверки ---
data class CheckResult(
    val isOk: Boolean, 
    val message: String, 
    val details: List<String> = emptyList()
)

class ArchitectureChecker(private val project: Project) {
    private val gson = Gson()
    
    fun check(selectedArch: String): CheckResult {
        val basePath = project.basePath ?: return CheckResult(false, "Не удалось определить путь к проекту")
        val psiFile = File(basePath, ".codemap/PSI.json")
        
        if (!psiFile.exists()) {
            return CheckResult(false, "Сначала проанализируйте проект (нажмите '🚀 Анализировать')")
        }

        val psiData = try {
            gson.fromJson(psiFile.readText(), PSIData::class.java)
        } catch (e: Exception) {
            return CheckResult(false, "Ошибка при чтении PSI.json: ${e.message}")
        }

        val rulesFile = javaClass.getResourceAsStream("/architecture_rules.json")?.bufferedReader()?.readText()
            ?: return CheckResult(false, "Файл правил архитектуры не найден в ресурсах")
            
        val config = gson.fromJson(rulesFile, ArchitectureRulesConfig::class.java)
        val archRule = config.architectures.find { it.name == selectedArch } 
            ?: return CheckResult(false, "Правила для архитектуры '$selectedArch' не найдены")

        val violations = mutableListOf<String>()
        
        // 1. Автоматическое определение ролей файлов на основе вызовов (Smart Detection)
        val fileRoles = mutableMapOf<String, String>() // filePath -> layerName
        
        psiData.files.forEach { file ->
            val allCalls = file.functions.flatMap { it.calls }
            val hasUI = allCalls.any { it.targetType == "UI" || it.targetType == "ANDROID" }
            val hasData = allCalls.any { it.targetType == "STORAGE" || it.targetType == "NETWORK" }
            
            val role = when {
                hasUI && !hasData -> "presentation"
                hasData && !hasUI -> "data"
                !hasUI && !hasData -> "domain"
                else -> "mixed" 
            }
            fileRoles[file.filePath] = role
        }

        // 2. Проверка зависимостей между слоями и нарушений SRP
        psiData.files.forEach { file ->
            val currentRole = fileRoles[file.filePath] ?: return@forEach
            val layerRule = archRule.layers.find { it.name == currentRole }
            
            if (currentRole == "mixed") {
                val uiFunctions = file.functions.filter { f -> f.calls.any { it.targetType == "UI" || it.targetType == "ANDROID" } }.map { it.name }
                val dataFunctions = file.functions.filter { f -> f.calls.any { it.targetType == "STORAGE" || it.targetType == "NETWORK" } }.map { it.name }
                violations.add("Файл ${file.fileName} смешивает логику (SRP): UI-функции ($uiFunctions), Data-функции ($dataFunctions)")
            }

            if (layerRule != null) {
                file.functions.forEach { function ->
                    function.calls.forEach { call ->
                        val targetFile = psiData.files.find { it.fileName == call.targetFile }
                        if (targetFile != null) {
                            val targetRole = fileRoles[targetFile.filePath] ?: "unknown"
                            
                            if (layerRule.mustNotDependOn?.contains(targetRole) == true) {
                                violations.add("Функция '${function.name}' в ${file.fileName}: вызывает ${call.targetName} из слоя '$targetRole' (запрещено для '$currentRole')")
                            }
                        }
                    }
                }
            }
        }

        return if (violations.isEmpty()) {
            CheckResult(true, "Проверка архитектуры '$selectedArch' пройдена успешно!")
        } else {
            CheckResult(false, "Обнаружены нарушения в '$selectedArch':", violations.distinct())
        }
    }
}
