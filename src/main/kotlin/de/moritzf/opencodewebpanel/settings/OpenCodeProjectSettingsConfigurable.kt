package de.moritzf.opencodewebpanel.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import de.moritzf.opencodewebpanel.configuration.OpenCodePortMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectDirectoryMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.server.OpenCodeExecutableProbe
import de.moritzf.opencodewebpanel.server.OpenCodeExecutableProbes
import de.moritzf.opencodewebpanel.server.OpenCodeProjectRuntime
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxExposure
import de.moritzf.opencodewebpanel.server.SbxExtraMount
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection
import de.moritzf.opencodewebpanel.server.SbxOpenCodeImages
import de.moritzf.opencodewebpanel.server.SbxOpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.SbxOpenCodeVersion
import de.moritzf.opencodewebpanel.server.SbxProcessRunner
import de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore
import de.moritzf.opencodewebpanel.server.formatOpenCodeServerLifecycleStatusText
import de.moritzf.opencodewebpanel.server.formatOpenCodeServerStatusDetail
import de.moritzf.opencodewebpanel.ui.OpenCodeServerLogDialog
import de.moritzf.opencodewebpanel.ui.confirmOpenCodeSandboxBinaryUpgrade
import de.moritzf.opencodewebpanel.ui.confirmOpenCodeSandboxReset
import de.moritzf.opencodewebpanel.ui.confirmOpenCodeServerRestart
import de.moritzf.opencodewebpanel.ui.sandboxSessionRetentionSummary
import java.awt.event.HierarchyEvent
import java.awt.event.ItemEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.text.DefaultFormatter

class OpenCodeProjectSettingsConfigurable(private val project: Project) : Configurable {
    private var panel: JComponent? = null
    private var controlListenersInstalled = false
    private var hydrating = false
    private var lifecycleConnection: MessageBusConnection? = null
    private var loadedSpecDirectory: String? = null
    private var setupCheckSequence = 0L
    private var setupCheckRunning = false
    private var directoryReloadQueued = false
    private var prefilledDirectory: String? = null
    private val serverStatusLabel =
        JBLabel().apply { toolTipText = "OpenCode server status for this project" }
    // Copyable: the server URL and the conversation store path are worth pasting elsewhere.
    private val diagnosticsLabel =
        JBLabel().apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            setCopyable(true)
        }
    private val installedHintLabel =
        JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private val setupChecklistLabel =
        JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private val checkSetupButton =
        JButton("Check Sandbox Setup").apply {
            toolTipText =
                "Check sandbox setup and guest network access using saved project settings"
        }
    private val setupCheckResultLabel =
        JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private val specStatusLabel =
        JBLabel().apply {
            foreground = com.intellij.util.ui.UIUtil.getErrorForeground()
            isVisible = false
        }
    private val restartServerButton =
        JButton("Restart Server", AllIcons.Actions.Restart).apply {
            toolTipText = "Restart OpenCode for this project"
            accessibleContext.accessibleName = "Restart OpenCode server"
        }
    private val viewServerLogButton =
        JButton("View Server Log", AllIcons.Actions.Show).apply {
            toolTipText = "Show this project's OpenCode server output"
            accessibleContext.accessibleName = "View OpenCode server log"
        }
    private val upgradeOpenCodeButton =
        JButton("Upgrade OpenCode", AllIcons.Actions.Lightning).apply {
            accessibleContext.accessibleName = "Upgrade OpenCode in sandbox"
            isVisible = false
        }
    private val resetSandboxButton =
        JButton("Reset Sandbox…", AllIcons.Actions.GC).apply {
            accessibleContext.accessibleName = "Reset Docker Sandbox"
            isVisible = false
        }
    private val actionsHintLabel =
        JBLabel("Apply first: these actions use the saved settings, not unapplied changes.").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            isVisible = false
        }
    private val sbxPrerequisitesLabel =
        JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private val sbxPrerequisitesLink =
        ActionLink("Docker Sandbox settings") { openApplicationSettings() }
    private var prerequisitesGeneration = 0L
    private var prerequisitesRequested = false
    /** Last [isModified] answer; Reset Sandbox must not run on settings that are not saved. */
    private var formModified = false
    private val autoPortRadioButton = JBRadioButton("Auto select")
    private val fixedPortRadioButton = JBRadioButton("Fixed port")
    private val fixedPortField =
        JBTextField().apply {
            columns = 6
            toolTipText = "Loopback port for this project's OpenCode server"
            emptyText.text = OpenCodeSettingsState.DEFAULT_FIXED_PORT.toString()
        }
    // A DSL comment wraps like the other descriptions; its text follows the runtime choice.
    private var portHint: JEditorPane? = null
    private val portControlsPanel = panel {
        buttonsGroup("Server port:") {
            row { cell(autoPortRadioButton) }
            row {
                cell(fixedPortRadioButton).gap(RightGap.SMALL)
                cell(fixedPortField)
            }
        }
        row { portHint = comment("").component }
    }
    private val autoProjectDirectoryRadioButton = JBRadioButton("Auto detect")
    private val customProjectDirectoryRadioButton = JBRadioButton("Custom directory")
    private val projectDirectoryField =
        TextFieldWithBrowseButton().apply {
            textField.columns = 40
            toolTipText = "Directory OpenCode should open for this IDE project"
            // The chooser stays usable in auto mode as a way to fill the path; only typing is
            // custom-only. Never disable the whole component: disabling hides the browse button
            // completely and it is not reliably shown again after re-enabling.
            addBrowseFolderListener(
                null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
            )
            addActionListener { customProjectDirectoryRadioButton.isSelected = true }
        }
    private val hostRuntimeRadioButton = JBRadioButton("Host CLI")
    private val sbxRuntimeRadioButton = JBRadioButton("Docker Sandbox")
    private val sbxOpenCodeV1RadioButton = JBRadioButton("1.x")
    private val sbxOpenCodeV2RadioButton = JBRadioButton("2.x")

    // Rarely changed: latest by default, Docker Hub's current 2.x tags load in the background.
    private val sbxOpenCodeImageModel = CollectionComboBoxModel(mutableListOf(SbxCli.LATEST_IMAGE))
    private val sbxOpenCodeImageComboBox =
        ComboBox(sbxOpenCodeImageModel).apply {
            isEditable = true
            prototypeDisplayValue = "sha256:0123456789abcdef0123456789"
            toolTipText =
                "Docker's OpenCode 2 image for new sandboxes: latest, a tag such as 2.2.7, or a sha256: digest"
            accessibleContext.accessibleName = "OpenCode 2 Docker image"
        }
    private val sbxOpenCodeImageHint =
        JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var sbxOpenCodeImageRow: Row? = null
    private var openCodeImageTagsRequested = false

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
        ButtonGroup().apply {
            add(sbxOpenCodeV1RadioButton)
            add(sbxOpenCodeV2RadioButton)
        }
    }

    private val sbxMemoryField =
        JBTextField().apply {
            columns = 6
            toolTipText = "Sandbox memory at create time (for example 4g)"
            accessibleContext.accessibleName = "Sandbox memory"
            // Marked while typing, like the port; Apply still rejects it with the reason.
            document.addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        val invalid = SbxCli.parseMemory(text) == null
                        putClientProperty("JComponent.outline", if (invalid) "error" else null)
                    }
                },
            )
        }
    // 1..32 like `SbxCli.parseCpus`: the spinner cannot hold an invalid count.
    private val sbxCpusField =
        JBIntSpinner(SbxCli.DEFAULT_CPUS.toInt(), 1, 32).apply {
            toolTipText = "Sandbox CPUs at create time, 1 to 32"
            accessibleContext.accessibleName = "Sandbox CPUs"
            // Typed digits count at once, so Apply sees them without leaving the field.
            ((editor as? JSpinner.DefaultEditor)?.textField?.formatter as? DefaultFormatter)
                ?.commitsOnValidEdit = true
        }

    private fun cpusText(): String = sbxCpusField.number.toString()

    private val sbxWorkingDirectoryField =
        JBTextField().apply {
            columns = 24
            toolTipText = "OpenCode working directory relative to the mounted repository root"
            accessibleContext.accessibleName = "Sandbox OpenCode working directory"
        }
    private val sbxShareHostConfigCheckBox =
        JBCheckBox("Share your OpenCode config with the sandbox (read-only)")
    private val sbxProtectSandboxFilesCheckBox =
        JBCheckBox("Protect sandbox files: opencode-sbx/ and local kits are read-only inside")
    private val sbxPersistSandboxSessionsCheckBox =
        JBCheckBox("Persist sandbox sessions and sign-ins across Reset Sandbox")
    private val sbxEnableIntellijMcpCheckBox =
        JBCheckBox("Let OpenCode in the sandbox use IntelliJ's MCP server")
    /** Tests replace these: they cannot show a chooser or a dialog. */
    internal var chooseFiles: (FileChooserDescriptor, Path?) -> List<Path> = ::chooseWithIde
    internal var editKitSpec: (Path, String?) -> Unit = { spec, template ->
        panel?.let {
            SbxKitSpecDialog(project, it, spec, portablePath(spec).removePrefix("./"), template)
                .show()
        }
    }
    private val hostPathEditor =
        SbxBrowsePathCellEditor("~/folder, /path or \${NAME:-fallback}/path") { current ->
            chooseHostPaths(current, multiple = false).firstOrNull()
        }
    private val kitPathEditor =
        SbxBrowsePathCellEditor("docker.io/org/kit:tag, git+https://… or a local kit") { current ->
            chooseKit(current)
        }
    private val extraMountTableModel =
        ListTableModel<ExtraMountRow>(
            object : ColumnInfo<ExtraMountRow, String>("Host Path") {
                override fun valueOf(item: ExtraMountRow): String = item.hostPath

                override fun isCellEditable(item: ExtraMountRow): Boolean = true

                override fun setValue(item: ExtraMountRow, value: String?) {
                    // A sandbox path that was the host path keeps following it.
                    if (item.sandboxPath == item.hostPath) item.sandboxPath = ""
                    item.hostPath = SbxCli.posixPath(value.orEmpty())
                }

                override fun getEditor(item: ExtraMountRow): TableCellEditor = hostPathEditor

                override fun getRenderer(item: ExtraMountRow): TableCellRenderer = hostPathRenderer
            },
            object : ColumnInfo<ExtraMountRow, String>("Sandbox Path") {
                // Shown empty while it is the host path, so only a real choice reads as one.
                override fun valueOf(item: ExtraMountRow): String =
                    item.sandboxPath.takeUnless { it == item.hostPath }.orEmpty()

                override fun isCellEditable(item: ExtraMountRow): Boolean = true

                override fun setValue(item: ExtraMountRow, value: String?) {
                    item.sandboxPath = SbxCli.posixPath(value.orEmpty())
                }

                override fun getRenderer(item: ExtraMountRow): TableCellRenderer =
                    sandboxPathRenderer
            },
            object : ColumnInfo<ExtraMountRow, Boolean>("Read-only") {
                override fun valueOf(item: ExtraMountRow): Boolean = item.readOnly

                override fun isCellEditable(item: ExtraMountRow): Boolean = true

                override fun getColumnClass(): Class<*> = Boolean::class.javaObjectType

                override fun setValue(item: ExtraMountRow, value: Boolean?) {
                    item.readOnly = value == true
                }
            },
        )
    /** Marks host paths a start would skip, so a typo is visible before the sandbox starts. */
    private val hostPathChecks = SbxPathChecks { text, workspace ->
        val problem = hostPathProblem(text, workspace)
        SbxPathState(problem, file = problem == null && hostPathIsFile(text, workspace))
    }
    private val hostPathRenderer =
        SbxPathCellRenderer(
            state = { text, table -> hostPathChecks.state(text, workspaceDirectory(), table) },
            icon = { _, state ->
                if (state?.file == true) AllIcons.FileTypes.Any_type else AllIcons.Nodes.Folder
            },
        )
    private val sandboxPathRenderer =
        SbxPathCellRenderer(
            state = { _, _ -> null },
            icon = { _, _ -> null },
            placeholder = "Same as host path",
        )
    /** Marks local kits sbx could not load: a missing folder, or one without a descriptor. */
    private val kitChecks = SbxPathChecks { text, workspace ->
        SbxPathState(SbxCli.localKitProblem(text, workspace))
    }
    private val kitRenderer =
        SbxPathCellRenderer(
            state = { text, table -> kitChecks.state(text, workspaceDirectory(), table) },
            icon = { text, _ ->
                when (SbxCli.kitSource(text)) {
                    SbxCli.KitSource.LOCAL ->
                        if (text.endsWith(".zip", ignoreCase = true)) AllIcons.FileTypes.Archive
                        else AllIcons.Nodes.Folder
                    SbxCli.KitSource.GIT -> AllIcons.Vcs.Branch
                    SbxCli.KitSource.REGISTRY -> AllIcons.Nodes.PpLib
                }
            },
        )

    private fun hostPathIsFile(raw: String, workspace: String): Boolean = runCatching {
        val text = raw.trim().removeSuffix(":ro").removeSuffix(":RO")
        val host =
            SbxCli.resolveExtraMounts(listOf(SbxExtraMount(text, "", false)), workspace)
                .single()
                .hostPath
        Files.isRegularFile(Path.of(host))
    }
        .getOrDefault(false)

    private fun hostPathProblem(raw: String?, workspace: String): String? {
        val text = raw?.trim().orEmpty().removeSuffix(":ro").removeSuffix(":RO")
        if (text.isEmpty()) return null
        val host = runCatching {
            SbxCli.resolveExtraMounts(listOf(SbxExtraMount(text, "", false)), workspace)
                .single()
                .hostPath
        }
            .getOrElse {
                return it.message ?: "Cannot resolve this host path."
            }
        val exists = runCatching { Files.exists(Path.of(host)) }.getOrDefault(false)
        return if (exists) null else "Not on this computer: $host. A start skips this mount."
    }

    private val extraMountTable =
        TableView<ExtraMountRow>(extraMountTableModel).apply {
            tableHeader.reorderingAllowed = false
            visibleRowCount = 4
            accessibleContext.accessibleName = "Extra sandbox file and directory mounts"
            toolTipText =
                "<html>Host path: a file or folder; it may start with \${NAME} or \${NAME:-fallback} " +
                    "from the host environment, and ~ is your home.<br>Sandbox path: empty mounts it " +
                    "at the host path; ~ and relative paths are under /home/agent.<br>Paths missing " +
                    "on this computer are skipped.</html>"
            emptyText.text = "No extra mounts"
            emptyText.appendSecondaryText(
                "Choose a file or folder…",
                SimpleTextAttributes.LINK_ATTRIBUTES,
            ) {
                addMountsFromChooser()
            }
        }
    private val extraMountPanel =
        ToolbarDecorator.createDecorator(extraMountTable)
            .setAddActionName("Add Mount")
            .setAddAction { button ->
                showAddPopup(
                    button.preferredPopupPoint,
                    addAction("File or Folder…", AllIcons.Nodes.Folder) { addMountsFromChooser() },
                    addAction("Type a Path, ~ or \${VARIABLE}", AllIcons.Actions.Edit) {
                        addTableRow(extraMountTable, extraMountTableModel, ExtraMountRow())
                    },
                )
            }
            .setRemoveActionUpdater { extraMountTable.selectedObject != null }
            .setRemoveAction {
                extraMountTable.selectedObject?.let {
                    extraMountTableModel.removeRow(extraMountTableModel.indexOf(it))
                }
            }
            .disableUpDownActions()
            .createPanel()
            // Toolbar, header and five rows.
            .apply { preferredSize = JBUI.size(480, 180) }
    private val kitTableModel =
        ListTableModel<KitRow>(
            object : ColumnInfo<KitRow, String>("Kit") {
                override fun valueOf(item: KitRow): String = item.ref

                override fun isCellEditable(item: KitRow): Boolean = true

                override fun setValue(item: KitRow, value: String?) {
                    item.ref = SbxCli.posixPath(value.orEmpty())
                }

                override fun getEditor(item: KitRow): TableCellEditor = kitPathEditor

                override fun getRenderer(item: KitRow): TableCellRenderer = kitRenderer
            },
        )
    private val kitTable =
        TableView<KitRow>(kitTableModel).apply {
            // One column: the "Kits:" label above already names it.
            tableHeader = null
            visibleRowCount = 4
            accessibleContext.accessibleName = "Sandbox kits"
            toolTipText =
                "A local kit folder or .zip file, a registry reference (for example " +
                    "docker.io/sbx/playwright-kit:latest) or a Git URL (git+https://…)"
            emptyText.text = "No kits"
            emptyText.appendSecondaryText(
                "Choose a kit folder or .zip…",
                SimpleTextAttributes.LINK_ATTRIBUTES,
            ) {
                addKitFromChooser()
            }
        }
    private val editKitSpecAction =
        object :
            DumbAwareAction(
                "Edit spec.yaml",
                "Edit the selected local kit's descriptor",
                AllIcons.Actions.Edit,
            ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                // No disk access here: toolbars update often. A missing file is reported on click.
                val ref = kitTable.selectedObject?.ref.orEmpty()
                event.presentation.isEnabled =
                    SbxCli.isLocalKitRef(ref) && !ref.endsWith(".zip", ignoreCase = true)
            }

            override fun actionPerformed(event: AnActionEvent) = editSelectedKitSpec()
        }
    private val kitPanel =
        ToolbarDecorator.createDecorator(kitTable)
            .setAddActionName("Add Kit")
            .setAddAction { button ->
                showAddPopup(
                    button.preferredPopupPoint,
                    addAction("Kit Folder or ZIP File…", AllIcons.Nodes.Folder) {
                        addKitFromChooser()
                    },
                    addAction("Registry or Git Reference", AllIcons.Nodes.PpLib) {
                        addTableRow(kitTable, kitTableModel, KitRow())
                    },
                    Separator.getInstance(),
                    addAction("Extra-Network Kit for This Project…", AllIcons.General.Web) {
                        addNetworkKit()
                    },
                )
            }
            .setRemoveActionUpdater { kitTable.selectedObject != null }
            .setRemoveAction {
                kitTable.selectedObject?.let { kitTableModel.removeRow(kitTableModel.indexOf(it)) }
            }
            .addExtraAction(editKitSpecAction)
            .disableUpDownActions()
            .createPanel()
            // Toolbar and five rows.
            .apply { preferredSize = JBUI.size(480, 154) }
    private var resourcesGroup: CollapsibleRow? = null
    private val sandboxOnlyPanel = panel {
        group("Docker Sandbox") {
            buttonsGroup("OpenCode version:") {
                row {
                    cell(sbxOpenCodeV1RadioButton)
                        .comment(
                            "Docker's OpenCode 1.x kit (${SbxCli.V1_KIT}). Upgrade OpenCode updates it inside the sandbox.",
                        )
                }
                row {
                    cell(sbxOpenCodeV2RadioButton)
                        .comment(
                            "Docker's OpenCode 2 kit. Choose it before the first start when sharing an OpenCode 2 config.",
                        )
                }
                indent {
                    sbxOpenCodeImageRow =
                        row("Docker image:") {
                                cell(sbxOpenCodeImageComboBox).gap(RightGap.SMALL)
                                cell(sbxOpenCodeImageHint)
                            }
                            .rowComment(
                                "Keep latest. Pin a tag or sha256: digest only while a new Docker image is broken; Docker keeps recent tags only. " +
                                    "<a href=\"$OPENCODE_IMAGE_TAGS_PAGE\">Tags on Docker Hub</a>",
                                action =
                                    HyperlinkEventAction { event ->
                                        val href = event.url?.toString() ?: event.description
                                        if (!href.isNullOrBlank()) BrowserUtil.browse(href)
                                    },
                            )
                }
                row {
                    comment("Switching the version or image needs a new sandbox; Apply asks first.")
                }
            }
            row("Working directory:") {
                cell(sbxWorkingDirectoryField)
                    .comment(
                        "Where OpenCode runs inside the project, e.g. ./app in a monorepo (default ./). Restarts OpenCode; the sandbox stays.",
                    )
            }
            row {
                cell(sbxShareHostConfigCheckBox)
                    .comment(
                        "Settings, skills, agents, commands and plugins from your OpenCode config folder. Credentials written into those files are readable in the sandbox; auth.json is never shared, so sign in inside the sandbox or use sbx secret. Needs a new sandbox; Apply asks first.",
                    )
            }
            row {
                cell(sbxProtectSandboxFilesCheckBox)
                    .comment(
                        "So OpenCode cannot change its own launch settings, launcher or network kit. Takes effect at the next Reset Sandbox.",
                    )
            }
            row {
                cell(sbxPersistSandboxSessionsCheckBox)
                    .comment(
                        "Stored on this computer in a plugin folder, separate from the Host CLI's conversations. Takes effect at the next Reset Sandbox.",
                    )
            }
            row {
                cell(sbxEnableIntellijMcpCheckBox)
                    .comment(
                        "Restarts OpenCode. The standalone launcher (opencode-sbx.sh) runs without it.",
                    )
            }
            resourcesGroup =
                collapsibleGroup("Resources, Mounts and Kits") {
                    row("Memory:") {
                        cell(sbxMemoryField)
                            .comment(
                                "e.g. 4g or 512m (default ${SbxCli.DEFAULT_MEMORY}). Takes effect at the next Reset Sandbox.",
                            )
                    }
                    row("CPUs:") {
                        cell(sbxCpusField)
                            .comment(
                                "1 to 32 (default ${SbxCli.DEFAULT_CPUS}). Takes effect at the next Reset Sandbox.",
                            )
                    }
                    row {
                        cell(extraMountPanel)
                            .label("Mounts:", LabelPosition.TOP)
                            .align(AlignX.FILL)
                            .comment(
                                "Host files or folders inside the sandbox. Adding, removing or changing Read-only needs a new sandbox; a new sandbox path applies on restart.",
                            )
                    }
                    row {
                        cell(kitPanel)
                            .label("Kits:", LabelPosition.TOP)
                            .align(AlignX.FILL)
                            .comment(
                                "Mixin kits with schemaVersion \"3\": tools, files, environment and network access for the sandbox. " +
                                    "A new kit at the end is added to a running sandbox unless it asks for new permissions; removing, reordering or editing an installed kit needs a new sandbox. " +
                                    "<a href=\"$SBX_KIT_DOCS_URL\">Docker kit docs</a>",
                                action =
                                    HyperlinkEventAction { event ->
                                        val href = event.url?.toString() ?: event.description
                                        if (!href.isNullOrBlank()) BrowserUtil.browse(href)
                                    },
                            )
                    }
                }
            row { cell(setupChecklistLabel) }
            row {
                cell(checkSetupButton)
                    .comment(
                        "Checks that the running sandbox reaches models.opencode.ai, using the saved settings; does not test provider sign-in.",
                    )
            }
            row { cell(setupCheckResultLabel) }
        }
    }

    override fun getDisplayName(): String = PROJECT_SETTINGS_DISPLAY_NAME

    override fun createComponent(): JComponent {
        installControlListenersOnce()
        panel = panel {
            buttonsGroup("OpenCode directory:") {
                row {
                    cell(autoProjectDirectoryRadioButton)
                        .comment("This IDE project's root: ${project.basePath ?: "not available"}")
                }
                row {
                    cell(customProjectDirectoryRadioButton).gap(RightGap.SMALL)
                    cell(projectDirectoryField).resizableColumn().align(AlignX.FILL)
                }
            }
            group("Runtime") {
                row { cell(specStatusLabel) }
                buttonsGroup {
                    row {
                        cell(hostRuntimeRadioButton)
                            .comment("Runs the opencode executable on this computer.")
                    }
                    row {
                        cell(sbxRuntimeRadioButton)
                            .comment(
                                "Runs OpenCode in a Docker Sandbox VM from Docker's OpenCode kits. Needs sbx ${SbxCli.MINIMUM_SBX_VERSION} or later and a Docker account; Apple silicon Mac, Windows 11 with Hyper-V (WHP), or Linux with KVM.",
                            )
                    }
                    indent {
                        row {
                            cell(sbxPrerequisitesLabel).gap(RightGap.SMALL)
                            cell(sbxPrerequisitesLink)
                        }
                    }
                }
                row {
                    comment(
                        "Saved in opencode-sbx/opencode-sbx.yaml with the project; the standalone launcher next to it reads the same file.",
                    )
                }
            }
            row { cell(sandboxOnlyPanel).align(AlignX.FILL) }
            group("Server") {
                row { cell(serverStatusLabel) }
                row { cell(diagnosticsLabel) }
                row {
                    cell(restartServerButton).gap(RightGap.SMALL)
                    cell(viewServerLogButton).gap(RightGap.SMALL)
                    cell(upgradeOpenCodeButton).gap(RightGap.SMALL)
                    cell(resetSandboxButton)
                }
                row { cell(actionsHintLabel) }
                row { cell(installedHintLabel) }
                row { cell(portControlsPanel).align(AlignX.FILL) }
            }
        }
        // Coming back from the Docker Sandbox settings link must not show the old prerequisites.
        panel!!.addHierarchyListener { event ->
            val showing = panel?.isShowing == true
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && showing) {
                prerequisitesRequested = false
                if (sbxRuntimeRadioButton.isSelected) loadSandboxPrerequisites()
            }
        }
        reset()
        subscribeToLifecycleChanges()
        updateServerStatus()
        return panel!!
    }

    /** Settings polls this after every edit, which keeps the "Apply first" hint current. */
    override fun isModified(): Boolean =
        formDiffersFromSavedSettings().also { modified ->
            if (panel != null && modified != formModified) {
                formModified = modified
                actionsHintLabel.isVisible = modified
                updateSandboxControls()
            }
        }

    private fun formDiffersFromSavedSettings(): Boolean {
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        val directoryChanged =
            selectedProjectDirectoryMode() != settings.projectDirectoryModeValue() ||
                projectDirectory() != settings.openCodeProjectDirectory
        if (directoryChanged) return true
        if (
            SbxLaunchSpec.inspect(effectiveDirectory())
                is de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection.Invalid
        ) {
            // Keep Apply reachable so the user can replace the invalid file with the form values.
            return true
        }
        if (sbxRuntimeRadioButton.isSelected && SbxCli.parseMemory(sbxMemoryField.text) == null) {
            return true
        }
        val loaded = loadedOrDefaultSpec() ?: return false
        val current = currentSpec() ?: return true
        // `name` is derived from the directory and has no form field; a hand-written name in
        // the spec must not keep Apply permanently enabled (every apply would rewrite it).
        return current.adoptStoredName(loaded) != loaded
    }

    override fun apply() {
        val nextMode = selectedProjectDirectoryMode()
        val nextDirectory = projectDirectory()
        if (nextMode == OpenCodeProjectDirectoryMode.CUSTOM && nextDirectory.isBlank()) {
            throw ConfigurationException(
                "OpenCode project directory must not be empty when custom mode is selected.",
            )
        }
        if (nextMode == OpenCodeProjectDirectoryMode.CUSTOM && !File(nextDirectory).isDirectory) {
            throw ConfigurationException(
                "OpenCode project directory must be an existing directory.",
            )
        }
        if (selectedPortMode() == OpenCodePortMode.FIXED) {
            val portText = fixedPortField.text.trim()
            // Blank stays lenient for compatibility (falls back to the default port); reject only
            // real garbage.
            if (
                portText.isNotEmpty() &&
                    (portText.toIntOrNull() == null || portText.toInt() !in 1..65535)
            ) {
                throw ConfigurationException("Fixed port must be a number between 1 and 65535.")
            }
        }
        commitTableEditors()
        // Enter in the directory field can apply before focus-lost loaded the new directory's
        // spec. Never write the previous directory's form into another project's YAML: load the
        // destination first; the confirmation below still lists the switch.
        if (!sameDirectory(effectiveDirectory(), loadedSpecDirectory)) {
            onDirectoryTargetChanged()
            // Replacing an invalid spec needs the user to have seen the values that replace it.
            if (SbxLaunchSpec.inspect(effectiveDirectory()) is SbxLaunchSpecInspection.Invalid) {
                throw ConfigurationException(
                    "The new OpenCode directory has an invalid sandbox spec. Review the message " +
                        "under Runtime and the loaded values, then apply again.",
                )
            }
        }
        if (sbxRuntimeRadioButton.isSelected) {
            if (SbxCli.parseMemory(sbxMemoryField.text) == null) {
                resourcesGroup?.expanded = true // the field may sit in the collapsed section
                throw ConfigurationException("Sandbox memory must be a value like 4g or 512m.")
            }
            if (
                sbxOpenCodeV2RadioButton.isSelected &&
                    SbxCli.openCodeImageProblem(selectedOpenCodeImage(), SbxOpenCodeVersion.V2) !=
                        null
            ) {
                throw ConfigurationException(
                    "The Docker image must be latest, a tag of an OpenCode 2 image such as 2.2.7, or a sha256: digest.",
                )
            }
        }
        val targetDirectory = effectiveDirectory()
        when (val inspection = SbxLaunchSpec.inspect(targetDirectory)) {
            is de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection.Invalid -> {
                if (
                    ApplicationManager.getApplication().isUnitTestMode ||
                        !MessageDialogBuilder.yesNo(
                                "Replace Invalid Sandbox Spec",
                                "The file at ${inspection.path} is invalid (${inspection.reason}). Replace it with the current form values?",
                            )
                            .yesText("Replace")
                            .noText("Cancel")
                            .ask(panel)
                ) {
                    throw ConfigurationException(
                        "Invalid sandbox spec at ${inspection.path}: ${inspection.reason}",
                    )
                }
            }
            else -> Unit
        }
        val spec =
            currentSpec()
                ?: throw ConfigurationException("Set an OpenCode project directory first.")
        val applier = OpenCodeProjectSettingsApplier(project)
        val plan =
            applier.prepare(
                OpenCodeProjectSettingsValues(
                    nextMode,
                    nextDirectory,
                    effectiveDirectory(),
                    selectedPortMode(),
                    fixedPortOrDefault(),
                ),
                spec,
                sandboxSessionRetentionSummary(spec.canonicalDirectory),
            )
        val preview = plan.preview
        var exposure = SbxExposure(emptyList(), "")
        if (spec.useSandbox) {
            var failure: Exception? = null
            val completed =
                com.intellij.openapi.progress.ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously(
                        Runnable {
                            try {
                                exposure = SbxExposure.of(spec, spec.canonicalDirectory)
                            } catch (
                                cancelled:
                                    com.intellij.openapi.progress.ProcessCanceledException,) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure = error
                            }
                        },
                        "Checking sandbox access",
                        true,
                        project,
                    )
            if (!completed) throw applyCancelled()
            failure?.let {
                throw ConfigurationException("Could not verify sandbox kit contents: ${it.message}")
            }
        }
        val exposureUnacknowledged =
            !exposure.isEmpty &&
                !SbxSandboxRecordStore.getInstance()
                    .isExposureAcknowledged(spec.canonicalDirectory, exposure.fingerprint)
        val serverState = projectBackend().getLifecycleState()
        if (
            plan.needsConfirmation(serverState, exposureUnacknowledged) &&
                !ApplicationManager.getApplication().isUnitTestMode
        ) {
            val startsStopped =
                !plan.recreate &&
                    plan.requiresStop &&
                    serverState == OpenCodeServerLifecycleState.STOPPED
            val message =
                (if (startsStopped) "OpenCode is stopped; Apply starts it.\n\n" else "") +
                    preview.message() +
                    if (exposureUnacknowledged) {
                        "\n\nThe sandbox gets access beyond the project:\n" +
                            exposure.items.joinToString("\n") { "• $it" }
                    } else {
                        ""
                    }
            val (title, confirmText) =
                when {
                    plan.recreate -> "Recreate Sandbox" to "Recreate Sandbox"
                    startsStopped -> "Start OpenCode" to "Apply and Start"
                    plan.requiresStop && serverState.isInterruptible() ->
                        "Restart OpenCode" to "Apply and Restart"
                    else -> "Allow Sandbox Access" to "Allow and Apply"
                }
            val confirmed =
                MessageDialogBuilder.yesNo(title, message)
                    .yesText(confirmText)
                    .noText("Cancel")
                    .icon(Messages.getWarningIcon())
                    .ask(panel)
            if (!confirmed) throw applyCancelled()
        }
        applier.apply(plan, exposure)
        fixedPortField.text = plan.values.fixedPort.toString()
        updateSandboxControls()
        updateServerStatus()
        showSpecStatus(SbxLaunchSpec.inspect(effectiveDirectory()))
    }

    override fun reset() {
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        hostPathChecks.clear()
        kitChecks.clear()
        hydrating = true
        try {
            when (settings.projectDirectoryModeValue()) {
                OpenCodeProjectDirectoryMode.AUTO ->
                    autoProjectDirectoryRadioButton.isSelected = true
                OpenCodeProjectDirectoryMode.CUSTOM ->
                    customProjectDirectoryRadioButton.isSelected = true
            }
            projectDirectoryField.text = settings.openCodeProjectDirectory
            loadSpecIntoUi(
                loadedOrDefaultSpec(),
                fromYaml = SbxLaunchSpec.load(effectiveDirectory()) != null,
            )
            loadedSpecDirectory = effectiveDirectory()
            showSpecStatus(SbxLaunchSpec.inspect(loadedSpecDirectory))
            setupCheckResultLabel.text = ""
            updateProjectDirectoryControls()
            updateSandboxControls()
            updateServerStatus()
        } finally {
            hydrating = false
        }
    }

    override fun disposeUIResources() {
        setupCheckSequence++
        setupCheckRunning = false
        openCodeImageTagsRequested = false
        prerequisitesRequested = false
        prerequisitesGeneration++
        formModified = false
        panel = null
        lifecycleConnection?.disconnect()
        lifecycleConnection = null
    }

    private fun installControlListenersOnce() {
        if (controlListenersInstalled) return
        controlListenersInstalled = true
        autoProjectDirectoryRadioButton.addItemListener { event ->
            // Back to Auto detect: drop an untouched prefill so the form is unmodified again.
            if (event.stateChange == ItemEvent.SELECTED && !hydrating) {
                if (prefilledDirectory != null && projectDirectory() == prefilledDirectory) {
                    projectDirectoryField.text =
                        OpenCodeProjectSettingsState.getInstance(project).openCodeProjectDirectory
                }
                prefilledDirectory = null
            }
            updateProjectDirectoryControls()
            onDirectoryTargetChanged()
        }
        customProjectDirectoryRadioButton.addItemListener { event ->
            // A custom directory starts from the detected project root.
            if (
                event.stateChange == ItemEvent.SELECTED &&
                    !hydrating &&
                    projectDirectory().isEmpty()
            ) {
                OpenCodeProjectSettingsState.autoDetectedProjectDirectory(project.basePath)?.let {
                    projectDirectoryField.text = it
                    prefilledDirectory = projectDirectory()
                }
            }
            updateProjectDirectoryControls()
            onDirectoryTargetChanged()
        }
        projectDirectoryField.textField.addFocusListener(
            object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent) {
                    onDirectoryTargetChanged()
                }
            },
        )
        // The folder chooser sets the text without focus passing through the field. setText
        // fires remove + insert; load once, after both.
        projectDirectoryField.textField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (hydrating || directoryReloadQueued) return
                    if (projectDirectoryField.textField.isFocusOwner) return
                    directoryReloadQueued = true
                    SwingUtilities.invokeLater {
                        directoryReloadQueued = false
                        if (panel != null) onDirectoryTargetChanged()
                    }
                }
            },
        )
        hostRuntimeRadioButton.addItemListener { updateSandboxControls() }
        sbxRuntimeRadioButton.addItemListener { updateSandboxControls() }
        autoPortRadioButton.addItemListener { updatePortControls() }
        fixedPortRadioButton.addItemListener { updatePortControls() }
        fixedPortField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = updatePortControls()
            },
        )
        restartServerButton.addActionListener { restartThisProjectServer() }
        viewServerLogButton.addActionListener { showThisProjectServerLog() }
        upgradeOpenCodeButton.addActionListener { upgradeOpenCodeInSandbox() }
        resetSandboxButton.addActionListener { resetThisProjectSandbox() }
        // Selecting 2.x deselects 1.x before 2.x reads as selected; update on both events.
        sbxOpenCodeV1RadioButton.addItemListener { updateSandboxControls() }
        sbxOpenCodeV2RadioButton.addItemListener { updateSandboxControls() }
        checkSetupButton.addActionListener { checkSandboxSetup() }
    }

    private fun projectBackend() = OpenCodeServerBackendRegistry.getInstance().backendFor(project)

    private fun subscribeToLifecycleChanges() {
        lifecycleConnection?.disconnect()
        lifecycleConnection =
            ApplicationManager.getApplication().messageBus.connect().also { connection ->
                connection.subscribe(
                    OpenCodeServerLifecycleListener.TOPIC,
                    object : OpenCodeServerLifecycleListener {
                        override fun stateChanged(
                            state: OpenCodeServerLifecycleState,
                            backendId: String,
                        ) {
                            if (backendId != projectBackend().backendId) return
                            ApplicationManager.getApplication()
                                .invokeLater(
                                    { if (panel != null) updateServerStatus() },
                                    ModalityState.stateForComponent(panel ?: serverStatusLabel),
                                )
                        }
                    },
                )
            }
    }

    private fun updateServerStatus() {
        if (buildingSearchIndex()) return
        val backend = projectBackend()
        val state = backend.getLifecycleState()
        val detail =
            formatOpenCodeServerStatusDetail(
                state,
                backend.getServerUrl(),
                backend.getServerVersion(),
                backend.backendId,
                backend.getWireProtocol(),
            )
        serverStatusLabel.text = formatOpenCodeServerLifecycleStatusText(state, detail)
        val sbx = backend as? SbxOpenCodeServerBackend
        diagnosticsLabel.text =
            sbx?.diagnostics()
                ?.rows()
                ?.let { rows ->
                    "<html><table cellpadding=\"0\" cellspacing=\"0\">" +
                        rows.joinToString("") { (label, value) ->
                            "<tr><td>${StringUtil.escapeXmlEntities(label)}:&nbsp;&nbsp;</td>" +
                                "<td>${StringUtil.escapeXmlEntities(value)}</td></tr>"
                        } +
                        "</table></html>"
                }
                .orEmpty()
        diagnosticsLabel.isVisible = sbx != null
        val starting =
            state == OpenCodeServerLifecycleState.STARTING ||
                state == OpenCodeServerLifecycleState.RESTARTING
        restartServerButton.isEnabled = !starting
        // Output of a start arrives after its state change; keep the button usable meanwhile.
        val logAvailable = starting || OpenCodeServerLogDialog.hasOutput(backend)
        viewServerLogButton.isEnabled = logAvailable
        viewServerLogButton.toolTipText =
            when {
                logAvailable -> "Show the end of this project's OpenCode server output"
                !OpenCodeSettingsState.getInstance().enableServerLogs ->
                    "Nothing to show. Server logs are off (OpenCode Settings, Server tab); the output of a start shows here while it runs."
                else -> "Nothing to show yet: OpenCode has not written any output."
            }
        val start =
            state == OpenCodeServerLifecycleState.STOPPED ||
                state == OpenCodeServerLifecycleState.FAILED
        restartServerButton.apply {
            text = if (start) "Start Server" else "Restart Server"
            icon = if (start) AllIcons.Actions.Execute else AllIcons.Actions.Restart
            toolTipText =
                if (start) "Start OpenCode for this project"
                else "Restart OpenCode for this project"
            accessibleContext.accessibleName =
                if (start) "Start OpenCode server" else "Restart OpenCode server"
        }
        updateSandboxControls()
    }

    private fun restartThisProjectServer() {
        if (!confirmOpenCodeServerRestart(project)) return
        OpenCodeProjectRuntime.getInstance(project).restart()
    }

    private fun upgradeOpenCodeInSandbox() {
        val backend = projectBackend() as? SbxOpenCodeServerBackend ?: return
        // The backend upgrades according to the saved spec, not unapplied form changes.
        val dockerKit =
            OpenCodeProjectSettingsState.getInstance(project)
                .effectiveProjectDirectory(project.basePath)
                ?.let(SbxLaunchSpec::load)
                ?.openCodeVersion == SbxOpenCodeVersion.V2
        if (!confirmOpenCodeSandboxBinaryUpgrade(project, dockerKit)) return
        backend.upgradeOpenCodeBinary(project)
    }

    private fun resetThisProjectSandbox() {
        if (projectBackend() !is SbxOpenCodeServerBackend) return
        if (!confirmOpenCodeSandboxReset(project)) return
        OpenCodeProjectRuntime.getInstance(project).resetSandbox()
    }

    /** Settings lives in the dialog's data context; outside it, open a dialog for the page. */
    private fun openApplicationSettings() {
        val component = panel ?: sbxPrerequisitesLink
        val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(component))
        val target = settings?.find(OpenCodeSettingsConfigurable.ID)
        if (settings != null && target != null) {
            settings
                .find(OpenCodeSettingsConfigurable::class.java)
                ?.selectTab(OpenCodeSettingsTab.DOCKER_SANDBOXES)
            settings.select(target)
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(
                project,
                OpenCodeSettingsConfigurable::class.java,
            ) {
                it.selectTab(OpenCodeSettingsTab.DOCKER_SANDBOXES)
            }
        }
    }

    /**
     * What a sandbox start needs from this computer, read off the EDT each time the page is shown:
     * an sbx that is new enough, and a network policy. Shown under the runtime choice before
     * anything is applied. The policy is read live like the start does; the stored flag only
     * answers when sbx cannot.
     */
    private fun loadSandboxPrerequisites() {
        val app = ApplicationManager.getApplication()
        if (prerequisitesRequested || app.isUnitTestMode || app.isHeadlessEnvironment) return
        prerequisitesRequested = true
        val generation = ++prerequisitesGeneration
        val settings = OpenCodeSettingsState.getInstance()
        val executable = settings.sbxExecutablePath()
        // Label-only update: run even while the settings dialog is modal, before it is showing.
        val modality = ModalityState.any()
        sbxPrerequisitesLabel.text = "Checking sbx…"
        app.executeOnPooledThread {
            val probe = runCatching {
                OpenCodeExecutableProbes.sbx(executable)
            }
                .getOrDefault(OpenCodeExecutableProbe(null, null))
            val usableSbx = probe.path?.takeIf { !probe.failed && !probe.tooOld }
            val policy = usableSbx?.let { sbx ->
                val result = runCatching {
                    SbxProcessRunner.run(SbxCli.buildPolicyLsCommand(sbx), emptyMap(), 15_000L)
                }
                    .getOrNull()
                sbxPolicyCheckOf(true, result).status
            }
            app.invokeLater(
                {
                    if (panel == null || generation != prerequisitesGeneration) {
                        return@invokeLater
                    }
                    val policyReady =
                        when (policy) {
                            SbxPolicyStatus.PRESENT -> true
                            SbxPolicyStatus.ABSENT -> false
                            else -> settings.sbxNetworkPolicyConsent
                        }
                    val (text, problem) = sandboxPrerequisitesText(probe, policyReady)
                    sbxPrerequisitesLabel.text = text
                    sbxPrerequisitesLabel.foreground =
                        if (problem) UIUtil.getErrorForeground()
                        else JBUI.CurrentTheme.ContextHelp.FOREGROUND
                },
                modality,
            )
        }
    }

    private fun checkSandboxSetup() {
        val backend = projectBackend() as? SbxOpenCodeServerBackend ?: return
        val sequence = ++setupCheckSequence
        val modality = ModalityState.stateForComponent(checkSetupButton)
        setupCheckRunning = true
        setupCheckResultLabel.text = "Checking sandbox setup and guest network access…"
        updateSandboxControls()
        backend.checkSetup().whenComplete { steps, error ->
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (panel == null || project.isDisposed || sequence != setupCheckSequence)
                            return@invokeLater
                        setupCheckRunning = false
                        val text =
                            if (backend !== projectBackend()) "Sandbox changed; check setup again."
                            else if (error != null) {
                                "Setup check failed. Check the sbx installation and try again."
                            } else {
                                de.moritzf.opencodewebpanel.server.SbxSetupChecklist.format(
                                    steps.orEmpty(),
                                )
                            }
                        setupCheckResultLabel.text =
                            "<html>" +
                                com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text)
                                    .replace("\n", "<br>") +
                                "</html>"
                        updateSandboxControls()
                    },
                    modality,
                )
        }
    }

    private fun showThisProjectServerLog() {
        OpenCodeServerLogDialog.show(panel ?: return, projectBackend())
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
            OpenCodeProjectDirectoryMode.AUTO ->
                OpenCodeProjectSettingsState.autoDetectedProjectDirectory(project.basePath)
            OpenCodeProjectDirectoryMode.CUSTOM ->
                custom.ifBlank {
                    OpenCodeProjectSettingsState.autoDetectedProjectDirectory(project.basePath)
                }
        } ?: settings.effectiveProjectDirectory(project.basePath)
    }

    private fun loadedOrDefaultSpec(): SbxLaunchSpec? {
        val directory = effectiveDirectory() ?: return null
        val canonical = OpenCodeServerProtocol.canonicalOpenCodeDirectory(directory) ?: directory
        return SbxLaunchSpec.load(canonical)
            ?: SbxLaunchSpec.defaults(
                canonical,
                hostPort = OpenCodeProjectSettingsState.getInstance(project).hostPortOrNull(),
            )
    }

    private fun currentSpec(): SbxLaunchSpec? {
        val directory = effectiveDirectory() ?: return null
        val canonical = OpenCodeServerProtocol.canonicalOpenCodeDirectory(directory) ?: directory
        return SbxLaunchSpec(
            canonicalDirectory = canonical,
            workingDirectory = SbxCli.posixPath(sbxWorkingDirectoryField.text).ifBlank { "./" },
            name = SbxCli.sandboxName(canonical),
            memory = SbxCli.sanitizeMemory(sbxMemoryField.text),
            cpus = SbxCli.sanitizeCpus(cpusText()),
            kits = SbxCli.parseLineList(kitRows().joinToString("\n") { it.ref }),
            extraMounts =
                extraMountRows()
                    .filter { it.hostPath.isNotBlank() }
                    .map {
                        // sbx's `path:ro` spelling typed into the host cell means read-only.
                        val suffix =
                            it.hostPath.endsWith(":ro", ignoreCase = true) && it.hostPath.length > 3
                        val host =
                            SbxCli.posixPath(if (suffix) it.hostPath.dropLast(3) else it.hostPath)
                        SbxExtraMount(
                            host,
                            SbxCli.posixPath(it.sandboxPath.ifBlank { host }),
                            it.readOnly || suffix,
                        )
                    }
                    .distinct(),
            shareHostOpencodeConfig = sbxShareHostConfigCheckBox.isSelected,
            openCodeVersion =
                if (sbxOpenCodeV2RadioButton.isSelected) {
                    SbxOpenCodeVersion.V2
                } else {
                    SbxOpenCodeVersion.V1
                },
            openCodeImage = selectedOpenCodeImage().takeIf { sbxOpenCodeV2RadioButton.isSelected },
            enableIntellijMcp = sbxEnableIntellijMcpCheckBox.isSelected,
            protectSandboxFiles = sbxProtectSandboxFilesCheckBox.isSelected,
            persistSandboxSessions = sbxPersistSandboxSessionsCheckBox.isSelected,
            useSandbox = sbxRuntimeRadioButton.isSelected,
            hostPort =
                if (selectedPortMode() == OpenCodePortMode.FIXED) {
                    fixedPortOrDefault()
                } else {
                    null
                },
        )
    }

    /** The typed or chosen image; null follows Docker's latest. */
    private fun selectedOpenCodeImage(): String? =
        SbxCli.normalizeOpenCodeImage(
            (sbxOpenCodeImageComboBox.editor.item ?: sbxOpenCodeImageModel.selectedItem)
                ?.toString(),
        )

    /**
     * Fills the image box with Docker Hub's current 2.x tags once per page, off the EDT, and keeps
     * whatever is typed. The box stays editable when Docker Hub is unreachable.
     */
    private fun loadOpenCodeImageTags() {
        if (openCodeImageTagsRequested || ApplicationManager.getApplication().isUnitTestMode) return
        openCodeImageTagsRequested = true
        sbxOpenCodeImageHint.text = "Loading tags…"
        val modality = ModalityState.stateForComponent(sbxOpenCodeImageComboBox)
        ApplicationManager.getApplication().executeOnPooledThread {
            val tags = SbxOpenCodeImages.fetchTags()
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (panel == null || project.isDisposed) return@invokeLater
                        if (tags == null) {
                            sbxOpenCodeImageHint.text = "Docker Hub unreachable; type a tag."
                            return@invokeLater
                        }
                        val typed = sbxOpenCodeImageComboBox.editor.item
                        sbxOpenCodeImageModel.replaceAll(
                            listOf(SbxCli.LATEST_IMAGE) + SbxOpenCodeImages.releases(tags, 2),
                        )
                        sbxOpenCodeImageModel.selectedItem = typed
                        sbxOpenCodeImageComboBox.editor.item = typed
                        sbxOpenCodeImageHint.text =
                            SbxOpenCodeImages.latestRelease(tags, 2)?.let {
                                "latest is OpenCode $it"
                            } ?: "latest is no OpenCode 2 image right now"
                    },
                    modality,
                )
        }
    }

    private fun loadSpecIntoUi(spec: SbxLaunchSpec?, fromYaml: Boolean = spec != null) {
        val sandbox = spec?.useSandbox ?: false
        if (sandbox) sbxRuntimeRadioButton.isSelected = true
        else hostRuntimeRadioButton.isSelected = true
        sbxMemoryField.text = spec?.memory ?: SbxCli.DEFAULT_MEMORY
        sbxCpusField.value = SbxCli.sanitizeCpus(spec?.cpus).toInt()
        sbxWorkingDirectoryField.text = spec?.workingDirectory ?: "./"
        sbxShareHostConfigCheckBox.isSelected = spec?.shareHostOpencodeConfig ?: false
        if (spec?.openCodeVersion == SbxOpenCodeVersion.V2) {
            sbxOpenCodeV2RadioButton.isSelected = true
        } else {
            sbxOpenCodeV1RadioButton.isSelected = true
        }
        val image = spec?.openCodeImage ?: SbxCli.LATEST_IMAGE
        sbxOpenCodeImageModel.selectedItem = image
        sbxOpenCodeImageComboBox.editor.item = image
        sbxEnableIntellijMcpCheckBox.isSelected = spec?.enableIntellijMcp ?: true
        sbxProtectSandboxFilesCheckBox.isSelected = spec?.protectSandboxFiles ?: true
        sbxPersistSandboxSessionsCheckBox.isSelected = spec?.persistSandboxSessions ?: true
        loadPortIntoUi(if (fromYaml) spec else null)
        loadKits(spec?.kits.orEmpty())
        loadExtraMounts(spec?.extraMounts.orEmpty())
        // Collapsed while everything in it is a default, so a configured sandbox shows its setup.
        resourcesGroup?.expanded =
            spec != null &&
                (spec.kits.isNotEmpty() ||
                    spec.extraMounts.isNotEmpty() ||
                    spec.memory != SbxCli.DEFAULT_MEMORY ||
                    spec.cpus != SbxCli.DEFAULT_CPUS)
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

    /** The spec's project root, spelled like the saved spec spells it. */
    private fun workspaceDirectory(): String? =
        effectiveDirectory()?.let { OpenCodeServerProtocol.canonicalOpenCodeDirectory(it) ?: it }

    private fun chooseWithIde(descriptor: FileChooserDescriptor, startAt: Path?): List<Path> {
        // Looking the start folder up also shows the chooser what was just written to disk.
        val start = startAt?.let {
            runCatching { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                .getOrNull()
        }
        return FileChooser.chooseFiles(descriptor, panel, project, start).mapNotNull {
            runCatching { it.toNioPath() }.getOrNull()
        }
    }

    /** Project-relative inside the project, `~` under home: the spec is shared with the team. */
    private fun portablePath(path: Path): String =
        SbxCli.portableHostPath(
            path.toString(),
            workspaceDirectory(),
            realPath = runCatching { path.toRealPath().toString() }.getOrNull(),
        )

    private fun chooseHostPaths(current: String, multiple: Boolean): List<String> {
        val workspace = workspaceDirectory()
        val start =
            current
                .takeIf { it.isNotBlank() && workspace != null }
                ?.let { text ->
                    runCatching {
                        SbxCli.resolveExtraMounts(
                                listOf(SbxExtraMount(text, "", false)),
                                workspace!!,
                            )
                            .single()
                            .hostPath
                    }
                        .getOrNull()
                }
                ?.let { runCatching { Path.of(it) }.getOrNull() }
        val descriptor =
            FileChooserDescriptor(true, true, true, true, false, multiple)
                .withTitle(if (multiple) "Select Files or Folders to Mount" else "Select Host Path")
                .withDescription("Files and folders the sandbox may use besides the project.")
                .withShowHiddenFiles(true)
                .apply { isForcedToUseIdeaFileChooser = true }
        return chooseFiles(descriptor, start).map(::portablePath)
    }

    private fun addMountsFromChooser() {
        val chosen = chooseHostPaths("", multiple = true)
        if (chosen.isEmpty()) return
        if (extraMountTable.isEditing) extraMountTable.cellEditor?.stopCellEditing()
        hostPathChecks.clear()
        var last = -1
        chosen.forEach { host ->
            val existing = extraMountTableModel.items.indexOfFirst { it.hostPath == host }
            last =
                if (existing >= 0) existing
                else {
                    extraMountTableModel.addRow(ExtraMountRow(hostPath = host))
                    extraMountTableModel.rowCount - 1
                }
        }
        selectRow(extraMountTable, last)
    }

    /** A kit folder, its descriptor (which stands for the folder), or a kit ZIP file. */
    private fun kitChooserDescriptor(): FileChooserDescriptor =
        object : FileChooserDescriptor(true, true, true, true, false, false) {
                override fun validateSelectedFiles(files: Array<VirtualFile>) {
                    files.forEach { file ->
                        val kit = SbxCli.kitPathForChosenFile(file.toNioPath())
                        val usable =
                            if (Files.isDirectory(kit)) SbxCli.localKitSpecFile(kit) != null
                            else SbxCli.isKitArchive(kit)
                        if (!usable) {
                            throw ConfigurationException(
                                "${file.name} is not a kit. Choose a folder that holds " +
                                    "spec.yaml, that spec.yaml, or a kit .zip file.",
                            )
                        }
                    }
                }
            }
            .withTitle("Select Kit")
            .withDescription("A kit folder (the one that holds spec.yaml) or a kit .zip file.")
            .withShowHiddenFiles(true)
            .apply { isForcedToUseIdeaFileChooser = true }

    /** The reference for a chosen kit, or null when the chooser was cancelled. */
    private fun chooseKit(current: String): String? {
        val workspace = workspaceDirectory()
        val start = workspace?.let { root ->
            SbxCli.localKitPath(current, root)?.takeIf { current.isNotBlank() }
                ?: SbxLaunchSpec.projectControlDir(root).takeIf { Files.isDirectory(it) }
                ?: runCatching { Path.of(root) }.getOrNull()
        }
        val chosen = chooseFiles(kitChooserDescriptor(), start).firstOrNull() ?: return null
        return portablePath(SbxCli.kitPathForChosenFile(chosen))
    }

    private fun addKitFromChooser() {
        val ref = chooseKit("") ?: return
        selectOrAddKit(ref)
    }

    private fun selectOrAddKit(ref: String) {
        if (kitTable.isEditing) kitTable.cellEditor?.stopCellEditing()
        kitChecks.clear()
        var row = kitTableModel.items.indexOfFirst { SbxCli.posixPath(it.ref) == ref }
        if (row < 0) {
            kitTableModel.addRow(KitRow(ref))
            row = kitTableModel.rowCount - 1
        }
        selectRow(kitTable, row)
    }

    /**
     * Adds the project's extra-network kit, writing its commented template first when there is
     * none, and opens it for editing: Settings is modal, so this is the moment to choose the hosts
     * before Apply installs the kit.
     */
    private fun addNetworkKit() {
        val workspace =
            workspaceDirectory()
                ?: run {
                    Messages.showWarningDialog(
                        panel ?: kitTable,
                        "Set an OpenCode project directory first.",
                        "OpenCode Project Directory Not Found",
                    )
                    return
                }
        val spec = SbxLaunchSpec.networkKitSpecPath(workspace)
        if (!Files.isRegularFile(spec)) {
            runCatching { SbxLaunchSpec.writeNetworkKitTemplate(workspace) }
                .onFailure { error ->
                    Messages.showErrorDialog(
                        panel ?: kitTable,
                        error.message ?: error::class.java.simpleName,
                        "Could Not Write the Extra-Network Kit",
                    )
                    return
                }
        }
        selectOrAddKit(SbxCli.networkKitRef())
        editKitSpec(spec, SbxCli.networkKitTemplateYaml())
        kitChecks.clear()
    }

    private fun editSelectedKitSpec() {
        val ref = kitTable.selectedObject?.ref ?: return
        val workspace = workspaceDirectory() ?: return
        val kit = SbxCli.localKitPath(ref, workspace) ?: return
        val spec =
            SbxCli.localKitSpecFile(kit)
                ?: run {
                    Messages.showWarningDialog(
                        panel ?: kitTable,
                        SbxCli.localKitProblem(ref, workspace) ?: "This kit has no spec.yaml.",
                        "Cannot Edit Kit",
                    )
                    return
                }
        val network = SbxCli.posixPath(ref) == SbxCli.networkKitRef()
        editKitSpec(spec, if (network) SbxCli.networkKitTemplateYaml() else null)
        kitChecks.clear()
        kitTable.repaint()
    }

    private fun addAction(text: String, icon: javax.swing.Icon, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(event: AnActionEvent) = run()
        }

    private fun showAddPopup(anchor: RelativePoint, vararg actions: AnAction) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                DefaultActionGroup(*actions),
                DataManager.getInstance().getDataContext(anchor.component),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
            .show(anchor)
    }

    private fun selectRow(table: JTable, row: Int) {
        if (row !in 0 until table.rowCount) return
        table.selectionModel.setSelectionInterval(row, row)
        table.scrollRectToVisible(table.getCellRect(row, 0, true))
    }

    private fun commitTableEditors() {
        extraMountTable.cellEditor?.stopCellEditing()
        kitTable.cellEditor?.stopCellEditing()
    }

    private fun extraMountRows(): List<ExtraMountRow> {
        return rowsWithLiveEditor(extraMountTable, extraMountTableModel.items) { row, column, value
            ->
            ExtraMountRow(
                hostPath = if (column == 0) value else row.hostPath,
                sandboxPath = if (column == 1) value else row.sandboxPath,
                readOnly = row.readOnly,
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
        extraMountTableModel.items = mounts.map {
            ExtraMountRow(it.hostPath, it.sandboxPath, it.readOnly)
        }
    }

    private fun updateProjectDirectoryControls() {
        val customSelected = customProjectDirectoryRadioButton.isSelected
        projectDirectoryField.textField.isEnabled = customSelected
    }

    private fun updateSandboxControls() {
        val sandbox = sbxRuntimeRadioButton.isSelected
        sandboxOnlyPanel.isVisible = sandbox
        val sandboxBackend = !OpenCodeServerBackend.isNative(projectBackend().backendId)
        val directory =
            OpenCodeProjectSettingsState.getInstance(project)
                .effectiveProjectDirectory(project.basePath)
        val state = projectBackend().getLifecycleState()
        val owned =
            directory != null &&
                de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore.getInstance()
                    .recordFor(directory) != null
        upgradeOpenCodeButton.isVisible = sandbox
        resetSandboxButton.isVisible = sandbox
        sbxPrerequisitesLabel.isVisible = sandbox
        sbxPrerequisitesLink.isVisible = sandbox
        if (sandbox) loadSandboxPrerequisites()
        val pinnable = sandbox && sbxOpenCodeV2RadioButton.isSelected
        sbxOpenCodeImageRow?.visible(pinnable)
        if (pinnable) loadOpenCodeImageTags()
        val busy =
            state == OpenCodeServerLifecycleState.STARTING ||
                state == OpenCodeServerLifecycleState.RESTARTING
        // Say why a sandbox action is unavailable instead of only greying it out.
        val vmBlocker =
            when {
                !sandboxBackend -> "Apply first: this project still runs the Host CLI."
                !owned -> "No sandbox yet. It is created when OpenCode starts the first time."
                busy -> "Wait until OpenCode has started."
                else -> null
            }
        upgradeOpenCodeButton.isEnabled = vmBlocker == null
        upgradeOpenCodeButton.toolTipText =
            vmBlocker
                ?: "Install the latest OpenCode release inside this sandbox. The VM and sessions stay; Reset Sandbox returns to Docker's image."
        // Same rule as the gear action (any sandbox backend; Reset cancels a pending start), plus
        // saved settings only: Reset recreates the VM from the saved YAML, never from this form.
        val resetBlocker =
            when {
                !sandboxBackend -> "Apply first: this project still runs the Host CLI."
                formModified -> "Apply first: Reset Sandbox uses the saved settings."
                else -> null
            }
        resetSandboxButton.isEnabled = resetBlocker == null
        resetSandboxButton.toolTipText =
            resetBlocker
                ?: "Delete this project's sandbox and create a new one from Docker's OpenCode kit. Applies create-time settings such as memory and CPUs."
        val checkBlocker =
            when {
                !sandboxBackend -> "Apply first: this project still runs the Host CLI."
                busy -> "Wait until OpenCode has started."
                setupCheckRunning -> "A check is running."
                else -> null
            }
        checkSetupButton.isEnabled = checkBlocker == null
        checkSetupButton.toolTipText =
            checkBlocker
                ?: "Check sandbox setup and guest network access using saved project settings"
        // What the next Reset would apply: the saved spec, not unapplied edits in this form.
        val spec = directory?.let(SbxLaunchSpec::load)
        val record = directory?.let {
            de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore.getInstance().recordFor(it)
        }
        val pending =
            if (sandbox && spec != null && !buildingSearchIndex()) {
                de.moritzf.opencodewebpanel.server.SbxInstalledVmStatus.hint(
                    de.moritzf.opencodewebpanel.server.SbxInstalledVmStatus.settings(spec, record),
                    adopted = record?.adopted == true,
                )
            } else {
                null
            }
        installedHintLabel.text = pending.orEmpty()
        installedHintLabel.isVisible = !pending.isNullOrBlank()
        setupChecklistLabel.text =
            if (buildingSearchIndex()) ""
            else
                "<html>" +
                    de.moritzf.opencodewebpanel.server.SbxSetupChecklist.format(
                            de.moritzf.opencodewebpanel.server.SbxSetupChecklist.projectSteps(
                                owned = record != null,
                                // Not the Host CLI's state while Docker Sandbox is chosen,
                                // unapplied.
                                running =
                                    sandboxBackend && state == OpenCodeServerLifecycleState.RUNNING,
                            ),
                        )
                        .replace("\n", "<br>") +
                    "</html>"
        setupChecklistLabel.isVisible = sandbox
        updatePortControls()
    }

    private fun updatePortControls() {
        fixedPortField.isEnabled = fixedPortRadioButton.isSelected
        val portText = fixedPortField.text.trim()
        val invalid =
            fixedPortRadioButton.isSelected &&
                portText.isNotEmpty() &&
                portText.toIntOrNull()?.takeIf { it in 1..65535 } == null
        fixedPortField.putClientProperty("JComponent.outline", if (invalid) "error" else null)
        val hint =
            when {
                invalid -> "Enter a port from 1 to 65535, or leave it empty for 4096."
                sbxRuntimeRadioButton.isSelected ->
                    "OpenCode listens on 4096 inside the sandbox; Auto publishes it on a free " +
                        "loopback port, Fixed on yours. Changing it does not restart the sandbox."
                else ->
                    "Auto picks a free loopback port at each start; Fixed binds yours, e.g. " +
                        "for a bookmarked browser tab."
            }
        portHint?.text =
            if (invalid) {
                "<font color=\"#${ColorUtil.toHex(UIUtil.getErrorForeground())}\">$hint</font>"
            } else {
                hint
            }
    }

    private fun selectedPortMode(): OpenCodePortMode {
        return if (fixedPortRadioButton.isSelected) OpenCodePortMode.FIXED
        else OpenCodePortMode.AUTO
    }

    private fun fixedPortOrDefault(): Int {
        return OpenCodeSettingsState.sanitizePort(
            fixedPortField.text.trim().toIntOrNull() ?: OpenCodeSettingsState.DEFAULT_FIXED_PORT,
        )
    }

    /** Canonical comparison: a trailing slash or symlinked spelling is the same project. */
    private fun sameDirectory(a: String?, b: String?): Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        return (OpenCodeServerProtocol.canonicalOpenCodeDirectory(a) ?: a) ==
            (OpenCodeServerProtocol.canonicalOpenCodeDirectory(b) ?: b)
    }

    private fun onDirectoryTargetChanged() {
        if (hydrating) return
        val next = effectiveDirectory()
        if (sameDirectory(next, loadedSpecDirectory)) return
        val inspection = SbxLaunchSpec.inspect(next)
        val destination =
            (inspection as? de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection.Valid)?.spec
                ?: next?.let {
                    SbxLaunchSpec.defaults(
                        it,
                        hostPort =
                            OpenCodeProjectSettingsState.getInstance(project).hostPortOrNull(),
                    )
                }
        // Project YAML is the source of truth. A destination with a spec always hydrates the
        // form; a destination without one gets app defaults. Do not keep the previous form.
        if (destination != null) {
            loadSpecIntoUi(
                destination,
                fromYaml =
                    inspection is de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection.Valid,
            )
        }
        loadedSpecDirectory = next
        showSpecStatus(inspection)
        setupCheckResultLabel.text = ""
        updateSandboxControls()
    }

    /**
     * An invalid spec fails closed as a sandbox; show that instead of the Host defaults it
     * hydrates.
     */
    private fun showSpecStatus(
        inspection: de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection,
    ) {
        val invalid =
            inspection as? de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection.Invalid
        specStatusLabel.isVisible = invalid != null
        if (invalid != null) {
            specStatusLabel.text =
                "<html>" +
                    com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(
                        "Invalid ${SbxCli.posixPath(invalid.path.toString())}: ${invalid.reason}. " +
                            "OpenCode will not start until the file is fixed, or replaced with these values on Apply.",
                    ) +
                    "</html>"
            sbxRuntimeRadioButton.isSelected = true
            // Apply can replace the file with this form: show everything it would write.
            resourcesGroup?.expanded = true
        }
    }

    companion object {
        /** plugin.xml `projectConfigurable` id. */
        const val ID = "opencode.web.panel.project.settings"
        const val PROJECT_SETTINGS_DISPLAY_NAME = "This Project"
        private const val SBX_KIT_DOCS_URL = "https://docs.docker.com/ai/sandboxes/customize/"
        private const val OPENCODE_IMAGE_TAGS_PAGE = "https://hub.docker.com/r/sbx/opencode/tags"
    }

    private class ExtraMountRow(
        var hostPath: String = "",
        var sandboxPath: String = "",
        var readOnly: Boolean = false,
    )

    private class KitRow(var ref: String = "")

    private fun <T> addTableRow(table: TableView<T>, model: ListTableModel<T>, item: T) {
        model.addRow(item)
        val row = model.rowCount - 1
        table.selectionModel.setSelectionInterval(row, row)
        table.scrollRectToVisible(table.getCellRect(row, 0, true))
        if (table.editCellAt(row, 0)) {
            (table.cellEditor as? SbxBrowsePathCellEditor)?.focusTarget?.requestFocusInWindow()
        }
    }
}
