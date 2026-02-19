package com.github.pronskiy.agentcontrol.toolWindow

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.github.pronskiy.agentcontrol.MyBundle
import com.github.pronskiy.agentcontrol.services.*
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.*
import javax.swing.*

internal fun containsWidget(parent: Component, target: Component): Boolean {
    if (parent === target) return true
    if (parent is Container) {
        for (child in parent.components) {
            if (containsWidget(child, target)) return true
        }
    }
    return false
}

class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        project.service<ClaudeHooksInstaller>().ensureHooksInstalled()

        val panel = KanbanBoardPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

private class KanbanBoardPanel(private val project: Project) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val idleColumn = ColumnPanel(MyBundle.message("kanban.idle"))
    private val runningColumn = ColumnPanel(MyBundle.message("kanban.running"))
    private val awaitingColumn = ColumnPanel(MyBundle.message("kanban.awaitingInput"))
    private val completedColumn = ColumnPanel(MyBundle.message("kanban.completed"))
    private val trackingService = project.service<TerminalTrackingService>()

    init {
        val topBar = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10)
            add(JBLabel(MyBundle.message("kanban.title")).apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }, BorderLayout.WEST)
            add(JButton(MyBundle.message("kanban.newTerminal")).apply {
                toolTipText = "Create new terminal session"
                addActionListener { createNewTerminal() }
            }, BorderLayout.EAST)
        }

        val columnsPanel = JBPanel<JBPanel<*>>(GridLayout(1, 4, 8, 0)).apply {
            border = JBUI.Borders.empty(0, 8, 8, 8)
            add(idleColumn)
            add(runningColumn)
            add(awaitingColumn)
            add(completedColumn)
        }

        add(topBar, BorderLayout.NORTH)
        add(columnsPanel, BorderLayout.CENTER)

        trackingService.addListener { cards ->
            SwingUtilities.invokeLater { updateCards(cards) }
        }

        // Show initial state
        val initialCards = trackingService.getCurrentCards()
        if (initialCards.isNotEmpty()) {
            updateCards(initialCards)
        }
    }

    private fun createNewTerminal() {
        TerminalToolWindowManager.getInstance(project).createShellWidget(
            project.basePath, "Terminal", true, true
        )
        trackingService.triggerPoll()

        // Move the newly created terminal tab to the editor area and pin it
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return@invokeLater
            val action = ActionManager.getInstance().getAction("MoveToolWindowTabToEditorAction") ?: return@invokeLater

            val dataContext = SimpleDataContext.builder()
                .add(PlatformDataKeys.TOOL_WINDOW, toolWindow)
                .add(CommonDataKeys.PROJECT, project)
                .build()

            val event = AnActionEvent.createFromDataContext(
                ActionPlaces.UNKNOWN,
                action.templatePresentation.clone(),
                dataContext,
            )
            action.actionPerformed(event)

            // Pin the newly opened editor tab
            ApplicationManager.getApplication().invokeLater {
                val fem = FileEditorManagerEx.getInstanceEx(project)
                val window = fem.currentWindow ?: return@invokeLater
                val file = window.selectedFile ?: return@invokeLater
                window.setFilePinned(file, true)
            }
        }
    }

    private fun updateCards(cards: List<TerminalCardData>) {
        idleColumn.setCards(cards.filter { it.state == TerminalState.IDLE }, project)
        runningColumn.setCards(cards.filter { it.state == TerminalState.RUNNING || it.state == TerminalState.CLAUDE_WORKING }, project)
        awaitingColumn.setCards(cards.filter { it.state == TerminalState.CLAUDE_WAITING }, project)
        completedColumn.setCards(cards.filter { it.state == TerminalState.COMPLETED }, project)
    }
}

private class ColumnPanel(title: String) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val cardsPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val emptyLabel = JBLabel(MyBundle.message("kanban.noTerminals")).apply {
        foreground = JBColor.GRAY
        alignmentX = Component.CENTER_ALIGNMENT
        border = JBUI.Borders.empty(20, 0)
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(6)
        )

        val header = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            border = JBUI.Borders.emptyBottom(6)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val scrollPane = JBScrollPane(cardsPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        cardsPanel.add(emptyLabel)
    }

    fun setCards(cards: List<TerminalCardData>, project: Project) {
        cardsPanel.removeAll()
        if (cards.isEmpty()) {
            cardsPanel.add(emptyLabel)
        } else {
            for (card in cards) {
                cardsPanel.add(TerminalCardPanel(card, project))
                cardsPanel.add(Box.createRigidArea(Dimension(0, 4)))
            }
        }
        cardsPanel.revalidate()
        cardsPanel.repaint()
    }
}

private class TerminalCardPanel(
    private val cardData: TerminalCardData,
    private val project: Project,
) : JBPanel<JBPanel<*>>() {

    private val claudeBackground = JBColor(Color(240, 235, 250), Color(65, 58, 80))
    private val defaultBackground = if (cardData.isClaudeCode) claudeBackground else JBColor(Color(245, 245, 245), Color(60, 63, 65))
    private val highlightBackground = JBColor(Color(200, 220, 255), Color(75, 90, 120))

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(6)
        )
        background = defaultBackground
        isOpaque = true
        maximumSize = Dimension(Int.MAX_VALUE, 100)
        alignmentX = Component.LEFT_ALIGNMENT

        val smallFont = font.deriveFont(font.size - 3f)

        if (cardData.isClaudeCode) {
            val badgeLabel = JBLabel(MyBundle.message("kanban.claude.badge")).apply {
                font = smallFont.deriveFont(Font.BOLD)
                foreground = JBColor(Color(120, 80, 200), Color(180, 150, 240))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(badgeLabel)
            add(Box.createRigidArea(Dimension(0, 2)))
        }

        val nameLabel = JBLabel(cardData.title).apply {
            font = smallFont.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(nameLabel)

        if (cardData.isClaudeCode && cardData.state == TerminalState.CLAUDE_WAITING) {
            add(Box.createRigidArea(Dimension(0, 2)))
            val waitingLabel = JBLabel(MyBundle.message("kanban.claude.waiting")).apply {
                font = smallFont
                foreground = JBColor(Color(120, 80, 200), Color(180, 150, 240))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(waitingLabel)
        }

        if (cardData.lastCommand.isNotEmpty()) {
            add(Box.createRigidArea(Dimension(0, 2)))
            val commandLabel = JBLabel(truncate(cardData.lastCommand, 40)).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, smallFont.size)
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(commandLabel)
        }

        if (cardData.outputPreview.isNotEmpty()) {
            add(Box.createRigidArea(Dimension(0, 2)))
            val outputArea = JBLabel("<html>${cardData.outputPreview.replace("\n", "<br>")}</html>").apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, smallFont.size)
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(outputArea)
        }

        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val clickListener = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                focusTerminal()
            }
        }
        addMouseListener(clickListener)
        for (child in components) {
            child.addMouseListener(clickListener)
            child.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    private fun focusTerminal() {
        // First try to find the terminal in the editor area
        val fem = FileEditorManager.getInstance(project)
        for (file in fem.openFiles) {
            for (editor in fem.getEditors(file)) {
                if (containsWidget(editor.component, cardData.widget.component)) {
                    fem.openFile(file, true)
                    cardData.widget.requestFocus()
                    highlightBriefly()
                    return
                }
            }
        }

        // Fall back to Terminal tool window
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        toolWindow.activate {
            val contentManager = toolWindow.contentManager
            for (content in contentManager.contents) {
                val component = content.component
                if (containsWidget(component, cardData.widget.component)) {
                    contentManager.setSelectedContent(content, true)
                    cardData.widget.requestFocus()
                    break
                }
            }
        }
        highlightBriefly()
    }

    private fun highlightBriefly() {
        background = highlightBackground
        repaint()
        Timer(150) {
            background = defaultBackground
            repaint()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) text.take(maxLength - 1) + "\u2026" else text
    }
}
