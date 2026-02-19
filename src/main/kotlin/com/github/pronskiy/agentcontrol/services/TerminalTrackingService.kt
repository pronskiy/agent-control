package com.github.pronskiy.agentcontrol.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.github.pronskiy.agentcontrol.toolWindow.containsWidget
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

enum class TerminalState {
    IDLE, RUNNING, COMPLETED,
    CLAUDE_WORKING,
    CLAUDE_WAITING,
}

data class TerminalCardData(
    val widget: TerminalWidget,
    val title: String,
    val state: TerminalState,
    val lastCommand: String,
    val outputPreview: String,
    val isClaudeCode: Boolean = false,
)

fun interface TerminalStateListener {
    fun onStateChanged(cards: List<TerminalCardData>)
}

@Service(Service.Level.PROJECT)
class TerminalTrackingService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {

    private val listeners = mutableListOf<TerminalStateListener>()
    private var previousCards: List<TerminalCardData> = emptyList()
    private val terminatedWidgets = mutableSetOf<TerminalWidget>()
    private val trackedWidgets = mutableSetOf<TerminalWidget>()
    private val viewedWidgets = mutableMapOf<TerminalWidget, Long>()
    private val claudeStateReader = ClaudeStateReader()

    init {
        coroutineScope.launch {
            while (isActive) {
                delay(1000)
                try {
                    pollTerminals()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Error polling terminals", e)
                }
            }
        }
    }

    fun triggerPoll() {
        coroutineScope.launch {
            delay(200)
            try {
                pollTerminals()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Error polling terminals", e)
            }
        }
    }

    private fun pollTerminals() {
        ApplicationManager.getApplication().invokeAndWait(
            { pollTerminalsOnEdt() },
            ModalityState.nonModal(),
        )
    }

    private fun pollTerminalsOnEdt() {
        val manager = TerminalToolWindowManager.getInstance(project)
        val widgets = manager.terminalWidgets

        for (widget in widgets) {
            if (widget !in trackedWidgets) {
                trackedWidgets.add(widget)
                widget.addTerminationCallback({ terminatedWidgets.add(widget) }, this@TerminalTrackingService)
            }
        }

        val cards = mutableListOf<TerminalCardData>()

        val projectBasePath = project.basePath ?: ""

        for (widget in widgets) {
            val title = widget.terminalTitle.buildTitle()
            val text = widget.getText().toString()
            val lines = text.lines().filter { it.isNotBlank() }
            val outputPreview = lines.takeLast(3).joinToString("\n")
            val lastCommand = lines.lastOrNull { it.startsWith("$") || it.startsWith("%") || it.startsWith(">") }
                ?.removePrefix("$ ")?.removePrefix("% ")?.removePrefix("> ")
                ?: ""

            val running = try {
                widget.isCommandRunning()
            } catch (e: Exception) {
                LOG.debug("isCommandRunning() failed for widget '$title'", e)
                false
            }

            val isClaude = text.contains("claude") && running
            val claudeSession = if (isClaude) claudeStateReader.getActiveSession(projectBasePath) else null

            val state = when {
                widget in terminatedWidgets -> TerminalState.COMPLETED
                isClaude && claudeSession?.state == "working" -> TerminalState.CLAUDE_WORKING
                isClaude && (claudeSession?.state == "waiting" || claudeSession?.state == "idle") -> TerminalState.CLAUDE_WAITING
                running -> TerminalState.RUNNING
                else -> TerminalState.IDLE
            }

            cards.add(
                TerminalCardData(
                    widget = widget,
                    title = title,
                    state = state,
                    lastCommand = lastCommand,
                    outputPreview = outputPreview,
                    isClaudeCode = isClaude,
                )
            )
        }

        // Also include terminated widgets no longer in the active set
        for (widget in terminatedWidgets) {
            if (widget !in widgets) {
                val title = widget.terminalTitle.buildTitle()
                cards.add(
                    TerminalCardData(
                        widget = widget,
                        title = title,
                        state = TerminalState.COMPLETED,
                        lastCommand = "",
                        outputPreview = "",
                    )
                )
            }
        }

        // Viewed-state transition: COMPLETED → IDLE after 3s of viewing
        val terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
        val selectedComponent = if (terminalToolWindow != null && terminalToolWindow.isVisible) {
            terminalToolWindow.contentManager.selectedContent?.component
        } else null

        val now = System.currentTimeMillis()
        val completedWidgets = cards.filter { it.state == TerminalState.COMPLETED }.map { it.widget }.toSet()

        for (widget in completedWidgets) {
            val isViewed = selectedComponent != null && containsWidget(selectedComponent, widget.component)
            if (isViewed) {
                val viewedSince = viewedWidgets.getOrPut(widget) { now }
                if (now - viewedSince >= 3000) {
                    terminatedWidgets.remove(widget)
                    viewedWidgets.remove(widget)
                }
            } else {
                viewedWidgets.remove(widget)
            }
        }
        // Clean up viewedWidgets for widgets that are no longer completed
        viewedWidgets.keys.removeAll { it !in completedWidgets }

        // Rebuild cards list to reflect any COMPLETED→IDLE transitions
        val updatedCards = cards.map { card ->
            if (card.state == TerminalState.COMPLETED && card.widget !in terminatedWidgets) {
                card.copy(state = TerminalState.IDLE)
            } else card
        }

        if (updatedCards != previousCards) {
            previousCards = updatedCards
            for (listener in listeners) {
                listener.onStateChanged(updatedCards)
            }
        }
    }

    fun addListener(listener: TerminalStateListener) {
        listeners.add(listener)
        if (previousCards.isNotEmpty()) {
            listener.onStateChanged(previousCards)
        }
    }

    fun removeListener(listener: TerminalStateListener) {
        listeners.remove(listener)
    }

    fun getCurrentCards(): List<TerminalCardData> = previousCards

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        private val LOG = logger<TerminalTrackingService>()
    }
}
