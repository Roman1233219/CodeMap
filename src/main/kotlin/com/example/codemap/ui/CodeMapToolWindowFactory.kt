package com.example.codemap.ui

import com.example.codemap.CodeMap.data.CodeMapCore
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.*

class CodeMapToolWindowFactory : ToolWindowFactory {

    private lateinit var core: CodeMapCore
    private lateinit var browser: JBCefBrowser
    private lateinit var scanBtn: JButton
    private lateinit var editSysBtn: JButton
    private lateinit var exportBtn: JButton
    private lateinit var progressBar: JProgressBar
    private var currentProject: Project? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        currentProject = project
        core = CodeMapCore(project)
        val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())

        // --- Панель управления ---
        val controls = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        scanBtn = JButton("🚀 Анализировать проект")
        progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
        }

        // Кнопки после прогресс-бара
        editSysBtn = JButton("📝 Системные функции")
        exportBtn = JButton("📤 Экспорт PSI.json")

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        controls.add(scanBtn, gbc)
        
        gbc.gridy = 1
        controls.add(progressBar, gbc)

        gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0.5
        controls.add(editSysBtn, gbc)
        
        gbc.gridx = 1
        controls.add(exportBtn, gbc)

        mainPanel.add(controls, BorderLayout.NORTH)

        // --- Браузер ---
        browser = JBCefBrowser()
        mainPanel.add(browser.component, BorderLayout.CENTER)

        // Логика кнопок
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
            val projectPath = project.basePath ?: return@addActionListener
            val file = File(projectPath, "system_functions.json")
            if (file.exists()) {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                if (virtualFile != null) {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            } else {
                JOptionPane.showMessageDialog(null, "Файл system_functions.json не найден в корне проекта")
            }
        }

        exportBtn.addActionListener {
            val projectPath = project.basePath ?: return@addActionListener
            val source = File(projectPath, ".codemap/PSI.json")
            if (source.exists()) {
                val desktop = File(System.getProperty("user.home"), "Desktop/PSI.json")
                try {
                    Files.copy(source.toPath(), desktop.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    JOptionPane.showMessageDialog(null, "Файл успешно экспортирован на Рабочий стол!")
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(null, "Ошибка экспорта: ${e.message}")
                }
            } else {
                JOptionPane.showMessageDialog(null, "Сначала запустите анализ!")
            }
        }

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
        
        loadVisualization()
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
                "<script>",
                """
                <script>
                    window.PSI_DATA = $psiJson;
                    window.SYSTEM_FUNCTIONS = $systemJson;
                """.trimIndent()
            )

        browser.loadHTML(finalHtml)
    }
}
