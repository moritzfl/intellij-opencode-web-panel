package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import de.moritzf.opencodewebpanel.toolWindow.SharedOpenCodeServerManager
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent

class OpenCodeProjectSettingsConfigurable(private val project: Project) : Configurable {
    private var panel: JComponent? = null
    private val autoProjectDirectoryRadioButton = JBRadioButton("Auto detect")
    private val customProjectDirectoryRadioButton = JBRadioButton("Custom directory")
    private val projectDirectoryField = TextFieldWithBrowseButton().apply {
        textField.columns = 40
        toolTipText = "Directory OpenCode should open for this IDE project"
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }
    private val detectProjectDirectoryButton = JButton("Detect").apply {
        toolTipText = "Auto-detect the OpenCode project directory and fill the path"
        accessibleContext.accessibleName = "Detect OpenCode project directory"
    }

    override fun getDisplayName(): String = "OpenCode Web Panel"

    override fun createComponent(): JComponent {
        ButtonGroup().apply {
            add(autoProjectDirectoryRadioButton)
            add(customProjectDirectoryRadioButton)
        }
        autoProjectDirectoryRadioButton.addItemListener { updateProjectDirectoryControls() }
        customProjectDirectoryRadioButton.addItemListener { updateProjectDirectoryControls() }
        detectProjectDirectoryButton.addActionListener { detectProjectDirectory() }

        panel = panel {
            buttonsGroup("OpenCode project directory:") {
                row {
                    cell(autoProjectDirectoryRadioButton)
                        .comment("Use this IDE project's root: ${project.basePath ?: "not available"}.")
                }
                row {
                    cell(customProjectDirectoryRadioButton).gap(RightGap.SMALL)
                    cell(projectDirectoryField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .gap(RightGap.SMALL)
                    cell(detectProjectDirectoryButton)
                }
            }
        }
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        return selectedProjectDirectoryMode() != settings.projectDirectoryModeValue() ||
            projectDirectory() != settings.openCodeProjectDirectory
    }

    override fun apply() {
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        val oldDirectory = settings.effectiveProjectDirectory(project.basePath)
        val nextMode = selectedProjectDirectoryMode()
        val nextDirectory = projectDirectory()
        if (nextMode == OpenCodeProjectDirectoryMode.CUSTOM && nextDirectory.isBlank()) {
            throw ConfigurationException("OpenCode project directory must not be empty when custom mode is selected.")
        }
        if (nextMode == OpenCodeProjectDirectoryMode.CUSTOM && !File(nextDirectory).isDirectory) {
            throw ConfigurationException("OpenCode project directory must be an existing directory.")
        }
        settings.projectDirectoryMode = nextMode.name
        settings.openCodeProjectDirectory = nextDirectory
        val newDirectory = settings.effectiveProjectDirectory(project.basePath)
        if (oldDirectory != newDirectory) {
            SharedOpenCodeServerManager.getInstance().stopServer()
            project.messageBus
                .syncPublisher(OpenCodeProjectSettingsListener.TOPIC)
                .projectDirectoryChanged(newDirectory)
        }
    }

    override fun reset() {
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        when (settings.projectDirectoryModeValue()) {
            OpenCodeProjectDirectoryMode.AUTO -> autoProjectDirectoryRadioButton.isSelected = true
            OpenCodeProjectDirectoryMode.CUSTOM -> customProjectDirectoryRadioButton.isSelected = true
        }
        projectDirectoryField.text = settings.openCodeProjectDirectory
        updateProjectDirectoryControls()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun projectDirectory(): String {
        return OpenCodeProjectSettingsState.sanitizeProjectDirectory(projectDirectoryField.text)
    }

    private fun selectedProjectDirectoryMode(): OpenCodeProjectDirectoryMode {
        return if (customProjectDirectoryRadioButton.isSelected) OpenCodeProjectDirectoryMode.CUSTOM else OpenCodeProjectDirectoryMode.AUTO
    }

    private fun updateProjectDirectoryControls() {
        val customSelected = customProjectDirectoryRadioButton.isSelected
        projectDirectoryField.isEnabled = customSelected
        detectProjectDirectoryButton.isEnabled = customSelected
    }

    private fun detectProjectDirectory() {
        val detectedDirectory = OpenCodeProjectSettingsState.autoDetectedProjectDirectory(project.basePath)
        if (detectedDirectory == null || !File(detectedDirectory).isDirectory) {
            Messages.showWarningDialog(
                panel ?: projectDirectoryField,
                "Could not auto-detect an OpenCode project directory for this IDE project.",
                "OpenCode Project Directory Not Found",
            )
            return
        }
        customProjectDirectoryRadioButton.isSelected = true
        projectDirectoryField.text = detectedDirectory
        updateProjectDirectoryControls()
    }
}
