package com.github.pronskiy.agentcontrol.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.GsonBuilder
import java.io.File

@Service(Service.Level.PROJECT)
class ClaudeHooksInstaller(private val project: Project) {

    fun ensureHooksInstalled() {
        val basePath = project.basePath ?: return
        try {
            installHookScript(basePath)
            installHooksConfig(basePath)
        } catch (e: Exception) {
            LOG.warn("Failed to install Claude hooks", e)
        }
    }

    private fun installHookScript(basePath: String) {
        val hooksDir = File(basePath, ".claude/hooks")
        hooksDir.mkdirs()

        val scriptFile = File(hooksDir, "agent-control-state.sh")
        val resourceContent = javaClass.getResourceAsStream("/hooks/agent-control-state.sh")
            ?.bufferedReader()?.readText() ?: return

        if (scriptFile.exists() && scriptFile.readText() == resourceContent) return

        scriptFile.writeText(resourceContent)
        scriptFile.setExecutable(true)
    }

    private fun installHooksConfig(basePath: String) {
        val claudeDir = File(basePath, ".claude")
        claudeDir.mkdirs()

        val settingsFile = File(claudeDir, "settings.json")
        val hookCommand = "\"\$CLAUDE_PROJECT_DIR\"/.claude/hooks/agent-control-state.sh"

        val root = if (settingsFile.exists()) {
            try {
                JsonParser.parseString(settingsFile.readText()).asJsonObject
            } catch (e: Exception) {
                LOG.warn("Failed to parse existing settings.json, creating new one", e)
                JsonObject()
            }
        } else {
            JsonObject()
        }

        val hooks = root.getAsJsonObject("hooks") ?: JsonObject().also { root.add("hooks", it) }

        for (event in HOOK_EVENTS) {
            ensureHookEntry(hooks, event, hookCommand)
        }

        val gson = GsonBuilder().setPrettyPrinting().create()
        settingsFile.writeText(gson.toJson(root))
    }

    private fun ensureHookEntry(hooks: JsonObject, event: String, command: String) {
        val eventArray = hooks.getAsJsonArray(event) ?: JsonArray().also { hooks.add(event, it) }

        // Check if our hook is already registered
        for (entry in eventArray) {
            val entryObj = entry.asJsonObject ?: continue
            val entryHooks = entryObj.getAsJsonArray("hooks") ?: continue
            for (hook in entryHooks) {
                val hookObj = hook.asJsonObject ?: continue
                if (hookObj.get("command")?.asString == command) return
            }
        }

        // Add our hook entry
        val hookObj = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", command)
        }
        val hooksArray = JsonArray().apply { add(hookObj) }
        val entry = JsonObject().apply {
            addProperty("matcher", "")
            add("hooks", hooksArray)
        }
        eventArray.add(entry)
    }

    companion object {
        private val LOG = logger<ClaudeHooksInstaller>()
        private val HOOK_EVENTS = listOf("SessionStart", "UserPromptSubmit", "PermissionRequest", "PostToolUse", "Stop", "SessionEnd")
    }
}
