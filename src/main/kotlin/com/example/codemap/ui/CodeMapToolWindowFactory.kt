package com.example.codemap.ui

import com.example.codemap.CodeMap.data.CodeMapCore
import com.example.codemap.CodeMap.data.Connection
import com.example.codemap.CodeMap.data.MapData
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
import java.awt.geom.QuadCurve2D
import javax.swing.*
import javax.swing.border.LineBorder

class CodeMapToolWindowFactory : ToolWindowFactory {

    private lateinit var core: CodeMapCore
    private lateinit var mainContainer: JBPanel<*>
    private lateinit var cardLayout: CardLayout
    private val categories = listOf("Экраны", "Логика", "Данные", "Сеть", "Утилиты", "Прочее")
    private val blockComponents = mutableMapOf<String, JComponent>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        core = CodeMapCore(project)
        cardLayout = CardLayout()
        mainContainer = JBPanel<JBPanel<*>>(cardLayout)

        // Экран-заглушка
        val placeholder = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            add(JBLabel("Нажмите 'Сканировать', затем 'Показать карту'").apply {
                font = Font("Arial", Font.PLAIN, 16)
            })
        }
        mainContainer.add(placeholder, "EMPTY")

        // Контейнер для сетки (будет обновляться)
        val gridWrapper = JBPanel<JBPanel<*>>(BorderLayout())
        mainContainer.add(gridWrapper, "GRID")

        // Экран деталей
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
            val scanBtn = JButton("Сканировать проект")
            val showBtn = JButton("Показать карту")
            val dbBtn = JButton("Открыть БД (JSON)")
            
            scanBtn.addActionListener {
                scanBtn.isEnabled = false
                core.refreshDatabase { scanBtn.isEnabled = true }
            }
            showBtn.addActionListener { onShowMap() }
            dbBtn.addActionListener { core.openDbFile() }

            add(scanBtn); add(showBtn); add(dbBtn)
        }
    }

    // Кастомная панель, которая рисует И блоки И стрелки поверх них
    private inner class MapPanel(val mapData: MapData) : JPanel(GridLayout(2, 3, 50, 50)) {
        init {
            border = JBUI.Borders.empty(40)
            background = Color.WHITE
            blockComponents.clear()
            categories.forEach { name ->
                val block = createCategoryBlock(name)
                blockComponents[name] = block
                add(block)
            }
        }

        override fun paintChildren(g: Graphics) {
            super.paintChildren(g) // Сначала рисуем сами блоки (детей)
            
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            // ЯРКАЯ КРАСНАЯ ПОМЕТКА СЛОЯ
            g2.color = Color.RED
            g2.font = Font("Arial", Font.BOLD, 16)
            g2.drawString("DEBUG: ARROWS ENGINE ACTIVE", 20, 25)
            
            // Рисуем стрелки
            mapData.connections.forEach { conn ->
                drawArrow(g2, conn)
            }
        }

        private fun drawArrow(g2: Graphics2D, conn: Connection) {
            val fromComp = blockComponents[conn.from] ?: return
            val toComp = blockComponents[conn.to] ?: return

            val r1 = fromComp.bounds
            val r2 = toComp.bounds
            
            val p1 = Point(r1.centerX.toInt(), r1.centerY.toInt())
            val p2 = Point(r2.centerX.toInt(), r2.centerY.toInt())

            val isEvent = conn.type == "EVENT"
            // Насыщенные цвета без прозрачности
            g2.color = if (isEvent) Color(0, 150, 0) else Color(0, 100, 255)
            
            val weight = Math.max(3f, 3f + conn.weight * 2.0f)
            g2.stroke = BasicStroke(Math.min(18f, weight), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val ctrlX = (p1.x + p2.x) / 2.0 + (if (isEvent) 70 else -70)
            val ctrlY = (p1.y + p2.y) / 2.0 + (if (isEvent) -70 else 70)
            
            val curve = QuadCurve2D.Double(p1.x.toDouble(), p1.y.toDouble(), ctrlX, ctrlY, p2.x.toDouble(), p2.y.toDouble())
            g2.draw(curve)
            
            // Жирная точка в конце
            g2.fillOval(p2.x - 10, p2.y - 10, 20, 20)
        }
    }

    private fun createCategoryBlock(name: String) = JPanel(BorderLayout()).apply {
        border = LineBorder(Color.BLACK, 4) // ОЧЕНЬ ТОЛСТАЯ ЧЕРНАЯ ГРАНИЦА
        background = Color.WHITE
        val label = JLabel(name.uppercase(), SwingConstants.CENTER).apply {
            font = Font("Arial", Font.BOLD, 20) // КРУПНЫЙ ЖИРНЫЙ ТЕКСТ
            foreground = Color.BLACK
        }
        add(label, BorderLayout.CENTER)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) { showDetails(name) }
        })
    }

    private fun showDetails(categoryName: String) {
        val mapData = core.buildMapOnTheFly()
        val data = mapData.blocks[categoryName] ?: emptyList()
        val panel = mainContainer.getComponent(2) as JPanel
        panel.removeAll()
        panel.layout = BorderLayout()

        val header = JPanel(BorderLayout()).apply {
            add(JButton("← Назад").apply {
                addActionListener { cardLayout.show(mainContainer, "GRID") }
            }, BorderLayout.WEST)
            add(JLabel(categoryName.uppercase(), SwingConstants.CENTER).apply {
                font = Font("Arial", Font.BOLD, 22)
                foreground = Color.BLACK
            }, BorderLayout.CENTER)
            border = JBUI.Borders.empty(15)
            background = Color.WHITE
        }
        
        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color.WHITE
            add(createFileRect(categoryName, true))
            data.forEach { node -> add(createFileRect(node.name, false, node.path)) }
        }
        
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(stack), BorderLayout.CENTER)
        panel.revalidate(); cardLayout.show(mainContainer, "DETAILS")
    }

    private fun createFileRect(text: String, isH: Boolean, path: String? = null) = JPanel(BorderLayout()).apply {
        maximumSize = Dimension(1200, 60)
        preferredSize = Dimension(100, 60)
        border = LineBorder(Color.BLACK, 2)
        background = if (isH) Color.decode("#D0D0D0") else Color.WHITE
        add(JLabel(text, SwingConstants.CENTER).apply {
            font = Font("Arial", if (isH) Font.BOLD else Font.PLAIN, 16)
            foreground = Color.BLACK
        })
        
        if (!isH && path != null) {
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) core.openProjectFile(path)
                }
                override fun mouseEntered(e: MouseEvent?) { background = Color.decode("#E3F2FD") }
                override fun mouseExited(e: MouseEvent?) { background = Color.WHITE }
            })
        }
    }
}
