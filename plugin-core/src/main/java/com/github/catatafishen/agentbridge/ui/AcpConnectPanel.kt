package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.BuildInfo
import com.github.catatafishen.agentbridge.bridge.TransportType
import com.github.catatafishen.agentbridge.memory.MemoryService
import com.github.catatafishen.agentbridge.psi.PsiBridgeService
import com.github.catatafishen.agentbridge.services.*
import com.github.catatafishen.agentbridge.services.AgentProfileManager.AgentProfileListener
import com.github.catatafishen.agentbridge.session.SessionSwitchService
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase
import com.github.catatafishen.agentbridge.session.db.ConversationListener
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.session.db.ConversationStatistics
import com.github.catatafishen.agentbridge.settings.*
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.*
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import kotlin.math.max

/**
 * Pre-connection landing panel with a step-by-step "getting started" layout:
 * Step 1 — MCP tool server (start/stop, port, status pill with tool call counter)
 * Step 2 — ACP agent connection (disabled until MCP is running)
 */
class AcpConnectPanel(
    private val project: Project,
    private val onConnect: (String, String?) -> Unit
) : JBPanel<AcpConnectPanel>(BorderLayout()) {

    companion object {
        private const val START_SERVER = "Start server"
        private const val STOP_SERVER = "Stop server"
        private val SESSION_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        private val LOG = Logger.getInstance(AcpConnectPanel::class.java)
    }

    /** Item model for the session resume dropdown. */
    sealed class SessionChoice(val displayText: String) {
        /** Resume the most recent session (default). */
        data class Latest(val record: ConversationService.SessionRecord) :
            SessionChoice(formatSession(record))

        /** Start a fresh session without resuming. */
        data object None : SessionChoice("None (fresh session)")

        /** Resume a specific older session. */
        data class Older(val record: ConversationService.SessionRecord) :
            SessionChoice(formatSession(record))

        override fun toString(): String = displayText

        companion object {
            private fun formatSession(record: ConversationService.SessionRecord): String {
                val date = SESSION_DATE_FORMAT.format(Date(record.updatedAt))
                val label = record.name.ifEmpty { record.agent }
                val base = "$date — $label"
                return if (record.turnCount > 0) "$base (${record.turnCount} turns)" else base
            }
        }
    }

    private val agentManager = ActiveAgentManager.getInstance(project)
    private val authService = AuthLoginService(project)
    private var inlineAuthProcess: Process? = null
    private var isConnectingAgent = false
        set(value) {
            if (field != value) {
                field = value
                ApplicationManager.getApplication().invokeLater {
                    refreshMcpState()
                }
            }
        }

    private enum class BinaryState { UNKNOWN, FOUND, MISSING }

    private val profileStatusCheckInProgress = AtomicBoolean(false)
    private var profileStatusTimer: javax.swing.Timer? = null
    private val binaryStateCache = mutableMapOf<String, BinaryState>()
    private var binaryScanCursor = 0
    private lateinit var toggleMissingBinaryVisibilityButton: InplaceButton

    override fun addNotify() {
        super.addNotify()
        startProfileStatusTimer()
        startStatsTimer()
    }

    override fun removeNotify() {
        super.removeNotify()
        stopProfileStatusTimer()
        stopStatsTimer()
    }

    private fun startProfileStatusTimer() {
        if (profileStatusTimer == null) {
            profileStatusTimer = javax.swing.Timer(1000) {
                if (isShowing) {
                    updateProfileStatus()
                }
            }
        }
        profileStatusTimer?.start()
    }

    private fun stopProfileStatusTimer() {
        profileStatusTimer?.stop()
    }

    // MCP controls
    private val mcpStartButton = JButton(START_SERVER)
    private val mcpSpinner = AsyncProcessIcon("mcp-toggle").apply {
        isVisible = false
        toolTipText = "Working…"
    }
    private val mcpAutoStartCheckbox = JBCheckBox("Auto-start on IDE open")
    private val mcpStatusLabel = JBLabel("Stopped")
    private val mcpUrlCopyButton = InplaceButton("Copy MCP URL", AllIcons.Actions.Copy) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(mcpRunningUrl), null)
    }.apply {
        isVisible = false
        accessibleContext.accessibleName = "Copy MCP URL"
    }
    private var mcpRunningUrl = ""
    private val toolCallLink = HyperlinkLabel("0 calls")
    private val toolCallEntries = mutableListOf<String>()
    private lateinit var statusPill: JBPanel<JBPanel<*>>

    // Stats bar labels
    private val lsAgentLabel = JBLabel("—")
    private val lsMetaLabel = JBLabel("—")
    private val lsAgoLabel = JBLabel("—")
    private val todayTimeLabel = JBLabel("—")
    private val todayMetaLabel = JBLabel("—")
    private val todayTokensLabel = JBLabel("—")
    private var statsRefreshTimer: javax.swing.Timer? = null

    // ACP controls
    private var acpSection: JComponent = JBPanel<JBPanel<*>>()
    private val profileCombo = ComboBox<AgentProfile>()
    private val profileStatusIcon = JBLabel()
    private val sessionCombo = ComboBox<SessionChoice>()
    private val recentSessionsPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
    }
    private lateinit var recentSessionsTitle: JBLabel
    private val connectButton = JButton("Connect")
    private val connectSpinner = AsyncProcessIcon("acp-connect").apply {
        isVisible = false
        toolTipText = "Connecting…"
    }
    private val acpAutoConnectCheckbox = JBCheckBox("Auto-connect on startup")
    private val acpHintLabel = JBLabel("Start the tool server above first").apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        font = JBUI.Fonts.smallFont()
        icon = AllIcons.General.Information
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }
    private val statusBanner = StatusBanner(project)

    init {
        isOpaque = false

        val maxContentWidth = JBUI.scale(480)

        val innerContent = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(20, 24)
            maximumSize = Dimension(maxContentWidth, Int.MAX_VALUE)

            val titleLabel = JBLabel("Hsx2Coder").apply {
                foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                font = JBUI.Fonts.label().deriveFont(36f).asBold()
                horizontalAlignment = SwingConstants.CENTER
                alignmentX = LEFT_ALIGNMENT
                // Allow label to fill width so horizontalAlignment centers text correctly
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                border = JBUI.Borders.empty(10, 0, 10, 0)
            }
            add(titleLabel)

            add(createMcpSection())
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(createCleanerSection())
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(createAcpSection().also { acpSection = it })
            add(Box.createVerticalGlue())
        }

        // Center the inner content horizontally with a max width
        val scrollContent = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
            add(innerContent, GridBagConstraints().apply {
                anchor = GridBagConstraints.NORTH
                fill = GridBagConstraints.VERTICAL
                weightx = 1.0
                weighty = 1.0
            })
        }

        val scrollPane = JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        add(scrollPane, BorderLayout.CENTER)

        val versionLabel = JBLabel(
            BuildInfo.getVersion()
        ).apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            font = JBUI.Fonts.smallFont()
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.empty(4, 0, 8, 0)
        }
        add(versionLabel, BorderLayout.SOUTH)

        subscribeToBridgeEvents()
        refreshMcpState()

        // If autostart is enabled and the server hasn't started yet, show a loading indicator
        // so the user can't click "Start server" while it's already being auto-started.
        val mcpSettings = McpServerSettings.getInstance(project)
        val mcpServerControl = McpServerControl.getInstance(project)
        if (mcpSettings.isAutoStart && mcpServerControl != null && !mcpServerControl.isRunning) {
            showAutoStartLoading()
        }

        // Smart agent binary detection on first run
        if (!AgentBridgeStorageSettings.getInstance().state.isAgentBinaryDetectionRun) {
            SmartAgentDetector(project).detectAllInBackground(false)
        }
    }

    // ── Section builders ──

    private fun createMcpSection(): JComponent {
        val section = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        section.add(
            createSectionHeader(
                step = 1,
                title = "Start tool server",
                description = "MCP server \u2014 tool server agents and clients can connect to"
            )
        )
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Status pill
        section.add(createStatusPill())
        section.add(Box.createVerticalStrut(JBUI.scale(14)))

        // Start/Stop button
        section.add(createMcpButton())
        section.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Auto-start option
        mcpAutoStartCheckbox.apply {
            isOpaque = false
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            alignmentX = LEFT_ALIGNMENT
            isSelected = McpServerSettings.getInstance(project).isAutoStart
            addActionListener { McpServerSettings.getInstance(project).isAutoStart = isSelected }
        }
        section.add(mcpAutoStartCheckbox)

        return section
    }

    // ── Stats section ──

    private fun createStatsSection(): JComponent {
        val dimColor = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        val smallFont = JBUI.Fonts.smallFont()
        val cardBg = JBColor(
            Color(0xF5, 0xF5, 0xF5),
            Color(0x3A, 0x3A, 0x3A)
        )
        val cardBorder = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(5, 8)
        )

        fun makeCard(title: String): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = cardBg
            border = cardBorder
            add(JBLabel(title).apply {
                font = smallFont.deriveFont(Font.BOLD)
                foreground = dimColor
            }, BorderLayout.NORTH)
        }

        // ── Last Session card ──
        val lsCard = makeCard("Last session")
        val lsContent = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(2)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(lsAgentLabel.apply { font = JBUI.Fonts.label() })
            add(Box.createVerticalStrut(JBUI.scale(1)))
            add(lsMetaLabel.apply { font = smallFont; foreground = dimColor })
            add(lsAgoLabel.apply { font = smallFont; foreground = dimColor })
        }
        lsCard.add(lsContent, BorderLayout.CENTER)

        // ── Today card ──
        val todayCard = makeCard("Today")
        val todayContent = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(2)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(todayTimeLabel.apply { font = JBUI.Fonts.label() })
            add(Box.createVerticalStrut(JBUI.scale(1)))
            add(todayMetaLabel.apply { font = smallFont; foreground = dimColor })
            add(todayTokensLabel.apply { font = smallFont; foreground = dimColor })
        }
        todayCard.add(todayContent, BorderLayout.CENTER)

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(64))
            add(lsCard)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(todayCard)
        }
    }

    // ── Data History Cleaner section ──

    private fun createCleanerSection(): JComponent {
        val section = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        section.add(
            createSectionHeader(
                step = 2,
                title = "Data History Cleaner",
                description = "Clear project-level data accumulated by the plugin"
            )
        )
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        section.add(createStatsSection())
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        val btnBg = JBColor(Color(0xFA, 0xFA, 0xFA), Color(0x44, 0x44, 0x44))

        fun cleanBtn(text: String, tooltip: String, icon: Icon, action: () -> Unit): JButton =
            JButton(text, icon).apply {
                this.toolTipText = tooltip
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
                background = btnBg
                font = JBUI.Fonts.smallFont()
                addActionListener { action() }
            }

        val b1 = cleanBtn("Clear Scratch Files",
            "Delete all scratch files created by the plugin in this IDE",
            AllIcons.Actions.GC) { confirmAndRun("Clear Scratch Files",
            "Delete all scratch files in the IDE? This cannot be undone.") { clearScratchFiles() } }
        val b2 = cleanBtn("Clear Session History",
            "Delete all saved sessions in this project",
            AllIcons.Actions.GC) { confirmAndRun("Clear Session History",
            "Delete all saved sessions in this project? The session list will be refreshed.") { clearSessionHistory() } }
        val b3 = cleanBtn("Clear Tool Statistics History",
            "Delete all recorded tool call statistics",
            AllIcons.Actions.GC) { confirmAndRun("Clear Tool Statistics History",
            "Delete all tool call statistics records? Session history will be preserved.") { clearToolStatistics() } }
        val b4 = cleanBtn("Clear Semantic Memory",
            "Delete all semantic memory stored for this project",
            AllIcons.Actions.GC) { confirmAndRun("Clear Semantic Memory",
            "Delete all semantic memory for this project? This includes all memory drawers and knowledge graph data.") { clearSemanticMemory() } }
        val b5 = cleanBtn("Clear Chat History",
            "Delete all conversation history in this project",
            AllIcons.Actions.GC) { confirmAndRun("Clear Chat History",
            "Delete all conversation history in this project? This includes all text messages and entries.") { clearChatHistory() } }

        fun col(vararg btns: JButton): JComponent =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentY = TOP_ALIGNMENT
                for (b in btns) {
                    add(b)
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                }
            }

        fun twoColumns(left: JComponent, right: JComponent): JComponent =
            JBPanel<JBPanel<*>>(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(JBUI.scale(480), JBUI.scale(64))
                add(left)
                add(right)
            }

        section.add(twoColumns(col(b1, b2, b3), col(b4, b5)))
        section.add(Box.createVerticalStrut(JBUI.scale(6)))

        val clearAllBtn = JButton("Clear All Data", AllIcons.Actions.GC).apply {
            toolTipText = "Delete all project data in .agentbridge and .agent-work — resets to first-use state"
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(JBUI.scale(480), JBUI.scale(28))
            background = JBColor(Color(0xCC, 0x33, 0x33), Color(0x8B, 0x22, 0x22))
            foreground = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0xEE, 0xEE, 0xEE))
            font = JBUI.Fonts.smallFont().asBold()
            addActionListener {
                confirmAndRun("Clear All Data",
                    "Delete all contents of .agentbridge and .agent-work?\n" +
                    "This will reset the project to first-use state.\nThis action cannot be undone.") { clearAllData() }
            }
        }
        section.add(clearAllBtn)

        return section
    }

    private fun confirmAndRun(title: String, message: String, action: () -> Unit) {
        val result = Messages.showYesNoDialog(
            project, message, title,
            Messages.getYesButton(), Messages.getNoButton(), Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    action()
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, "$title completed successfully.", title)
                    }
                } catch (e: Exception) {
                    LOG.warn("$title failed", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "$title failed: ${e.message}", title)
                    }
                }
            }
        }
    }

    private fun clearScratchFiles() {
        ApplicationManager.getApplication().runWriteAction {
            val scratchService = ScratchFileService.getInstance()
            val scratchRoot = ScratchRootType.getInstance()
            val rootFile = scratchService.getVirtualFile(scratchRoot)
            if (rootFile == null) return@runWriteAction
            for (child in rootFile.children) {
                child.delete(this)
            }
        }
    }

    private fun clearSessionHistory() {
        ConversationService.getInstance(project).deleteAllHistory()
        ApplicationManager.getApplication().invokeLater { refreshSessionCombo() }
    }

    private fun clearToolStatistics() {
        val db = ConversationDatabase.getInstance(project)
        db.withConnection { conn ->
            conn.createStatement().executeUpdate("DELETE FROM tool_call_events")
            conn.createStatement().executeUpdate(
                """
                DELETE FROM events WHERE event_type = 'tool_call'
            """
            )
            null
        }
    }

    private fun clearSemanticMemory() {
        val memoryService = MemoryService.getInstance(project)
        try {
            memoryService.dispose()
        } catch (_: Exception) {
        }
        val memoryDir = AgentBridgeStorageSettings.getInstance().getProjectMemoryDir(project)
        if (Files.exists(memoryDir)) {
            Files.walk(memoryDir).sorted(Comparator.reverseOrder()).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: java.io.IOException) {
                }
            }
        }
    }

    private fun clearChatHistory() {
        val db = ConversationDatabase.getInstance(project)
        db.withConnection { conn ->
            val stmt = conn.createStatement()
            stmt.executeUpdate("DELETE FROM text_events")
            stmt.executeUpdate("DELETE FROM thinking_events")
            stmt.executeUpdate("DELETE FROM nudge_events")
            stmt.executeUpdate(
                """
                DELETE FROM events WHERE event_type IN ('text', 'thinking', 'nudge')
            """
            )
            null
        }
    }

    private fun clearAllData() {
        try {
            val base = project.basePath ?: return
            val agentbridgeDir = java.nio.file.Paths.get(base, ".agentbridge")
            val agentWorkDir = java.nio.file.Paths.get(base, ".agent-work")
            for (dir in listOf(agentbridgeDir, agentWorkDir)) {
                if (java.nio.file.Files.exists(dir)) {
                    java.nio.file.Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { path ->
                        try { java.nio.file.Files.deleteIfExists(path) } catch (_: java.io.IOException) { }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Clear All Data failed", e)
        }
    }

    private fun createStatusPill(): JComponent {
        val pill = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            background = JBColor(
                Color(0xF0, 0xF0, 0xF0),
                Color(0x3C, 0x3C, 0x3C)
            )
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8)
            )
        }

        mcpStatusLabel.icon = AllIcons.General.InspectionsOKEmpty
        mcpStatusLabel.font = JBUI.Fonts.label()
        pill.add(mcpStatusLabel, BorderLayout.WEST)

        val eastPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
        }
        eastPanel.add(mcpUrlCopyButton, BorderLayout.WEST)
        eastPanel.add(toolCallLink, BorderLayout.EAST)

        toolCallLink.font = JBUI.Fonts.smallFont()
        toolCallLink.setToolTipText("Click to view recent tool calls")
        toolCallLink.addHyperlinkListener { showToolCallPopup() }
        pill.add(eastPanel, BorderLayout.EAST)

        statusPill = pill
        return pill
    }

    private fun createMcpButton(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }

        mcpStartButton.icon = AllIcons.Actions.Execute
        mcpStartButton.addActionListener { toggleMcpServer() }
        panel.add(mcpStartButton, BorderLayout.CENTER)

        val eastPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(mcpSpinner)
        }
        panel.add(eastPanel, BorderLayout.EAST)

        return panel
    }

    private fun createAcpSection(): JComponent {
        val section = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        val storage = AgentBridgeStorageSettings.getInstance()
        toggleMissingBinaryVisibilityButton = InplaceButton("Toggle missing-binary agents", AllIcons.Actions.Show) {
            storage.state.setShowOnlyInstalledAgents(!storage.state.isShowOnlyInstalledAgents())
            updateMissingBinaryVisibilityToggleButtonUi()
            refreshProfileCombo()
        }.apply {
            accessibleContext.accessibleName = "Toggle missing-binary agents"
            val btnSize = JBUI.scale(24)
            preferredSize = Dimension(btnSize, btnSize)
            maximumSize = Dimension(btnSize, btnSize)
            minimumSize = Dimension(btnSize, btnSize)
        }
        updateMissingBinaryVisibilityToggleButtonUi()

        section.add(
            createSectionHeader(
                step = 3,
                title = "Connect agent",
                description = "ACP \u2014 launch and connect an AI coding agent",
                trailingAction = null,
            )
        )
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Agent profile selector
        section.add(createProfileSelector())
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Session resume selector
        section.add(createSessionSelector())
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Hint shown when MCP is not running
        section.add(acpHintLabel)
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Connect split button
        section.add(createAcpButton())
        section.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Auto-connect option
        acpAutoConnectCheckbox.apply {
            isOpaque = false
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            alignmentX = LEFT_ALIGNMENT
            isSelected = agentManager.isAutoConnect
            addActionListener { agentManager.isAutoConnect = isSelected }
        }
        section.add(acpAutoConnectCheckbox)
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Status banner
        statusBanner.alignmentX = LEFT_ALIGNMENT
        section.add(statusBanner)

        return section
    }

    private fun createAcpButton(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }

        connectButton.icon = AllIcons.Actions.Execute
        connectButton.addActionListener { doConnect() }
        panel.add(connectButton, BorderLayout.CENTER)

        val spinnerWrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
        }
        spinnerWrapper.add(connectSpinner, BorderLayout.CENTER)
        panel.add(spinnerWrapper, BorderLayout.EAST)

        return panel
    }

    private fun createProfileSelector(): JComponent {
        refreshProfileCombo()
        profileCombo.renderer = SimpleListCellRenderer.create { label, value, _ ->
            val name = value?.displayName ?: ""
            label.text = if (value?.isExperimental == true) "$name (experimental)" else name
        }
        profileCombo.alignmentX = LEFT_ALIGNMENT
        profileCombo.font = JBUI.Fonts.smallFont()
        // Let combo fill remaining horizontal space
        profileCombo.minimumSize = Dimension(JBUI.scale(80), JBUI.scale(26))
        profileCombo.preferredSize = Dimension(JBUI.scale(220), JBUI.scale(26))
        profileCombo.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
        profileCombo.addActionListener { updateProfileStatus() }

        val btnSize = JBUI.scale(24)

        val searchButton = InplaceButton("Search for installed agents", AllIcons.Actions.Download) {
            ShellEnvironment.refresh()
            SmartAgentDetector(project).detectAllInBackground(true)
        }.apply {
            accessibleContext.accessibleName = "Search for installed agents"
            preferredSize = Dimension(btnSize, btnSize)
            maximumSize = Dimension(btnSize, btnSize)
            minimumSize = Dimension(btnSize, btnSize)
        }

        val settingsButton = InplaceButton("Configure agent", AllIcons.General.GearPlain) {
            val profile = profileCombo.selectedItem as? AgentProfile
            if (profile != null) {
                showAgentSettings(profile.id)
            }
        }.apply {
            accessibleContext.accessibleName = "Configure agent"
            preferredSize = Dimension(btnSize, btnSize)
            maximumSize = Dimension(btnSize, btnSize)
            minimumSize = Dimension(btnSize, btnSize)
        }

        val eastPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(profileStatusIcon)
            add(Box.createHorizontalStrut(JBUI.scale(2)))
            add(toggleMissingBinaryVisibilityButton)
            add(Box.createHorizontalStrut(JBUI.scale(2)))
            add(searchButton)
            add(Box.createHorizontalStrut(JBUI.scale(2)))
            add(settingsButton)
        }

        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))

            add(profileCombo)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(eastPanel)
        }

        updateProfileStatus()
        return panel
    }

    private fun updateMissingBinaryVisibilityToggleButtonUi() {
        val hidingMissingBinaryAgents = AgentBridgeStorageSettings.getInstance().state.isShowOnlyInstalledAgents()
        if (hidingMissingBinaryAgents) {
            toggleMissingBinaryVisibilityButton.icon = AllIcons.Actions.Show
            toggleMissingBinaryVisibilityButton.toolTipText =
                "Showing only agents with binary found. Click to show all agents."
        } else {
            toggleMissingBinaryVisibilityButton.icon = AllIcons.General.Filter
            toggleMissingBinaryVisibilityButton.toolTipText =
                "Showing all agents. Click to hide missing-binary agents."
        }
    }

    private fun updateProfileStatus() {
        if (!profileStatusCheckInProgress.compareAndSet(false, true)) return

        val allProfiles = agentManager.availableProfiles.toList()
        if (allProfiles.isEmpty()) {
            profileStatusCheckInProgress.set(false)
            return
        }

        val selected = profileCombo.selectedItem as? AgentProfile
        val selectedId = selected?.id
        val target = allProfiles.firstOrNull { binaryStateCache[it.id] == BinaryState.UNKNOWN }
            ?: allProfiles[binaryScanCursor % max(allProfiles.size, 1)]
        binaryScanCursor = (binaryScanCursor + 1) % max(allProfiles.size, 1)

        val profileId = target.id
        val binaryName = target.binaryName
        val alternates = target.alternateNames.toTypedArray()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val resolver = if (target.transportType == TransportType.CLAUDE_CLI) ClaudeAgentBinaryResolver()
                else AcpClientBinaryResolver(profileId, binaryName, *alternates)

                var resolvedPath = resolver.resolve()
                if (resolvedPath != null && !resolvedPath.contains("/") && !resolvedPath.contains("\\")) {
                    resolvedPath = BinaryDetector.findBinaryPath(resolvedPath)
                }

                val newState = if (resolvedPath != null && java.io.File(resolvedPath)
                        .exists()
                ) BinaryState.FOUND else BinaryState.MISSING
                val oldState = binaryStateCache[profileId] ?: BinaryState.UNKNOWN
                val changed = oldState != newState
                binaryStateCache[profileId] = newState

                ApplicationManager.getApplication().invokeLater {
                    if (selectedId == profileId) {
                        if (newState == BinaryState.FOUND) {
                            profileStatusIcon.icon = AllIcons.General.InspectionsOK
                            profileStatusIcon.toolTipText = "Binary found: $resolvedPath"
                        } else {
                            profileStatusIcon.icon = AllIcons.General.Error
                            profileStatusIcon.toolTipText =
                                "Binary not found. Use auto-detect button or check Settings."
                        }
                    }
                    if (changed) refreshProfileCombo()
                }
            } finally {
                profileStatusCheckInProgress.set(false)
            }
        }
    }

    private fun showAgentSettings(profileId: String) {
        val configurableClass = when (profileId) {
            AgentProfileManager.COPILOT_PROFILE_ID -> CopilotClientConfigurable::class.java
            AgentProfileManager.OPENCODE_PROFILE_ID -> OpenCodeClientConfigurable::class.java
            AgentProfileManager.CLAUDE_CLI_PROFILE_ID -> ClaudeCliClientConfigurable::class.java
            AgentProfileManager.JUNIE_PROFILE_ID -> JunieClientConfigurable::class.java
            AgentProfileManager.KIRO_PROFILE_ID -> KiroClientConfigurable::class.java
            AgentProfileManager.CODEX_PROFILE_ID -> CodexClientConfigurable::class.java
            AgentProfileManager.HERMES_PROFILE_ID -> HermesClientConfigurable::class.java
            AgentProfileManager.OPENCLAW_PROFILE_ID -> OpenClawClientConfigurable::class.java
            else -> ClientAgentsGroupConfigurable::class.java
        }
        ShowSettingsUtil.getInstance().showSettingsDialog(project, configurableClass)
    }

    private fun refreshProfileCombo() {
        val allProfiles = agentManager.availableProfiles.toList()
        allProfiles.forEach { profile ->
            binaryStateCache.putIfAbsent(profile.id, BinaryState.UNKNOWN)
        }

        val showOnlyInstalled = AgentBridgeStorageSettings.getInstance().state.isShowOnlyInstalledAgents()
        val filteredProfiles = if (showOnlyInstalled) {
            allProfiles.filter { profile ->
                when (binaryStateCache[profile.id] ?: BinaryState.UNKNOWN) {
                    BinaryState.FOUND -> true
                    BinaryState.MISSING -> false
                    BinaryState.UNKNOWN -> true
                }
            }
        } else {
            allProfiles
        }

        val previousSelectedId = (profileCombo.selectedItem as? AgentProfile)?.id
        val activeId = agentManager.activeProfileId
        val previousItems = (0 until profileCombo.itemCount).mapNotNull { profileCombo.getItemAt(it)?.id }
        val nextItems = filteredProfiles.map { it.id }
        if (previousItems == nextItems) {
            val selected = filteredProfiles.firstOrNull { it.id == previousSelectedId }
                ?: filteredProfiles.firstOrNull { it.id == activeId }
                ?: filteredProfiles.firstOrNull()
            if (selected != null && (profileCombo.selectedItem as? AgentProfile)?.id != selected.id) {
                profileCombo.selectedItem = selected
            }
            return
        }

        profileCombo.removeAllItems()
        filteredProfiles.forEach { profileCombo.addItem(it) }

        val selected = filteredProfiles.firstOrNull { it.id == previousSelectedId }
            ?: filteredProfiles.firstOrNull { it.id == activeId }
            ?: filteredProfiles.firstOrNull()
        if (selected != null) {
            profileCombo.selectedItem = selected
        }
    }

    private fun createSessionSelector(): JComponent {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        panel.add(JBLabel("Resume session").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            alignmentX = LEFT_ALIGNMENT
        })
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))

        sessionCombo.renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = value?.displayText ?: ""
        }
        sessionCombo.alignmentX = LEFT_ALIGNMENT
        sessionCombo.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        panel.add(sessionCombo)

        // Recent sessions list
        panel.add(Box.createVerticalStrut(JBUI.scale(12)))
        panel.add(JBLabel("Recent sessions").apply {
            font = JBUI.Fonts.smallFont().asBold()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            alignmentX = LEFT_ALIGNMENT
            isVisible = false // Hidden if no sessions
        }.also { recentSessionsTitle = it })
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))
        panel.add(recentSessionsPanel)

        refreshSessionCombo()
        return panel
    }

    private fun refreshSessionCombo() {
        sessionCombo.removeAllItems()
        val sessionStore = ConversationService.getInstance(project)
        val sessions = sessionStore.listSessions().toList()

        if (sessions.isNotEmpty()) {
            sessionCombo.addItem(SessionChoice.Latest(sessions.first()))
        }
        sessionCombo.addItem(SessionChoice.None)
        for (i in 1 until sessions.size) {
            sessionCombo.addItem(SessionChoice.Older(sessions[i]))
        }

        // Populate recent list
        recentSessionsPanel.removeAll()
        val recentCount = minOf(sessions.size, 6)
        for (i in 0 until recentCount) {
            val record = sessions[i]
            val choice = if (i == 0) SessionChoice.Latest(record) else SessionChoice.Older(record)
            recentSessionsPanel.add(createRecentSessionItem(choice))
        }

        if (::recentSessionsTitle.isInitialized) {
            recentSessionsTitle.isVisible = sessions.isNotEmpty()
        }
        recentSessionsPanel.revalidate()
        recentSessionsPanel.repaint()
    }

    private fun createRecentSessionItem(choice: SessionChoice): JComponent {
        val record = when (choice) {
            is SessionChoice.Latest -> choice.record
            is SessionChoice.Older -> choice.record
            else -> return Box.createGlue() as JComponent
        }

        val date = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(record.updatedAt))
        val name = record.name.ifEmpty { record.agent }
        val shortName = if (name.length > 40) name.take(37) + "..." else name
        val turns = if (record.turnCount > 0) " (${record.turnCount})" else ""

        val item = JBLabel("$date \u2014 $shortName$turns").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(4, 4)
            alignmentX = LEFT_ALIGNMENT
            icon = AgentIconProvider.getIcon(record.agent)
        }

        item.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                sessionCombo.selectedItem = choice
            }

            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                item.foreground = JBUI.CurrentTheme.Link.Foreground.HOVERED
            }

            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                item.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            }
        })

        return item
    }

    // ── Shared UI helpers ──

    private fun createSectionHeader(
        step: Int,
        title: String,
        description: String,
        @Suppress("UNUSED_PARAMETER") trailingAction: JComponent? = null
    ): JComponent {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        val fullTitle = "$step. $title"
        val titleLabel = JBLabel(fullTitle).apply {
            font = JBUI.Fonts.label().deriveFont(JBUI.Fonts.label().size2D * 1.25f).asBold()
            border = JBUI.Borders.empty(12, 0, 4, 0)
            alignmentX = LEFT_ALIGNMENT
            toolTipText = fullTitle
        }

        val titleRow = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT

            add(titleLabel)
            add(Box.createHorizontalGlue())

            // Keep row height stable while allowing horizontal growth.
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        panel.add(titleRow)
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))
        panel.add(JBLabel(description).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = JBUI.Fonts.label()
            alignmentX = LEFT_ALIGNMENT
        })

        return panel
    }

    // ── MCP state management ──

    private fun subscribeToBridgeEvents() {
        val connection = project.messageBus.connect()

        connection.subscribe(
            McpHttpServer.STATUS_TOPIC,
            McpHttpServer.StatusListener {
                ApplicationManager.getApplication().invokeLater { refreshMcpState() }
            })

        connection.subscribe(
            PsiBridgeService.TOOL_CALL_TOPIC,
            PsiBridgeService.ToolCallListener { event ->
                ApplicationManager.getApplication()
                    .invokeLater { addToolCallEntry(event.toolName(), event.durationMs(), event.success()) }
            })

        connection.subscribe(
            ConversationListener.TOPIC,
            object : ConversationListener {
                override fun historyChanged(allHistoryCleared: Boolean) {
                    ApplicationManager.getApplication().invokeLater { refreshSessionCombo() }
                }
            })

        connection.subscribe(
            AgentProfileManager.TOPIC,
            AgentProfileListener { profileId ->
                ApplicationManager.getApplication().invokeLater {
                    val currentProfile = profileCombo.selectedItem as? AgentProfile
                    if (currentProfile?.id == profileId) {
                        updateProfileStatus()
                    }
                }
            })
    }

    // ── Stats section update ──

    private fun refreshStats() {
        if (!isShowing) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val sessions = ConversationService.getInstance(project).listSessions()
                val lastSession = sessions.firstOrNull()

                val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val todayRows = ConversationStatistics.queryDailyTurnStats(
                    ConversationDatabase.getInstance(project), today, today
                )
                val todayTurns = todayRows.sumOf { it.turns() }
                val todayTools = todayRows.sumOf { it.toolCalls() }
                val todayTimeMs = todayRows.sumOf { it.durationMs() }
                val todayInTok = todayRows.sumOf { it.inputTokens() }
                val todayOutTok = todayRows.sumOf { it.outputTokens() }

                ApplicationManager.getApplication().invokeLater {
                    updateStatLabels(lastSession, todayTurns, todayTools, todayTimeMs, todayInTok, todayOutTok)
                }
            } catch (_: Exception) {
                // Stats are advisory — never crash the refresh loop
            }
        }
    }

    private fun updateStatLabels(
        lastSession: ConversationService.SessionRecord?,
        todayTurns: Int,
        todayTools: Int,
        todayTimeMs: Long,
        todayInTok: Long,
        todayOutTok: Long
    ) {
        if (lastSession != null) {
            lsAgentLabel.text = lastSession.agent
            lsMetaLabel.text = "${lastSession.turnCount} turn${if (lastSession.turnCount != 1) "s" else ""}"
            val ago = System.currentTimeMillis() - lastSession.updatedAt
            lsAgoLabel.text = when {
                ago < 60_000 -> "just now"
                ago < 3_600_000 -> "${ago / 60_000}m ago"
                ago < 86_400_000 -> "${ago / 3_600_000}h ago"
                else -> "${ago / 86_400_000}d ago"
            }
        } else {
            lsAgentLabel.text = "\u2014"
            lsMetaLabel.text = "No sessions yet"
            lsAgoLabel.text = ""
        }

        val hasToday = todayTurns > 0 || todayTimeMs > 0
        if (hasToday) {
            todayTimeLabel.text = TimerDisplayFormatter.formatElapsedTime(todayTimeMs / 1000)
            todayMetaLabel.text =
                "$todayTurns turn${if (todayTurns != 1) "s" else ""} \u00b7 $todayTools tool${if (todayTools != 1) "s" else ""}"
            val totalTok = todayInTok + todayOutTok
            todayTokensLabel.text = if (totalTok > 0)
                "${TimerDisplayFormatter.formatTokenCount(todayInTok)} / ${
                    TimerDisplayFormatter.formatTokenCount(
                        todayOutTok
                    )
                } tokens"
            else ""
        } else {
            todayTimeLabel.text = "\u2014"
            todayMetaLabel.text = "No activity"
            todayTokensLabel.text = ""
        }
    }

    // ── Stats timer lifecycle ──

    private fun startStatsTimer() {
        if (statsRefreshTimer == null) {
            statsRefreshTimer = javax.swing.Timer(10_000) {
                if (isShowing) refreshStats()
            }
        }
        statsRefreshTimer?.start()
        // Also fire immediately so the bar isn't empty on first paint
        refreshStats()
    }

    private fun stopStatsTimer() {
        statsRefreshTimer?.stop()
    }

    private fun refreshMcpState() {
        // Always stop the spinner — we're reflecting a settled state
        mcpSpinner.suspend()
        mcpSpinner.isVisible = false

        val mcpServer = McpServerControl.getInstance(project)
        if (mcpServer == null) {
            mcpStartButton.isEnabled = false
            mcpStartButton.text = START_SERVER
            mcpStartButton.icon = AllIcons.Actions.Execute
            mcpStatusLabel.text = "Error — McpServerControl service not registered"
            mcpStatusLabel.icon = AllIcons.General.Error
            statusPill.background = JBColor(
                Color(0xFD, 0xE0, 0xE0),
                Color(0x3B, 0x2E, 0x2E)
            )
            updateAcpEnabled(false)
            return
        }

        val running = mcpServer.isRunning
        val port = mcpServer.port

        mcpStartButton.isEnabled = !isConnectingAgent
        mcpStartButton.text = if (running) STOP_SERVER else START_SERVER
        mcpStartButton.icon = if (running) AllIcons.Actions.Suspend else AllIcons.Actions.Execute

        if (running && port > 0) {
            mcpRunningUrl = "http://127.0.0.1:$port/mcp"
            mcpStatusLabel.text = "Running \u2014 $mcpRunningUrl"
            mcpStatusLabel.icon = AllIcons.General.InspectionsOK
            mcpUrlCopyButton.isVisible = true
            statusPill.background = JBColor(
                Color(0xE8, 0xF5, 0xE9),
                Color(0x2E, 0x3B, 0x2E)
            )
        } else {
            mcpRunningUrl = ""
            mcpStatusLabel.text = "Stopped"
            mcpStatusLabel.icon = AllIcons.General.InspectionsOKEmpty
            mcpUrlCopyButton.isVisible = false
            statusPill.background = JBColor(
                Color(0xF0, 0xF0, 0xF0),
                Color(0x3C, 0x3C, 0x3C)
            )
        }

        updateAcpEnabled(running)
    }

    private fun updateAcpEnabled(mcpRunning: Boolean) {
        fun setEnabled(component: Component, enabled: Boolean) {
            component.isEnabled = enabled
            if (component is Container) {
                for (child in component.components) {
                    setEnabled(child, enabled)
                }
            }
        }
        setEnabled(acpSection, mcpRunning)
        acpSection.isVisible = true
        acpHintLabel.isVisible = !mcpRunning
    }

    private fun toggleMcpServer() {
        val mcpServer = McpServerControl.getInstance(project)
        if (mcpServer == null) {
            showError("MCP server service is not available — check plugin installation")
            return
        }

        val stopping = mcpServer.isRunning
        mcpStartButton.isEnabled = false
        mcpStartButton.text = if (stopping) "Stopping…" else "Starting…"
        mcpStartButton.icon = null
        mcpSpinner.isVisible = true
        mcpSpinner.resume()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (stopping) {
                    mcpServer.stop()
                } else {
                    val port = McpServerSettings.getInstance(project).port
                    mcpServer.start(port)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater { showError("MCP server error: ${e.message}") }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    refreshMcpState()
                }
            }
        }
    }

    /**
     * Enters a loading state when auto-start is in progress (server not yet running).
     * A 5-second timeout resets the button if STATUS_TOPIC never fires (e.g., startup failed).
     */
    private fun showAutoStartLoading() {
        mcpStartButton.isEnabled = false
        mcpStartButton.text = "Starting\u2026"
        mcpStartButton.icon = null
        mcpSpinner.isVisible = true
        mcpSpinner.resume()
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                if (mcpSpinner.isVisible) {
                    refreshMcpState()
                }
            }
        }, 5, TimeUnit.SECONDS)
    }

    private fun addToolCallEntry(toolName: String, durationMs: Long, success: Boolean) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val status = if (success) "\u2713" else "\u2717"
        val entry = "$time  $status  $toolName  (${durationMs}ms)"

        toolCallEntries.add(entry)
        while (toolCallEntries.size > 200) {
            toolCallEntries.removeAt(0)
        }

        toolCallLink.setHyperlinkText("${toolCallEntries.size} calls")
    }

    private fun showToolCallPopup() {
        if (toolCallEntries.isEmpty()) return

        val listModel = DefaultListModel<String>()
        toolCallEntries.forEach { listModel.addElement(it) }

        val list = JBList(listModel).apply {
            emptyText.text = "No tool calls recorded"
        }
        list.font = JBUI.Fonts.create(Font.MONOSPACED, UIUtil.getLabelFont().size)
        list.visibleRowCount = minOf(toolCallEntries.size, 15)
        list.cellRenderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = value ?: ""
            if (label.text.contains("  \u2717  ")) {
                label.foreground = JBUI.CurrentTheme.Label.errorForeground()
            }
            label.font = JBUI.Fonts.create(Font.MONOSPACED, UIUtil.getLabelFont().size)
        }

        val scrollPane = JBScrollPane(list)
        scrollPane.preferredSize = Dimension(JBUI.scale(420), JBUI.scale(250))

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, list)
            .setTitle("Recent Tool Calls")
            .setResizable(true)
            .setMovable(true)
            .setFocusable(true)
            .createPopup()
            .showUnderneathOf(toolCallLink)
    }

    private fun doConnect() {
        val selectedProfile = profileCombo.selectedItem as? AgentProfile
        if (selectedProfile == null) {
            statusBanner.showError("No agent profile selected — configure one in Settings.")
            return
        }

        val profileId = selectedProfile.id

        val cmd = agentManager.getCustomAcpCommandFor(profileId)
        if (cmd.isBlank()) {
            statusBanner.showError("No start command configured for ${selectedProfile.displayName} — check Settings.")
            return
        }

        val customCommand = if (cmd.isNotBlank() && cmd != selectedProfile.defaultStartCommand) cmd else null

        // Show immediate visual feedback before any work starts, so the EDT is free to repaint
        // the button before applySessionChoice or onConnect run.
        isConnectingAgent = true
        statusBanner.dismissCurrent()
        connectButton.isEnabled = false
        connectButton.text = "Connecting\u2026"
        connectSpinner.isVisible = true

        // Defer the actual work to the next EDT cycle so the button state paints first.
        // applySessionChoice may do file I/O (deleteIfExists for None/Older cases) which
        // should not block the current event handler.
        ApplicationManager.getApplication().invokeLater {
            applySessionChoice(profileId)
            onConnect(profileId, customCommand)
        }
    }

    private fun applySessionChoice(profileId: String) {
        val settings = GenericSettings(profileId, project)
        val sameAgent = agentManager.activeProfileId == profileId
        val sessionSwitch = SessionSwitchService.getInstance(project)

        when (val choice = sessionCombo.selectedItem as? SessionChoice) {
            is SessionChoice.None -> {
                settings.resumeSessionId = null
                // Clear Claude CLI resume state so it starts fresh (no --resume).
                if (sameAgent) sessionSwitch.clearClaudeResumeState()
                // Delete the session ID file so restoreConversation() finds nothing and
                // the chat pane opens empty rather than restoring the previous session.
                ConversationService.getInstance(project).resetCurrentSessionId(project.basePath)
            }

            is SessionChoice.Latest -> {
                switchCurrentSession(choice.record.id)
                // Re-export the current session so the JSONL has the correct last-prompt.
                // Without this, a stale export (e.g. from a previous IDE session) would be
                // reused and Claude CLI could branch from the wrong message.
                if (sameAgent) sessionSwitch.exportForRestart(profileId)
            }

            is SessionChoice.Older -> {
                switchCurrentSession(choice.record.id)
                settings.resumeSessionId = null
                // Export the older session to Claude CLI format. switchCurrentSession()
                // already updated .current-session-id, so exportForRestart() will pick
                // up the correct session. For agent switches, onAgentSwitch() handles this.
                if (sameAgent) sessionSwitch.exportForRestart(profileId)
            }

            null -> { /* no selection — keep defaults */
            }
        }
    }

    private fun switchCurrentSession(sessionId: String) {
        val basePath = project.basePath ?: return
        val sessionsDir = java.io.File(basePath, ".agent-work/sessions")
        val currentIdFile = java.io.File(sessionsDir, ".current-session-id")
        try {
            sessionsDir.mkdirs()
            currentIdFile.writeText(sessionId)
        } catch (e: Exception) {
            statusBanner.showError("Failed to switch session: ${e.message}")
        }
    }

    // ── Public API for AgenticCopilotToolWindowContent ──

    fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            isConnectingAgent = false
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            connectSpinner.isVisible = false
            val profile = agentManager.activeProfile
            when {
                authService.isAuthenticationError(message) && profile.isSupportsOAuthSignIn ->
                    statusBanner.showAuthError(
                        "Not signed in to ${profile.displayName} — click Sign In below.",
                        onSignIn = { startInlineAuth() },
                        onRetry = { doConnect() }
                    )

                authService.isAuthenticationError(message) && profile.terminalSignInCommand != null -> {
                    val signInCmd = profile.terminalSignInCommand!!
                    statusBanner.showAuthError(
                        "Not signed in — click Sign In to run '$signInCmd' in a terminal.",
                        onSignIn = { authService.startTerminalSignIn(signInCmd) },
                        onRetry = { doConnect() }
                    )
                }

                authService.isAuthenticationError(message) ->
                    statusBanner.showError("$message — check your credentials and click Connect to retry.")

                else -> statusBanner.showError(message)
            }
        }
    }

    private fun startInlineAuth() {
        connectButton.isEnabled = false
        connectButton.text = "Signing in…"
        inlineAuthProcess?.destroy()
        inlineAuthProcess = authService.startInlineAuth(
            onDeviceCode = { info: AuthLoginService.DeviceCodeInfo ->
                statusBanner.showDeviceCode(info.code, info.url)
            },
            onAuthComplete = {
                statusBanner.hideDeviceCode()
                inlineAuthProcess = null
                authService.clearPendingAuthError()
                connectButton.isEnabled = true
                connectButton.text = "Connect"
                statusBanner.showInfo("Signed in — click Connect to continue.")
            },
            onFallback = {
                statusBanner.hideDeviceCode()
                inlineAuthProcess = null
                connectButton.isEnabled = true
                connectButton.text = "Connect"
                authService.startCopilotLogin()
            },
        )
    }

    fun resetConnectButton() {
        ApplicationManager.getApplication().invokeLater {
            isConnectingAgent = false
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            connectSpinner.isVisible = false
            acpAutoConnectCheckbox.isSelected = agentManager.isAutoConnect
            refreshProfileCombo()
            refreshSessionCombo()
            sessionCombo.selectedItem = SessionChoice.None
            updateProfileStatus()
        }
    }

    /** Shows "Connecting…" state for auto-connect scenarios. */
    fun showConnecting() {
        ApplicationManager.getApplication().invokeLater {
            isConnectingAgent = true
            statusBanner.dismissCurrent()
            connectButton.isEnabled = false
            connectButton.text = "Connecting\u2026"
            connectSpinner.isVisible = true
        }
    }

    fun refreshMcpStatus() {
        refreshMcpState()
    }
}
