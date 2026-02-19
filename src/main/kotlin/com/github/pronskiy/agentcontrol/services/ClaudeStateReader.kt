package com.github.pronskiy.agentcontrol.services

import com.intellij.openapi.diagnostic.logger
import java.io.File

data class ClaudeSession(
    val sessionId: String,
    val state: String,
    val cwd: String,
    val timestamp: Long,
)

class ClaudeStateReader {

    private val stateDir = File("/tmp/claude-agent-control")
    private var cachedSessions: List<ClaudeSession> = emptyList()
    private var lastDirModified: Long = 0L
    private var lastFileTimestamps: Map<String, Long> = emptyMap()

    fun getActiveSessions(projectBasePath: String): List<ClaudeSession> {
        refreshIfNeeded()
        val now = System.currentTimeMillis() / 1000
        return cachedSessions.filter { session ->
            session.cwd == projectBasePath && (now - session.timestamp) < STALE_THRESHOLD_SECONDS
        }
    }

    fun getActiveSession(projectBasePath: String): ClaudeSession? {
        return getActiveSessions(projectBasePath).maxByOrNull { it.timestamp }
    }

    private fun refreshIfNeeded() {
        if (!stateDir.isDirectory) {
            cachedSessions = emptyList()
            return
        }

        val files = stateDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        val currentTimestamps = files.associate { it.name to it.lastModified() }

        if (currentTimestamps == lastFileTimestamps) return

        lastFileTimestamps = currentTimestamps
        cachedSessions = files.mapNotNull { parseStateFile(it) }
    }

    private fun parseStateFile(file: File): ClaudeSession? {
        return try {
            val content = file.readText()
            val sessionId = extractJsonString(content, "session_id") ?: return null
            val state = extractJsonString(content, "state") ?: return null
            val cwd = extractJsonString(content, "cwd") ?: return null
            val timestamp = extractJsonLong(content, "timestamp") ?: return null
            ClaudeSession(sessionId, state, cwd, timestamp)
        } catch (e: Exception) {
            LOG.debug("Failed to parse state file: ${file.name}", e)
            null
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\":\"([^\"]*)\""
        return Regex(pattern).find(json)?.groupValues?.get(1)
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = "\"$key\":(\\d+)"
        return Regex(pattern).find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    companion object {
        private val LOG = logger<ClaudeStateReader>()
        private const val STALE_THRESHOLD_SECONDS = 300L
    }
}
