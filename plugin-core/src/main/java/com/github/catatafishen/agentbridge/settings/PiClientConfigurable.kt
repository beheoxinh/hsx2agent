package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.acp.client.AcpClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.services.GenericSettings
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import java.nio.file.Path

/**
 * Settings page for the Pi coding-agent CLI (https://pi.dev).
 *
 * Mirrors [OpenCodeClientConfigurable]: binary auto-detect, custom path, bubble color,
 * and session history limit. A "Refresh model definitions" hook is reserved for when the
 * extension-based MCP bridge lands — Pi caches model metadata under its config dir.
 */
@Suppress("unused")
class PiClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("Pi"),
    SearchableConfigurable {

    private val statusLabel = JBLabel()
    private val refreshResultLabel = JBLabel()
    private val genericSettings = GenericSettings(AGENT_ID)

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Status:") {
            cell(statusLabel)
        }
        row {
            val installNote = JBLabel(
                "<html>Install with <code>npm install -g --ignore-scripts @earendil-works/pi-coding-agent</code>. " +
                    "Ensure <code>pi</code> is on PATH and run <code>pi /login</code> once to authenticate.</html>"
            )
            installNote.foreground = UIUtil.getContextHelpForeground()
            cell(installNote)
        }
        row {
            val link = HyperlinkLabel("Open Pi docs (pi.dev)")
            link.setHyperlinkTarget("https://pi.dev/docs/latest/quickstart")
            cell(link)
        }
        separator()
        row("Pi binary:") {
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
        row("Bubble color:") {
            cell(ThemeColorComboBox())
                .comment(
                    "Choose a theme-aware accent color for message bubbles when using Pi."
                )
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)) },
                    @Suppress("kotlin:S6619")
                    { AcpClient.saveAgentBubbleColorKey(AGENT_ID, it?.name) }
                )
        }
        row("Session history limit:") {
            spinner(0..2_000_000, 50_000)
                .comment(
                    "Maximum characters of conversation history exported to Pi's session JSONL. " +
                        "0 = unlimited. Pi handles compaction internally via the 'compaction' " +
                        "settings block in ~/.pi/agent/settings.json. Default: unlimited (0)."
                )
                .bindIntValue(
                    { genericSettings.getContextHistoryLimit(DEFAULT_CONTEXT_LIMIT_CHARS) },
                    { genericSettings.setContextHistoryLimit(it) }
                )
        }
        separator()
        row("Model definitions:") {
            button("Refresh") { refreshModelDefinitions() }
                .comment(
                    "Clear cached provider/model metadata so Pi re-fetches it on next launch. " +
                        "Pi stores per-provider state under ~/.pi/agent/ (override with PI_CODING_AGENT_DIR)."
                )
            cell(refreshResultLabel)
        }
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        refreshStatusAsync()
    }

    private fun refreshModelDefinitions() {
        try {
            val deleted = deleteModelCache()
            if (deleted) {
                refreshResultLabel.text = "✓ Cache cleared — Pi will refresh model definitions on next launch"
                refreshResultLabel.foreground = JBColor(0x008000, 0x4EC94E)
            } else {
                refreshResultLabel.text = "No cached model definitions found"
                refreshResultLabel.foreground = UIUtil.getContextHelpForeground()
            }
        } catch (e: Exception) {
            refreshResultLabel.text = "Error: ${e.message}"
            refreshResultLabel.foreground = JBColor(0xCC0000, 0xFF6B6B)
        }
    }

    private fun refreshStatusAsync() {
        statusLabel.text = "Checking..."
        statusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val version = AcpClientBinaryResolver(AGENT_ID, AGENT_ID).detectVersion()
            ApplicationManager.getApplication().invokeLater {
                if (version != null) {
                    statusLabel.text = "✓ Pi found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text =
                        "Pi not found on PATH — install with npm install -g --ignore-scripts @earendil-works/pi-coding-agent"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_LIMIT_CHARS = 0
        const val ID = "com.github.catatafishen.agentbridge.client.pi"
        private const val AGENT_ID = "pi"

        /**
         * Resolves the Pi agent directory. Honors `PI_CODING_AGENT_DIR` first, otherwise
         * falls back to the documented default ({@code ~/.pi/agent}).
         */
        private fun resolveAgentDir(): Path {
            val override = System.getenv("PI_CODING_AGENT_DIR")
            if (!override.isNullOrBlank()) return Path.of(override)
            val home = Path.of(System.getProperty("user.home"))
            return when {
                SystemInfo.isWindows -> {
                    val appData = System.getenv("APPDATA")
                    if (appData != null) Path.of(appData, "pi", "agent") else home.resolve(".pi/agent")
                }

                else -> home.resolve(".pi/agent")
            }
        }

        /**
         * Deletes Pi's cached model-definition file (if any). Pi keeps lightweight
         * caches in its agent directory; clearing them forces a fresh fetch on next launch.
         */
        fun deleteModelCache(): Boolean {
            val candidates = listOf("models.json", "providers.json", "models-cache.json")
            var deleted = false
            val dir = resolveAgentDir()
            for (name in candidates) {
                if (java.nio.file.Files.deleteIfExists(dir.resolve(name))) {
                    deleted = true
                }
            }
            return deleted
        }
    }
}
