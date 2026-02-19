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
    private val dismissedCompleted = mutableSetOf<TerminalWidget>()
    private val knownClaudeWidgets = mutableSetOf<TerminalWidget>()
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

    private data class CollectedWidgetData(
        val widget: TerminalWidget,
        val title: String,
        val text: String,
        val outputPreview: String,
        val lastCommand: String,
    )

    private fun pollTerminals() {
        // Phase 1 (EDT): collect widget data, register callbacks
        val collectedData = mutableListOf<CollectedWidgetData>()
        ApplicationManager.getApplication().invokeAndWait({
            val manager = TerminalToolWindowManager.getInstance(project)
            val widgets = manager.terminalWidgets

            for (widget in widgets) {
                if (widget !in trackedWidgets) {
                    trackedWidgets.add(widget)
                    widget.addTerminationCallback({ terminatedWidgets.add(widget) }, this@TerminalTrackingService)
                }
            }

            for (widget in widgets) {
                val title = widget.terminalTitle.buildTitle()
                val text = widget.getText().toString()
                val lines = text.lines().filter { it.isNotBlank() }
                val outputPreview = lines.takeLast(3).joinToString("\n")
                val lastCommand = lines.lastOrNull { it.startsWith("$") || it.startsWith("%") || it.startsWith(">") }
                    ?.removePrefix("$ ")?.removePrefix("% ")?.removePrefix("> ")
                    ?: ""
                collectedData.add(CollectedWidgetData(widget, title, text, outputPreview, lastCommand))
            }
        }, ModalityState.nonModal())

        // Phase 2 (background thread): check isCommandRunning() — requires non-EDT
        val runningMap = mutableMapOf<TerminalWidget, Boolean>()
        for (data in collectedData) {
            runningMap[data.widget] = try {
                data.widget.isCommandRunning()
            } catch (e: Exception) {
                LOG.debug("isCommandRunning() failed for widget '${data.title}'", e)
                false
            }
        }

        // Phase 3 (EDT): compute states and notify listeners
        ApplicationManager.getApplication().invokeAndWait({
            val projectBasePath = project.basePath ?: ""
            val activeWidgets = collectedData.map { it.widget }.toSet()
            val cards = mutableListOf<TerminalCardData>()

            for (data in collectedData) {
                val running = runningMap[data.widget] ?: false

                val claudeSession = claudeStateReader.getActiveSession(projectBasePath)
                if (claudeSession != null && data.text.contains("claude", ignoreCase = true)) {
                    knownClaudeWidgets.add(data.widget)
                }

                val isClaude = data.widget in knownClaudeWidgets

                if (isClaude && claudeSession?.state != "completed") {
                    dismissedCompleted.remove(data.widget)
                }

                val state = when {
                    data.widget in terminatedWidgets -> TerminalState.COMPLETED
                    isClaude && claudeSession?.state == "completed" && data.widget !in dismissedCompleted -> TerminalState.COMPLETED
                    isClaude && claudeSession?.state == "working" -> TerminalState.CLAUDE_WORKING
                    isClaude && claudeSession?.state == "waiting" -> TerminalState.CLAUDE_WAITING
                    isClaude -> TerminalState.IDLE
                    running -> TerminalState.RUNNING
                    else -> TerminalState.IDLE
                }

                cards.add(
                    TerminalCardData(
                        widget = data.widget,
                        title = data.title,
                        state = state,
                        lastCommand = data.lastCommand,
                        outputPreview = data.outputPreview,
                        isClaudeCode = isClaude,
                    )
                )
            }

            // Also include terminated widgets no longer in the active set
            for (widget in terminatedWidgets) {
                if (widget !in activeWidgets) {
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
                        if (widget in terminatedWidgets) {
                            terminatedWidgets.remove(widget)
                        } else {
                            dismissedCompleted.add(widget)
                        }
                        viewedWidgets.remove(widget)
                    }
                } else {
                    viewedWidgets.remove(widget)
                }
            }
            viewedWidgets.keys.removeAll { it !in completedWidgets }

            val updatedCards = cards.map { card ->
                if (card.state == TerminalState.COMPLETED && card.widget !in terminatedWidgets
                    && card.widget in dismissedCompleted) {
                    card.copy(state = TerminalState.IDLE)
                } else card
            }

            if (updatedCards != previousCards) {
                previousCards = updatedCards
                for (listener in listeners) {
                    listener.onStateChanged(updatedCards)
                }
            }
        }, ModalityState.nonModal())
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
