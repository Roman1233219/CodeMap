package com.example.codemap.ui

import com.example.codemap.CodeMap.data.CodeMapCore
import com.example.codemap.CodeMap.data.CodeMapData
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.LineBorder

class CodeMapToolWindowFactory : ToolWindowFactory {

    private lateinit var core: CodeMapCore
    private lateinit var mainContainer: JBPanel<*>
    private lateinit var cardLayout: CardLayout
    private val categories = listOf("Presentation", "Domain", "Data", "Infrastructure", "DI", "Common/Utils")

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        core = CodeMapCore(project)
        cardLayout = CardLayout()
        mainContainer = JBPanel<JBPanel<*>>(cardLayout)

        val placeholder = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            add(JBLabel("Нажмите 'Сканировать', чтобы увидеть структуру блоков"))
        }
        mainContainer.add(placeholder, "EMPTY")

        val gridWrapper = JBPanel<JBPanel<*>>(BorderLayout())
        mainContainer.add(gridWrapper, "GRID")

        val detailsPanel = JBPanel<JBPanel<*>>(BorderLayout())
        mainContainer.add(detailsPanel, "DETAILS")

        val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
        wrapper.add(createTopControls {
            val data = core.buildMapOnTheFly()
            gridWrapper.removeAll()
            gridWrapper.add(MapPanel(data))
            gridWrapper.revalidate()
            cardLayout.show(mainContainer, "GRID")
        }, BorderLayout.NORTH)
        
        wrapper.add(mainContainer, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(wrapper, "", false)
        toolWindow.contentManager.addContent(content)
        cardLayout.show(mainContainer, "EMPTY")
    }

    private fun createTopControls(onShowMap: () -> Unit): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            val scanBtn = JButton("Сканировать")
            val showBtn = JButton("Показать карту")
            val exportBtn = JButton("Экспорт на стол")
            
            scanBtn.addActionListener {
                scanBtn.isEnabled = false
                core.refreshDatabase { 
                    scanBtn.isEnabled = true
                    onShowMap()
                }
            }
            showBtn.addActionListener { onShowMap() }
            exportBtn.addActionListener { core.exportToDesktop() }

            add(scanBtn); add(showBtn); add(exportBtn)
        }
    }

    private inner class MapPanel(val mapData: CodeMapData) : JPanel(GridLayout(2, 3, 20, 20)) {
        init {
            border = JBUI.Borders.empty(20)
            background = Color.WHITE
            categories.forEach { name ->
                val subBlocks = mapData.blocks[name]
                val totalFiles = subBlocks?.values?.sumOf { it.size } ?: 0
                add(createCategoryBlock(name, totalFiles))
            }
        }
    }

    private fun createCategoryBlock(name: String, fileCount: Int) = JPanel(BorderLayout()).apply {
        border = LineBorder(Color.BLACK, 2)
        background = Color.LIGHT_GRAY
        val label = JLabel("<html><center>${name.uppercase()}<br>($fileCount файлов)</center></html>", SwingConstants.CENTER)
        label.font = Font("Arial", Font.BOLD, 14)
        add(label, BorderLayout.CENTER)
        
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) { showDetails(name) }
        })
    }

    private fun showDetails(categoryName: String) {
        val mapData = core.buildMapOnTheFly()
        // Извлекаем все файлы из всех подблоков данной категории
        val allFiles = mapData.blocks[categoryName]?.values?.flatten() ?: emptyList()
        
        val panel = mainContainer.getComponent(2) as JPanel
        panel.removeAll()
        panel.layout = BorderLayout()
        
        val header = JPanel(BorderLayout())
        header.add(JButton("← Назад").apply {
            addActionListener { cardLayout.show(mainContainer, "GRID") }
        }, BorderLayout.WEST)
        header.add(JLabel("Блок: $categoryName", SwingConstants.CENTER), BorderLayout.CENTER)
        panel.add(header, BorderLayout.NORTH)
        
        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color.WHITE
            allFiles.forEach { file ->
                val fileRow = JPanel(BorderLayout()).apply {
                    maximumSize = Dimension(1200, 100); border = LineBorder(Color.GRAY, 1)
                    background = Color(240, 240, 240)
                    
                    val methodsInfo = StringBuilder("<html>")
                    methodsInfo.append("<i>Group: ${file.subBlock}</i><br>")
                    file.classes.forEach { clazz ->
                        methodsInfo.append("<b>Class: ${clazz.name}</b><br>")
                        clazz.methods.forEach { method ->
                            val actions = method.calls.count { it.type == "ACTION" }
                            val requests = method.calls.count { it.type == "REQUEST" }
                            methodsInfo.append("&nbsp;&nbsp;- ${method.name} (A: $actions, R: $requests)<br>")
                        }
                    }
                    methodsInfo.append("</html>")
                    
                    add(JLabel("${file.fileName}"), BorderLayout.NORTH)
                    add(JLabel(methodsInfo.toString()), BorderLayout.CENTER)
                    
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (e.clickCount == 2) core.openProjectFile(file.path)
                        }
                    })
                }
                add(fileRow)
                add(Box.createRigidArea(Dimension(0, 10)))
            }
        }
        panel.add(JBScrollPane(stack), BorderLayout.CENTER)
        panel.revalidate(); cardLayout.show(mainContainer, "DETAILS")
    }
}
