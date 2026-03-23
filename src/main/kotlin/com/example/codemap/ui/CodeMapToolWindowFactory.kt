package com.example.codemap.ui

import com.example.codemap.CodeMap.data.CodeMapCore
import com.example.codemap.CodeMap.server.CodeMapServer
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.*
import javax.swing.*

class CodeMapToolWindowFactory : ToolWindowFactory {

    private lateinit var core: CodeMapCore
    private var server: CodeMapServer? = null
    private lateinit var mainPanel: JBPanel<*>
    private lateinit var progressBar: JProgressBar
    private lateinit var scanBtn: JButton
    private lateinit var showBtn: JButton
    private lateinit var exportBtn: JButton
    private var browser: JBCefBrowser? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        core = CodeMapCore(project)
        server = CodeMapServer(project.basePath ?: "")
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
        exportBtn = JButton("Экспорт")
        
        progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            preferredSize = Dimension(150, 20)
        }

        scanBtn.addActionListener {
            scanBtn.isEnabled = false
            showBtn.isEnabled = false
            exportBtn.isEnabled = false
            progressBar.value = 0
            
            core.refreshDatabase(
                onProgress = { p -> SwingUtilities.invokeLater { progressBar.value = p } },
                onFinished = {
                    SwingUtilities.invokeLater {
                        scanBtn.isEnabled = true
                        showBtn.isEnabled = true
                        exportBtn.isEnabled = true
                    }
                }
            )
        }

        showBtn.addActionListener {
            // 1. Запускаем сервер (если еще не запущен)
            server?.start(8080)
            
            // 2. Вызываем системный браузер
            BrowserUtil.browse("http://localhost:8080/index.html")
            
            // 3. Также обновляем внутренний браузер плагина
            browser?.loadURL("http://localhost:8080/index.html")
        }

        exportBtn.addActionListener {
            core.exportToDesktop()
        }

        controls.add(scanBtn)
        controls.add(progressBar)
        controls.add(showBtn)
        controls.add(exportBtn)

        mainPanel.add(controls, BorderLayout.NORTH)
        mainPanel.add(browser!!.component, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
