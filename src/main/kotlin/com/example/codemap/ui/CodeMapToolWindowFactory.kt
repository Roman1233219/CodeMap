package com.example.codemap.ui

import com.example.codemap.CodeMap.data.CodeMapCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import java.awt.*
import javax.swing.*

class CodeMapToolWindowFactory : ToolWindowFactory {

    private lateinit var core: CodeMapCore
    private lateinit var mainPanel: JBPanel<*>
    private lateinit var progressBar: JProgressBar
    private lateinit var scanBtn: JButton

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        core = CodeMapCore(project)
        mainPanel = JBPanel<JBPanel<*>>(BorderLayout())

        val controls = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            insets = Insets(10, 10, 10, 10)
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        scanBtn = JButton("Спарсить и Анализировать (V6)")
        progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
        }

        scanBtn.addActionListener {
            scanBtn.isEnabled = false
            progressBar.value = 0
            
            core.refreshDatabase(
                onProgress = { p -> SwingUtilities.invokeLater { progressBar.value = p } },
                onFinished = {
                    SwingUtilities.invokeLater {
                        scanBtn.isEnabled = true
                    }
                }
            )
        }

        controls.add(scanBtn, gbc)
        gbc.gridy = 1
        controls.add(progressBar, gbc)

        mainPanel.add(controls, BorderLayout.NORTH)

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
