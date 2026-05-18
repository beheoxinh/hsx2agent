package com.github.catatafishen.agentbridge.ui.review

import com.github.catatafishen.agentbridge.psi.review.AgentEditHighlighter
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession
import com.github.catatafishen.agentbridge.psi.review.ReviewSessionTopic
import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

private val APPROVED_BG = com.intellij.ui.JBColor(
    Color(0, 120, 0, 90), Color(80, 200, 80, 90)
)

class AutoApproveToggleAction(private val project: Project) : ToggleAction(
    "Auto-Approve",
    "Apply agent edits without per-file approval",
    AllIcons.Actions.Checked
), CustomComponentAction {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        return McpServerSettings.getInstance(project).isAutoApproveAgentEdits
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        McpServerSettings.getInstance(project).isAutoApproveAgentEdits = state
        if (state) AgentEditSession.getInstance(project).onAutoApproveTurnedOn()
        project.messageBus.syncPublisher(ReviewSessionTopic.TOPIC).reviewStateChanged()
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return object : ActionButton(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
            override fun paintButtonLook(g: Graphics) {
                if (isSelected) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = APPROVED_BG
                    val arc = JBUI.scale(4)
                    g2.fillRoundRect(2, 2, width - 4, height - 4, arc, arc)
                    g2.dispose()
                    val icon = presentation.icon
                    if (icon != null) {
                        val x = (width - icon.iconWidth) / 2
                        val y = (height - icon.iconHeight) / 2
                        icon.paintIcon(this, g, x, y)
                    }
                } else {
                    super.paintButtonLook(g)
                }
            }
        }
    }
}

class AutoCleanOnNewPromptToggleAction(private val project: Project) : ToggleAction(
    "Auto-Clean on New Prompt",
    "Remove approved rows automatically when starting a new prompt",
    AllIcons.Actions.ClearCash
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        return McpServerSettings.getInstance(project).isAutoCleanReviewOnNewPrompt
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        McpServerSettings.getInstance(project).isAutoCleanReviewOnNewPrompt = state
    }
}

class ShowEditorHighlightsToggleAction(private val project: Project) : ToggleAction(
    "Show Editor Highlights",
    "Paint agent-edit background colors in the editor. Disable when git diff colors are sufficient",
    AllIcons.Actions.Highlighting
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        return McpServerSettings.getInstance(project).isShowEditorHighlights
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        McpServerSettings.getInstance(project).isShowEditorHighlights = state
        val highlighter = AgentEditHighlighter.getInstance(project)
        if (state) {
            highlighter.refreshAll()
        } else {
            highlighter.clearAll()
        }
    }
}

class CleanApprovedAction(private val project: Project) : DumbAwareAction(
    "Clean Approved",
    "Remove all approved rows from the list",
    AllIcons.Actions.GC
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        AgentEditSession.getInstance(project).removeAllApproved()
    }

    override fun update(e: AnActionEvent) {
        val session = AgentEditSession.getInstance(project)
        e.presentation.isEnabled = session.reviewItems.any { it.approved() }
    }
}

class ApproveAllAction(private val project: Project) : DumbAwareAction(
    "Approve All",
    "Approve all pending changes",
    AllIcons.Actions.Checked
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        AgentEditSession.getInstance(project).acceptAll()
    }

    override fun update(e: AnActionEvent) {
        val session = AgentEditSession.getInstance(project)
        e.presentation.isEnabled = session.hasPendingChanges()
    }
}
