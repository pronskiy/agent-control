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
import org.jetbrains.plugins.terminal.vfs.TerminalSessionVirtualFileImpl
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
    private var renamingInProgress = false

    init {
        val topBar = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10)
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
            val action = ActionManager.getInstance().getAction("Terminal.MoveToEditor") ?: return@invokeLater

            val dataContext = SimpleDataContext.builder()
                .add(PlatformDataKeys.TOOL_WINDOW, toolWindow)
                .add(PlatformDataKeys.TOOL_WINDOW_CONTENT_MANAGER, toolWindow.contentManager)
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
        if (renamingInProgress) return
        val renameStart = { renamingInProgress = true }
        val renameEnd = { renamingInProgress = false; trackingService.triggerPoll() }
        idleColumn.setCards(cards.filter { it.state == TerminalState.IDLE }, project, renameStart, renameEnd)
        runningColumn.setCards(cards.filter { it.state == TerminalState.RUNNING || it.state == TerminalState.CLAUDE_WORKING }, project, renameStart, renameEnd)
        awaitingColumn.setCards(cards.filter { it.state == TerminalState.CLAUDE_WAITING }, project, renameStart, renameEnd)
        completedColumn.setCards(cards.filter { it.state == TerminalState.COMPLETED }, project, renameStart, renameEnd)
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

    fun setCards(cards: List<TerminalCardData>, project: Project, onRenameStart: () -> Unit, onRenameEnd: () -> Unit) {
        cardsPanel.removeAll()
        if (cards.isEmpty()) {
            cardsPanel.add(emptyLabel)
        } else {
            for (card in cards) {
                cardsPanel.add(TerminalCardPanel(card, project, onRenameStart, onRenameEnd))
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
    private val onRenameStart: () -> Unit,
    private val onRenameEnd: () -> Unit,
) : JBPanel<JBPanel<*>>() {

    private val claudeBackground = JBColor(Color(240, 235, 250), Color(65, 58, 80))
    private val defaultBackground = if (cardData.isClaudeCode) claudeBackground else JBColor(Color(245, 245, 245), Color(60, 63, 65))
    private val highlightBackground = JBColor(Color(200, 220, 255), Color(75, 90, 120))
    private val nameLabel: JBLabel

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
        val tinyFont = font.deriveFont(font.size - 5f)

        if (cardData.isClaudeCode) {
            val badgeLabel = JBLabel(MyBundle.message("kanban.claude.badge")).apply {
                font = smallFont.deriveFont(Font.BOLD)
                foreground = JBColor(Color(120, 80, 200), Color(180, 150, 240))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(badgeLabel)
            add(Box.createRigidArea(Dimension(0, 2)))
        }

        nameLabel = JBLabel(cardData.title).apply {
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
                font = Font(Font.MONOSPACED, Font.PLAIN, tinyFont.size)
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(commandLabel)
        }

        if (cardData.outputPreview.isNotEmpty()) {
            add(Box.createRigidArea(Dimension(0, 2)))
            val outputArea = JBLabel("<html>${cardData.outputPreview.replace("\n", "<br>")}</html>").apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, tinyFont.size)
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(outputArea)
        }

        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val mouseListener = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                if (e != null && SwingUtilities.isLeftMouseButton(e)) {
                    focusTerminal()
                }
            }
            override fun mousePressed(e: java.awt.event.MouseEvent?) {
                if (e != null && e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseReleased(e: java.awt.event.MouseEvent?) {
                if (e != null && e.isPopupTrigger) showContextMenu(e)
            }
        }
        addMouseListener(mouseListener)
        for (child in components) {
            child.addMouseListener(mouseListener)
            child.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    private fun showContextMenu(e: java.awt.event.MouseEvent) {
        val menu = JPopupMenu()
        menu.add(JMenuItem(MyBundle.message("kanban.rename")).apply {
            addActionListener { startRename() }
        })
        menu.add(JMenuItem(MyBundle.message("kanban.close")).apply {
            addActionListener { closeTerminal() }
        })
        menu.show(e.component, e.x, e.y)
    }

    private fun startRename() {
        onRenameStart()
        val index = (0 until componentCount).firstOrNull { getComponent(it) === nameLabel }
        if (index == null) {
            onRenameEnd()
            return
        }

        val textField = JTextField(cardData.title).apply {
            font = nameLabel.font
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + 4)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        remove(nameLabel)
        add(textField, index)
        revalidate()
        repaint()
        textField.requestFocusInWindow()
        textField.selectAll()

        var applied = false

        fun applyRename() {
            if (applied) return
            applied = true
            val newName = textField.text.trim()
            if (newName.isNotEmpty() && newName != cardData.title) {
                cardData.widget.terminalTitle.change { userDefinedTitle = newName }
                nameLabel.text = newName
            }
            remove(textField)
            add(nameLabel, index)
            revalidate()
            repaint()
            onRenameEnd()
        }

        fun cancelRename() {
            if (applied) return
            applied = true
            remove(textField)
            add(nameLabel, index)
            revalidate()
            repaint()
            onRenameEnd()
        }

        textField.addActionListener { applyRename() }
        textField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                applyRename()
            }
        })
        textField.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "cancelRename"
        )
        textField.actionMap.put("cancelRename", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                cancelRename()
            }
        })
    }

    private fun closeTerminal() {
        // Try editor area first
        val fem = FileEditorManager.getInstance(project)
        for (file in fem.openFiles) {
            if (file is TerminalSessionVirtualFileImpl && file.terminalWidget === cardData.widget) {
                fem.closeFile(file)
                return
            }
        }

        // Fall back to Terminal tool window
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        val contentManager = toolWindow.contentManager
        for (content in contentManager.contents) {
            if (containsWidget(content.component, cardData.widget.component)) {
                contentManager.removeContent(content, true)
                return
            }
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
