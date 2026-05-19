package com.github.catatafishen.agentbridge.ui

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

/**
 * A popup that shows a vertical timeline of all tool calls within a specific
 * conversation turn, providing an "execution trace" of the agent's actions.
 */
internal object TurnTracePopup {

    private var currentPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    data class Request(
        val project: Project,
        val turnId: String,
        val toolCalls: List<EntryData.ToolCall>
    )

    private fun popupWidth() = JBUI.scale(700)
    private fun popupHeight() = JBUI.scale(500)

    fun show(request: Request) {
        currentPopup?.cancel()

        val contentPanel = buildContentPanel(request)
        val scrollPane = JBScrollPane(
            contentPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            preferredSize = Dimension(popupWidth(), popupHeight())
            border = JBUI.Borders.empty()
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, scrollPane)
            .setTitle("Execution Trace — Turn ${request.turnId.take(8)}")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        currentPopup = popup

        val frame = WindowManager.getInstance().getFrame(request.project)
        if (frame != null) {
            popup.showInCenterOf(frame.rootPane)
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun buildContentPanel(request: Request): JBPanel<*> {
        val panel = object : JBPanel<JBPanel<*>>(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 20
            override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = height
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(16)
        }

        if (request.toolCalls.isEmpty()) {
            panel.add(JBLabel("No tool calls recorded for this turn.").apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = JComponent.CENTER_ALIGNMENT
            })
            return panel
        }

        request.toolCalls.forEachIndexed { index, toolCall ->
            panel.add(WorkCard(toolCall))
            if (index < request.toolCalls.size - 1) {
                panel.add(Box.createVerticalStrut(JBUI.scale(16)))
            }
        }

        panel.add(Box.createVerticalGlue())
        return panel
    }

    private class WorkCard(toolCall: EntryData.ToolCall) : JBPanel<WorkCard>() {
        init {
            layout = BorderLayout()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(12)
            )
            alignmentX = LEFT_ALIGNMENT

            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                val titleLabel = JBLabel(toolCall.mcpDisplayName ?: toolCall.title).apply {
                    font = JBUI.Fonts.label().asBold()
                    foreground = NativeChatColors.kindColor(toolCall.kind)
                }
                add(titleLabel, BorderLayout.WEST)

                if (toolCall.durationMs > 0) {
                    val timeLabel = JBLabel("${toolCall.durationMs}ms").apply {
                        foreground = UIUtil.getInactiveTextColor()
                        font = JBUI.Fonts.smallFont()
                    }
                    add(timeLabel, BorderLayout.EAST)
                }
            }
            add(header, BorderLayout.NORTH)

            val body = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.emptyTop(8)

                if (toolCall.filePath != null) {
                    val pathLabel = JBLabel(toolCall.filePath!!).apply {
                        foreground = UIUtil.getContextHelpForeground()
                        font = JBUI.Fonts.smallFont()
                        icon = com.intellij.icons.AllIcons.FileTypes.Any_type
                    }
                    add(pathLabel)
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                }

                add(PipelineComponent(toolCall))

                if (toolCall.status == "error" || toolCall.mcpErrorMessage != null) {
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    val errorLabel =
                        JBLabel("<html><body>${toolCall.mcpErrorMessage ?: toolCall.result ?: "Unknown error"}</body></html>").apply {
                            foreground = NativeChatColors.ERROR
                            font = JBUI.Fonts.smallFont()
                        }
                    add(errorLabel)
                }
            }
            add(body, BorderLayout.CENTER)
        }
    }

    private class PipelineComponent(toolCall: EntryData.ToolCall) : JPanel() {
        init {
            isOpaque = false
            layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
            border = JBUI.Borders.emptyTop(4)

            // Define stages
            add(StageNode("Input", true))
            add(Arrow())

            val permDenied = toolCall.autoDenied
            add(StageNode("Permission", !permDenied, if (permDenied) "Denied" else "Allowed"))
            add(Arrow())

            val preHooks = toolCall.hookStages.filter { it.trigger() == "pre" }
            val preFailed = preHooks.any { it.outcome() == "blocked" || it.outcome() == "error" }
            add(StageNode("Pre-hooks", !preFailed && !permDenied, if (preHooks.isEmpty()) "Skipped" else null))
            add(Arrow())

            val toolSuccess = toolCall.status != "error" && !permDenied && !preFailed
            add(StageNode("Execution", toolSuccess))
            add(Arrow())

            val postHooks = toolCall.hookStages.filter { it.trigger() == "success" || it.trigger() == "failure" }
            add(StageNode("Post-hooks", toolSuccess, if (postHooks.isEmpty()) "Skipped" else null))
            add(Arrow())

            add(StageNode("Output", toolSuccess))
        }

        private class StageNode(label: String, success: Boolean, subtext: String? = null) : JPanel() {
            init {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                val circle = object : JPanel() {
                    override fun paintComponent(g: Graphics) {
                        val g2 = g as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = if (success) {
                            JBColor(Color(0x4A, 0x90, 0x4A), Color(130, 190, 130)) // Green
                        } else {
                            NativeChatColors.ERROR
                        }
                        g2.fillOval(0, 0, width - 1, height - 1)
                    }
                }.apply {
                    preferredSize = Dimension(JBUI.scale(12), JBUI.scale(12))
                    maximumSize = preferredSize
                    alignmentX = CENTER_ALIGNMENT
                }
                add(circle)
                add(Box.createVerticalStrut(JBUI.scale(2)))
                add(JBLabel(label).apply {
                    font = JBUI.Fonts.miniFont()
                    alignmentX = CENTER_ALIGNMENT
                })
                if (subtext != null) {
                    add(JBLabel(subtext).apply {
                        font = JBUI.Fonts.miniFont()
                        foreground = UIUtil.getInactiveTextColor()
                        alignmentX = CENTER_ALIGNMENT
                    })
                }
            }
        }

        private class Arrow : JBLabel("\u2192") {
            init {
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.empty(0, 4, 14, 4)
            }
        }
    }
}
