package com.example.codemap.ui

import com.example.codemap.CodeMap.data.CodeMapCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.*
import java.io.File
import javax.swing.*

class CodeMapToolWindowFactory : ToolWindowFactory {

    private lateinit var core: CodeMapCore
    private lateinit var browser: JBCefBrowser
    private lateinit var scanBtn: JButton
    private lateinit var progressBar: JProgressBar

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        core = CodeMapCore(project)
        val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())

        // --- Верхняя панель управления ---
        val controls = JPanel(FlowLayout(FlowLayout.LEFT))
        scanBtn = JButton("Анализировать проект")
        progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            preferredSize = Dimension(200, 20)
        }

        controls.add(scanBtn)
        controls.add(progressBar)
        mainPanel.add(controls, BorderLayout.NORTH)

        // --- Браузер для визуализации ---
        browser = JBCefBrowser()
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

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
        
        // Первичная загрузка (если данные уже есть)
        loadVisualization()
    }

    private fun loadVisualization() {
        val htmlResource = javaClass.getResourceAsStream("/webapp/index.html")?.bufferedReader()?.readText() ?: "<h1>HTML Not Found</h1>"
        
        // Читаем данные из файлов
        val psiJson = File(System.getProperty("user.home") + File.separator + "Desktop", "PSI.json").let {
            if (it.exists()) it.readText() else "{}"
        }
        val systemJson = javaClass.getResourceAsStream("/webapp/system_functions.json")?.bufferedReader()?.readText() ?: "{}"

        // Инъекция данных прямо в JS переменные перед загрузкой страницы
        val injectedHtml = htmlResource.replace(
            "<script",
            """
            <script>
                window.PSI_DATA = $psiJson;
                window.SYSTEM_FUNCTIONS = $systemJson;
            </script>
            <script
            """.trimIndent()
        )

        // Загружаем HTML. JCEF позволяет грузить контент как Data URI или через loadHTML
        browser.loadHTML(injectedHtml)
    }
}
