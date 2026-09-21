package de.moritzf.opencodewebpanel.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxProcessRunner
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicLong
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.SpinnerNumberModel

class OpenCodeSettingsConfigurable : Configurable, Configurable.NoMargin {
    private var panel: JComponent? = null
    private val passwordField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Password used for the local OpenCode web server"
    }
    private val showPasswordButton = JToggleButton(AllIcons.Actions.Show).apply {
        toolTipText = "Show password"
        accessibleContext.accessibleName = "Show password"
    }
    private val copyPasswordButton = JButton("Copy", AllIcons.Actions.Copy).apply {
        toolTipText = "Copy password to clipboard"
        accessibleContext.accessibleName = "Copy password"
    }
    private val generatePasswordButton = JButton("Generate").apply {
        toolTipText = "Generate a new password; apply settings to save it"
        accessibleContext.accessibleName = "Generate password"
    }

    private val autoBinaryRadioButton = JBRadioButton("Auto detect")
    private val customBinaryRadioButton = JBRadioButton("OpenCode path")
    private val autoSbxRadioButton = JBRadioButton("Auto detect")
    private val customSbxRadioButton = JBRadioButton("Sbx path")
    private val sbxPathField = TextFieldWithBrowseButton().apply {
        textField.columns = 40
        toolTipText = "Path to the sbx executable"
        // The chooser stays usable in auto mode as a way to fill the path; only typing is
        // custom-only. Never disable the whole component: disabling hides the browse button
        // completely and it is not reliably shown again after re-enabling.
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
        addActionListener { customSbxRadioButton.isSelected = true }
    }
    private val detectSbxButton = JButton("Detect").apply {
        toolTipText = "Auto-detect sbx and fill the path"
        accessibleContext.accessibleName = "Detect sbx path"
    }
    private val sbxLoginHintLabel = JBLabel("Sign in with sbx login in a terminal. The panel does not embed OAuth.")
    private val sbxPolicyHintLabel = JBLabel("No sandbox network policy yet.")
    private val setupChecklistLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val initSbxPolicyButton = JButton("Set up default network policy").apply {
        toolTipText = "Runs Docker's balanced policy once on this computer so sandboxes can reach typical AI APIs and package sites"
        accessibleContext.accessibleName = "Set up default sandbox network policy"
    }
    private val binaryPathField = TextFieldWithBrowseButton().apply {
        textField.columns = 40
        toolTipText = "Path to the opencode executable"
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
        addActionListener { customBinaryRadioButton.isSelected = true }
    }
    private val detectBinaryButton = JButton("Detect").apply {
        toolTipText = "Auto-detect opencode and fill the path"
        accessibleContext.accessibleName = "Detect OpenCode path"
    }
    private val ideProxyRadioButton = JBRadioButton("Use IDE HTTP Proxy")
    private val environmentProxyRadioButton = JBRadioButton("Use environment variables")
    private val noProxyRadioButton = JBRadioButton("No proxy")
    private val enableServerLogsCheckBox = JBCheckBox("Write server logs to disk")
    private val openFileLinksInIdeCheckBox = JBCheckBox("Enable IDE navigation from OpenCode")
    private val openExternalLinksInBrowserCheckBox = JBCheckBox("Open external HTTP links in the system browser")
    private val enableCodeNavigationCheckBox = JBCheckBox("Also navigate code references in chat")
    private val openDiffsInIdeCheckBox = JBCheckBox("Open diffs in the IDE on Ctrl/Cmd+Click or Alt+Click")
    private val enableChatFileDropCheckBox = JBCheckBox("Enable file drop and paste into chat")
    private val forceCompactLayoutCheckBox = JBCheckBox("Lock to compact view")
    private val hideWebsiteButtonCheckBox = JBCheckBox("Hide the OpenCode website button")
    private val fasterPathHoverPreviewCheckBox = JBCheckBox("Faster path previews on tabs and projects")
    private val syncThemeWithIdeCheckBox = JBCheckBox("Sync OpenCode color scheme with the IDE theme")
    private val suppressProjectSwitchPromptsCheckBox = JBCheckBox("Suppress project-switch prompts")
    private val mirrorBrowserCursorCheckBox = JBCheckBox("Mirror the web page mouse cursor")
    private val recoverStalledEventStreamCheckBox = JBCheckBox("Reconnect the panel after a stalled connection")
    private val recoverFailedChunkLoadsCheckBox = JBCheckBox("Reload the panel after a failed page chunk")
    private val recoverStalledRendererCheckBox = JBCheckBox("Reload the panel when its renderer goes silent")
    private val notifyOpenCodeUpdatesCheckBox = JBCheckBox("Show an update indicator when a newer matching OpenCode release is available")
    private val enableSystemNotificationsCheckBox = JBCheckBox("Forward OpenCode system notifications to the IDE")
    private val enablePermissionNotificationActionsCheckBox = JBCheckBox("Offer Allow/Deny actions on permission notifications")
    private val showAgentStatusBadgeCheckBox = JBCheckBox("Show agent status on the tool window icon")
    private val autoContinueInterruptedSessionsCheckBox = JBCheckBox("Automatically continue interrupted conversations after recovery")
    private val waitForIntellijMcpServerCheckBox = JBCheckBox("Wait for IntelliJ MCP server before starting OpenCode")
    private val uiZoomSpinner = JSpinner(
        SpinnerNumberModel(
            OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT,
            OpenCodeSettingsState.MIN_UI_ZOOM_PERCENT,
            OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT,
            10,
        ),
    ).apply {
        toolTipText = "Scale the embedded OpenCode UI"
        accessibleContext.accessibleName = "OpenCode UI zoom percentage"
    }
    private val hintLabel = JBLabel().apply { isVisible = false }
    private val hiddenPasswordEchoChar = passwordField.echoChar
    private val passwordLoadGeneration = AtomicLong(0)
    private var savedPassword: String? = null
    private var passwordLoading = false
    private var passwordLoadError: String? = null
    private var controlListenersInstalled = false

    private data class CheckBoxSettingBinding(
        val checkBox: JBCheckBox,
        val read: OpenCodeSettingsState.() -> Boolean,
        val write: OpenCodeSettingsState.(Boolean) -> Unit,
        /** When set, a change to this toggle is broadcast as [OpenCodeSettingsListener.uiSettingChanged]. */
        val uiSetting: OpenCodeUiSetting? = null,
    )

    private val checkBoxSettingBindings = listOf(
        CheckBoxSettingBinding(openFileLinksInIdeCheckBox, { openFileLinksInIde }, { value -> openFileLinksInIde = value }, OpenCodeUiSetting.FILE_LINK_NAVIGATION),
        CheckBoxSettingBinding(openExternalLinksInBrowserCheckBox, { openExternalLinksInBrowser }, { value -> openExternalLinksInBrowser = value }, OpenCodeUiSetting.EXTERNAL_LINK_NAVIGATION),
        CheckBoxSettingBinding(enableCodeNavigationCheckBox, { enableCodeNavigation }, { value -> enableCodeNavigation = value }, OpenCodeUiSetting.CODE_NAVIGATION),
        CheckBoxSettingBinding(openDiffsInIdeCheckBox, { openDiffsInIde }, { value -> openDiffsInIde = value }, OpenCodeUiSetting.DIFF_NAVIGATION),
        CheckBoxSettingBinding(enableChatFileDropCheckBox, { enableChatFileDrop }, { value -> enableChatFileDrop = value }, OpenCodeUiSetting.CHAT_FILE_DROP),
        CheckBoxSettingBinding(forceCompactLayoutCheckBox, { forceCompactLayout }, { value -> forceCompactLayout = value }, OpenCodeUiSetting.COMPACT_LAYOUT),
        CheckBoxSettingBinding(hideWebsiteButtonCheckBox, { hideWebsiteButton }, { value -> hideWebsiteButton = value }, OpenCodeUiSetting.HIDE_WEBSITE_BUTTON),
        CheckBoxSettingBinding(fasterPathHoverPreviewCheckBox, { fasterPathHoverPreview }, { value -> fasterPathHoverPreview = value }, OpenCodeUiSetting.PATH_HOVER_PREVIEW),
        CheckBoxSettingBinding(syncThemeWithIdeCheckBox, { syncThemeWithIde }, { value -> syncThemeWithIde = value }, OpenCodeUiSetting.IDE_THEME_SYNC),
        CheckBoxSettingBinding(suppressProjectSwitchPromptsCheckBox, { suppressProjectSwitchPrompts }, { value -> suppressProjectSwitchPrompts = value }, OpenCodeUiSetting.PROJECT_SWITCH_PROMPT_SUPPRESSION),
        CheckBoxSettingBinding(mirrorBrowserCursorCheckBox, { mirrorBrowserCursor }, { value -> mirrorBrowserCursor = value }, OpenCodeUiSetting.BROWSER_CURSOR_MIRROR),
        CheckBoxSettingBinding(recoverStalledEventStreamCheckBox, { recoverStalledEventStream }, { value -> recoverStalledEventStream = value }, OpenCodeUiSetting.EVENT_STREAM_WATCHDOG),
        CheckBoxSettingBinding(recoverFailedChunkLoadsCheckBox, { recoverFailedChunkLoads }, { value -> recoverFailedChunkLoads = value }, OpenCodeUiSetting.CHUNK_LOAD_RECOVERY),
        CheckBoxSettingBinding(recoverStalledRendererCheckBox, { recoverStalledRenderer }, { value -> recoverStalledRenderer = value }, OpenCodeUiSetting.RENDERER_WATCHDOG),
        // System notifications need no page interaction: the Kotlin-side event consumer
        // re-checks the setting on every event.
        CheckBoxSettingBinding(notifyOpenCodeUpdatesCheckBox, { notifyOpenCodeUpdates }, { value -> notifyOpenCodeUpdates = value }),
        CheckBoxSettingBinding(enableSystemNotificationsCheckBox, { enableSystemNotifications }, { value -> enableSystemNotifications = value }),
        CheckBoxSettingBinding(enablePermissionNotificationActionsCheckBox, { enablePermissionNotificationActions }, { value -> enablePermissionNotificationActions = value }),
        CheckBoxSettingBinding(showAgentStatusBadgeCheckBox, { showAgentStatusBadge }, { value -> showAgentStatusBadge = value }, OpenCodeUiSetting.AGENT_STATUS_BADGE),
        CheckBoxSettingBinding(autoContinueInterruptedSessionsCheckBox, { autoContinueInterruptedSessions }, { value -> autoContinueInterruptedSessions = value }),
        CheckBoxSettingBinding(waitForIntellijMcpServerCheckBox, { waitForIntellijMcpServer }, { value -> waitForIntellijMcpServer = value }),
        CheckBoxSettingBinding(enableServerLogsCheckBox, { enableServerLogs }, { value -> enableServerLogs = value }),
    )

    override fun getDisplayName(): String = "OpenCode Web Panel"

    override fun createComponent(): JComponent {
        return try {
            createSettingsComponent()
        } catch (error: Throwable) {
            Logger.getInstance(OpenCodeSettingsConfigurable::class.java)
                .error("Failed to create OpenCode Web Panel settings", error)
            JBLabel(
                "<html>Failed to open OpenCode settings.<br>${error.javaClass.simpleName}: ${error.message.orEmpty()}</html>",
            ).also { panel = it }
        }
    }

    private fun createSettingsComponent(): JComponent {
        installControlListenersOnce()

        val serverSetupPanel = panel {
            group("OpenCode Launch") {
                row {
                    comment("Which runtime starts OpenCode. Host CLI runs opencode on this machine; Docker Sandbox runs it inside a Docker Sandbox. Runtime is chosen per project.")
                }
                buttonsGroup("Host CLI — OpenCode executable:") {
                    row {
                        cell(autoBinaryRadioButton)
                            .comment("Find opencode from PATH plus common install locations, including Homebrew, system paths, and npm locations on Windows.")
                    }
                    row {
                        cell(customBinaryRadioButton).gap(RightGap.SMALL)
                        cell(binaryPathField)
                            .resizableColumn()
                            .align(AlignX.FILL)
                            .gap(RightGap.SMALL)
                        cell(detectBinaryButton)
                    }
                }
                buttonsGroup("Docker Sandbox — sbx executable:") {
                    row {
                        cell(autoSbxRadioButton)
                            .comment("Find sbx from PATH plus Homebrew and WinGet locations.")
                    }
                    row {
                        cell(customSbxRadioButton).gap(RightGap.SMALL)
                        cell(sbxPathField)
                            .resizableColumn()
                            .align(AlignX.FILL)
                            .gap(RightGap.SMALL)
                        cell(detectSbxButton)
                    }
                }
            }
            group("Authentication") {
                row("Password:") {
                    cell(passwordField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .gap(RightGap.SMALL)
                    cell(showPasswordButton).gap(RightGap.SMALL)
                    cell(copyPasswordButton).gap(RightGap.SMALL)
                    cell(generatePasswordButton)
                }
                row {
                    cell(hintLabel)
                }
            }
            buttonsGroup("HTTP proxy:") {
                row {
                    cell(ideProxyRadioButton)
                        .comment("Forward the IDE HTTP or SOCKS proxy, including auto-detect and PAC. PAC is resolved for a generic HTTPS target.")
                }
                row {
                    cell(environmentProxyRadioButton)
                        .comment("Use HTTP_PROXY and HTTPS_PROXY from the environment the IDE was started with.")
                }
                row {
                    cell(noProxyRadioButton)
                        .comment("Do not send OpenCode provider traffic through a proxy.")
                }
                row {
                    comment(
                        "Applies to Host CLI only. Docker Sandboxes send provider traffic through the sandbox gateway; " +
                            "do not set HTTP_PROXY inside the VM. If your company requires an HTTP proxy, configure it " +
                            "in Docker Sandboxes on the host — this setting does not control or bypass that proxy.",
                    )
                }
            }
            group("Startup") {
                row {
                    cell(waitForIntellijMcpServerCheckBox)
                        .comment("If IntelliJ's MCP server is enabled, wait briefly for it to report that it is running before launching OpenCode.")
                }
            }
            group("Server Logs") {
                row {
                    cell(enableServerLogsCheckBox)
                        .comment("Persist OpenCode server output in the IDE log directory and prune old log files automatically.")
                }
            }
            group("Docker Sandboxes") {
                row {
                    comment(
                        "Prerequisites for Docker Sandbox runtimes (per project on " +
                            "Tools → OpenCode Web Panel (Project)). Not needed for Host CLI.",
                    )
                }
                row {
                    cell(sbxLoginHintLabel)
                }
                row {
                    cell(sbxPolicyHintLabel)
                }
                row {
                    cell(setupChecklistLabel)
                }
                row {
                    cell(initSbxPolicyButton)
                        .comment(
                            "Sandboxes block all outbound internet until a policy exists. " +
                                "Docker's balanced profile allows common AI APIs and package registries " +
                                "and denies everything else. This is machine-wide (every sbx sandbox on this " +
                                "computer), not just this IDE. The plugin never runs it silently. " +
                                "<a href=\"$SBX_POLICY_DOCS_URL\">Docker network defaults</a>",
                            action = HyperlinkEventAction { event ->
                                val href = event.url?.toString() ?: event.description
                                if (!href.isNullOrBlank()) BrowserUtil.browse(href)
                            },
                        )
                }
            }
        }
        val uiSettingsPanel = panel {
            group("Browser Appearance") {
                row("Zoom:") {
                    cell(uiZoomSpinner)
                        .comment("Scale the embedded OpenCode UI. Default: ${OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT}%.")
                }
                row {
                    cell(forceCompactLayoutCheckBox)
                        .comment("Keep the compact mobile layout even when the panel is wide. On: classic review panel. Off: OpenCode's redesigned desktop review panel.")
                }
                row {
                    cell(hideWebsiteButtonCheckBox)
                        .comment("Hide OpenCode's floating help button that opens the OpenCode website. It only overlaps the message box in the panel. Turn off if an OpenCode update needs the control visible again.")
                }
                row {
                    cell(fasterPathHoverPreviewCheckBox)
                        .comment("Session tabs wait 2s in OpenCode; the panel shows that path preview after 250ms. Home project rows get the same preview so duplicate names stay distinguishable. Turn off if an OpenCode update clashes with the overlay.")
                }
                row {
                    cell(syncThemeWithIdeCheckBox)
                        .comment("Patches the browser's prefers-color-scheme media query to match the IntelliJ theme. Only affects OpenCode when its color scheme is set to System.")
                }
                row {
                    cell(mirrorBrowserCursorCheckBox)
                        .comment("Apply the hovered page element's cursor style to the panel, like a regular browser would. Fixes the embedded browser never showing text or link cursors and resize cursors getting stuck.")
                }
            }
            group("IDE Navigation") {
                row {
                    cell(openFileLinksInIdeCheckBox)
                        .comment("Open local file links and changed-file buttons in IntelliJ.")
                }
                indent {
                    row {
                        cell(enableCodeNavigationCheckBox)
                            .comment("Open file names, class names, and code references from chat.")
                    }
                }
                row {
                    cell(openDiffsInIdeCheckBox)
                        .comment("Ctrl+Click (Cmd+Click on macOS) or Alt+Click a diff in chat or in the changes list to open it in the IDE's diff viewer. F4 then jumps to the original file.")
                }
            }
            group("Link Handling") {
                row {
                    cell(openExternalLinksInBrowserCheckBox)
                        .comment("Open external HTTP and HTTPS links in the system browser instead of navigating the embedded panel.")
                }
            }
            group("Chat File Input") {
                row {
                    cell(enableChatFileDropCheckBox)
                        .comment("Use OpenCode's native handling for project file references and file attachments.")
                }
            }
            group("OpenCode Event Handling") {
                row {
                    cell(notifyOpenCodeUpdatesCheckBox)
                        .comment("Shows a lightning bolt on the OpenCode tool-window title bar. Hover for the version; click to upgrade a sandbox or copy the Host CLI command. 1.x checks npm opencode-ai; 2.x checks @opencode/cli. Does not suggest switching major versions.")
                }
                row {
                    cell(enableSystemNotificationsCheckBox)
                        .comment("Show OpenCode browser notifications as IntelliJ notifications and route notification clicks back to OpenCode.")
                }
                indent {
                    row {
                        cell(enablePermissionNotificationActionsCheckBox)
                            .comment("Answer agent permission requests directly from the IDE notification without switching to the panel.")
                    }
                }
                row {
                    cell(suppressProjectSwitchPromptsCheckBox)
                        .comment("Hide OpenCode in-app prompts that ask this panel to switch to another session or project for approval.")
                }
                row {
                    cell(showAgentStatusBadgeCheckBox)
                        .comment("Overlay the tool window icon with a live indicator while the agent works and a warning while it awaits your input.")
                }
                row {
                    cell(autoContinueInterruptedSessionsCheckBox)
                        .comment("Send a continuation prompt to recently active sessions after the server restarts or recovers, if their last assistant turn was interrupted.")
                }
                row {
                    cell(recoverStalledEventStreamCheckBox)
                        .comment("Reopen the panel's connection to OpenCode when it goes silent, which happens when sleep or a network change severs it without closing it. Without this the panel keeps showing answered permission prompts and refuses new messages until you reload it.")
                }
                row {
                    cell(recoverFailedChunkLoadsCheckBox)
                        .comment("Reload the page once when a lazy-loaded OpenCode chunk fails (\"Failed to fetch dynamically imported module\"), which leaves the page stuck behind OpenCode's error boundary until reloaded. CEF only reports main-frame failures to the IDE; without this the boundary sits there until you restart the panel manually.")
                }
                row {
                    cell(recoverStalledRendererCheckBox)
                        .comment("Watch the page's heartbeat and reload it when the embedded browser goes silent without an error (a stuck or dead JCEF renderer, as happens with out-of-process browser mode). Persistent stalls recreate the panel; a failure card with Retry is the fallback.")
                }
            }
        }
        panel = OpenCodeSettingsTabbedPane().apply {
            addTab("OpenCode Server Setup", serverSetupPanel)
            addTab("OpenCode UI Settings", uiSettingsPanel)
        }
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeSettingsState.getInstance()
        val passwordModified = password() != savedPassword
        val binaryModeModified = selectedBinaryMode() != settings.binaryModeValue()
        val binaryPathModified = binaryPath() != settings.binaryPath.trim()
        val sbxBinaryModeModified = selectedSbxBinaryMode() != settings.sbxBinaryModeValue()
        val sbxBinaryPathModified = sbxPath() != settings.sbxBinaryPath.trim()
        val proxyModified = isProxySettingModified(settings)
        val checkBoxSettingsModified = checkBoxSettingBindings.any { it.checkBox.isSelected != it.read(settings) }
        val uiZoomModified = uiZoomPercent() != OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        return passwordModified || binaryModeModified ||
            binaryPathModified || sbxBinaryModeModified || sbxBinaryPathModified ||
            proxyModified || checkBoxSettingsModified || uiZoomModified
    }

    override fun apply() {
        passwordLoadGeneration.incrementAndGet()
        passwordLoading = false
        val editedPassword = password().takeIf { it != savedPassword }
        val settings = OpenCodeSettingsState.getInstance()
        val oldBinaryMode = settings.binaryModeValue()
        val oldBinaryPath = settings.binaryPath.trim()
        val oldSbxBinaryMode = settings.sbxBinaryModeValue()
        val oldSbxBinaryPath = settings.sbxBinaryPath.trim()
        val oldProxyMode = settings.proxyModeValue()
        val oldUiZoomPercent = OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        val oldSystemNotificationsEnabled = settings.enableSystemNotifications
        val pendingBroadcasts = checkBoxSettingBindings.mapNotNull { binding ->
            val uiSetting = binding.uiSetting ?: return@mapNotNull null
            val newValue = binding.checkBox.isSelected
            if (binding.read(settings) == newValue) return@mapNotNull null
            uiSetting to newValue
        }

        val passwordUpdate = resolveAndSavePasswordOffEdt(editedPassword)
        val oldPassword = passwordUpdate.previous
        val nextPassword = passwordUpdate.current
        savedPassword = nextPassword
        setPasswordText(nextPassword)
        passwordLoadError = null

        val nextBinaryMode = selectedBinaryMode()
        val nextBinaryPath = binaryPath()
        val nextSbxBinaryMode = selectedSbxBinaryMode()
        val nextSbxBinaryPath = sbxPath()
        val nextUiZoomPercent = uiZoomPercent()
        settings.binaryMode = nextBinaryMode.name
        settings.binaryPath = nextBinaryPath
        settings.sbxBinaryMode = nextSbxBinaryMode.name
        settings.sbxBinaryPath = nextSbxBinaryPath
        settings.proxyMode = selectedProxyMode().name
        checkBoxSettingBindings.forEach { it.write(settings, it.checkBox.isSelected) }
        settings.uiZoomPercent = nextUiZoomPercent
        binaryPathField.text = nextBinaryPath
        sbxPathField.text = nextSbxBinaryPath
        updatePasswordHint()
        updateBinaryControls()
        updateSbxControls()
        updateRuntimeControls()
        updateUiDependencyControls()
        val registry = OpenCodeServerBackendRegistry.getInstance()
        val passwordChanged = oldPassword != nextPassword
        val nativeChanged = oldBinaryMode != nextBinaryMode || oldBinaryPath != nextBinaryPath ||
            oldProxyMode != settings.proxyModeValue()
        val sbxChanged = oldSbxBinaryMode != nextSbxBinaryMode || oldSbxBinaryPath != nextSbxBinaryPath
        if (passwordChanged || nativeChanged || sbxChanged) {
            if (passwordChanged || nativeChanged) registry.stopAllNativeBackends()
            if (passwordChanged || sbxChanged) registry.stopAllSbxBackends()
            val scope = when {
                passwordChanged || (nativeChanged && sbxChanged) -> OpenCodeRestartScope.ALL
                nativeChanged -> OpenCodeRestartScope.NATIVE
                else -> OpenCodeRestartScope.SBX
            }
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .serverRestartRequested(scope)
        }
        if (oldUiZoomPercent != nextUiZoomPercent) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .uiZoomChanged(nextUiZoomPercent)
        }
        if (pendingBroadcasts.isNotEmpty()) {
            val publisher = ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
            pendingBroadcasts.forEach { (setting, enabled) -> publisher.uiSettingChanged(setting, enabled) }
        }
        if (oldSystemNotificationsEnabled != settings.enableSystemNotifications) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .systemNotificationsChanged(settings.enableSystemNotifications)
        }
    }

    override fun reset() {
        val settings = OpenCodeSettingsState.getInstance()
        when (settings.binaryModeValue()) {
            OpenCodeBinaryMode.AUTO -> autoBinaryRadioButton.isSelected = true
            OpenCodeBinaryMode.CUSTOM -> customBinaryRadioButton.isSelected = true
        }
        binaryPathField.text = settings.binaryPath.trim()
        when (settings.sbxBinaryModeValue()) {
            OpenCodeBinaryMode.AUTO -> autoSbxRadioButton.isSelected = true
            OpenCodeBinaryMode.CUSTOM -> customSbxRadioButton.isSelected = true
        }
        sbxPathField.text = settings.sbxBinaryPath.trim()
        when (settings.proxyModeValue()) {
            OpenCodeProxyMode.IDE -> ideProxyRadioButton.isSelected = true
            OpenCodeProxyMode.ENVIRONMENT -> environmentProxyRadioButton.isSelected = true
            OpenCodeProxyMode.NONE -> noProxyRadioButton.isSelected = true
        }
        checkBoxSettingBindings.forEach { it.checkBox.isSelected = it.read(settings) }
        uiZoomSpinner.value = OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        loadPasswordField()
        updatePasswordHint()
        updateBinaryControls()
        updateSbxControls()
        updateRuntimeControls()
        updateUiDependencyControls()
    }

    override fun disposeUIResources() {
        panel = null
        passwordLoadGeneration.incrementAndGet()
    }

    private fun resolveAndSavePasswordOffEdt(editedPassword: String?): OpenCodePasswordStore.PasswordUpdate {
        val store = OpenCodePasswordStore.getInstance()
        val operation = ThrowableComputable<OpenCodePasswordStore.PasswordUpdate, Exception> {
            store.resolveAndSaveBlocking(editedPassword)
        }
        val app = ApplicationManager.getApplication()
        return try {
            if (app.isDispatchThread && !app.isUnitTestMode) {
                ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    operation,
                    "Saving OpenCode Password",
                    false,
                    null,
                )
            } else {
                operation.compute()
            }
        } catch (error: Exception) {
            passwordLoadError = "Could not save password to secure storage: ${error.message ?: error::class.java.simpleName}"
            updatePasswordHint()
            throw ConfigurationException(passwordLoadError!!)
        }
    }

    /**
     * The controls are fields of this configurable, so they survive `disposeUIResources()` and
     * a later `createComponent()`; registering listeners per creation would stack duplicates
     * that fire actions (e.g. password generation) multiple times per click.
     */
    private fun installControlListenersOnce() {
        if (controlListenersInstalled) return
        controlListenersInstalled = true
        ButtonGroup().apply {
            add(autoBinaryRadioButton)
            add(customBinaryRadioButton)
        }
        ButtonGroup().apply {
            add(autoSbxRadioButton)
            add(customSbxRadioButton)
        }
        ButtonGroup().apply {
            add(ideProxyRadioButton)
            add(environmentProxyRadioButton)
            add(noProxyRadioButton)
        }
        autoBinaryRadioButton.addItemListener { updateBinaryControls() }
        customBinaryRadioButton.addItemListener { updateBinaryControls() }
        autoSbxRadioButton.addItemListener { updateSbxControls() }
        customSbxRadioButton.addItemListener { updateSbxControls() }
        showPasswordButton.addActionListener { updatePasswordVisibility() }
        copyPasswordButton.addActionListener { copyPassword() }
        generatePasswordButton.addActionListener {
            setPasswordText(OpenCodePasswordStore.getInstance().generatePasswordForEditing())
            updatePasswordHint()
        }
        detectBinaryButton.addActionListener { detectBinaryPath() }
        detectSbxButton.addActionListener { detectSbxPath() }
        initSbxPolicyButton.addActionListener { consentToSbxPolicy() }
        openFileLinksInIdeCheckBox.addItemListener { updateUiDependencyControls() }
        enableSystemNotificationsCheckBox.addItemListener { updateUiDependencyControls() }
    }

    private fun password(): String? = String(passwordField.password).trim().ifBlank { null }

    private fun selectedBinaryMode(): OpenCodeBinaryMode {
        return if (customBinaryRadioButton.isSelected) OpenCodeBinaryMode.CUSTOM else OpenCodeBinaryMode.AUTO
    }

    private fun binaryPath(): String = binaryPathField.text.trim()

    private fun selectedSbxBinaryMode(): OpenCodeBinaryMode {
        return if (customSbxRadioButton.isSelected) OpenCodeBinaryMode.CUSTOM else OpenCodeBinaryMode.AUTO
    }

    private fun sbxPath(): String = sbxPathField.text.trim()

    private fun selectedProxyMode(): OpenCodeProxyMode {
        return when {
            environmentProxyRadioButton.isSelected -> OpenCodeProxyMode.ENVIRONMENT
            noProxyRadioButton.isSelected -> OpenCodeProxyMode.NONE
            else -> OpenCodeProxyMode.IDE
        }
    }

    private fun isProxySettingModified(settings: OpenCodeSettingsState): Boolean {
        return selectedProxyMode() != settings.proxyModeValue()
    }

    private fun uiZoomPercent(): Int {
        return OpenCodeSettingsState.sanitizeUiZoomPercent((uiZoomSpinner.value as? Number)?.toInt() ?: OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT)
    }

    private fun loadPasswordField() {
        val store = OpenCodePasswordStore.getInstance()
        val cachedPassword = store.cachedPassword()
        if (cachedPassword != null && password() == savedPassword) {
            savedPassword = cachedPassword
            setPasswordText(cachedPassword)
        }

        val generation = passwordLoadGeneration.incrementAndGet()
        val fieldValueAtRequest = password()
        val savedPasswordAtRequest = savedPassword
        passwordLoading = cachedPassword == null
        passwordLoadError = null
        updatePasswordHint()

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.loadFreshBlocking() }
            ApplicationManager.getApplication().invokeLater({
                if (generation != passwordLoadGeneration.get()) return@invokeLater
                passwordLoading = false
                result.fold(
                    onSuccess = { loadedPassword ->
                        savedPassword = loadedPassword
                        val currentFieldValue = password()
                        if (currentFieldValue == fieldValueAtRequest || currentFieldValue == savedPasswordAtRequest) {
                            setPasswordText(loadedPassword)
                        }
                    },
                    onFailure = { error ->
                        if (cachedPassword == null) {
                            passwordLoadError = "Could not load password from secure storage: ${error.message ?: error::class.java.simpleName}"
                        }
                    },
                )
                updatePasswordHint()
            }, passwordLoadModality())
        }
    }

    private fun passwordLoadModality(): ModalityState {
        val component = panel ?: passwordField
        if (!component.isShowing) return ModalityState.defaultModalityState()
        return runCatching { ModalityState.stateForComponent(component) }
            .getOrDefault(ModalityState.defaultModalityState())
    }

    private fun setPasswordText(password: String?) {
        passwordField.text = password.orEmpty()
    }

    private fun updatePasswordVisibility() {
        val visible = showPasswordButton.isSelected
        passwordField.echoChar = if (visible) 0.toChar() else hiddenPasswordEchoChar
        val action = if (visible) "Hide" else "Show"
        showPasswordButton.toolTipText = "$action password"
        showPasswordButton.accessibleContext.accessibleName = "$action password"
    }

    private fun copyPassword() {
        val password = password() ?: return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(password), null)
    }

    private fun detectBinaryPath() {
        customBinaryRadioButton.isSelected = true
        val detectedPath = OpenCodeServerProtocol.detectExecutablePath()
        if (detectedPath == null) {
            Messages.showWarningDialog(
                panel ?: binaryPathField,
                "Could not find opencode on PATH or in common install locations.",
                "OpenCode Path Not Found",
            )
            return
        }
        binaryPathField.text = detectedPath
        updateBinaryControls()
    }

    private fun detectSbxPath() {
        customSbxRadioButton.isSelected = true
        val detectedPath = OpenCodeServerProtocol.detectExecutablePath(SbxCli.DEFAULT_EXECUTABLE)
        if (detectedPath == null) {
            Messages.showWarningDialog(
                panel ?: sbxPathField,
                "Could not find sbx on PATH or in common install locations.",
                "Sbx Path Not Found",
            )
            return
        }
        sbxPathField.text = detectedPath
        updateSbxControls()
    }

    private fun consentToSbxPolicy() {
        val confirmed = MessageDialogBuilder.yesNo(
            "Set up sandbox network policy",
            "Docker Sandboxes start with no internet. This runs sbx policy init balanced once " +
                "on this computer: typical AI APIs and package sites are allowed, and everything " +
                "else stays blocked.\n\n" +
                "It applies to every sandbox on this machine, including ones outside IntelliJ. " +
                "The plugin will not change the policy again afterwards.",
        )
            .yesText("Set up policy")
            .noText("Cancel")
            .icon(Messages.getWarningIcon())
            .ask(panel)
        if (!confirmed) return
        val settings = OpenCodeSettingsState.getInstance()
        val executable = OpenCodeServerProtocol.resolveExecutableForLaunch(
            if (customSbxRadioButton.isSelected) sbxPath() else settings.sbxExecutablePath(),
        )
        initSbxPolicyButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SbxProcessRunner.run(
                SbxCli.buildPolicyInitCommand(executable),
                emptyMap(),
                60_000L,
            )
            ApplicationManager.getApplication().invokeLater {
                if (result.exitCode == 0) {
                    settings.sbxNetworkPolicyConsent = true
                } else {
                    Messages.showErrorDialog(
                        panel ?: initSbxPolicyButton,
                        "sbx policy init failed (exit ${result.exitCode}).\n${result.output.takeLast(2000)}",
                        "Sandbox Network Policy",
                    )
                }
                if (panel != null) updateRuntimeControls()
            }
        }
    }

    private fun updatePasswordHint() {
        val currentPassword = password()
        val loadError = passwordLoadError
        var isError = false
        val hint = when {
            passwordLoading && currentPassword == null -> "Loading password from secure storage..."
            loadError != null -> {
                isError = true
                loadError
            }
            currentPassword == null && savedPassword == null -> "No password yet; apply settings to generate and store one."
            currentPassword != savedPassword -> "Apply settings to save the updated password."
            else -> null
        }
        hintLabel.foreground = if (isError) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        hintLabel.text = hint.orEmpty()
        hintLabel.isVisible = hint != null
        copyPasswordButton.isEnabled = currentPassword != null
    }

    private fun updateBinaryControls() {
        binaryPathField.textField.isEnabled = customBinaryRadioButton.isSelected
        detectBinaryButton.isEnabled = customBinaryRadioButton.isSelected
    }

    private fun updateSbxControls() {
        sbxPathField.textField.isEnabled = customSbxRadioButton.isSelected
        detectSbxButton.isEnabled = customSbxRadioButton.isSelected
    }

    private fun updateRuntimeControls() {
        val consented = OpenCodeSettingsState.getInstance().sbxNetworkPolicyConsent
        sbxPolicyHintLabel.text = if (consented) {
            "Default network policy is set on this machine. The plugin will not change it again."
        } else {
            "No sandbox network policy yet. Sandboxes cannot reach the internet until you set one up."
        }
        initSbxPolicyButton.isEnabled = !consented
        val executable = if (customSbxRadioButton.isSelected) sbxPath() else OpenCodeSettingsState.getInstance().sbxExecutablePath()
        val sbxFound = OpenCodeServerProtocol.detectExecutablePath(executable) != null
        setupChecklistLabel.text = "<html>" +
            de.moritzf.opencodewebpanel.server.SbxSetupChecklist.format(
                de.moritzf.opencodewebpanel.server.SbxSetupChecklist.appSteps(sbxFound, consented),
            ).replace("\n", "<br>") + "</html>"
        updateSbxControls()
        updateBinaryControls()
    }

    private fun updateUiDependencyControls() {
        enableCodeNavigationCheckBox.isEnabled = openFileLinksInIdeCheckBox.isSelected
        enablePermissionNotificationActionsCheckBox.isEnabled = enableSystemNotificationsCheckBox.isSelected
    }

    companion object {
        private const val SBX_POLICY_DOCS_URL = "https://docs.docker.com/ai/sandboxes/security/defaults/"
    }
}
