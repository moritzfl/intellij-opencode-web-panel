package de.moritzf.opencodewebpanel.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.server.formatOpenCodeServerLifecycleStatusText
import de.moritzf.opencodewebpanel.toolWindow.confirmOpenCodeServerRestart
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicLong
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.SpinnerNumberModel

class OpenCodeSettingsConfigurable : Configurable {
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
    private val autoPortRadioButton = JBRadioButton("Auto select")
    private val fixedPortRadioButton = JBRadioButton("Fixed port")
    private val fixedPortField = JBTextField().apply {
        columns = 6
        toolTipText = "Loopback port for the local OpenCode server"
    }
    private val autoBinaryRadioButton = JBRadioButton("Auto detect")
    private val customBinaryRadioButton = JBRadioButton("OpenCode path")
    private val binaryPathField = TextFieldWithBrowseButton().apply {
        textField.columns = 40
        toolTipText = "Path to the opencode executable"
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
    }
    private val detectBinaryButton = JButton("Detect").apply {
        toolTipText = "Auto-detect opencode and fill the path"
        accessibleContext.accessibleName = "Detect OpenCode path"
    }
    private val restartServerButton = JButton("Restart Server", AllIcons.Actions.Restart).apply {
        toolTipText = "Stop and restart the local OpenCode server"
        accessibleContext.accessibleName = "Restart OpenCode server"
    }
    private val viewServerLogButton = JButton("View Server Log", AllIcons.Actions.Show).apply {
        toolTipText = "Show recent OpenCode server output"
        accessibleContext.accessibleName = "View OpenCode server log"
    }
    private val enableServerLogsCheckBox = JBCheckBox("Write server logs to disk")
    private val serverStatusLabel = JBLabel().apply {
        toolTipText = "Current OpenCode server status"
    }
    private val openMostRecentConversationCheckBox = JBCheckBox("Open the most recent conversation for the project on startup")
    private val openFileLinksInIdeCheckBox = JBCheckBox("Enable IDE navigation from OpenCode")
    private val openExternalLinksInBrowserCheckBox = JBCheckBox("Open external HTTP links in the system browser")
    private val enableCodeNavigationCheckBox = JBCheckBox("Also navigate code references in chat")
    private val openDiffsInIdeCheckBox = JBCheckBox("Open diffs in the IDE on Alt+Click")
    private val enableChatFileDropCheckBox = JBCheckBox("Enable file drop and paste into chat")
    private val forceCompactLayoutCheckBox = JBCheckBox("Lock to compact view")
    private val syncThemeWithIdeCheckBox = JBCheckBox("Sync OpenCode color scheme with the IDE theme")
    private val suppressProjectSwitchPromptsCheckBox = JBCheckBox("Suppress project-switch prompts")
    private val mirrorBrowserCursorCheckBox = JBCheckBox("Mirror the web page mouse cursor")
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
    private var lifecycleConnection: com.intellij.util.messages.MessageBusConnection? = null
    private var controlListenersInstalled = false

    private data class CheckBoxSettingBinding(
        val checkBox: JBCheckBox,
        val read: OpenCodeSettingsState.() -> Boolean,
        val write: OpenCodeSettingsState.(Boolean) -> Unit,
        /** When set, a change to this toggle is broadcast as [OpenCodeSettingsListener.uiSettingChanged]. */
        val uiSetting: OpenCodeUiSetting? = null,
    )

    private val checkBoxSettingBindings = listOf(
        CheckBoxSettingBinding(openMostRecentConversationCheckBox, { openMostRecentConversationOnStartup }, { value -> openMostRecentConversationOnStartup = value }),
        CheckBoxSettingBinding(openFileLinksInIdeCheckBox, { openFileLinksInIde }, { value -> openFileLinksInIde = value }, OpenCodeUiSetting.FILE_LINK_NAVIGATION),
        CheckBoxSettingBinding(openExternalLinksInBrowserCheckBox, { openExternalLinksInBrowser }, { value -> openExternalLinksInBrowser = value }, OpenCodeUiSetting.EXTERNAL_LINK_NAVIGATION),
        CheckBoxSettingBinding(enableCodeNavigationCheckBox, { enableCodeNavigation }, { value -> enableCodeNavigation = value }, OpenCodeUiSetting.CODE_NAVIGATION),
        CheckBoxSettingBinding(openDiffsInIdeCheckBox, { openDiffsInIde }, { value -> openDiffsInIde = value }, OpenCodeUiSetting.DIFF_NAVIGATION),
        CheckBoxSettingBinding(enableChatFileDropCheckBox, { enableChatFileDrop }, { value -> enableChatFileDrop = value }, OpenCodeUiSetting.CHAT_FILE_DROP),
        CheckBoxSettingBinding(forceCompactLayoutCheckBox, { forceCompactLayout }, { value -> forceCompactLayout = value }, OpenCodeUiSetting.COMPACT_LAYOUT),
        CheckBoxSettingBinding(syncThemeWithIdeCheckBox, { syncThemeWithIde }, { value -> syncThemeWithIde = value }, OpenCodeUiSetting.IDE_THEME_SYNC),
        CheckBoxSettingBinding(suppressProjectSwitchPromptsCheckBox, { suppressProjectSwitchPrompts }, { value -> suppressProjectSwitchPrompts = value }, OpenCodeUiSetting.PROJECT_SWITCH_PROMPT_SUPPRESSION),
        CheckBoxSettingBinding(mirrorBrowserCursorCheckBox, { mirrorBrowserCursor }, { value -> mirrorBrowserCursor = value }, OpenCodeUiSetting.BROWSER_CURSOR_MIRROR),
        // System notifications need no page interaction: the Kotlin-side event consumer
        // re-checks the setting on every event.
        CheckBoxSettingBinding(enableSystemNotificationsCheckBox, { enableSystemNotifications }, { value -> enableSystemNotifications = value }),
        CheckBoxSettingBinding(enablePermissionNotificationActionsCheckBox, { enablePermissionNotificationActions }, { value -> enablePermissionNotificationActions = value }),
        CheckBoxSettingBinding(showAgentStatusBadgeCheckBox, { showAgentStatusBadge }, { value -> showAgentStatusBadge = value }, OpenCodeUiSetting.AGENT_STATUS_BADGE),
        CheckBoxSettingBinding(autoContinueInterruptedSessionsCheckBox, { autoContinueInterruptedSessions }, { value -> autoContinueInterruptedSessions = value }),
        CheckBoxSettingBinding(waitForIntellijMcpServerCheckBox, { waitForIntellijMcpServer }, { value -> waitForIntellijMcpServer = value }),
        CheckBoxSettingBinding(enableServerLogsCheckBox, { enableServerLogs }, { value -> enableServerLogs = value }),
    )

    override fun getDisplayName(): String = "OpenCode Web Panel"

    override fun createComponent(): JComponent {
        installControlListenersOnce()

        val serverSetupPanel = panel {
            buttonsGroup("OpenCode executable:") {
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
            buttonsGroup("Server port:") {
                row {
                    cell(autoPortRadioButton)
                        .comment("Let OpenCode choose an available loopback port. This is the default.")
                }
                row {
                    cell(fixedPortRadioButton).gap(RightGap.SMALL)
                    cell(fixedPortField)
                        .comment("Use a fixed port on 127.0.0.1. Default: ${OpenCodeSettingsState.DEFAULT_FIXED_PORT}.")
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
            group("Server Lifecycle") {
                row {
                    cell(serverStatusLabel)
                }
                row {
                    cell(restartServerButton)
                }
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
                indent {
                    row {
                        cell(viewServerLogButton)
                            .comment("Show recent OpenCode server output.")
                    }
                }
            }
        }
        val uiSettingsPanel = panel {
            group("Project Startup") {
                row {
                    cell(openMostRecentConversationCheckBox)
                        .comment("When the panel opens, ask the OpenCode server for this project's latest conversation (by last activity) and open it. When off, only bind the project directory and leave the session choice to OpenCode.")
                }
            }
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
                        .comment("Alt+Click a diff in chat or in the changes list to open it in the IDE's diff viewer.")
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
            }
        }
        panel = JBTabbedPane().apply {
            addTab("OpenCode Server Setup", serverSetupPanel)
            addTab("OpenCode UI Settings", uiSettingsPanel)
        }
        reset()
        subscribeToLifecycleChanges()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeSettingsState.getInstance()
        val selectedPortMode = selectedPortMode()
        val passwordModified = password() != savedPassword
        val portModeModified = selectedPortMode != settings.portModeValue()
        val fixedPortModified = fixedPortOrDefault() != OpenCodeSettingsState.sanitizePort(settings.fixedPort)
        val binaryModeModified = selectedBinaryMode() != settings.binaryModeValue()
        val binaryPathModified = binaryPath() != settings.binaryPath.trim()
        val checkBoxSettingsModified = checkBoxSettingBindings.any { it.checkBox.isSelected != it.read(settings) }
        val uiZoomModified = uiZoomPercent() != OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        return passwordModified || portModeModified || fixedPortModified || binaryModeModified || binaryPathModified ||
            checkBoxSettingsModified || uiZoomModified
    }

    override fun apply() {
        val passwordForApply = passwordForApply()
        val settings = OpenCodeSettingsState.getInstance()
        val oldPortMode = settings.portModeValue()
        val oldFixedPort = OpenCodeSettingsState.sanitizePort(settings.fixedPort)
        val oldBinaryMode = settings.binaryModeValue()
        val oldBinaryPath = settings.binaryPath.trim()
        val oldUiZoomPercent = OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        val oldPassword = savedPassword
        val pendingBroadcasts = checkBoxSettingBindings.mapNotNull { binding ->
            val uiSetting = binding.uiSetting ?: return@mapNotNull null
            val newValue = binding.checkBox.isSelected
            if (binding.read(settings) == newValue) return@mapNotNull null
            uiSetting to newValue
        }

        val nextPassword = passwordForApply ?: OpenCodePasswordStore.getInstance().generatePasswordForEditing()
        OpenCodePasswordStore.getInstance().saveBlocking(nextPassword)
        savedPassword = nextPassword
        setPasswordText(nextPassword)
        passwordLoadError = null

        val nextPortMode = selectedPortMode()
        val nextFixedPort = fixedPortOrDefault()
        val nextBinaryMode = selectedBinaryMode()
        val nextBinaryPath = binaryPath()
        val nextUiZoomPercent = uiZoomPercent()
        settings.portMode = nextPortMode.name
        settings.fixedPort = nextFixedPort
        settings.binaryMode = nextBinaryMode.name
        settings.binaryPath = nextBinaryPath
        checkBoxSettingBindings.forEach { it.write(settings, it.checkBox.isSelected) }
        settings.uiZoomPercent = nextUiZoomPercent
        fixedPortField.text = nextFixedPort.toString()
        binaryPathField.text = nextBinaryPath
        updatePasswordHint()
        updatePortControls()
        updateBinaryControls()
        updateServerLogControls()
        updateUiDependencyControls()

        if (oldPassword != nextPassword || oldPortMode != nextPortMode || oldFixedPort != nextFixedPort ||
            oldBinaryMode != nextBinaryMode || oldBinaryPath != nextBinaryPath
        ) {
            SharedOpenCodeServerManager.getInstance().stopServer()
            // Restart open panels right away; a stopped server would otherwise stay stopped until a
            // new tool-window content is created, leaving existing panels blank.
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .serverRestartRequested()
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
    }

    override fun reset() {
        val settings = OpenCodeSettingsState.getInstance()
        when (settings.portModeValue()) {
            OpenCodePortMode.AUTO -> autoPortRadioButton.isSelected = true
            OpenCodePortMode.FIXED -> fixedPortRadioButton.isSelected = true
        }
        when (settings.binaryModeValue()) {
            OpenCodeBinaryMode.AUTO -> autoBinaryRadioButton.isSelected = true
            OpenCodeBinaryMode.CUSTOM -> customBinaryRadioButton.isSelected = true
        }
        fixedPortField.text = OpenCodeSettingsState.sanitizePort(settings.fixedPort).toString()
        binaryPathField.text = settings.binaryPath.trim()
        checkBoxSettingBindings.forEach { it.checkBox.isSelected = it.read(settings) }
        uiZoomSpinner.value = OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        loadPasswordField()
        updatePasswordHint()
        updatePortControls()
        updateBinaryControls()
        updateServerLogControls()
        updateUiDependencyControls()
        updateServerStatus()
    }

    override fun disposeUIResources() {
        panel = null
        passwordLoadGeneration.incrementAndGet()
        lifecycleConnection?.disconnect()
        lifecycleConnection = null
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
            add(autoPortRadioButton)
            add(fixedPortRadioButton)
        }
        ButtonGroup().apply {
            add(autoBinaryRadioButton)
            add(customBinaryRadioButton)
        }
        autoPortRadioButton.addItemListener { updatePortControls() }
        fixedPortRadioButton.addItemListener { updatePortControls() }
        autoBinaryRadioButton.addItemListener { updateBinaryControls() }
        customBinaryRadioButton.addItemListener { updateBinaryControls() }
        showPasswordButton.addActionListener { updatePasswordVisibility() }
        copyPasswordButton.addActionListener { copyPassword() }
        generatePasswordButton.addActionListener {
            setPasswordText(OpenCodePasswordStore.getInstance().generatePasswordForEditing())
            updatePasswordHint()
        }
        detectBinaryButton.addActionListener { detectBinaryPath() }
        restartServerButton.addActionListener { restartServer() }
        viewServerLogButton.addActionListener { showServerLog() }
        enableServerLogsCheckBox.addItemListener { updateServerLogControls() }
        openFileLinksInIdeCheckBox.addItemListener { updateUiDependencyControls() }
        enableSystemNotificationsCheckBox.addItemListener { updateUiDependencyControls() }
    }

    private fun subscribeToLifecycleChanges() {
        lifecycleConnection?.disconnect()
        lifecycleConnection = ApplicationManager.getApplication().messageBus.connect().also { connection ->
            connection.subscribe(
                OpenCodeServerLifecycleListener.TOPIC,
                object : OpenCodeServerLifecycleListener {
                    override fun stateChanged(state: OpenCodeServerLifecycleState) {
                        ApplicationManager.getApplication().invokeLater {
                            if (panel != null) updateServerStatus()
                        }
                    }
                },
            )
        }
    }

    private fun updateServerStatus() {
        val serverManager = SharedOpenCodeServerManager.getInstance()
        val state = serverManager.getLifecycleState()
        val serverUrl = serverManager.getServerUrl()
        val detail = if (state == OpenCodeServerLifecycleState.RUNNING && !serverUrl.isNullOrBlank()) {
            val version = serverManager.getServerVersion()?.takeIf { it.isNotBlank() }
                ?.let { " (OpenCode $it)" }
                .orEmpty()
            ": $serverUrl$version"
        } else {
            ""
        }
        serverStatusLabel.text = formatOpenCodeServerLifecycleStatusText(state, detail)
    }

    private fun password(): String? = String(passwordField.password).trim().ifBlank { null }

    private fun passwordForApply(): String? {
        passwordLoadGeneration.incrementAndGet()
        val currentPassword = password()
        passwordLoading = false
        if (currentPassword != null) {
            passwordLoadError = null
            return currentPassword
        }
        val store = OpenCodePasswordStore.getInstance()
        // Prefer the already-loaded value to avoid a blocking secure-storage read on the EDT;
        // only fall back to a fresh read when nothing has been cached yet.
        val result = store.cachedPassword()?.let { Result.success(it) }
            ?: runCatching { store.loadFreshBlocking() }
        val loadedPassword = result.getOrElse { error ->
            passwordLoadError = "Could not load password from secure storage: ${error.message ?: error::class.java.simpleName}"
            updatePasswordHint()
            throw ConfigurationException(passwordLoadError!!)
        }
        savedPassword = loadedPassword
        setPasswordText(loadedPassword)
        passwordLoadError = null
        updatePasswordHint()
        return loadedPassword
    }

    private fun selectedPortMode(): OpenCodePortMode {
        return if (fixedPortRadioButton.isSelected) OpenCodePortMode.FIXED else OpenCodePortMode.AUTO
    }

    private fun fixedPortOrDefault(): Int {
        return OpenCodeSettingsState.sanitizePort(fixedPortField.text.trim().toIntOrNull() ?: OpenCodeSettingsState.DEFAULT_FIXED_PORT)
    }

    private fun selectedBinaryMode(): OpenCodeBinaryMode {
        return if (customBinaryRadioButton.isSelected) OpenCodeBinaryMode.CUSTOM else OpenCodeBinaryMode.AUTO
    }

    private fun binaryPath(): String = binaryPathField.text.trim()

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
            }, ModalityState.stateForComponent(panel ?: passwordField))
        }
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

    private fun restartServer() {
        if (!confirmOpenCodeServerRestart(null)) return
        ApplicationManager.getApplication().messageBus
            .syncPublisher(OpenCodeSettingsListener.TOPIC)
            .serverRestartRequested()
    }

    private fun showServerLog() {
        val serverManager = SharedOpenCodeServerManager.getInstance()
        val logFile = serverManager.getServerLogFile()
        if (logFile == null) {
            Messages.showErrorDialog(panel ?: viewServerLogButton, "No server log available yet.", "Open Server Log")
            return
        }
        try {
            java.awt.Desktop.getDesktop().open(logFile.toFile())
        } catch (e: Exception) {
            Messages.showErrorDialog(
                panel ?: restartServerButton,
                "Could not open server log: ${e.message ?: e::class.java.simpleName}",
                "Open Server Log",
            )
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

    private fun updatePortControls() {
        fixedPortField.isEnabled = fixedPortRadioButton.isSelected
    }

    private fun updateBinaryControls() {
        binaryPathField.isEnabled = customBinaryRadioButton.isSelected
        detectBinaryButton.isEnabled = customBinaryRadioButton.isSelected
    }

    private fun updateServerLogControls() {
        viewServerLogButton.isEnabled = enableServerLogsCheckBox.isSelected
    }

    private fun updateUiDependencyControls() {
        enableCodeNavigationCheckBox.isEnabled = openFileLinksInIdeCheckBox.isSelected
        enablePermissionNotificationActionsCheckBox.isEnabled = enableSystemNotificationsCheckBox.isSelected
    }
}
