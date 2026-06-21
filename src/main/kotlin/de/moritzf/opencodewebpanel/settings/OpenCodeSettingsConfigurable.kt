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
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.toolWindow.SharedOpenCodeServerManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicLong
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
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
        isFocusable = false
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
    private val serverStatusLabel = JBLabel().apply {
        toolTipText = "Current OpenCode server status"
    }
    private val openMostRecentConversationCheckBox = JBCheckBox("Open the most recent conversation for the project on startup")
    private val openFileLinksInIdeCheckBox = JBCheckBox("Open local file links in the IDE")
    private val openExternalLinksInBrowserCheckBox = JBCheckBox("Open external HTTP links in the system browser")
    private val enableCodeNavigationCheckBox = JBCheckBox("Enable click-to-navigate on code references in chat")
    private val enableChatFileDropCheckBox = JBCheckBox("Enable file drag and drop into chat")
    private val forceCompactLayoutCheckBox = JBCheckBox("Lock to compact view")
    private val syncThemeWithIdeCheckBox = JBCheckBox("Sync OpenCode color scheme with the IDE theme")
    private val suppressProjectSwitchPromptsCheckBox = JBCheckBox("Suppress project-switch prompts")
    private val enableSystemNotificationsCheckBox = JBCheckBox("Forward OpenCode system notifications to the IDE")
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

    override fun getDisplayName(): String = "OpenCode Web Panel"

    override fun createComponent(): JComponent {
        val portGroup = ButtonGroup().apply {
            add(autoPortRadioButton)
            add(fixedPortRadioButton)
        }
        val binaryGroup = ButtonGroup().apply {
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

        val serverSetupPanel = panel {
            buttonsGroup("Binary:") {
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
            buttonsGroup("Port:") {
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
            row {
                cell(serverStatusLabel)
            }
            row {
                cell(restartServerButton).gap(RightGap.SMALL)
                cell(viewServerLogButton)
            }
        }
        val uiSettingsPanel = panel {
            row("Zoom:") {
                cell(uiZoomSpinner)
                    .comment("Scale the embedded OpenCode UI. Default: ${OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT}%.")
            }
            row {
                cell(openMostRecentConversationCheckBox)
                    .comment("When the tool window opens, restore the project's most recent OpenCode conversation instead of opening a new conversation.")
            }
            row {
                cell(openFileLinksInIdeCheckBox)
                    .comment("Open markdown links that point to workspace-relative, absolute, or file: paths in IntelliJ.")
            }
            row {
                cell(enableCodeNavigationCheckBox)
                    .comment("Click on file names, class names, or code references in chat messages to open them in IntelliJ.")
            }
            row {
                cell(openExternalLinksInBrowserCheckBox)
                    .comment("Open http:// and https:// links outside the local OpenCode app in the system browser instead of navigating the embedded panel.")
            }
            row {
                cell(enableChatFileDropCheckBox)
                    .comment("Allow dropping images, PDFs, and text files into the embedded OpenCode chat input.")
            }
            row {
                cell(forceCompactLayoutCheckBox)
                    .comment("Prevent the OpenCode UI from switching to a wide desktop layout when the panel is enlarged.")
            }
            row {
                cell(syncThemeWithIdeCheckBox)
                    .comment("Patches the browser's prefers-color-scheme media query to match the IntelliJ theme. Only affects OpenCode when its color scheme is set to System.")
            }
            row {
                cell(suppressProjectSwitchPromptsCheckBox)
                    .comment("Hide OpenCode notifications that ask this panel to switch to another session or project for approval.")
            }
            row {
                cell(enableSystemNotificationsCheckBox)
                    .comment("Show OpenCode browser notifications as IntelliJ notifications and route notification clicks back to OpenCode.")
            }
            row {
                cell(waitForIntellijMcpServerCheckBox)
                    .comment("If IntelliJ's MCP server is enabled, wait briefly for it to report that it is running before launching OpenCode.")
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
        val uiSettingsModified = openMostRecentConversationCheckBox.isSelected != settings.openMostRecentConversationOnStartup ||
            openFileLinksInIdeCheckBox.isSelected != settings.openFileLinksInIde ||
            openExternalLinksInBrowserCheckBox.isSelected != settings.openExternalLinksInBrowser ||
            enableCodeNavigationCheckBox.isSelected != settings.enableCodeNavigation ||
            enableChatFileDropCheckBox.isSelected != settings.enableChatFileDrop ||
            forceCompactLayoutCheckBox.isSelected != settings.forceCompactLayout ||
            syncThemeWithIdeCheckBox.isSelected != settings.syncThemeWithIde ||
            suppressProjectSwitchPromptsCheckBox.isSelected != settings.suppressProjectSwitchPrompts ||
            enableSystemNotificationsCheckBox.isSelected != settings.enableSystemNotifications ||
            waitForIntellijMcpServerCheckBox.isSelected != settings.waitForIntellijMcpServer ||
            uiZoomPercent() != OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        return passwordModified || portModeModified || fixedPortModified || binaryModeModified || binaryPathModified || uiSettingsModified
    }

    override fun apply() {
        val passwordForApply = passwordForApply()
        val settings = OpenCodeSettingsState.getInstance()
        val oldPortMode = settings.portModeValue()
        val oldFixedPort = OpenCodeSettingsState.sanitizePort(settings.fixedPort)
        val oldBinaryMode = settings.binaryModeValue()
        val oldBinaryPath = settings.binaryPath.trim()
        val oldUiZoomPercent = OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        val oldOpenFileLinksInIde = settings.openFileLinksInIde
        val oldOpenExternalLinksInBrowser = settings.openExternalLinksInBrowser
        val oldEnableCodeNavigation = settings.enableCodeNavigation
        val oldForceCompactLayout = settings.forceCompactLayout
        val oldSyncThemeWithIde = settings.syncThemeWithIde
        val oldSuppressProjectSwitchPrompts = settings.suppressProjectSwitchPrompts
        val oldEnableSystemNotifications = settings.enableSystemNotifications
        val oldPassword = savedPassword

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
        settings.openMostRecentConversationOnStartup = openMostRecentConversationCheckBox.isSelected
        settings.openFileLinksInIde = openFileLinksInIdeCheckBox.isSelected
        settings.openExternalLinksInBrowser = openExternalLinksInBrowserCheckBox.isSelected
        settings.enableCodeNavigation = enableCodeNavigationCheckBox.isSelected
        settings.enableChatFileDrop = enableChatFileDropCheckBox.isSelected
        settings.forceCompactLayout = forceCompactLayoutCheckBox.isSelected
        settings.syncThemeWithIde = syncThemeWithIdeCheckBox.isSelected
        settings.suppressProjectSwitchPrompts = suppressProjectSwitchPromptsCheckBox.isSelected
        settings.enableSystemNotifications = enableSystemNotificationsCheckBox.isSelected
        settings.waitForIntellijMcpServer = waitForIntellijMcpServerCheckBox.isSelected
        settings.uiZoomPercent = nextUiZoomPercent
        fixedPortField.text = nextFixedPort.toString()
        binaryPathField.text = nextBinaryPath
        updatePasswordHint()
        updatePortControls()
        updateBinaryControls()

        if (oldPassword != nextPassword || oldPortMode != nextPortMode || oldFixedPort != nextFixedPort ||
            oldBinaryMode != nextBinaryMode || oldBinaryPath != nextBinaryPath
        ) {
            SharedOpenCodeServerManager.getInstance().stopServer()
        }
        if (oldUiZoomPercent != nextUiZoomPercent) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .uiZoomChanged(nextUiZoomPercent)
        }
        if (oldOpenFileLinksInIde != settings.openFileLinksInIde) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .fileLinkNavigationChanged(settings.openFileLinksInIde)
        }
        if (oldOpenExternalLinksInBrowser != settings.openExternalLinksInBrowser) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .externalLinkNavigationChanged(settings.openExternalLinksInBrowser)
        }
        if (oldEnableCodeNavigation != settings.enableCodeNavigation) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .codeNavigationChanged(settings.enableCodeNavigation)
        }
        if (oldForceCompactLayout != settings.forceCompactLayout) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .compactLayoutChanged(settings.forceCompactLayout)
        }
        if (oldSyncThemeWithIde != settings.syncThemeWithIde) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .ideThemeSyncChanged(settings.syncThemeWithIde)
        }
        if (oldSuppressProjectSwitchPrompts != settings.suppressProjectSwitchPrompts) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .projectSwitchPromptSuppressionChanged(settings.suppressProjectSwitchPrompts)
        }
        if (oldEnableSystemNotifications != settings.enableSystemNotifications) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .systemNotificationsChanged(settings.enableSystemNotifications)
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
        openMostRecentConversationCheckBox.isSelected = settings.openMostRecentConversationOnStartup
        openFileLinksInIdeCheckBox.isSelected = settings.openFileLinksInIde
        openExternalLinksInBrowserCheckBox.isSelected = settings.openExternalLinksInBrowser
        enableCodeNavigationCheckBox.isSelected = settings.enableCodeNavigation
        enableChatFileDropCheckBox.isSelected = settings.enableChatFileDrop
        forceCompactLayoutCheckBox.isSelected = settings.forceCompactLayout
        syncThemeWithIdeCheckBox.isSelected = settings.syncThemeWithIde
        suppressProjectSwitchPromptsCheckBox.isSelected = settings.suppressProjectSwitchPrompts
        enableSystemNotificationsCheckBox.isSelected = settings.enableSystemNotifications
        waitForIntellijMcpServerCheckBox.isSelected = settings.waitForIntellijMcpServer
        uiZoomSpinner.value = OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        loadPasswordField()
        updatePasswordHint()
        updatePortControls()
        updateBinaryControls()
        updateServerStatus()
    }

    override fun disposeUIResources() {
        panel = null
        passwordLoadGeneration.incrementAndGet()
        lifecycleConnection?.disconnect()
        lifecycleConnection = null
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
        serverStatusLabel.text = formatLifecycleStatusText(state, serverManager.getServerUrl())
    }

    private fun formatLifecycleStatusText(state: OpenCodeServerLifecycleState, serverUrl: String?): String {
        val detail = if (state == OpenCodeServerLifecycleState.RUNNING && !serverUrl.isNullOrBlank()) {
            ": ${escapeStatusHtml(serverUrl)}"
        } else {
            ""
        }
        return "<html><span style=\"color: ${state.colorHex}\">&#9679;</span>&nbsp;Server: ${state.displayLabel}$detail</html>"
    }

    private fun escapeStatusHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
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
        val result = runCatching { OpenCodePasswordStore.getInstance().loadFreshBlocking() }
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
        SharedOpenCodeServerManager.getInstance().stopServer()
    }

    private fun showServerLog() {
        val serverManager = SharedOpenCodeServerManager.getInstance()
        val logFile = serverManager.getServerLogFile()
        if (logFile == null) {
            Messages.showInfoMessage(panel ?: viewServerLogButton, "No server log available yet.", "Open Server Log")
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
}
