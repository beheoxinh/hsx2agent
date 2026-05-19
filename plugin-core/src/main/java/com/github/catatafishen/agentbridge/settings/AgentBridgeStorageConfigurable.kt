package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings.StorageLocationMode
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*

/**
 * Root settings page for AgentBridge storage. Holds the shared storage
 * location choice; child pages (Tool Statistics, Memory, Chat History)
 * configure specific stores below it.
 *
 * This page now includes "Project Files" and "Scratch File Types" as sub-tabs
 * to keep the root settings menu clean.
 */
class AgentBridgeStorageConfigurable @Suppress("unused") constructor(
    private val project: Project
) : SearchableConfigurable {

    private val settings = AgentBridgeStorageSettings.getInstance()

    private var mainPanel: JComponent? = null

    // Tab 1: General (Storage Location)
    private var locationPanel: JPanel? = null
    private var projectDefaultButton: JRadioButton? = null
    private var userHomeButton: JRadioButton? = null
    private var customButton: JRadioButton? = null
    private var customField: TextFieldWithBrowseButton? = null

    // Tab 2: Project Files
    private val projectFilesConfigurable = ProjectFilesConfigurable()

    // Tab 3: Scratch File Types
    private val scratchTypesConfigurable = ScratchTypesConfigurable()

    override fun getDisplayName(): String = "Storage"

    override fun getId(): String = ID

    override fun createComponent(): JComponent {
        val tabbedPane = JBTabbedPane()

        tabbedPane.addTab("General", createLocationPanel())

        projectFilesConfigurable.createComponent().let {
            tabbedPane.addTab("Project Files", it)
        }

        scratchTypesConfigurable.createComponent().let {
            tabbedPane.addTab("Scratch File Types", it)
        }

        mainPanel = tabbedPane

        reset()
        return tabbedPane
    }

    private fun createLocationPanel(): JComponent {
        val projectDefault = JRadioButton("Project directory")
        val userHome = JRadioButton("User home directory")
        val custom = JRadioButton("Custom directory")
        val pathField = createCustomPathField()

        ButtonGroup().apply {
            add(projectDefault)
            add(userHome)
            add(custom)
        }

        val updateCustomField = {
            pathField.isEnabled = custom.isSelected
        }
        listOf(projectDefault, userHome, custom).forEach { button ->
            button.addActionListener { updateCustomField() }
        }

        projectDefaultButton = projectDefault
        userHomeButton = userHome
        customButton = custom
        customField = pathField

        val panel = FormBuilder.createFormBuilder()
            .addComponent(
                JBLabel(
                    "<html><body style='width: 520px'>Configure where Hsx2Agent stores per-project data files " +
                        "such as tool-call statistics and semantic memory.</body></html>"
                )
            )
            .addSeparator(8)
            .addComponent(optionPanel(projectDefault, "{project}/.agentbridge", projectDefaultStoragePath()))
            .addComponent(
                optionPanel(
                    userHome,
                    "Shared home directory",
                    "${pathForHtml(AgentBridgeStorageSettings.getUserHomeStorageRoot())}/projects/&lt;project-name&gt;-&lt;hash&gt;"
                )
            )
            .addComponent(customOptionPanel(custom, pathField))
            .addSeparator(12)
            .addComponent(
                contextLabel(
                    "<b>Note:</b> Changing the storage location takes effect on the next IDE restart."
                )
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply {
                border = JBUI.Borders.empty(8)
            }

        locationPanel = panel
        updateCustomField()
        return panel
    }

    override fun isModified(): Boolean {
        return isLocationModified() ||
            projectFilesConfigurable.isModified ||
            scratchTypesConfigurable.isModified
    }

    private fun isLocationModified(): Boolean {
        val selectedMode = selectedMode()
        return selectedMode != settings.storageLocationMode ||
            customPathText() != (settings.customStorageRoot ?: "")
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        applyLocation()
        projectFilesConfigurable.apply()
        scratchTypesConfigurable.apply()
    }

    private fun applyLocation() {
        val mode = selectedMode()
        val customPath = customPathText()
        if (mode == StorageLocationMode.CUSTOM && customPath.isBlank()) {
            throw ConfigurationException("Select a custom Hsx2Agent storage directory.")
        }
        settings.storageLocationMode = mode
        settings.customStorageRoot = customPath.ifBlank { null }
    }

    override fun reset() {
        resetLocation()
        projectFilesConfigurable.reset()
        scratchTypesConfigurable.reset()
    }

    private fun resetLocation() {
        when (settings.storageLocationMode) {
            StorageLocationMode.PROJECT -> projectDefaultButton?.isSelected = true
            StorageLocationMode.USER_HOME -> userHomeButton?.isSelected = true
            StorageLocationMode.CUSTOM -> customButton?.isSelected = true
        }
        customField?.text = settings.customStorageRoot ?: ""
        customField?.isEnabled = customButton?.isSelected == true
    }

    override fun disposeUIResources() {
        mainPanel = null
        locationPanel = null
        projectDefaultButton = null
        userHomeButton = null
        customButton = null
        customField = null
        projectFilesConfigurable.disposeUIResources()
        scratchTypesConfigurable.disposeUIResources()
    }

    private fun selectedMode(): StorageLocationMode = when {
        userHomeButton?.isSelected == true -> StorageLocationMode.USER_HOME
        customButton?.isSelected == true -> StorageLocationMode.CUSTOM
        else -> StorageLocationMode.PROJECT
    }

    private fun customPathText(): String = customField?.text?.trim().orEmpty()

    private fun createCustomPathField(): TextFieldWithBrowseButton {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Hsx2Agent Storage Directory")
            .withDescription(
                "Hsx2Agent per-project data files such as tool-call statistics and semantic memory " +
                    "will be stored under this directory."
            )
        return TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, descriptor)
            textField.let { field ->
                if (field is com.intellij.ui.components.JBTextField) {
                    field.emptyText.text = AgentBridgeStorageSettings.getUserHomeStorageRoot().toString()
                }
            }
        }
    }

    private fun optionPanel(button: JRadioButton, label: String, path: String): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            add(button, BorderLayout.NORTH)
            add(contextLabel("$label: <code>$path</code>").indented(), BorderLayout.CENTER)
        }

    private fun customOptionPanel(button: JRadioButton, field: TextFieldWithBrowseButton): JPanel =
        JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            button.alignmentX = Component.LEFT_ALIGNMENT
            add(button)
            add(
                contextLabel("Chosen root stores data under <code>&lt;path&gt;/projects/&lt;project-name&gt;-&lt;hash&gt;</code>.").indented()
                    .apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
            add(Box.createVerticalStrut(4))
            add(field.indented().apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

    private fun contextLabel(html: String): JBLabel =
        JBLabel("<html><body style='width: 520px'>$html</body></html>").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }

    private fun JComponent.indented(): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(24)
            add(this@indented, BorderLayout.CENTER)
        }

    private fun projectDefaultStoragePath(): String {
        val basePath = project.basePath ?: return "{project}/.agentbridge"
        return pathForHtml(java.nio.file.Path.of(basePath, ".agentbridge"))
    }

    private fun pathForHtml(path: java.nio.file.Path): String =
        StringUtil.escapeXmlEntities(path.toString())

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.storage"
    }
}
