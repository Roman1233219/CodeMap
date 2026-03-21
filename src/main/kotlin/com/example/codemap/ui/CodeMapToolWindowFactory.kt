package com.example.codemap.ui

import com.example.codemap.CodeMap.data.CodeMapCore
import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.*
import javax.swing.*

class CodeMapToolWindowFactory : ToolWindowFactory {

    private lateinit var core: CodeMapCore
    private lateinit var mainPanel: JBPanel<*>
    private lateinit var progressBar: JProgressBar
    private lateinit var scanBtn: JButton
    private lateinit var showBtn: JButton
    private var browser: JBCefBrowser? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        core = CodeMapCore(project)
        mainPanel = JBPanel<JBPanel<*>>(BorderLayout())

        if (!JBCefApp.isSupported()) {
            mainPanel.add(JBLabel("JCEF не поддерживается в этой среде.", SwingConstants.CENTER))
            val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
            toolWindow.contentManager.addContent(content)
            return
        }

        browser = JBCefBrowser()

        val controls = JPanel(FlowLayout(FlowLayout.LEFT))
        scanBtn = JButton("Спарсить данные")
        showBtn = JButton("Показать визуализацию")
        progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            preferredSize = Dimension(150, 20)
        }

        scanBtn.addActionListener {
            scanBtn.isEnabled = false
            showBtn.isEnabled = false
            core.refreshDatabase(
                onProgress = { p -> SwingUtilities.invokeLater { progressBar.value = p } },
                onFinished = {
                    SwingUtilities.invokeLater {
                        scanBtn.isEnabled = true
                        showBtn.isEnabled = true
                    }
                }
            )
        }

        showBtn.addActionListener {
            val jsonData = core.getJsonData()
            if (jsonData == "{}") return@addActionListener
            
            // Используем Gson для безопасного экранирования всего JSON
            val safeJson = Gson().toJson(jsonData)
            
            val htmlStream = javaClass.getResourceAsStream("/webapp/index.html")
            val htmlText = htmlStream?.bufferedReader()?.use { it.readText() } ?: "<h1>Error</h1>"
            
            // Добавляем обработчик загрузки, чтобы впрыснуть данные как только страница готова
            browser?.jbCefClient?.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true) {
                        browser?.executeJavaScript(
                            "window.initData(JSON.parse($safeJson));", 
                            browser.url, 0
                        )
                    }
                }
            }, browser!!.cefBrowser)

            browser?.loadHTML(htmlText)
        }

        controls.add(scanBtn); controls.add(progressBar); controls.add(showBtn)
        mainPanel.add(controls, BorderLayout.NORTH)
        mainPanel.add(browser!!.component, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
