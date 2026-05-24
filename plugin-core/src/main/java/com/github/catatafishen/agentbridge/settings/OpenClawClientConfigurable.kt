package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil

@Suppress("unused")
class OpenClawClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("OpenClaw"),
    SearchableConfigurable {

    private val statusLabel = JBLabel()

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Status:") {
            cell(statusLabel)
        }
        row {
            val installNote = JBLabel(
                "<html>Install with <code>npm install -g openclaw</code>. " +
                    "Ensure it's available on PATH.</html>"
            )
            installNote.foreground = UIUtil.getContextHelpForeground()
            cell(installNote)
        }
        row {
            val link = HyperlinkLabel("OpenClaw on GitHub — github.com/openclaw/openclaw")
            link.setHyperlinkTarget("https://github.com/openclaw/openclaw")
            cell(link)
        }
        separator()
        row("OpenClaw binary:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { emptyText.text = "Auto-detect (leave empty)" }
                .comment("Leave empty to auto-detect on PATH.")
                .bindText(
                    { AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID).orEmpty() },
                    { AgentProfileManager.getInstance().saveBinaryPath(AGENT_ID, it.trim()) }
                )
        }
        separator()
        row("Gateway URL:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent {
                    emptyText.text = "wss://your-gateway.example.com"
                    toolTipText = "OpenClaw Gateway WebSocket URL"
                }
                .comment("The WebSocket URL of your OpenClaw Gateway.")
                .bindText(
                    { loadSetting(KEY_GATEWAY_URL) },
                    { saveSetting(KEY_GATEWAY_URL, it.trim()) }
                )
        }
        row("Gateway token:") {
            passwordField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent {
                    toolTipText = "OpenClaw Gateway auth token"
                }
                .comment("Authentication token for the Gateway.")
                .bindText(
                    { loadSetting(KEY_GATEWAY_TOKEN) },
                    { saveSetting(KEY_GATEWAY_TOKEN, it.trim()) }
                )
        }
        row("Default session key:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent {
                    emptyText.text = "agent:main:main"
                    toolTipText = "Default Gateway session key"
                }
                .comment("Default session key (e.g., agent:main:main).")
                .bindText(
                    { loadSetting(KEY_DEFAULT_SESSION) },
                    { saveSetting(KEY_DEFAULT_SESSION, it.trim()) }
                )
        }
        row {
            val docsLink = HyperlinkLabel("OpenClaw Gateway docs — docs.openclaw.ai/cli/acp")
            docsLink.setHyperlinkTarget("https://docs.openclaw.ai/cli/acp")
            cell(docsLink)
        }
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        refreshStatusAsync()
    }

    private fun refreshStatusAsync() {
        statusLabel.text = "Checking..."
        statusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val version = AcpClientBinaryResolver(AGENT_ID, AGENT_ID).detectVersion()
            ApplicationManager.getApplication().invokeLater {
                if (version != null) {
                    statusLabel.text = "\u2713 OpenClaw found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text = "OpenClaw not found on PATH — install with npm install -g openclaw"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.client.openclaw"
        private const val AGENT_ID = "openclaw"
        private const val KEY_GATEWAY_URL = "openclaw.gatewayUrl"
        private const val KEY_GATEWAY_TOKEN = "openclaw.gatewayToken"
        private const val KEY_DEFAULT_SESSION = "openclaw.defaultSession"

        private fun loadSetting(key: String): String {
            return com.intellij.ide.util.PropertiesComponent.getInstance().getValue(key, "")
        }

        private fun saveSetting(key: String, value: String) {
            com.intellij.ide.util.PropertiesComponent.getInstance().setValue(key, value)
        }
    }
}
