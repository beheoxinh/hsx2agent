package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.agent.claude.ClaudeCliClient
import com.github.catatafishen.agentbridge.agent.codex.CodexAppServerClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AgentIconProvider {
    @JvmField
    val ICON: Icon = com.intellij.util.IconUtil.toSize(loadIcon("agentbridge.svg"), 16, 16)

    private val defaultIcon: Icon = ICON
    private val claudeIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("claude.svg"), 16, 16)
    private val copilotIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("copilot.svg"), 16, 16)
    private val opencodeIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("opencode.svg"), 16, 16)
    private val junieIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("junie.svg"), 16, 16)
    private val kiroIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("kiro.svg"), 16, 16)
    private val codexIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("codex.svg"), 16, 16)
    private val hermesIcon: Icon = com.intellij.util.IconUtil.toSize(loadIcon("hermes.svg"), 16, 16)

    fun getDefaultIcon(): Icon = defaultIcon

    fun getIcon(name: String?): Icon {
        if (name == null) return defaultIcon
        return getIconForProfile(name) ?: getIconForDisplayName(name) ?: defaultIcon
    }

    private fun getIconForDisplayName(name: String): Icon? {
        return when (name) {
            "GitHub Copilot" -> copilotIcon
            "OpenCode" -> opencodeIcon
            "Junie" -> junieIcon
            "Kiro" -> kiroIcon
            "Hermes Agent" -> hermesIcon
            "Claude CLI", "Claude Code CLI", "claude" -> claudeIcon
            "Codex" -> codexIcon
            else -> null
        }
    }

    fun getIconForProfile(profileId: String?): Icon? {
        return when (profileId) {
            ClaudeCliClient.PROFILE_ID -> claudeIcon
            AgentProfileManager.COPILOT_PROFILE_ID -> copilotIcon
            AgentProfileManager.OPENCODE_PROFILE_ID -> opencodeIcon
            AgentProfileManager.JUNIE_PROFILE_ID -> junieIcon
            AgentProfileManager.KIRO_PROFILE_ID -> kiroIcon
            AgentProfileManager.HERMES_PROFILE_ID -> hermesIcon
            CodexAppServerClient.PROFILE_ID -> codexIcon
            else -> null
        }
    }

    private fun loadIcon(filename: String): Icon {
        return IconLoader.getIcon("/icons/expui/$filename", AgentIconProvider::class.java)
    }
}
