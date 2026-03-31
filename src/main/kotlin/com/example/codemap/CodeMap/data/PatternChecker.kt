package com.example.codemap.CodeMap.data

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import java.io.File

// --- Структуры данных для правил паттернов ---
data class PatternRuleDetail(
    val type: String,
    val suffix: String? = null,
    val mustNotImport: List<String>? = null,
    val mustHaveReferenceTo: String? = null,
    val names: List<String>? = null,
    val description: String
)

data class PatternRule(
    val name: String,
    val rules: List<PatternRuleDetail>
)

data class PatternRulesConfig(val patterns: List<PatternRule>)

class PatternChecker(private val project: Project) {
    private val gson = Gson()

    fun check(selectedPattern: String): CheckResult {
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

        val rulesFile = javaClass.getResourceAsStream("/pattern_rules.json")?.bufferedReader()?.readText()
            ?: return CheckResult(false, "Файл правил паттернов не найден")

        val config = gson.fromJson(rulesFile, PatternRulesConfig::class.java)
        val patternRule = config.patterns.find { it.name == selectedPattern } 
            ?: return CheckResult(false, "Правила для паттерна '$selectedPattern' не найдены")

        val violations = mutableListOf<String>()

        patternRule.rules.forEach { detail ->
            when (detail.type) {
                "class_suffix" -> {
                    val targetSuffix = detail.suffix ?: ""
                    // 1. Сначала ищем по суффиксу
                    var targets = psiData.files.filter { 
                        it.fileName.removeSuffix(".kt").removeSuffix(".java").endsWith(targetSuffix) 
                    }
                    
                    // 2. Если по суффиксу ничего не нашли, ищем "по поведению" (через связи)
                    if (targets.isEmpty() && targetSuffix.isNotEmpty()) {
                        targets = findByBehavior(psiData, targetSuffix)
                        if (targets.isNotEmpty()) {
                            violations.add("Инфо: Компоненты '$targetSuffix' определены по связям (суффиксы не найдены).")
                        }
                    }
                    
                    if (targets.isEmpty() && targetSuffix.isNotEmpty()) {
                        violations.add("Предупреждение: Не удалось найти компоненты для '$targetSuffix' ни по имени, ни по связям.")
                    }

                    targets.forEach { file ->
                        // Проверка на запрещенные вызовы (с детализацией по функциям)
                        if (detail.mustNotImport != null) {
                            file.functions.forEach { function ->
                                val badCalls = function.calls.filter { it.targetType == "UI" || it.targetType == "ANDROID" }
                                if (badCalls.isNotEmpty()) {
                                    val badMethods = badCalls.map { it.targetName }.distinct().joinToString(", ")
                                    violations.add("Функция '${function.name}' в ${file.fileName}: ${detail.description} (обнаружены вызовы: $badMethods)")
                                }
                            }
                        }
                        
                        // Проверка на наличие обязательной связи
                        if (detail.mustHaveReferenceTo != null) {
                            val hasRef = file.functions.flatMap { it.calls }
                                .any { it.targetFile.contains(detail.mustHaveReferenceTo, ignoreCase = true) || it.targetName.contains(detail.mustHaveReferenceTo, ignoreCase = true) }
                            
                            if (!hasRef) {
                                violations.add("Файл ${file.fileName}: ${detail.description} (в функциях не найдена связь с ${detail.mustHaveReferenceTo})")
                            }
                        }
                    }
                }
                
                "interface_presence" -> {
                    val requiredNames = detail.names ?: emptyList()
                    requiredNames.forEach { req ->
                        val found = psiData.files.any { it.fileName.contains(req, ignoreCase = true) }
                        if (!found) {
                            violations.add("Паттерн ${selectedPattern}: ${detail.description} (отсутствует компонент '$req')")
                        }
                    }
                }
            }
        }

        return if (violations.isEmpty()) {
            CheckResult(true, "Проверка паттерна '$selectedPattern' пройдена успешно!")
        } else {
            CheckResult(false, "Результаты проверки '$selectedPattern':", violations.distinct())
        }
    }

    private fun findByBehavior(psiData: PSIData, targetSuffix: String): List<FileNode> {
        val uiFiles = psiData.files.filter { f ->
            f.functions.flatMap { it.calls }.any { it.targetType == "UI" || it.targetType == "ANDROID" }
        }

        return when (targetSuffix) {
            "ViewModel", "Presenter" -> {
                val candidates = mutableListOf<FileNode>()
                uiFiles.forEach { uiFile ->
                    uiFile.functions.flatMap { it.calls }.forEach { call ->
                        val calledFile = psiData.files.find { it.fileName == call.targetFile }
                        if (calledFile != null && calledFile != uiFile) {
                            val hasUI = calledFile.functions.flatMap { it.calls }.any { it.targetType == "UI" || it.targetType == "ANDROID" }
                            if (!hasUI) {
                                candidates.add(calledFile)
                            }
                        }
                    }
                }
                candidates.distinctBy { it.filePath }
            }
            "Interactor", "Repository" -> {
                psiData.files.filter { f ->
                    val hasUI = f.functions.flatMap { it.calls }.any { it.targetType == "UI" }
                    val hasData = f.functions.flatMap { it.calls }.any { it.targetType == "NETWORK" || it.targetType == "STORAGE" }
                    !hasUI && hasData
                }
            }
            else -> emptyList()
        }
    }
}
