package com.github.pronskiy.agentcontrol.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import javax.swing.SwingUtilities

enum class TerminalState {
    IDLE, RUNNING, COMPLETED
}

data class TerminalCardData(
    val widget: TerminalWidget,
    val title: String,
    val state: TerminalState,
    val lastCommand: String,
    val outputPreview: String,
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
        SwingUtilities.invokeAndWait {
            pollTerminalsOnEdt()
        }
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

        for (widget in widgets) {
            val title = widget.terminalTitle.buildTitle()
            val state = when {
                widget in terminatedWidgets -> TerminalState.COMPLETED
                widget.isCommandRunning() -> TerminalState.RUNNING
                else -> TerminalState.IDLE
            }
            val text = widget.getText().toString()
            val lines = text.lines().filter { it.isNotBlank() }
            val outputPreview = lines.takeLast(3).joinToString("\n")
            val lastCommand = lines.lastOrNull { it.startsWith("$") || it.startsWith("%") || it.startsWith(">") }
                ?.removePrefix("$ ")?.removePrefix("% ")?.removePrefix("> ")
                ?: ""

            cards.add(
                TerminalCardData(
                    widget = widget,
                    title = title,
                    state = state,
                    lastCommand = lastCommand,
                    outputPreview = outputPreview,
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

        if (cards != previousCards) {
            previousCards = cards
            for (listener in listeners) {
                listener.onStateChanged(cards)
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
