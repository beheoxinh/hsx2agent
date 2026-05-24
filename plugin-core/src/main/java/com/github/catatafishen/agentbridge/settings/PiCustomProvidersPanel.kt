package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.agent.pi.PiCustomProvidersService
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * Editor panel for custom Pi providers (Settings → Tools → AgentBridge → Agents → Pi).
 *
 * Backed by a snapshot of [PiCustomProvidersService]. Saves on `apply()`. The list is
 * shown as a sortable table with Add / Edit / Remove buttons.
 */
class PiCustomProvidersPanel : JPanel(BorderLayout()) {

    private val rows: MutableList<PiCustomProvidersService.Entry> = mutableListOf()
    private val tableModel = ProvidersTableModel(rows)
    private val table = JBTable(tableModel)

    init {
        table.rowHeight = JBUI.scale(24)
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { onAdd() }
            .setEditAction { onEdit() }
            .setRemoveAction { onRemove() }
            .disableUpDownActions()

        add(decorator.createPanel(), BorderLayout.CENTER)
        reset()
    }

    fun isModified(): Boolean = rows != PiCustomProvidersService.getInstance().getProviders()

    fun apply() {
        PiCustomProvidersService.getInstance().setProviders(rows)
    }

    fun reset() {
        rows.clear()
        rows.addAll(PiCustomProvidersService.getInstance().getProviders())
        tableModel.fireTableDataChanged()
    }

    private fun onAdd() {
        val entry = PiCustomProvidersService.Entry()
        if (PiProviderEditorDialog(entry).showAndGet()) {
            val err = entry.validate()
            if (err != null) {
                Messages.showErrorDialog(this, err, "Invalid Provider")
                return
            }
            rows.add(entry)
            tableModel.fireTableDataChanged()
            table.setRowSelectionInterval(rows.size - 1, rows.size - 1)
        }
    }

    private fun onEdit() {
        val idx = table.selectedRow
        if (idx < 0) return
        val copy = rows[idx].copy()
        if (PiProviderEditorDialog(copy).showAndGet()) {
            val err = copy.validate()
            if (err != null) {
                Messages.showErrorDialog(this, err, "Invalid Provider")
                return
            }
            rows[idx] = copy
            tableModel.fireTableRowsUpdated(idx, idx)
        }
    }

    private fun onRemove() {
        val idx = table.selectedRow
        if (idx < 0) return
        rows.removeAt(idx)
        tableModel.fireTableDataChanged()
    }

    private class ProvidersTableModel(private val data: List<PiCustomProvidersService.Entry>) : AbstractTableModel() {
        private val cols = arrayOf("ID", "Name", "Base URL", "API", "Model")
        override fun getRowCount(): Int = data.size
        override fun getColumnCount(): Int = cols.size
        override fun getColumnName(column: Int): String = cols[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val e = data[rowIndex]
            return when (columnIndex) {
                0 -> e.id
                1 -> e.displayName
                2 -> e.baseUrl
                3 -> e.api
                4 -> e.modelId
                else -> ""
            }
        }
    }
}

/**
 * Modal editor for a single [PiCustomProvidersService.Entry]. The supplied entry is
 * mutated in place when the user accepts.
 */
private class PiProviderEditorDialog(
    private val entry: PiCustomProvidersService.Entry
) : DialogWrapper(true) {

    private var idField = entry.id
    private var nameField = entry.displayName
    private var baseUrlField = entry.baseUrl
    private var apiField = entry.api.ifEmpty { "openai-completions" }
    private var apiKeyEnvField = entry.apiKeyEnv
    private var apiKeyValueField = entry.apiKeyValue
    private var modelIdField = entry.modelId
    private var modelNameField = entry.modelName
    private var contextField = if (entry.contextWindow > 0) entry.contextWindow else 128_000
    private var maxTokensField = if (entry.maxTokens > 0) entry.maxTokens else 4096
    private var supportsImageField = entry.supportsImage
    private var supportsReasoningField = entry.supportsReasoning
    private var authHeaderField = entry.authHeader

    init {
        title = if (entry.id.isBlank()) "Add Pi Provider" else "Edit Pi Provider"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Provider ID:") {
            textField()
                .align(AlignX.FILL)
                .applyToComponent {
                    text = idField
                    document.addDocumentListener(textListener { idField = text })
                    emptyText.text = "9router"
                }
                .comment("Unique identifier — referenced by Pi as `--provider <id>`.")
        }
        row("Display name:") {
            textField()
                .align(AlignX.FILL)
                .applyToComponent {
                    text = nameField
                    document.addDocumentListener(textListener { nameField = text })
                    emptyText.text = "9Router"
                }
        }
        row("Base URL:") {
            textField()
                .align(AlignX.FILL)
                .applyToComponent {
                    text = baseUrlField
                    document.addDocumentListener(textListener { baseUrlField = text })
                    emptyText.text = "http://localhost:20128/v1"
                }
                .comment("OpenAI-compatible endpoint. Must start with http:// or https://.")
        }
        row("API:") {
            comboBox(
                listOf(
                    "openai-completions", "openai-responses", "anthropic-messages",
                    "google-generative-ai", "azure-openai-responses", "openai-codex-responses",
                    "mistral-conversations", "google-vertex", "bedrock-converse-stream"
                )
            )
                .applyToComponent {
                    selectedItem = apiField
                    addActionListener { apiField = (selectedItem as? String).orEmpty() }
                }
                .comment("Streaming protocol used by Pi. Most local proxies (LM Studio, Ollama, 9Router) use openai-completions.")
        }
        row("API key env var:") {
            textField()
                .align(AlignX.FILL)
                .applyToComponent {
                    text = apiKeyEnvField
                    document.addDocumentListener(textListener { apiKeyEnvField = text })
                    emptyText.text = "Auto-derived (e.g. 9ROUTER_API_KEY)"
                }
                .comment("Pi reads the key from this env var. Leave blank to derive from the provider ID.")
        }
        row("API key value:") {
            passwordField()
                .align(AlignX.FILL)
                .applyToComponent {
                    text = apiKeyValueField
                    document.addDocumentListener(textListener { apiKeyValueField = text })
                }
                .comment("Stored locally in piCustomProviders.xml. AgentBridge exports it into Pi's environment on launch.")
        }
        row("Auth header:") {
            checkBox("Send Authorization: Bearer <key>")
                .applyToComponent {
                    isSelected = authHeaderField
                    addActionListener { authHeaderField = isSelected }
                }
        }
        separator()
        row("Model ID:") {
            textField()
                .align(AlignX.FILL)
                .applyToComponent {
                    text = modelIdField
                    document.addDocumentListener(textListener { modelIdField = text })
                    emptyText.text = "9route-agent"
                }
                .comment("Model identifier sent in completion requests.")
        }
        row("Model name:") {
            textField()
                .align(AlignX.FILL)
                .applyToComponent {
                    text = modelNameField
                    document.addDocumentListener(textListener { modelNameField = text })
                    emptyText.text = "9Route Agent"
                }
        }
        row("Context window:") {
            intTextField(1024..2_000_000)
                .applyToComponent {
                    text = contextField.toString()
                    document.addDocumentListener(textListener {
                        contextField = text.toIntOrNull() ?: contextField
                    })
                }
        }
        row("Max output tokens:") {
            intTextField(16..200_000)
                .applyToComponent {
                    text = maxTokensField.toString()
                    document.addDocumentListener(textListener {
                        maxTokensField = text.toIntOrNull() ?: maxTokensField
                    })
                }
        }
        row {
            checkBox("Supports images")
                .applyToComponent {
                    isSelected = supportsImageField
                    addActionListener { supportsImageField = isSelected }
                }
        }
        row {
            checkBox("Supports reasoning / extended thinking")
                .applyToComponent {
                    isSelected = supportsReasoningField
                    addActionListener { supportsReasoningField = isSelected }
                }
        }
    }

    override fun doOKAction() {
        entry.id = idField.trim()
        entry.displayName = nameField.trim()
        entry.baseUrl = baseUrlField.trim()
        entry.api = apiField.trim()
        entry.apiKeyEnv = apiKeyEnvField.trim()
        entry.apiKeyValue = apiKeyValueField
        entry.modelId = modelIdField.trim()
        entry.modelName = modelNameField.trim()
        entry.contextWindow = contextField
        entry.maxTokens = maxTokensField
        entry.supportsImage = supportsImageField
        entry.supportsReasoning = supportsReasoningField
        entry.authHeader = authHeaderField
        super.doOKAction()
    }

    private fun textListener(onChange: () -> Unit): javax.swing.event.DocumentListener {
        return object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        }
    }
}
