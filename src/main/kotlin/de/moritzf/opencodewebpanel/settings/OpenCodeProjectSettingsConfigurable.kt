package de.moritzf.opencodewebpanel.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.SbxOpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxExtraMount
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import de.moritzf.opencodewebpanel.server.formatOpenCodeServerLifecycleStatusText
import de.moritzf.opencodewebpanel.server.formatOpenCodeServerStatusDetail
import de.moritzf.opencodewebpanel.toolWindow.confirmOpenCodeSandboxBinaryUpgrade
import de.moritzf.opencodewebpanel.toolWindow.confirmOpenCodeServerRestart
import de.moritzf.opencodewebpanel.toolWindow.requestOpenCodeServerRestart
import java.awt.Component
import java.io.File
import javax.swing.AbstractCellEditor
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellEditor

class OpenCodeProjectSettingsConfigurable(private val project: Project) : Configurable {
    private var panel: JComponent? = null
    private var controlListenersInstalled = false
    private var hydrating = false
    private var lifecycleConnection: MessageBusConnection? = null
    private var loadedSpecDirectory: String? = null
    private val serverStatusLabel = JBLabel().apply {
        toolTipText = "OpenCode server status for this project"
    }
    private val diagnosticsLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val installedHintLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val setupChecklistLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val restartServerButton = JButton("Restart Server", AllIcons.Actions.Restart).apply {
        toolTipText = "Restart OpenCode for this project"
        accessibleContext.accessibleName = "Restart OpenCode server"
    }
    private val viewServerLogButton = JButton("View Server Log", AllIcons.Actions.Show).apply {
        toolTipText = "Show this project's OpenCode server output"
        accessibleContext.accessibleName = "View OpenCode server log"
    }
    private val upgradeOpenCodeButton = JButton("Upgrade OpenCode", AllIcons.Actions.Lightning).apply {
        toolTipText = "Run opencode upgrade inside this sandbox. The VM and sessions stay. Only available once the sandbox runtime is selected and the sandbox is set up."
        accessibleContext.accessibleName = "Upgrade OpenCode in sandbox"
        isVisible = false
    }
    private val autoPortRadioButton = JBRadioButton("Auto select")
    private val fixedPortRadioButton = JBRadioButton("Fixed port")
    private val fixedPortField = JBTextField().apply {
        columns = 6
        toolTipText = "Loopback port for this project's OpenCode server"
    }
    private val portControlsPanel = panel {
        buttonsGroup("Server port:") {
            row {
                cell(autoPortRadioButton)
                    .comment("Pick a free loopback port. Docker Sandbox still serves 4096 inside the VM; the published host port is ephemeral.")
            }
            row {
                cell(fixedPortRadioButton).gap(RightGap.SMALL)
                cell(fixedPortField)
                    .comment("Bind this loopback port. Docker Sandbox republishes VM 4096 here. Default: ${OpenCodeSettingsState.DEFAULT_FIXED_PORT}.")
            }
        }
    }
    private val autoProjectDirectoryRadioButton = JBRadioButton("Auto detect")
    private val customProjectDirectoryRadioButton = JBRadioButton("Custom directory")
    private val projectDirectoryField = TextFieldWithBrowseButton().apply {
        textField.columns = 40
        toolTipText = "Directory OpenCode should open for this IDE project"
        // The chooser stays usable in auto mode as a way to fill the path; only typing is
        // custom-only. Never disable the whole component: disabling hides the browse button
        // completely and it is not reliably shown again after re-enabling.
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor())
        addActionListener { customProjectDirectoryRadioButton.isSelected = true }
    }
    private val detectProjectDirectoryButton = JButton("Detect").apply {
        toolTipText = "Auto-detect the OpenCode project directory and fill the path"
        accessibleContext.accessibleName = "Detect OpenCode project directory"
    }
    private val hostRuntimeRadioButton = JBRadioButton("Host (native CLI)")
    private val sbxRuntimeRadioButton = JBRadioButton("Docker Sandbox (sbx)")
    init {
        ButtonGroup().apply {
            add(autoPortRadioButton)
            add(fixedPortRadioButton)
        }
        ButtonGroup().apply {
            add(autoProjectDirectoryRadioButton)
            add(customProjectDirectoryRadioButton)
        }
        ButtonGroup().apply {
            add(hostRuntimeRadioButton)
            add(sbxRuntimeRadioButton)
        }
    }
    private val sbxMemoryField = JBTextField().apply {
        columns = 6
        toolTipText = "Sandbox memory at create time (for example 4g)"
        accessibleContext.accessibleName = "Sandbox memory"
    }
    private val sbxCpusField = JBTextField().apply {
        columns = 4
        toolTipText = "Sandbox CPUs at create time"
        accessibleContext.accessibleName = "Sandbox CPUs"
    }
    private val sbxShareHostConfigCheckBox = JBCheckBox("Share host OpenCode config and file secrets")
    private val sbxProtectSandboxFilesCheckBox = JBCheckBox("Protect sandbox files")
    private val sbxPersistSandboxSessionsCheckBox = JBCheckBox("Persist sandbox sessions across Reset")
    private val sbxEnableIntellijMcpCheckBox = JBCheckBox("Enable IntelliJ MCP in the sandbox")
    private val hostPathEditor = BrowsePathCellEditor(
        FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
            .withTitle("Select Host Path")
            .withDescription("Choose a file or folder to mount into the sandbox.")
            .withShowHiddenFiles(true)
            .apply { isForcedToUseIdeaFileChooser = true },
    )
    private val kitPathEditor = BrowsePathCellEditor(
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Kit")
            .withDescription("Choose a local kit directory. File refs, Git URLs, and OCI refs can be typed. Protect sandbox files only overlays kit directories.")
            .withShowHiddenFiles(true)
            .apply { isForcedToUseIdeaFileChooser = true },
    )
    private val extraMountTableModel = ListTableModel<ExtraMountRow>(
        object : ColumnInfo<ExtraMountRow, String>("Host Path") {
            override fun valueOf(item: ExtraMountRow): String = item.hostPath
            override fun isCellEditable(item: ExtraMountRow): Boolean = true
            override fun setValue(item: ExtraMountRow, value: String?) {
                item.hostPath = SbxCli.posixPath(value.orEmpty())
            }
            override fun getEditor(item: ExtraMountRow): TableCellEditor = hostPathEditor
        },
        object : ColumnInfo<ExtraMountRow, String>("Sandbox Path") {
            override fun valueOf(item: ExtraMountRow): String = item.sandboxPath
            override fun isCellEditable(item: ExtraMountRow): Boolean = true
            override fun setValue(item: ExtraMountRow, value: String?) {
                item.sandboxPath = SbxCli.posixPath(value.orEmpty())
            }
        },
    )
    private val extraMountTable = TableView<ExtraMountRow>(extraMountTableModel).apply {
        tableHeader.reorderingAllowed = false
        visibleRowCount = 4
        accessibleContext.accessibleName = "Extra sandbox file and directory mounts"
    }
    private val extraMountPanel = ToolbarDecorator.createDecorator(extraMountTable)
        .setAddAction { addTableRow(extraMountTable, extraMountTableModel, ExtraMountRow()) }
        .setRemoveActionUpdater { extraMountTable.selectedObject != null }
        .setRemoveAction {
            extraMountTable.selectedObject?.let { extraMountTableModel.removeRow(extraMountTableModel.indexOf(it)) }
        }
        .disableUpDownActions()
        .createPanel()
        .apply { preferredSize = JBUI.size(480, 140) }
    private val kitTableModel = ListTableModel<KitRow>(
        object : ColumnInfo<KitRow, String>("Kit") {
            override fun valueOf(item: KitRow): String = item.ref
            override fun isCellEditable(item: KitRow): Boolean = true
            override fun setValue(item: KitRow, value: String?) {
                item.ref = SbxCli.posixPath(value.orEmpty())
            }
            override fun getEditor(item: KitRow): TableCellEditor = kitPathEditor
        },
    )
    private val kitTable = TableView<KitRow>(kitTableModel).apply {
        tableHeader.reorderingAllowed = false
        visibleRowCount = 4
        accessibleContext.accessibleName = "Sandbox kits"
        toolTipText = "Local kit file, Git URL, or OCI ref (for example docker.io/sbx/playwright-kit:latest)"
    }
    private val kitPanel = ToolbarDecorator.createDecorator(kitTable)
        .setAddAction { addTableRow(kitTable, kitTableModel, KitRow()) }
        .setRemoveActionUpdater { kitTable.selectedObject != null }
        .setRemoveAction {
            kitTable.selectedObject?.let { kitTableModel.removeRow(kitTableModel.indexOf(it)) }
        }
        .disableUpDownActions()
        .createPanel()
        .apply { preferredSize = JBUI.size(480, 140) }
    private val createNetworkKitButton = JButton("Create extra-network kit template").apply {
        toolTipText = "Writes opencode-sbx/opencode-network-kit/spec.yaml with common hosts commented out"
        accessibleContext.accessibleName = "Create extra-network kit template"
    }
    private val sandboxOnlyPanel = panel {
        group("Docker Sandbox") {
            row("Memory:") {
                cell(sbxMemoryField)
                    .comment("Used when creating a sandbox (default ${SbxCli.DEFAULT_MEMORY}). Changing this recreates the VM (gear menu → Reset Sandbox).")
            }
            row("CPUs:") {
                cell(sbxCpusField)
                    .comment("Used when creating a sandbox (default ${SbxCli.DEFAULT_CPUS}). Changing this recreates the VM (gear menu → Reset Sandbox).")
            }
            row {
                cell(sbxShareHostConfigCheckBox)
                    .comment("Mounts the host OpenCode config directory read-only (opencode.json/jsonc, skills, agents, commands, plugins), plus file-based auth.json via environment. The sandbox can read those files and cannot change them. Sessions and browser preferences stay separate. Environment API keys use sbx secret. Changing this recreates the VM.")
            }
            row {
                cell(sbxProtectSandboxFilesCheckBox)
                    .comment("Overlays the opencode-sbx/ folder (spec, launchers, extra-network kit) and other local kit directories as read-only extra workspaces on the writable project tree. Git/OCI kits are unchanged. Applies when the sandbox is created (gear menu → Reset Sandbox).")
            }
            row {
                cell(sbxPersistSandboxSessionsCheckBox)
                    .comment("Keeps this sandbox's OpenCode conversations on the host under a plugin data directory (not Host CLI's opencode.db). Reset Sandbox recreates the VM but remounts the same store. Applies at create time.")
            }
            row {
                cell(sbxEnableIntellijMcpCheckBox)
                    .comment("Overlays the IntelliJ MCP server as host.docker.internal. Ignored by opencode-sbx.sh.")
            }
            row {
                cell(extraMountPanel)
                    .label("Mounts:", LabelPosition.TOP)
                    .align(AlignX.FILL)
                    .comment("Host file or folder is mounted as-is; a symlink is created at the sandbox path when they differ. Changing this recreates the VM. Use + to add a row; double-click a cell to edit it.")
            }
            row {
                cell(kitPanel)
                    .label("Kits:", LabelPosition.TOP)
                    .align(AlignX.FILL)
                    .comment(
                        "YAML applied when the sandbox is created (tools, files, env, network). " +
                            "Mixin kits only — do not use a kit that replaces the OpenCode agent. " +
                            "Appending a new, uniquely named kit uses sbx kit add and preserves sessions. Removing/reordering kits recreates the VM. " +
                            "Use + to add a row; double-click a cell to edit it. " +
                            "<a href=\"$SBX_KIT_DOCS_URL\">Docker kit docs</a>",
                        action = HyperlinkEventAction { event ->
                            val href = event.url?.toString() ?: event.description
                            if (!href.isNullOrBlank()) BrowserUtil.browse(href)
                        },
                    )
            }
            row {
                cell(createNetworkKitButton)
                    .comment("Writes opencode-sbx/opencode-network-kit/spec.yaml with hosts commented out. Configure it before the first Apply. Editing an already installed kit does not update the sandbox: Reset Sandbox reloads it but deletes VM-only data. Restart alone is insufficient. Network access belongs in a kit, not the launch YAML.")
            }
            row {
                cell(installedHintLabel)
            }
            row {
                cell(setupChecklistLabel)
            }
            row {
                comment("Sbx location, login, and network policy: Tools → OpenCode Web Panel → Docker Sandboxes.")
            }
        }
    }

    override fun getDisplayName(): String = PROJECT_SETTINGS_DISPLAY_NAME

    override fun createComponent(): JComponent {
        installControlListenersOnce()
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
            group("Server") {
                row {
                    cell(serverStatusLabel)
                }
                row {
                    cell(diagnosticsLabel)
                }
                row {
                    cell(restartServerButton).gap(RightGap.SMALL)
                    cell(viewServerLogButton).gap(RightGap.SMALL)
                    cell(upgradeOpenCodeButton)
                }
                row {
                    cell(portControlsPanel).align(AlignX.FILL)
                }
            }
            group("Runtime") {
                buttonsGroup {
                    row {
                        cell(hostRuntimeRadioButton)
                            .comment("Native opencode serve on this machine. Stored as useSandbox: false in opencode-sbx.yaml.")
                    }
                    row {
                        cell(sbxRuntimeRadioButton)
                            .comment("OpenCode inside a Docker Sandbox. Requires sbx and a Docker account. Apple silicon macOS, Windows 11 with WHP, or KVM Linux.")
                    }
                }
            }
            row {
                cell(sandboxOnlyPanel).align(AlignX.FILL)
            }
        }
        reset()
        subscribeToLifecycleChanges()
        updateServerStatus()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        val directoryChanged = selectedProjectDirectoryMode() != settings.projectDirectoryModeValue() ||
            projectDirectory() != settings.openCodeProjectDirectory
        if (directoryChanged) return true
        val loaded = loadedOrDefaultSpec() ?: return false
        val current = currentSpec() ?: return true
        // `name` is derived from the directory and has no form field; a hand-written name in
        // the spec must not keep Apply permanently enabled (every apply would rewrite it).
        return current.adoptStoredName(loaded) != loaded
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
        if (selectedPortMode() == OpenCodePortMode.FIXED) {
            val portText = fixedPortField.text.trim()
            // Blank stays lenient for compatibility (falls back to the default port); reject only real garbage.
            if (portText.isNotEmpty() && (portText.toIntOrNull() == null || portText.toInt() !in 1..65535)) {
                throw ConfigurationException("Fixed port must be a number between 1 and 65535.")
            }
        }
        commitTableEditors()
        if (sbxRuntimeRadioButton.isSelected) {
            if (SbxCli.parseMemory(sbxMemoryField.text) == null) {
                throw ConfigurationException("Sandbox memory must be a value like 4g or 512m.")
            }
            if (SbxCli.parseCpus(sbxCpusField.text) == null) {
                throw ConfigurationException("Sandbox CPUs must be an integer from 1 to 32.")
            }
        }
        val targetDirectory = effectiveDirectory()
        when (val inspection = SbxLaunchSpec.inspect(targetDirectory)) {
            is de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection.Invalid -> {
                if (ApplicationManager.getApplication().isUnitTestMode ||
                    !MessageDialogBuilder.yesNo(
                        "Replace invalid sandbox spec",
                        "The file at ${inspection.path} is invalid (${inspection.reason}). Replace it with the current form values?",
                    ).yesText("Replace").noText("Cancel").ask(panel)
                ) {
                    throw ConfigurationException("Invalid sandbox spec at ${inspection.path}: ${inspection.reason}")
                }
            }
            else -> Unit
        }
        val spec = currentSpec()
            ?: throw ConfigurationException("Set an OpenCode project directory first.")
        // The preview compares what will be written against the *destination's* current spec.
        // Diffing the old directory's spec on a directory switch would report kit/mount changes
        // that are really another project's settings, and offer "Recreate" for a write that only
        // creates a fresh spec. Stopping the previous backend is decided by directoryChanged below.
        val storedDestinationSpec = SbxLaunchSpec.load(spec.canonicalDirectory)
        val canonicalOldDirectory = OpenCodeServerProtocol.canonicalOpenCodeDirectory(oldDirectory) ?: oldDirectory
        val preview = de.moritzf.opencodewebpanel.server.SbxApplyPreview.build(
            directory = spec.canonicalDirectory,
            oldSpec = storedDestinationSpec,
            newSpec = spec,
            directoryChanged = canonicalOldDirectory != null && canonicalOldDirectory != spec.canonicalDirectory,
            portChanged = storedDestinationSpec?.hostPort != spec.hostPort,
            historyNote = sandboxSessionRetentionSummary(spec.canonicalDirectory),
        )
        if (preview.changes.isNotEmpty() &&
            !ApplicationManager.getApplication().isUnitTestMode
        ) {
            val recreate = preview.effect == de.moritzf.opencodewebpanel.server.SbxApplyEffect.RECREATE
            val confirmed = MessageDialogBuilder.yesNo(preview.confirmTitle(), preview.message())
                .yesText(if (recreate) "Recreate" else "Apply")
                .noText("Cancel")
                .icon(if (recreate) Messages.getWarningIcon() else Messages.getInformationIcon())
                .ask(panel)
            if (!confirmed) throw ConfigurationException("Cancelled.")
        }
        val registry = OpenCodeServerBackendRegistry.getInstance()
        // Resolve before saving useSandbox: afterwards the registry selects the new runtime.
        val oldBackend = registry.backendFor(project)
        // Keep a hand-written sandbox name from the destination spec; only the derived default
        // is refreshed from the (possibly changed) directory.
        val specToPersist = storedDestinationSpec?.let { spec.adoptStoredName(it) } ?: spec
        if (SbxLaunchSpec.persist(specToPersist) == null) {
            throw ConfigurationException("Could not save ${SbxLaunchSpec.PROJECT_SPEC_NAME}. Check the project directory permissions and IDE log.")
        }
        settings.projectDirectoryMode = nextMode.name
        settings.openCodeProjectDirectory = nextDirectory
        settings.portMode = selectedPortMode().name
        settings.fixedPort = fixedPortOrDefault()
        fixedPortField.text = settings.fixedPort.toString()
        val newDirectory = settings.effectiveProjectDirectory(project.basePath)
        val directoryChanged = oldDirectory != newDirectory
        val runtimeChanged = (oldBackend is SbxOpenCodeServerBackend) != spec.useSandbox
        val shouldStop = directoryChanged || runtimeChanged ||
            preview.effect == de.moritzf.opencodewebpanel.server.SbxApplyEffect.RESTART ||
            preview.effect == de.moritzf.opencodewebpanel.server.SbxApplyEffect.RECREATE
        val modality = ModalityState.defaultModalityState()
        if (shouldStop) {
            oldBackend.stopServer {
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) {
                        project.messageBus.syncPublisher(OpenCodeProjectSettingsListener.TOPIC).serverRestartRequested()
                    }
                }, modality)
            }
        } else if (preview.effect == de.moritzf.opencodewebpanel.server.SbxApplyEffect.LIVE &&
            oldBackend is SbxOpenCodeServerBackend
        ) {
            val reload = preview.changes.any { it.summary == "Server port" } &&
                oldBackend.getLifecycleState() == OpenCodeServerLifecycleState.RUNNING
            oldBackend.applyLiveSettings {
                if (!reload) return@applyLiveSettings
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) {
                        project.messageBus.syncPublisher(OpenCodeProjectSettingsListener.TOPIC).serverReloadRequested()
                    }
                }, modality)
            }
        }
        updateSandboxControls()
        updateServerStatus()
    }

    override fun reset() {
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        hydrating = true
        try {
            when (settings.projectDirectoryModeValue()) {
                OpenCodeProjectDirectoryMode.AUTO -> autoProjectDirectoryRadioButton.isSelected = true
                OpenCodeProjectDirectoryMode.CUSTOM -> customProjectDirectoryRadioButton.isSelected = true
            }
            projectDirectoryField.text = settings.openCodeProjectDirectory
            loadSpecIntoUi(loadedOrDefaultSpec(), fromYaml = SbxLaunchSpec.load(effectiveDirectory()) != null)
            loadedSpecDirectory = effectiveDirectory()
            updateProjectDirectoryControls()
            updateSandboxControls()
            updateServerStatus()
        } finally {
            hydrating = false
        }
    }

    override fun disposeUIResources() {
        panel = null
        lifecycleConnection?.disconnect()
        lifecycleConnection = null
    }

    private fun installControlListenersOnce() {
        if (controlListenersInstalled) return
        controlListenersInstalled = true
        autoProjectDirectoryRadioButton.addItemListener {
            updateProjectDirectoryControls()
            onDirectoryTargetChanged()
        }
        customProjectDirectoryRadioButton.addItemListener {
            updateProjectDirectoryControls()
            onDirectoryTargetChanged()
        }
        projectDirectoryField.textField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) {
                onDirectoryTargetChanged()
            }
        })
        detectProjectDirectoryButton.addActionListener { detectProjectDirectory() }
        hostRuntimeRadioButton.addItemListener { updateSandboxControls() }
        sbxRuntimeRadioButton.addItemListener { updateSandboxControls() }
        autoPortRadioButton.addItemListener { updatePortControls() }
        fixedPortRadioButton.addItemListener { updatePortControls() }
        restartServerButton.addActionListener { restartThisProjectServer() }
        viewServerLogButton.addActionListener { showThisProjectServerLog() }
        upgradeOpenCodeButton.addActionListener { upgradeOpenCodeInSandbox() }
        createNetworkKitButton.addActionListener { createNetworkKitTemplate() }
    }

    private fun projectBackend() = OpenCodeServerBackendRegistry.getInstance().backendFor(project)

    private fun subscribeToLifecycleChanges() {
        lifecycleConnection?.disconnect()
        lifecycleConnection = ApplicationManager.getApplication().messageBus.connect().also { connection ->
            connection.subscribe(
                OpenCodeServerLifecycleListener.TOPIC,
                object : OpenCodeServerLifecycleListener {
                    override fun stateChanged(state: OpenCodeServerLifecycleState, backendId: String) {
                        if (backendId != projectBackend().backendId) return
                        ApplicationManager.getApplication().invokeLater({
                            if (panel != null) updateServerStatus()
                        }, ModalityState.stateForComponent(panel ?: serverStatusLabel))
                    }
                },
            )
        }
    }

    private fun updateServerStatus() {
        val backend = projectBackend()
        val state = backend.getLifecycleState()
        val detail = formatOpenCodeServerStatusDetail(
            state,
            backend.getServerUrl(),
            backend.getServerVersion(),
            backend.backendId,
        )
        serverStatusLabel.text = formatOpenCodeServerLifecycleStatusText(state, detail)
        val sbx = backend as? SbxOpenCodeServerBackend
        diagnosticsLabel.text = sbx?.diagnostics()?.format().orEmpty()
        diagnosticsLabel.isVisible = sbx != null
        restartServerButton.isEnabled = state != OpenCodeServerLifecycleState.STARTING &&
            state != OpenCodeServerLifecycleState.RESTARTING
        updateSandboxControls()
    }

    private fun restartThisProjectServer() {
        if (!confirmOpenCodeServerRestart(project)) return
        requestOpenCodeServerRestart(project)
    }

    private fun upgradeOpenCodeInSandbox() {
        val backend = projectBackend() as? SbxOpenCodeServerBackend ?: return
        if (!confirmOpenCodeSandboxBinaryUpgrade(project)) return
        backend.upgradeOpenCodeBinary(project)
    }

    private fun showThisProjectServerLog() {
        val logFile = projectBackend().getServerLogFile()
        if (logFile == null) {
            Messages.showErrorDialog(panel ?: viewServerLogButton, "No server log available yet.", "Open Server Log")
            return
        }
        try {
            java.awt.Desktop.getDesktop().open(logFile.toFile())
        } catch (e: Exception) {
            Messages.showErrorDialog(
                panel ?: viewServerLogButton,
                "Could not open server log: ${e.message ?: e::class.java.simpleName}",
                "Open Server Log",
            )
        }
    }

    private fun projectDirectory(): String {
        return OpenCodeProjectSettingsState.sanitizeProjectDirectory(projectDirectoryField.text)
    }

    private fun selectedProjectDirectoryMode(): OpenCodeProjectDirectoryMode {
        return if (customProjectDirectoryRadioButton.isSelected) {
            OpenCodeProjectDirectoryMode.CUSTOM
        } else {
            OpenCodeProjectDirectoryMode.AUTO
        }
    }

    private fun effectiveDirectory(): String? {
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        val mode = selectedProjectDirectoryMode()
        val custom = projectDirectory()
        return when (mode) {
            OpenCodeProjectDirectoryMode.AUTO -> OpenCodeProjectSettingsState.autoDetectedProjectDirectory(project.basePath)
            OpenCodeProjectDirectoryMode.CUSTOM -> custom.ifBlank {
                OpenCodeProjectSettingsState.autoDetectedProjectDirectory(project.basePath)
            }
        } ?: settings.effectiveProjectDirectory(project.basePath)
    }

    private fun loadedOrDefaultSpec(): SbxLaunchSpec? {
        val directory = effectiveDirectory() ?: return null
        val canonical = OpenCodeServerProtocol.canonicalOpenCodeDirectory(directory) ?: directory
        return SbxLaunchSpec.load(canonical) ?: SbxLaunchSpec.fromSettings(
            OpenCodeSettingsState.getInstance(),
            canonical,
            hostPort = OpenCodeProjectSettingsState.getInstance(project).hostPortOrNull(),
        )
    }

    private fun currentSpec(): SbxLaunchSpec? {
        val directory = effectiveDirectory() ?: return null
        val canonical = OpenCodeServerProtocol.canonicalOpenCodeDirectory(directory) ?: directory
        return SbxLaunchSpec(
            canonicalDirectory = canonical,
            name = SbxCli.sandboxName(canonical),
            memory = SbxCli.sanitizeMemory(sbxMemoryField.text),
            cpus = SbxCli.sanitizeCpus(sbxCpusField.text),
            kits = SbxCli.parseLineList(kitRows().joinToString("\n") { it.ref }),
            extraMounts = extraMountRows().filter { it.hostPath.isNotBlank() }.map {
                SbxExtraMount(SbxCli.posixPath(it.hostPath), SbxCli.posixPath(it.sandboxPath.ifBlank { it.hostPath }))
            }.distinct(),
            shareHostOpencodeConfig = sbxShareHostConfigCheckBox.isSelected,
            enableIntellijMcp = sbxEnableIntellijMcpCheckBox.isSelected,
            protectSandboxFiles = sbxProtectSandboxFilesCheckBox.isSelected,
            persistSandboxSessions = sbxPersistSandboxSessionsCheckBox.isSelected,
            useSandbox = sbxRuntimeRadioButton.isSelected,
            hostPort = if (selectedPortMode() == OpenCodePortMode.FIXED) {
                fixedPortOrDefault()
            } else {
                null
            },
        )
    }

    private fun loadSpecIntoUi(spec: SbxLaunchSpec?, fromYaml: Boolean = spec != null) {
        val sandbox = spec?.useSandbox ?: false
        if (sandbox) sbxRuntimeRadioButton.isSelected = true else hostRuntimeRadioButton.isSelected = true
        sbxMemoryField.text = spec?.memory ?: SbxCli.DEFAULT_MEMORY
        sbxCpusField.text = spec?.cpus ?: SbxCli.DEFAULT_CPUS
        sbxShareHostConfigCheckBox.isSelected = spec?.shareHostOpencodeConfig ?: false
        sbxEnableIntellijMcpCheckBox.isSelected = spec?.enableIntellijMcp ?: true
        sbxProtectSandboxFilesCheckBox.isSelected = spec?.protectSandboxFiles ?: true
        sbxPersistSandboxSessionsCheckBox.isSelected = spec?.persistSandboxSessions ?: true
        loadPortIntoUi(if (fromYaml) spec else null)
        loadKits(spec?.kits.orEmpty())
        loadExtraMounts(spec?.extraMounts.orEmpty())
    }

    private fun loadPortIntoUi(yaml: SbxLaunchSpec?) {
        val pinned = yaml?.hostPort?.takeIf { it in 1..65535 }
        if (yaml != null) {
            if (pinned != null) {
                fixedPortRadioButton.isSelected = true
                fixedPortField.text = pinned.toString()
            } else {
                autoPortRadioButton.isSelected = true
            }
            return
        }
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        when (settings.portModeValue()) {
            OpenCodePortMode.AUTO -> autoPortRadioButton.isSelected = true
            OpenCodePortMode.FIXED -> fixedPortRadioButton.isSelected = true
        }
        fixedPortField.text = OpenCodeSettingsState.sanitizePort(settings.fixedPort).toString()
    }

    private fun loadKits(kits: List<String>) {
        if (kitTable.isEditing) kitTable.cellEditor?.cancelCellEditing()
        kitTableModel.items = kits.map { KitRow(it) }
    }

    private fun createNetworkKitTemplate() {
        val directory = effectiveDirectory() ?: run {
            Messages.showWarningDialog(
                panel ?: createNetworkKitButton,
                "Set an OpenCode project directory first.",
                "OpenCode Project Directory Not Found",
            )
            return
        }
        val canonical = OpenCodeServerProtocol.canonicalOpenCodeDirectory(directory) ?: directory
        val kitDir = java.nio.file.Path.of(canonical, SbxCli.PROJECT_CONTROL_DIR, SbxCli.NETWORK_KIT_DIR)
        val specPath = kitDir.resolve("spec.yaml")
        if (java.nio.file.Files.isRegularFile(specPath)) {
            val overwrite = MessageDialogBuilder.yesNo(
                "Replace extra-network kit template",
                "A kit already exists at ${SbxCli.posixPath(specPath.toString())}. Replace it with a fresh commented template?",
            )
                .yesText("Replace")
                .noText("Keep existing")
                .ask(panel)
            if (!overwrite) {
                addNetworkKitRefIfMissing()
                return
            }
        }
        runCatching {
            java.nio.file.Files.createDirectories(kitDir)
            java.nio.file.Files.writeString(specPath, SbxCli.networkKitTemplateYaml())
        }.onFailure { error ->
            Messages.showErrorDialog(
                panel ?: createNetworkKitButton,
                error.message ?: error::class.java.simpleName,
                "Could Not Write Kit Template",
            )
            return
        }
        addNetworkKitRefIfMissing()
        Messages.showInfoMessage(
            panel ?: createNetworkKitButton,
            "Wrote ${SbxCli.posixPath(specPath.toString())}. Configure it before Apply. A new kit is appended without deleting sessions. Editing an installed kit requires Reset Sandbox, which deletes VM-only data; Restart alone does not reload it.",
            "Extra-Network Kit Template",
        )
    }

    private fun addNetworkKitRefIfMissing() {
        val ref = SbxCli.networkKitRef()
        if (kitTable.isEditing) kitTable.cellEditor?.stopCellEditing()
        if (kitTableModel.items.none { SbxCli.posixPath(it.ref) == ref }) {
            kitTableModel.addRow(KitRow(ref))
        }
    }

    private fun commitTableEditors() {
        extraMountTable.cellEditor?.stopCellEditing()
        kitTable.cellEditor?.stopCellEditing()
    }

    private fun extraMountRows(): List<ExtraMountRow> {
        return rowsWithLiveEditor(extraMountTable, extraMountTableModel.items) { row, column, value ->
            ExtraMountRow(
                hostPath = if (column == 0) value else row.hostPath,
                sandboxPath = if (column == 1) value else row.sandboxPath,
            )
        }
    }

    private fun kitRows(): List<KitRow> {
        return rowsWithLiveEditor(kitTable, kitTableModel.items) { row, _, value -> KitRow(value) }
    }

    private fun <T> rowsWithLiveEditor(
        table: TableView<T>,
        items: List<T>,
        replace: (T, Int, String) -> T,
    ): List<T> {
        if (!table.isEditing) return items
        val row = table.editingRow
        val column = table.editingColumn
        if (row !in items.indices || column < 0) return items
        val value = table.cellEditor?.cellEditorValue as? String ?: return items
        return items.toMutableList().also { it[row] = replace(items[row], column, value) }
    }

    private fun loadExtraMounts(mounts: List<SbxExtraMount>) {
        if (extraMountTable.isEditing) extraMountTable.cellEditor?.cancelCellEditing()
        extraMountTableModel.items = mounts.map { ExtraMountRow(it.hostPath, it.sandboxPath) }
    }

    private fun updateProjectDirectoryControls() {
        val customSelected = customProjectDirectoryRadioButton.isSelected
        projectDirectoryField.textField.isEnabled = customSelected
        detectProjectDirectoryButton.isEnabled = customSelected
    }

    private fun updateSandboxControls() {
        val sandbox = sbxRuntimeRadioButton.isSelected
        sandboxOnlyPanel.isVisible = sandbox
        val sandboxBackend = !OpenCodeServerBackend.isNative(projectBackend().backendId)
        val directory = OpenCodeProjectSettingsState.getInstance(project).effectiveProjectDirectory(project.basePath)
        val owned = directory != null &&
            de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore.getInstance().recordFor(directory) != null
        val state = projectBackend().getLifecycleState()
        upgradeOpenCodeButton.isVisible = sandbox
        upgradeOpenCodeButton.isEnabled = sandboxBackend && owned &&
            state != OpenCodeServerLifecycleState.STARTING &&
            state != OpenCodeServerLifecycleState.RESTARTING
        val spec = currentSpec()
        val record = directory?.let { de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore.getInstance().recordFor(it) }
        val pending = if (sandbox && spec != null) {
            de.moritzf.opencodewebpanel.server.SbxInstalledVmStatus.hint(
                de.moritzf.opencodewebpanel.server.SbxInstalledVmStatus.settings(spec, record),
                adopted = record?.adopted == true,
            )
        } else {
            null
        }
        installedHintLabel.text = pending.orEmpty()
        installedHintLabel.isVisible = !pending.isNullOrBlank()
        setupChecklistLabel.text = "<html>" +
            de.moritzf.opencodewebpanel.server.SbxSetupChecklist.format(
                de.moritzf.opencodewebpanel.server.SbxSetupChecklist.projectSteps(
                    useSandbox = sandbox,
                    owned = owned,
                    running = state == OpenCodeServerLifecycleState.RUNNING,
                ),
            ).replace("\n", "<br>") + "</html>"
        setupChecklistLabel.isVisible = sandbox
        updatePortControls()
    }

    private fun updatePortControls() {
        fixedPortField.isEnabled = fixedPortRadioButton.isSelected
    }

    private fun selectedPortMode(): OpenCodePortMode {
        return if (fixedPortRadioButton.isSelected) OpenCodePortMode.FIXED else OpenCodePortMode.AUTO
    }

    private fun fixedPortOrDefault(): Int {
        return OpenCodeSettingsState.sanitizePort(
            fixedPortField.text.trim().toIntOrNull() ?: OpenCodeSettingsState.DEFAULT_FIXED_PORT,
        )
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
        onDirectoryTargetChanged()
    }

    private fun onDirectoryTargetChanged() {
        if (hydrating) return
        val next = effectiveDirectory()
        if (next == loadedSpecDirectory) return
        val inspection = SbxLaunchSpec.inspect(next)
        val destination = (inspection as? de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection.Valid)?.spec
            ?: next?.let {
                SbxLaunchSpec.fromSettings(
                    OpenCodeSettingsState.getInstance(),
                    it,
                    hostPort = OpenCodeProjectSettingsState.getInstance(project).hostPortOrNull(),
                )
            }
        // Project YAML is the source of truth. A destination with a spec always hydrates the
        // form; a destination without one gets app defaults. Do not keep the previous form.
        if (destination != null) {
            loadSpecIntoUi(
                destination,
                fromYaml = inspection is de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection.Valid,
            )
        }
        loadedSpecDirectory = next
        updateSandboxControls()
    }

    companion object {
        const val PROJECT_SETTINGS_DISPLAY_NAME = "OpenCode Web Panel (Project)"
        private const val SBX_KIT_DOCS_URL = "https://docs.docker.com/ai/sandboxes/customize/kits/"

        fun sandboxSessionRetentionSummary(canonicalDirectory: String): String {
            val record = de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore.getInstance().recordFor(canonicalDirectory)
                ?: return "There is no plugin-owned sandbox yet."
            val persistHost = SbxCli.sandboxPersistDataHome(record.name)
            return if (SbxCli.recordHasPersistMount(record, persistHost)) {
                "Conversation history is on the host persist mount and should survive recreation."
            } else {
                "Conversation history in this VM will be dropped."
            }
        }
    }

    private class ExtraMountRow(
        var hostPath: String = "",
        var sandboxPath: String = "",
    )

    private class KitRow(
        var ref: String = "",
    )

    private class BrowsePathCellEditor(
        descriptor: com.intellij.openapi.fileChooser.FileChooserDescriptor,
    ) : AbstractCellEditor(), TableCellEditor {
        private val field = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(null, descriptor)
        }

        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int,
        ): Component {
            field.text = value as? String ?: ""
            return field
        }

        override fun getCellEditorValue(): Any = SbxCli.posixPath(field.text)
    }

    private fun <T> addTableRow(table: TableView<T>, model: ListTableModel<T>, item: T) {
        model.addRow(item)
        val row = model.rowCount - 1
        table.selectionModel.setSelectionInterval(row, row)
        table.editCellAt(row, 0)
    }
}
