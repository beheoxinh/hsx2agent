package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.services.LiveToolCallEntry
import com.github.catatafishen.agentbridge.services.LiveToolCallService
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.github.catatafishen.agentbridge.session.db.ConversationQuery
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.ui.renderers.ArgumentAwareRenderer
import com.github.catatafishen.agentbridge.ui.renderers.ToolRenderers
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

internal object ToolCallPopup {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ToolCallPopup::class.java)

    private var currentPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    private val KIND_COLORS = mapOf(
        "read" to JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185)),
        "edit" to JBColor(Color(0xA0, 0x7A, 0x3A), Color(205, 155, 95)),
        "execute" to JBColor(Color(0x4A, 0x90, 0x4A), Color(130, 190, 130)),
        "search" to JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185)),
        "think" to JBColor(Color(0x7A, 0x70, 0xA8), Color(170, 155, 210)),
        "other" to JBColor(Color(0x78, 0x7C, 0x80), Color(160, 165, 170)),
    )

    data class Request(
        val project: Project,
        val title: String,
        val kind: String,
        val paramsPanel: JComponent?,
        val resultPanel: JComponent,
        val toolDescription: String? = null,
        val autoDenied: Boolean = false,
        val denialReason: String? = null,
        val failed: Boolean = false,
        val hookStages: List<com.github.catatafishen.agentbridge.services.hooks.HookStageResult> = emptyList()
    )

    private fun popupWidth() = JBUI.scale(975)
    private fun popupHeight() = JBUI.scale(840)

    fun show(project: Project, toolCallId: String, contextComponent: Component) {
        val liveId = toolCallId.toLongOrNull()
        if (liveId != null) {
            val entry = LiveToolCallService.getInstance(project).entries.find { it.callId() == liveId }
            if (entry != null) {
                show(fromLiveEntry(project, entry))
                return
            }
        }

        // Try historic
        val service = ConversationService.getInstance(project)
        val query =
            ConversationQuery(com.github.catatafishen.agentbridge.session.db.ConversationDatabase.getInstance(project))
        val historic = query.findToolCall(toolCallId)
        if (historic != null) {
            show(fromHistoricEntry(project, historic))
        }
    }

    private fun fromLiveEntry(project: Project, entry: LiveToolCallEntry): Request {
        val registry = ToolRegistry.getInstance(project)
        val toolDef = registry.findById(entry.toolName())
        val kind = toolDef?.kind()?.value() ?: entry.category() ?: "other"
        val mcpDescription = if (toolDef != null && !toolDef.isBuiltIn) toolDef.description() else null

        val title = toolChipTitle(project, entry.toolName(), entry.input())
        val paramsPanel = if (!entry.input().isNullOrBlank()) {
            ToolRenderers.jsonEditor(ToolCallArgParser.prettyJson(entry.input()), project)
        } else null

        val status = when {
            entry.isRunning -> "running"
            entry.success() == true -> "success"
            else -> "failed"
        }

        val resultPanel = renderToolResultPanel(
            project,
            entry.toolName(),
            status,
            entry.output(),
            entry.input(),
            null, // live entries don't have description in the entry itself
            false, // TODO: handle auto-denied in live service
            null
        )

        return Request(
            project = project,
            title = title,
            kind = kind,
            paramsPanel = paramsPanel,
            resultPanel = resultPanel,
            toolDescription = mcpDescription,
            failed = status == "failed",
            hookStages = entry.hookStages().toList()
        )
    }

    private fun fromHistoricEntry(project: Project, entry: ConversationQuery.ToolCallHistoryEntry): Request {
        val registry = ToolRegistry.getInstance(project)
        val toolDef = registry.findById(entry.toolName())
        val kind = toolDef?.kind()?.value() ?: entry.category() ?: "other"
        val mcpDescription = if (toolDef != null && !toolDef.isBuiltIn) toolDef.description() else null

        val title = toolChipTitle(project, entry.toolName(), entry.arguments())
        val paramsPanel = if (!entry.arguments().isNullOrBlank()) {
            ToolRenderers.jsonEditor(ToolCallArgParser.prettyJson(entry.arguments()!!), project)
        } else null

        val resultPanel = renderToolResultPanel(
            project,
            entry.toolName(),
            entry.status() ?: if (entry.success()) "success" else "failed",
            entry.result(),
            entry.arguments(),
            null,
            false,
            null
        )

        return Request(
            project = project,
            title = title,
            kind = kind,
            paramsPanel = paramsPanel,
            resultPanel = resultPanel,
            toolDescription = mcpDescription,
            failed = !entry.success() || entry.status() == "failed",
            hookStages = entry.hookStages().map {
                com.github.catatafishen.agentbridge.services.hooks.HookStageResult(
                    it.trigger(), it.scriptName(), it.outcome(), it.durationMs(), it.detail()
                )
            }
        )
    }

    private fun toolChipTitle(project: Project, baseName: String?, arguments: String?): String {
        if (baseName == null) return "Tool Call"
        val clean = baseName.trim('\'', '"')
        val toolDef = ToolRegistry.getInstance(project).findById(clean)
        val display = toolDef?.displayName() ?: clean.replaceFirstChar { it.uppercaseChar() }
        val subtitle = formatToolSubtitle(clean, arguments)
        return if (subtitle != null) "$display — $subtitle" else display
    }

    private fun formatToolSubtitle(toolName: String, arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        return try {
            val json = com.google.gson.JsonParser.parseString(arguments).asJsonObject
            when (toolName) {
                "read_file", "write_file", "create_file", "edit_text", "patch", "git_blame", "git_show", "git_diff" ->
                    json["path"]?.asString ?: json["file"]?.asString

                "run_command", "bash" -> json["title"]?.asString ?: json["command"]?.asString
                "run_tests" -> json["target"]?.asString
                "run_configuration" -> json["name"]?.asString
                "search_text", "grep" -> json["query"]?.asString
                "search_symbols", "go_to_declaration", "find_references", "find_implementations" -> json["symbol"]?.asString
                    ?: json["query"]?.asString

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderToolResultPanel(
        project: Project,
        baseName: String?,
        status: String?,
        details: String?,
        arguments: String? = null,
        description: String? = null,
        autoDenied: Boolean = false,
        denialReason: String? = null
    ): JComponent {
        val container = ToolRenderers.listPanel()

        if (autoDenied) {
            container.add(JBLabel("<html><body style='width: 450px'><span style='color: #FF0000; font-weight: bold;'>Tool call was automatically denied.</span><br/>Reason: ${denialReason ?: "Security policy"}</body></html>").apply {
                border = JBUI.Borders.emptyBottom(8)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        if (!description.isNullOrBlank()) {
            val html = FileNavigator(project).markdownToHtml(description)
            container.add(JBLabel("<html><body style='width: 450px'>$html</body></html>").apply {
                border = JBUI.Borders.emptyBottom(8)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        if (status == "failed") {
            val label = JBLabel("Error Details").apply {
                foreground = JBColor.RED
                font = JBUI.Fonts.label().asBold()
                border = JBUI.Borders.emptyBottom(4)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            container.add(label)
            container.add(ToolRenderers.codePanel(details ?: "Unknown error"))
            return container
        }

        val finalDetails =
            if (details.isNullOrBlank() && !arguments.isNullOrBlank()) "Parameters: $arguments" else details
        if (finalDetails.isNullOrBlank()) {
            val label = when (status) {
                "running" -> "⏳ Running…"
                else -> if (baseName != null) "Tool $baseName completed with no output." else "Completed"
            }
            container.add(JBLabel(label).apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            return container
        }

        if (baseName != null) {
            val registry = ToolRegistry.getInstance(project)
            val renderer = ToolRenderers.get(baseName, registry)
            if (renderer != null) {
                try {
                    val component = if (renderer is ArgumentAwareRenderer) {
                        renderer.render(finalDetails, arguments)
                    } else {
                        renderer.render(finalDetails)
                    }
                    if (component != null) {
                        container.add(component)
                        return container
                    }
                } catch (e: Exception) {
                    LOG.warn("Custom renderer $renderer failed", e)
                }
            }
        }

        val fallbackContent = if (ToolCallArgParser.isJson(finalDetails)) {
            ToolRenderers.jsonEditor(ToolCallArgParser.prettyJson(finalDetails), project)
        } else {
            ToolRenderers.codeOrScratchPanel(finalDetails)
        }
        container.add(fallbackContent)

        return container
    }

    fun show(request: Request) {
        currentPopup?.cancel()

        val kindColor = KIND_COLORS[request.kind] ?: KIND_COLORS["other"]!!
        val panelBg = UIUtil.getPanelBackground()
        val tintedBg = ToolRenderers.blendColor(kindColor, panelBg, 0.07)

        val contentPanel = buildContentPanel(
            tintedBg,
            request.resultPanel,
            request.paramsPanel,
            request.toolDescription,
            request.autoDenied,
            request.denialReason,
            request.failed,
            request.hookStages
        )

        val width = popupWidth()
        val height = popupHeight()

        val scrollPane = JBScrollPane(
            contentPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            preferredSize = Dimension(width, height)
            border = JBUI.Borders.empty()
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, scrollPane)
            .setTitle(request.title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)
            .setCancelKeyEnabled(true)
            .setMinSize(Dimension(JBUI.scale(350), JBUI.scale(180)))
            .createPopup()
        currentPopup = popup

        val frame = WindowManager.getInstance().getFrame(request.project)
        if (frame != null) {
            val rootPane = frame.rootPane
            val relPoint = com.intellij.ui.awt.RelativePoint(
                rootPane,
                java.awt.Point(
                    (rootPane.width - width) / 2,
                    (rootPane.height - height) / 2,
                )
            )
            popup.show(relPoint)
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun buildContentPanel(
        bg: Color,
        resultPanel: JComponent,
        paramsPanel: JComponent?,
        toolDescription: String? = null,
        autoDenied: Boolean = false,
        denialReason: String? = null,
        failed: Boolean = false,
        hookStages: List<com.github.catatafishen.agentbridge.services.hooks.HookStageResult> = emptyList()
    ): JBPanel<JBPanel<*>> {
        val panel = object : JBPanel<JBPanel<*>>(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) =
                16

            override fun getScrollableBlockIncrement(
                visibleRect: java.awt.Rectangle,
                orientation: Int,
                direction: Int,
            ) = height

            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            border = JBUI.Borders.empty(8, 12)
        }

        // Execution Pipeline
        panel.add(PipelineComponent(hookStages, autoDenied, failed))
        panel.add(Box.createVerticalStrut(JBUI.scale(12)))
        panel.add(JSeparator().apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })
        panel.add(Box.createVerticalStrut(JBUI.scale(12)))

        if (autoDenied) {
            val denialPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = bg
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.RED, 1),
                    JBUI.Borders.empty(8)
                )
                alignmentX = JComponent.LEFT_ALIGNMENT

                add(JBLabel("Tool call was automatically denied by the plugin.").apply {
                    foreground = JBColor.RED
                    font = JBUI.Fonts.label().asBold()
                    alignmentX = JComponent.LEFT_ALIGNMENT
                })
                if (denialReason != null) {
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(JBLabel("<html><body style='width:580px'>Reason: $denialReason</body></html>").apply {
                        foreground = JBColor.RED
                        alignmentX = JComponent.LEFT_ALIGNMENT
                    })
                }
            }
            panel.add(denialPanel)
            panel.add(Box.createVerticalStrut(JBUI.scale(12)))
        }

        if (toolDescription != null) {
            val descLabel = JBLabel("<html><body style='width:580px'>$toolDescription</body></html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(0, 0, 6, 0)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            panel.add(descLabel)
            panel.add(JSeparator().apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            })
            panel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        if (paramsPanel != null) {
            panel.add(sectionLabel("Parameters"))
            paramsPanel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(paramsPanel)
            panel.add(Box.createVerticalStrut(JBUI.scale(6)))
            panel.add(JSeparator().apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            })
        }

        panel.add(sectionLabel(if (failed) "Error" else "Result"))
        resultPanel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(resultPanel)

        panel.add(Box.createVerticalGlue())

        return panel
    }

    private fun sectionLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont().asBold()
            border = JBUI.Borders.empty(4, 0, 6, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    }

    private class PipelineComponent(
        hookStages: List<com.github.catatafishen.agentbridge.services.hooks.HookStageResult>,
        autoDenied: Boolean,
        failed: Boolean
    ) : JPanel() {
        init {
            isOpaque = false
            layout = FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0)
            alignmentX = JComponent.LEFT_ALIGNMENT

            add(StageNode("Input", true))
            add(Arrow())

            add(StageNode("Permission", !autoDenied, if (autoDenied) "Denied" else "Allowed"))
            add(Arrow())

            val preHooks = hookStages.filter { it.trigger() == "pre" }
            val preFailed = preHooks.any { it.outcome() == "blocked" || it.outcome() == "error" }
            add(
                StageNode(
                    "Pre-hooks",
                    !preFailed && !autoDenied,
                    if (preHooks.isEmpty()) "Skipped" else "${preHooks.size} run"
                )
            )
            add(Arrow())

            val toolSuccess = !failed && !autoDenied && !preFailed
            add(StageNode("Execution", toolSuccess))
            add(Arrow())

            val postHooks = hookStages.filter { it.trigger() == "success" || it.trigger() == "failure" }
            add(StageNode("Post-hooks", toolSuccess, if (postHooks.isEmpty()) "Skipped" else "${postHooks.size} run"))
            add(Arrow())

            add(StageNode("Output", toolSuccess))
        }
    }

    private class StageNode(label: String, success: Boolean, subtext: String? = null) : JPanel() {
        init {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val nodeColor = when {
                subtext == "Skipped" -> UIUtil.getInactiveTextColor()
                subtext == "Denied" || subtext == "Failed" || !success -> NativeChatColors.ERROR
                else -> JBColor(Color(0x4A, 0x90, 0x4A), Color(130, 190, 130))
            }

            val circle = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = nodeColor
                    g2.fillOval(0, 0, width - 1, height - 1)
                }
            }.apply {
                preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14))
                maximumSize = preferredSize
                alignmentX = JComponent.CENTER_ALIGNMENT
            }
            add(circle)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(JBLabel(label).apply {
                font = JBUI.Fonts.smallFont().asBold()
                alignmentX = JComponent.CENTER_ALIGNMENT
            })
            if (subtext != null) {
                add(JBLabel(subtext).apply {
                    font = JBUI.Fonts.miniFont()
                    foreground = nodeColor
                    alignmentX = JComponent.CENTER_ALIGNMENT
                })
            }
        }
    }

    private class Arrow : JBLabel("\u2192") {
        init {
            foreground = UIUtil.getInactiveTextColor()
            font = JBUI.Fonts.label().asBold()
            border = JBUI.Borders.empty(0, 4, 18, 4)
        }
    }
}
