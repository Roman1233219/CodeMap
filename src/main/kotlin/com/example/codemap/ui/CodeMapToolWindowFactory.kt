package com.example.codemap.ui

import com.example.codemap.CodeMap.data.CodeMapCore
import com.example.codemap.CodeMap.data.ArchitectureChecker
import com.example.codemap.CodeMap.data.PatternChecker
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.*

class CodeMapToolWindowFactory : ToolWindowFactory {

    private lateinit var core: CodeMapCore
    private lateinit var browser: JBCefBrowser
    private lateinit var jsQuery: JBCefJSQuery
    private lateinit var backQuery: JBCefJSQuery
    private lateinit var downloadQuery: JBCefJSQuery
    private lateinit var instructionQuery: JBCefJSQuery
    private lateinit var scanBtn: JButton
    private lateinit var editSysBtn: JButton
    private lateinit var exportBtn: JButton
    private lateinit var progressBar: JProgressBar
    private lateinit var archComboBox: JComboBox<String>
    private lateinit var patternComboBox: JComboBox<String>
    private lateinit var checkArchBtn: JButton
    private var currentProject: Project? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        currentProject = project
        core = CodeMapCore(project)
        
        ensureSystemFunctionsFileExists(project)

        val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())
        val controls = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 2, 2, 2)
            fill = GridBagConstraints.HORIZONTAL
            weighty = 0.0
        }

        scanBtn = JButton("🚀 Анализировать проект")
        progressBar = JProgressBar(0, 100).apply { isStringPainted = true }
        editSysBtn = JButton("📝 Системные функции")
        exportBtn = JButton("📤 Экспорт PSI.json")
        
        val architectures = arrayOf("Архитектура", "Clean Architecture", "Layered", "Modular", "Onion", "Hexagonal")
        archComboBox = JComboBox(architectures)
        
        val patterns = arrayOf("Паттерны", "MVVM", "MVI", "MVP", "MVC", "VIPER", "Redux")
        patternComboBox = JComboBox(patterns)
        
        checkArchBtn = JButton("Проверить")

        gbc.gridy = 0
        
        gbc.gridx = 0; gbc.weightx = 0.0
        controls.add(scanBtn, gbc)
        
        gbc.gridx = 1; gbc.weightx = 1.0
        controls.add(progressBar, gbc)
        
        gbc.gridx = 2; gbc.weightx = 0.0
        controls.add(editSysBtn, gbc)
        
        gbc.gridx = 3; gbc.weightx = 0.0
        controls.add(exportBtn, gbc)
        
        gbc.gridx = 4; gbc.weightx = 0.0
        controls.add(archComboBox, gbc)
        
        gbc.gridx = 5; gbc.weightx = 0.0
        controls.add(patternComboBox, gbc)
        
        gbc.gridx = 6; gbc.weightx = 0.0
        controls.add(checkArchBtn, gbc)

        mainPanel.add(controls, BorderLayout.NORTH)

        // --- Инициализация браузера и JS Queries ---
        browser = JBCefBrowser()
        jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        jsQuery.addHandler { arg ->
            val parts = arg.split("|")
            if (parts.size >= 2) {
                jumpToCode(project, parts[0], parts[1].toIntOrNull() ?: 0)
            }
            null
        }

        backQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        backQuery.addHandler { loadVisualization(); null }

        downloadQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        downloadQuery.addHandler { arg ->
            val desktop = File(System.getProperty("user.home"), "Desktop/Architecture_Report.txt")
            desktop.writeText(arg)
            JOptionPane.showMessageDialog(null, "Отчет сохранен на Рабочий стол!")
            null
        }

        instructionQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        instructionQuery.addHandler { showInstructionsHtml(); null }

        mainPanel.add(browser.component, BorderLayout.CENTER)

        scanBtn.addActionListener {
            scanBtn.isEnabled = false
            progressBar.value = 0
            core.refreshDatabase(
                onProgress = { p -> SwingUtilities.invokeLater { progressBar.value = p } },
                onFinished = {
                    SwingUtilities.invokeLater {
                        scanBtn.isEnabled = true
                        loadVisualization()
                    }
                }
            )
        }

        editSysBtn.addActionListener {
            val file = File(project.basePath, "system_functions.json")
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }

        exportBtn.addActionListener {
            val source = File(project.basePath, ".codemap/PSI.json")
            if (source.exists()) {
                val desktop = File(System.getProperty("user.home"), "Desktop/PSI.json")
                Files.copy(source.toPath(), desktop.toPath(), StandardCopyOption.REPLACE_EXISTING)
                JOptionPane.showMessageDialog(null, "Экспортировано на Рабочий стол!")
            }
        }
        
        checkArchBtn.addActionListener {
            val selectedArch = archComboBox.selectedItem as String
            val selectedPattern = patternComboBox.selectedItem as String
            
            val archChecker = ArchitectureChecker(project)
            val patternChecker = PatternChecker(project)
            
            val reportData = mutableListOf<Map<String, Any>>()
            
            if (selectedArch != "Архитектура") {
                val res = archChecker.check(selectedArch)
                reportData.add(mapOf("title" to "Архитектура: $selectedArch", "isOk" to res.isOk, "message" to res.message, "details" to res.details))
            }
            
            if (selectedPattern != "Паттерны") {
                val res = patternChecker.check(selectedPattern)
                reportData.add(mapOf("title" to "Паттерн: $selectedPattern", "isOk" to res.isOk, "message" to res.message, "details" to res.details))
            }
            
            if (reportData.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Выберите архитектуру или паттерн для проверки")
            } else {
                showHtmlReport(reportData)
            }
        }

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
        
        loadVisualization()
    }

    private fun showHtmlReport(data: List<Map<String, Any>>) {
        val htmlTemplate = javaClass.getResourceAsStream("/webapp/architecture.html")?.bufferedReader()?.readText() ?: "<h1>Error loading template</h1>"
        val json = Gson().toJson(mapOf("results" to data))
        val finalHtml = htmlTemplate.replace(
            "<!-- DATA_INJECTION_MARKER -->",
            """
            <script>
                window.REPORT_DATA = $json;
                window.backToMap = function() { ${backQuery.inject("")} };
                window.downloadReport = function() { 
                    const text = document.getElementById('content').innerText;
                    ${downloadQuery.inject("text")} 
                };
            </script>
            """.trimIndent()
        )
        browser.loadHTML(finalHtml)
    }

    private fun showInstructionsHtml() {
        val htmlTemplate = javaClass.getResourceAsStream("/webapp/instructions.html")?.bufferedReader()?.readText() ?: "<h1>Error loading template</h1>"
        val finalHtml = htmlTemplate.replace(
            "<!-- DATA_INJECTION_MARKER -->",
            "<script>window.backToMap = function() { ${backQuery.inject("")} };</script>"
        )
        browser.loadHTML(finalHtml)
    }

    private fun jumpToCode(project: Project, filePath: String, line: Int) {
        ApplicationManager.getApplication().invokeLater {
            val file = File(filePath)
            if (file.exists()) {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                if (virtualFile != null) {
                    val descriptor = OpenFileDescriptor(project, virtualFile, line, 0)
                    FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                }
            }
        }
    }

    private fun ensureSystemFunctionsFileExists(project: Project) {
        val projectPath = project.basePath ?: return
        val file = File(projectPath, "system_functions.json")
        if (!file.exists()) {
            val defaultContent = javaClass.getResourceAsStream("/webapp/system_functions.json")?.bufferedReader()?.readText() ?: "{}"
            file.writeText(defaultContent)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        }
    }

    private fun loadVisualization() {
        val projectPath = currentProject?.basePath ?: return
        val htmlResource = javaClass.getResourceAsStream("/webapp/index.html")?.bufferedReader()?.readText() ?: "<h1>HTML Not Found</h1>"
        
        val psiFile = File(projectPath, ".codemap/PSI.json")
        val psiJson = if (psiFile.exists()) psiFile.readText() else "{ \"files\": [] }"
        val systemFile = File(projectPath, "system_functions.json")
        val systemJson = if (systemFile.exists()) systemFile.readText() else "{}"

        val renderJs = javaClass.getResourceAsStream("/webapp/render.js")?.bufferedReader()?.readText() ?: ""
        val detectorJs = javaClass.getResourceAsStream("/webapp/android-detector.js")?.bufferedReader()?.readText() ?: ""

        val finalHtml = htmlResource
            .replace("<script src=\"render.js\"></script>", "<script>$renderJs</script>")
            .replace("<script src=\"android-detector.js\"></script>", "<script>$detectorJs</script>")
            .replace(
                "<!-- DATA_INJECTION_MARKER -->",
                """
                <script>
                    window.PSI_DATA = $psiJson;
                    window.SYSTEM_FUNCTIONS = $systemJson;
                    window.jumpToCode = function(path, line) {
                        ${jsQuery.inject("path + '|' + line")}
                    };
                    window.showInstructions = function() {
                        ${instructionQuery.inject("")}
                    };
                </script>
                """.trimIndent()
            )

        browser.loadHTML(finalHtml)
    }
}
