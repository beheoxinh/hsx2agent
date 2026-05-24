package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.agent.claude.ClaudeCliClient
import com.github.catatafishen.agentbridge.agent.codex.CodexAppServerClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AgentIconProvider {
    private val claudeIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("claude.svg"), 16, 16)
    private val copilotIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("copilot.svg"), 16, 16)
    private val opencodeIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("opencode.svg"), 16, 16)
    private val junieIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("junie.svg"), 16, 16)
    private val kiroIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("kiro.svg"), 16, 16)
    private val codexIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("codex.svg"), 16, 16)
    private val hermesIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("hermes.svg"), 16, 16)
    private val openclawIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("openclaw.svg"), 16, 16)
    private val piIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("pi.svg"), 16, 16)

    fun getIcon(name: String?): Icon? {
        if (name == null) return null
        return getIconForProfile(name) ?: getIconForDisplayName(name)
    }

    private fun getIconForDisplayName(name: String): Icon? {
        return when (name) {
            "GitHub Copilot" -> copilotIcon
            "OpenCode" -> opencodeIcon
            "OpenClaw" -> openclawIcon
            "Junie" -> junieIcon
            "Kiro" -> kiroIcon
            "Hermes Agent" -> hermesIcon
            "Claude CLI", "Claude Code CLI", "claude" -> claudeIcon
            "Codex" -> codexIcon
            "Pi", "pi" -> piIcon
            else -> null
        }
    }

    fun getIconForProfile(profileId: String?): Icon? {
        return when (profileId) {
            ClaudeCliClient.PROFILE_ID -> claudeIcon
            AgentProfileManager.COPILOT_PROFILE_ID -> copilotIcon
            AgentProfileManager.OPENCODE_PROFILE_ID -> opencodeIcon
            AgentProfileManager.OPENCLAW_PROFILE_ID -> openclawIcon
            AgentProfileManager.JUNIE_PROFILE_ID -> junieIcon
            AgentProfileManager.KIRO_PROFILE_ID -> kiroIcon
            AgentProfileManager.HERMES_PROFILE_ID -> hermesIcon
            AgentProfileManager.PI_PROFILE_ID -> piIcon
            CodexAppServerClient.PROFILE_ID -> codexIcon
            else -> null
        }
    }

    private fun loadIcon(filename: String): Icon {
        return IconLoader.getIcon("/icons/expui/$filename", AgentIconProvider::class.java)
    }
}
