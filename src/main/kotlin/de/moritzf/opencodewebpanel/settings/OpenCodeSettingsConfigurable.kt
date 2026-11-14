package de.moritzf.opencodewebpanel.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import de.moritzf.opencodewebpanel.configuration.OpenCodeBinaryMode
import de.moritzf.opencodewebpanel.configuration.OpenCodePasswordStore
import de.moritzf.opencodewebpanel.configuration.OpenCodeProxyMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeRestartScope
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.configuration.OpenCodeUiSetting
import de.moritzf.opencodewebpanel.server.OpenCodeExecutableProbe
import de.moritzf.opencodewebpanel.server.OpenCodeExecutableProbes
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxProcessRunner
import de.moritzf.opencodewebpanel.ui.confirmOpenCodeServersRestart
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.SpinnerNumberModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

class OpenCodeSettingsConfigurable : Configurable, Configurable.NoMargin {
    private var panel: JComponent? = null
    private val passwordField =
        JBPasswordField().apply {
            columns = 40
            toolTipText = "Password used for the local OpenCode web server"
        }
    private val showPasswordButton =
        JToggleButton(AllIcons.Actions.Show).apply {
            toolTipText = "Show password"
            accessibleContext.accessibleName = "Show password"
        }
    private val copyPasswordButton =
        JButton("Copy", AllIcons.Actions.Copy).apply {
            toolTipText = "Copy password to clipboard"
            accessibleContext.accessibleName = "Copy password"
        }
    private val copyPasswordFeedbackTimer =
        Timer(1_500) {
                copyPasswordButton.apply {
                    text = "Copy"
                    icon = AllIcons.Actions.Copy
                    toolTipText = "Copy password to clipboard"
                    accessibleContext.accessibleName = "Copy password"
                }
            }
            .apply { isRepeats = false }
    private val generatePasswordButton =
        JButton("Generate").apply {
            toolTipText = "Generate a new password; apply settings to save it"
            accessibleContext.accessibleName = "Generate password"
        }

    private val autoBinaryRadioButton = JBRadioButton("Auto detect")
    private val customBinaryRadioButton = JBRadioButton("Custom path")
    private val autoSbxRadioButton = JBRadioButton("Auto detect")
    private val customSbxRadioButton = JBRadioButton("Custom path")
    private val binaryStatusLabel = JBLabel().apply { setAllowAutoWrapping(true) }
    private val sbxStatusLabel = JBLabel().apply { setAllowAutoWrapping(true) }
    private val probeTimer =
        Timer(PROBE_DELAY_MILLIS) { probeExecutables() }.apply { isRepeats = false }
    private val probeGeneration = AtomicLong(0)
    private var detectedOpenCodePath: String? = null
    private var detectedSbxPath: String? = null
    private var prefilledOpenCodePath: String? = null
    private var prefilledSbxPath: String? = null
    private val sbxPathField =
        TextFieldWithBrowseButton().apply {
            textField.columns = 40
            toolTipText = "Path to the sbx executable"
            // The chooser stays usable in auto mode as a way to fill the path; only typing is
            // custom-only. Never disable the whole component: disabling hides the browse button
            // completely and it is not reliably shown again after re-enabling.
            addBrowseFolderListener(
                null,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
            )
            addActionListener { customSbxRadioButton.isSelected = true }
        }
    private val sbxLoginHintLabel =
        JBLabel(
            "Sign in once with sbx login in a terminal; the panel does not show Docker's sign-in.",
        )
    private val sbxPolicyHintLabel = JBLabel("No sandbox network policy yet.")
    private val initSbxPolicyButton =
        JButton("Set Up Default Network Policy…").apply {
            toolTipText =
                "Runs Docker's balanced policy once on this computer so sandboxes can reach typical AI APIs and package sites"
            accessibleContext.accessibleName = "Set up default sandbox network policy"
        }
    private val binaryPathField =
        TextFieldWithBrowseButton().apply {
            textField.columns = 40
            toolTipText = "Path to the opencode executable"
            addBrowseFolderListener(
                null,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
            )
            addActionListener { customBinaryRadioButton.isSelected = true }
        }
    private val ideProxyRadioButton = JBRadioButton("Use IDE HTTP Proxy")
    private val environmentProxyRadioButton = JBRadioButton("Use environment variables")
    private val noProxyRadioButton = JBRadioButton("No proxy")
    // Settings search indexes labels, not comments: labels carry the words people search for.
    private val enableServerLogsCheckBox = JBCheckBox("Write server logs to disk")
    private val openFileLinksInIdeCheckBox = JBCheckBox("Open file links in the IDE")
    private val openExternalLinksInBrowserCheckBox =
        JBCheckBox("Open web links in the system browser")
    private val enableCodeNavigationCheckBox =
        JBCheckBox("Also open file, class, and code references in chat")
    private val openDiffsInIdeCheckBox =
        JBCheckBox("Open diffs in the IDE with Ctrl/Cmd+Click or Alt+Click")
    private val enableChatFileDropCheckBox = JBCheckBox("Drop and paste files into chat")
    private val forceCompactLayoutCheckBox =
        JBCheckBox("Keep the compact (mobile) layout at any panel width")
    private val showResponseModelInCompactLayoutCheckBox =
        JBCheckBox("Show agent and model on responses in the compact layout (OpenCode 2)")
    private val hideWebsiteButtonCheckBox = JBCheckBox("Hide OpenCode's floating website button")
    private val fasterPathHoverPreviewCheckBox =
        JBCheckBox("Show path previews on tabs, projects, and sessions faster")
    private val syncThemeWithIdeCheckBox =
        JBCheckBox("Match OpenCode's color scheme (dark/light) to the IDE theme")
    private val suppressProjectSwitchPromptsCheckBox =
        JBCheckBox("Hide prompts to switch to another session or project")
    private val mirrorBrowserCursorCheckBox =
        JBCheckBox("Show the page's mouse cursors (text, link, resize)")
    private val recoverStalledEventStreamCheckBox =
        JBCheckBox("Reconnect after sleep or a network change (OpenCode 1.x)")
    private val recoverFailedChunkLoadsCheckBox =
        JBCheckBox("Reload the page when part of it fails to load")
    private val recoverStalledRendererCheckBox =
        JBCheckBox("Reload the panel when the embedded browser stops responding")
    private val notifyOpenCodeUpdatesCheckBox =
        JBCheckBox("Show when a newer OpenCode release is available")
    private val enableSystemNotificationsCheckBox =
        JBCheckBox("Show OpenCode notifications in the IDE")
    private val enablePermissionNotificationActionsCheckBox =
        JBCheckBox("With Allow and Deny buttons on permission requests")
    private val showAgentStatusBadgeCheckBox =
        JBCheckBox("Show agent status on the tool window icon")
    private val warnForeignSessionCheckBox =
        JBCheckBox("Warn when a conversation belongs to another folder or workspace")
    private val autoContinueInterruptedSessionsCheckBox =
        JBCheckBox("Continue interrupted conversations after a restart")
    private val waitForIntellijMcpServerCheckBox =
        JBCheckBox("Wait for the IntelliJ MCP server before starting OpenCode")
    private val uiZoomSpinner =
        JSpinner(
                SpinnerNumberModel(
                    OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT,
                    OpenCodeSettingsState.MIN_UI_ZOOM_PERCENT,
                    OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT,
                    10,
                ),
            )
            .apply {
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
    private val policyCheckGeneration = AtomicLong(0)
    private var policyStatus = SbxPolicyStatus.UNKNOWN
    private var policyCheckDetail: String? = null
    private var policyCheckedExecutable: String? = null
    private var resetting = false
    private var pendingTab: OpenCodeSettingsTab? = null

    private data class CheckBoxSettingBinding(
        val checkBox: JBCheckBox,
        /** Where the checkbox is laid out; Restore defaults resets the Panel tab's. */
        val tab: OpenCodeSettingsTab,
        val read: OpenCodeSettingsState.() -> Boolean,
        val write: OpenCodeSettingsState.(Boolean) -> Unit,
        /**
         * When set, a change to this toggle is broadcast as
         * [OpenCodeSettingsListener.uiSettingChanged].
         */
        val uiSetting: OpenCodeUiSetting? = null,
    )

    private val checkBoxSettingBindings =
        listOf(
            CheckBoxSettingBinding(
                openFileLinksInIdeCheckBox,
                OpenCodeSettingsTab.PANEL,
                { openFileLinksInIde },
                { value -> openFileLinksInIde = value },
                OpenCodeUiSetting.FILE_LINK_NAVIGATION,
            ),
            CheckBoxSettingBinding(
                openExternalLinksInBrowserCheckBox,
                OpenCodeSettingsTab.PANEL,
                { openExternalLinksInBrowser },
                { value -> openExternalLinksInBrowser = value },
                OpenCodeUiSetting.EXTERNAL_LINK_NAVIGATION,
            ),
            CheckBoxSettingBinding(
                enableCodeNavigationCheckBox,
                OpenCodeSettingsTab.PANEL,
                { enableCodeNavigation },
                { value -> enableCodeNavigation = value },
                OpenCodeUiSetting.CODE_NAVIGATION,
            ),
            CheckBoxSettingBinding(
                openDiffsInIdeCheckBox,
                OpenCodeSettingsTab.PANEL,
                { openDiffsInIde },
                { value -> openDiffsInIde = value },
                OpenCodeUiSetting.DIFF_NAVIGATION,
            ),
            CheckBoxSettingBinding(
                enableChatFileDropCheckBox,
                OpenCodeSettingsTab.PANEL,
                { enableChatFileDrop },
                { value -> enableChatFileDrop = value },
                OpenCodeUiSetting.CHAT_FILE_DROP,
            ),
            CheckBoxSettingBinding(
                forceCompactLayoutCheckBox,
                OpenCodeSettingsTab.PANEL,
                { forceCompactLayout },
                { value -> forceCompactLayout = value },
                OpenCodeUiSetting.COMPACT_LAYOUT,
            ),
            CheckBoxSettingBinding(
                showResponseModelInCompactLayoutCheckBox,
                OpenCodeSettingsTab.PANEL,
                { showResponseModelInCompactLayout },
                { value -> showResponseModelInCompactLayout = value },
                OpenCodeUiSetting.RESPONSE_MODEL_LABEL,
            ),
            CheckBoxSettingBinding(
                hideWebsiteButtonCheckBox,
                OpenCodeSettingsTab.PANEL,
                { hideWebsiteButton },
                { value -> hideWebsiteButton = value },
                OpenCodeUiSetting.HIDE_WEBSITE_BUTTON,
            ),
            CheckBoxSettingBinding(
                fasterPathHoverPreviewCheckBox,
                OpenCodeSettingsTab.PANEL,
                { fasterPathHoverPreview },
                { value -> fasterPathHoverPreview = value },
                OpenCodeUiSetting.PATH_HOVER_PREVIEW,
            ),
            CheckBoxSettingBinding(
                syncThemeWithIdeCheckBox,
                OpenCodeSettingsTab.PANEL,
                { syncThemeWithIde },
                { value -> syncThemeWithIde = value },
                OpenCodeUiSetting.IDE_THEME_SYNC,
            ),
            CheckBoxSettingBinding(
                suppressProjectSwitchPromptsCheckBox,
                OpenCodeSettingsTab.PANEL,
                { suppressProjectSwitchPrompts },
                { value -> suppressProjectSwitchPrompts = value },
                OpenCodeUiSetting.PROJECT_SWITCH_PROMPT_SUPPRESSION,
            ),
            CheckBoxSettingBinding(
                mirrorBrowserCursorCheckBox,
                OpenCodeSettingsTab.PANEL,
                { mirrorBrowserCursor },
                { value -> mirrorBrowserCursor = value },
                OpenCodeUiSetting.BROWSER_CURSOR_MIRROR,
            ),
            CheckBoxSettingBinding(
                recoverStalledEventStreamCheckBox,
                OpenCodeSettingsTab.PANEL,
                { recoverStalledEventStream },
                { value -> recoverStalledEventStream = value },
                OpenCodeUiSetting.EVENT_STREAM_WATCHDOG,
            ),
            CheckBoxSettingBinding(
                recoverFailedChunkLoadsCheckBox,
                OpenCodeSettingsTab.PANEL,
                { recoverFailedChunkLoads },
                { value -> recoverFailedChunkLoads = value },
                OpenCodeUiSetting.CHUNK_LOAD_RECOVERY,
            ),
            CheckBoxSettingBinding(
                recoverStalledRendererCheckBox,
                OpenCodeSettingsTab.PANEL,
                { recoverStalledRenderer },
                { value -> recoverStalledRenderer = value },
                OpenCodeUiSetting.RENDERER_WATCHDOG,
            ),
            // System notifications need no page interaction: the Kotlin-side event consumer
            // re-checks the setting on every event.
            CheckBoxSettingBinding(
                notifyOpenCodeUpdatesCheckBox,
                OpenCodeSettingsTab.SERVER,
                { notifyOpenCodeUpdates },
                { value -> notifyOpenCodeUpdates = value },
            ),
            CheckBoxSettingBinding(
                enableSystemNotificationsCheckBox,
                OpenCodeSettingsTab.PANEL,
                { enableSystemNotifications },
                { value -> enableSystemNotifications = value },
            ),
            CheckBoxSettingBinding(
                enablePermissionNotificationActionsCheckBox,
                OpenCodeSettingsTab.PANEL,
                { enablePermissionNotificationActions },
                { value -> enablePermissionNotificationActions = value },
            ),
            CheckBoxSettingBinding(
                showAgentStatusBadgeCheckBox,
                OpenCodeSettingsTab.PANEL,
                { showAgentStatusBadge },
                { value -> showAgentStatusBadge = value },
                OpenCodeUiSetting.AGENT_STATUS_BADGE,
            ),
            CheckBoxSettingBinding(
                warnForeignSessionCheckBox,
                OpenCodeSettingsTab.PANEL,
                { warnForeignSession },
                { value -> warnForeignSession = value },
                OpenCodeUiSetting.FOREIGN_SESSION_WARNING,
            ),
            CheckBoxSettingBinding(
                autoContinueInterruptedSessionsCheckBox,
                OpenCodeSettingsTab.PANEL,
                { autoContinueInterruptedSessions },
                { value -> autoContinueInterruptedSessions = value },
            ),
            CheckBoxSettingBinding(
                waitForIntellijMcpServerCheckBox,
                OpenCodeSettingsTab.SERVER,
                { waitForIntellijMcpServer },
                { value -> waitForIntellijMcpServer = value },
            ),
            CheckBoxSettingBinding(
                enableServerLogsCheckBox,
                OpenCodeSettingsTab.SERVER,
                { enableServerLogs },
                { value -> enableServerLogs = value },
            ),
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
                )
                .also { panel = it }
        }
    }

    private fun createSettingsComponent(): JComponent {
        installControlListenersOnce()

        val serverPanel = panel {
            row {
                comment(
                    "Applies to every project; the executable and HTTP Proxy only to projects " +
                        "on the Host CLI runtime, chosen in each project's " +
                        "<a href=\"project\">project settings</a>.",
                    action = HyperlinkEventAction { openProjectSettings() },
                )
            }
            group("OpenCode Executable") {
                buttonsGroup {
                    row {
                        cell(autoBinaryRadioButton)
                            .comment(
                                "Searches PATH and common install locations (Homebrew, npm, bun, Windows installers).",
                            )
                    }
                    row {
                        cell(customBinaryRadioButton).gap(RightGap.SMALL)
                        cell(binaryPathField).resizableColumn().align(AlignX.FILL)
                    }
                    row { cell(binaryStatusLabel).resizableColumn().align(AlignX.FILL) }
                }
            }
            group("Authentication") {
                row("Password:") {
                    cell(passwordField).resizableColumn().align(AlignX.FILL).gap(RightGap.SMALL)
                    cell(showPasswordButton).gap(RightGap.SMALL)
                    cell(copyPasswordButton).gap(RightGap.SMALL)
                    cell(generatePasswordButton)
                }
                row { cell(hintLabel) }
                row {
                    comment(
                        "Protects every OpenCode server the panel starts, Host CLI and Docker Sandbox. Stored in the IDE's password safe.",
                    )
                }
            }
            group("HTTP Proxy") {
                buttonsGroup {
                    row {
                        cell(ideProxyRadioButton)
                            .comment(
                                "Includes auto-detect and PAC; PAC is resolved for a generic HTTPS address.",
                            )
                    }
                    row {
                        cell(environmentProxyRadioButton)
                            .comment(
                                "HTTP_PROXY and HTTPS_PROXY from the environment the IDE started with.",
                            )
                    }
                    row { cell(noProxyRadioButton).comment("Provider traffic goes out directly.") }
                }
                row {
                    comment(
                        "Host CLI only. Docker Sandboxes route traffic through Docker's gateway; " +
                            "configure a company proxy in Docker Sandboxes.",
                    )
                }
            }
            group("Startup and Logs") {
                row {
                    cell(waitForIntellijMcpServerCheckBox)
                        .comment(
                            "Only when IntelliJ's MCP server is enabled; waits briefly so OpenCode finds its tools.",
                        )
                }
                row {
                    cell(enableServerLogsCheckBox)
                        .comment("In the IDE log directory; old logs are removed automatically.")
                }
            }
            group("Updates") {
                row {
                    cell(notifyOpenCodeUpdatesCheckBox)
                        .comment(
                            "A lightning bolt in the panel's title bar. Click it to upgrade a sandbox or to copy the Host CLI command. Stays within the major version you use.",
                        )
                }
            }
        }
        val sandboxPanel = panel {
            row {
                comment(
                    "For projects that run OpenCode in a Docker Sandbox. Each project chooses " +
                        "its runtime in its <a href=\"project\">project settings</a>.",
                    action = HyperlinkEventAction { openProjectSettings() },
                )
            }
            group("sbx Executable") {
                buttonsGroup {
                    row {
                        cell(autoSbxRadioButton)
                            .comment("Searches PATH plus Homebrew and WinGet locations.")
                    }
                    row {
                        cell(customSbxRadioButton).gap(RightGap.SMALL)
                        cell(sbxPathField).resizableColumn().align(AlignX.FILL)
                    }
                    row { cell(sbxStatusLabel).resizableColumn().align(AlignX.FILL) }
                }
            }
            group("Sign-In") { row { cell(sbxLoginHintLabel) } }
            group("Network Policy") {
                row { cell(sbxPolicyHintLabel) }
                row {
                    cell(initSbxPolicyButton)
                        .comment(
                            "Sandboxes cannot reach the internet until a policy exists. Docker's balanced " +
                                "profile allows common AI APIs and package registries and blocks the rest. It " +
                                "applies to every sandbox on this computer; the plugin never sets it up " +
                                "without asking. <a href=\"$SBX_POLICY_DOCS_URL\">Docker network defaults</a>",
                            action =
                                HyperlinkEventAction { event ->
                                    val href = event.url?.toString() ?: event.description
                                    if (!href.isNullOrBlank()) BrowserUtil.browse(href)
                                },
                        )
                }
            }
        }
        val uiSettingsPanel = panel {
            row {
                comment(
                        "Most of these options change OpenCode's page in the panel. If one clashes " +
                            "with an OpenCode update, turn it off: the page reloads without it.",
                    )
                    .resizableColumn()
                link("Restore defaults") { restorePanelDefaults() }.align(AlignX.RIGHT)
            }
            group("Appearance") {
                row("Zoom:") {
                    cell(uiZoomSpinner).gap(RightGap.SMALL)
                    label("%")
                        .comment("Default: ${OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT}%.")
                }
                row {
                    cell(forceCompactLayoutCheckBox)
                        .comment(
                            "Off: OpenCode switches to its desktop layout and review panel when the panel is wide.",
                        )
                }
                row {
                    cell(showResponseModelInCompactLayoutCheckBox)
                        .comment(
                            "OpenCode 2 leaves them out in the compact layout, including the model an automatic router picked.",
                        )
                }
                row {
                    cell(hideWebsiteButtonCheckBox)
                        .comment("It overlaps the message box in the panel.")
                }
                row {
                    cell(fasterPathHoverPreviewCheckBox)
                        .comment(
                            "After 250 ms instead of OpenCode's 2 s, also on Home rows, so projects with the same name stay distinguishable.",
                        )
                }
                row {
                    cell(syncThemeWithIdeCheckBox)
                        .comment("Applies while OpenCode's color scheme is set to System.")
                }
                row {
                    cell(mirrorBrowserCursorCheckBox)
                        .comment(
                            "The embedded browser does not change the cursor on its own; without this, resize cursors can get stuck.",
                        )
                }
            }
            group("Navigation and Input") {
                row {
                    cell(openFileLinksInIdeCheckBox)
                        .comment("Local file links and changed-file buttons open in the editor.")
                }
                indent { row { cell(enableCodeNavigationCheckBox) } }
                row {
                    cell(openDiffsInIdeCheckBox)
                        .comment(
                            "In chat and in the changes list. F4 jumps from the diff to the file.",
                        )
                }
                row {
                    cell(openExternalLinksInBrowserCheckBox)
                        .comment("Instead of navigating the panel away from OpenCode.")
                }
                row {
                    cell(enableChatFileDropCheckBox)
                        .comment(
                            "Project files become @path references; other files and screenshots become attachments.",
                        )
                }
            }
            group("Notifications and Status") {
                row {
                    cell(enableSystemNotificationsCheckBox)
                        .comment("Clicking one shows its conversation in the panel.")
                }
                indent { row { cell(enablePermissionNotificationActionsCheckBox) } }
                row {
                    cell(showAgentStatusBadgeCheckBox)
                        .comment("While the agent works, and when it waits for your input.")
                }
                row {
                    cell(suppressProjectSwitchPromptsCheckBox)
                        .comment(
                            "OpenCode asks the panel to switch sessions or projects for approvals elsewhere; the panel stays where it is.",
                        )
                }
                row {
                    cell(warnForeignSessionCheckBox)
                        .comment(
                            "An IDE warning and a red border when the open conversation's folder is not this project's OpenCode directory. Opening it is not blocked.",
                        )
                }
            }
            group("Recovery") {
                row {
                    cell(autoContinueInterruptedSessionsCheckBox)
                        .comment(
                            "Sends a continuation prompt to recently active conversations whose last answer was cut off.",
                        )
                }
                row {
                    cell(recoverStalledEventStreamCheckBox)
                        .comment("OpenCode 2 reconnects on its own and is left unchanged.")
                }
                row {
                    cell(recoverFailedChunkLoadsCheckBox)
                        .comment(
                            "Recovers from \"Failed to fetch dynamically imported module\", which otherwise needs a manual reload.",
                        )
                }
                row {
                    cell(recoverStalledRendererCheckBox)
                        .comment(
                            "Reloads the page, then recreates the panel if it stays silent.",
                        )
                }
            }
        }
        val tabs =
            mapOf(
                OpenCodeSettingsTab.SERVER to serverPanel,
                OpenCodeSettingsTab.PANEL to uiSettingsPanel,
                OpenCodeSettingsTab.DOCKER_SANDBOXES to sandboxPanel,
            )
        panel =
            OpenCodeSettingsTabbedPane().apply {
                OpenCodeSettingsTab.entries.forEach { addTab(it.title, tabs.getValue(it)) }
                pendingTab?.let { selectedIndex = it.ordinal }
                pendingTab = null
            }
        reset()
        checkSbxPolicy()
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
        val checkBoxSettingsModified = checkBoxSettingBindings.any {
            it.checkBox.isSelected != it.read(settings)
        }
        val uiZoomModified =
            uiZoomPercent() != OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent)
        return passwordModified ||
            binaryModeModified ||
            binaryPathModified ||
            sbxBinaryModeModified ||
            sbxBinaryPathModified ||
            proxyModified ||
            checkBoxSettingsModified ||
            uiZoomModified
    }

    override fun apply() {
        val settings = OpenCodeSettingsState.getInstance()
        validateChangedExecutables(settings)
        val hostChanged = hostLaunchChanged(settings)
        val sandboxChanged = sandboxLaunchChanged(settings)
        // Before anything is saved, so declining leaves every setting and server unchanged.
        confirmServerRestart(appRestartScope(passwordEdited(), hostChanged, sandboxChanged))
        passwordLoadGeneration.incrementAndGet()
        passwordLoading = false
        val editedPassword = password().takeIf { it != savedPassword }
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
        val scope = appRestartScope(oldPassword != nextPassword, hostChanged, sandboxChanged)
        if (scope != null) {
            val registry = OpenCodeServerBackendRegistry.getInstance()
            // Restart what was in use; a server the user stopped stays stopped until started.
            val inUse =
                registry
                    .backends()
                    .filter {
                        scope.covers(it.backendId) &&
                            it.getLifecycleState() != OpenCodeServerLifecycleState.STOPPED
                    }
                    .mapTo(HashSet()) { it.backendId }
            if (scope != OpenCodeRestartScope.SBX) registry.stopAllNativeBackends()
            if (scope != OpenCodeRestartScope.NATIVE) registry.stopAllSbxBackends()
            ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .serverRestartRequested(scope, inUse)
        }
        if (oldUiZoomPercent != nextUiZoomPercent) {
            ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .uiZoomChanged(nextUiZoomPercent)
        }
        if (pendingBroadcasts.isNotEmpty()) {
            val publisher =
                ApplicationManager.getApplication()
                    .messageBus
                    .syncPublisher(OpenCodeSettingsListener.TOPIC)
            pendingBroadcasts.forEach { (setting, enabled) ->
                publisher.uiSettingChanged(setting, enabled)
            }
        }
        if (oldSystemNotificationsEnabled != settings.enableSystemNotifications) {
            ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .systemNotificationsChanged(settings.enableSystemNotifications)
        }
    }

    override fun reset() {
        val settings = OpenCodeSettingsState.getInstance()
        resetting = true
        try {
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
            prefilledOpenCodePath = null
            prefilledSbxPath = null
        } finally {
            resetting = false
        }
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
        scheduleExecutableProbe()
    }

    override fun disposeUIResources() {
        panel = null
        passwordLoadGeneration.incrementAndGet()
        policyCheckGeneration.incrementAndGet()
        probeGeneration.incrementAndGet()
        probeTimer.stop()
    }

    /**
     * The stored consent flag only says a policy existed once; a policy set up in a terminal, by
     * the organisation, or removed since is only visible live. Read-only: never initializes one.
     */
    private fun checkSbxPolicy() {
        val app = ApplicationManager.getApplication()
        // Building searchable options creates this page headless; never run sbx there.
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return
        val generation = policyCheckGeneration.incrementAndGet()
        val executable = sbxExecutableForChecks()
        policyCheckedExecutable = executable
        // Label-only update: run even while the settings dialog is modal, before it is showing.
        val modality = ModalityState.any()
        policyStatus = SbxPolicyStatus.CHECKING
        policyCheckDetail = null
        updateRuntimeControls()
        app.executeOnPooledThread {
            val resolved = OpenCodeServerProtocol.detectExecutablePath(executable)
            val result = resolved?.let { sbx ->
                runCatching {
                    SbxProcessRunner.run(
                        SbxCli.buildPolicyLsCommand(sbx),
                        emptyMap(),
                        15_000L,
                    )
                }
                    .getOrNull()
            }
            val check = sbxPolicyCheckOf(resolved != null, result)
            app.invokeLater(
                {
                    if (generation != policyCheckGeneration.get() || panel == null) {
                        return@invokeLater
                    }
                    policyStatus = check.status
                    policyCheckDetail = check.detail
                    // Match the start-time check: an existing policy counts as consent, and a
                    // removed one must be noticed before the next sandbox start.
                    when (check.status) {
                        SbxPolicyStatus.PRESENT ->
                            OpenCodeSettingsState.getInstance().sbxNetworkPolicyConsent = true
                        SbxPolicyStatus.ABSENT ->
                            OpenCodeSettingsState.getInstance().sbxNetworkPolicyConsent = false
                        else -> Unit
                    }
                    updateRuntimeControls()
                },
                modality,
            )
        }
    }

    /** The sbx the form selects, applied or not; a blank custom path falls back like a start. */
    private fun sbxExecutableForChecks(): String =
        if (customSbxRadioButton.isSelected) sbxPath().ifBlank { SbxCli.DEFAULT_EXECUTABLE }
        else SbxCli.DEFAULT_EXECUTABLE

    /** A cleared field keeps the stored password, so only a non-blank new value counts. */
    private fun passwordEdited(): Boolean = password().let { it != null && it != savedPassword }

    /**
     * Compares what a start would launch, not the raw fields: a path left in the disabled field
     * under Auto detect changes nothing a server uses.
     */
    private fun hostLaunchChanged(settings: OpenCodeSettingsState): Boolean =
        openCodeExecutableForLaunch() != settings.executablePath() ||
            selectedProxyMode() != settings.proxyModeValue()

    private fun sandboxLaunchChanged(settings: OpenCodeSettingsState): Boolean =
        sbxExecutableForChecks() != settings.sbxExecutablePath()

    private fun openCodeExecutableForLaunch(): String =
        if (customBinaryRadioButton.isSelected) {
            binaryPath().ifBlank { OpenCodeSettingsState.DEFAULT_EXECUTABLE }
        } else {
            OpenCodeSettingsState.DEFAULT_EXECUTABLE
        }

    private fun confirmServerRestart(scope: OpenCodeRestartScope?) {
        if (scope == null || ApplicationManager.getApplication().isUnitTestMode) return
        val interrupted =
            OpenCodeServerBackendRegistry.getInstance().backends().filter {
                scope.covers(it.backendId) && it.getLifecycleState().isInterruptible()
            }
        val host = interrupted.count { OpenCodeServerBackend.isNative(it.backendId) }
        if (!confirmOpenCodeServersRestart(panel, host, interrupted.size - host)) {
            throw applyCancelled()
        }
    }

    private fun resolveAndSavePasswordOffEdt(
        editedPassword: String?,
    ): OpenCodePasswordStore.PasswordUpdate {
        val store = OpenCodePasswordStore.getInstance()
        val operation =
            ThrowableComputable<OpenCodePasswordStore.PasswordUpdate, Exception> {
                store.resolveAndSaveBlocking(editedPassword)
            }
        val app = ApplicationManager.getApplication()
        return try {
            if (app.isDispatchThread && !app.isUnitTestMode) {
                ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously(
                        operation,
                        "Saving OpenCode Password",
                        false,
                        null,
                    )
            } else {
                operation.compute()
            }
        } catch (error: Exception) {
            passwordLoadError =
                "Could not save password to secure storage: ${error.message ?: error::class.java.simpleName}"
            updatePasswordHint()
            throw ConfigurationException(passwordLoadError!!)
        }
    }

    /**
     * The controls are fields of this configurable, so they survive `disposeUIResources()` and a
     * later `createComponent()`; registering listeners per creation would stack duplicates that
     * fire actions (e.g. password generation) multiple times per click.
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
        autoBinaryRadioButton.addItemListener { event ->
            // Back to Auto detect: drop an untouched prefill so the form is unmodified again.
            if (event.stateChange == ItemEvent.SELECTED && !resetting) {
                if (prefilledOpenCodePath != null && binaryPath() == prefilledOpenCodePath) {
                    binaryPathField.text = OpenCodeSettingsState.getInstance().binaryPath.trim()
                }
                prefilledOpenCodePath = null
            }
            updateBinaryControls()
            scheduleExecutableProbe()
        }
        customBinaryRadioButton.addItemListener { event ->
            // Choosing a custom path starts from what auto detection found.
            if (event.stateChange == ItemEvent.SELECTED && !resetting && binaryPath().isEmpty()) {
                detectedOpenCodePath?.let {
                    binaryPathField.text = it
                    prefilledOpenCodePath = it
                }
            }
            updateBinaryControls()
            scheduleExecutableProbe()
        }
        autoSbxRadioButton.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED && !resetting) {
                if (prefilledSbxPath != null && sbxPath() == prefilledSbxPath) {
                    sbxPathField.text = OpenCodeSettingsState.getInstance().sbxBinaryPath.trim()
                }
                prefilledSbxPath = null
            }
            updateSbxControls()
            scheduleExecutableProbe()
        }
        customSbxRadioButton.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED && !resetting && sbxPath().isEmpty()) {
                detectedSbxPath?.let {
                    sbxPathField.text = it
                    prefilledSbxPath = it
                }
            }
            updateSbxControls()
            scheduleExecutableProbe()
        }
        // Probe typed paths once typing ends (focus leaves the field), not on every pause: a
        // partial path could resolve to an unrelated program and run it. Browsing and prefills
        // change the text without focus and probe right away.
        for (field in listOf(binaryPathField.textField, sbxPathField.textField)) {
            field.document.addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        if (!field.isFocusOwner) scheduleExecutableProbe()
                    }
                },
            )
            field.addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) = scheduleExecutableProbe()
                },
            )
        }
        showPasswordButton.addActionListener { updatePasswordVisibility() }
        copyPasswordButton.addActionListener { copyPassword() }
        generatePasswordButton.addActionListener {
            setPasswordText(OpenCodePasswordStore.getInstance().generatePasswordForEditing())
            updatePasswordHint()
        }
        initSbxPolicyButton.addActionListener { consentToSbxPolicy() }
        openFileLinksInIdeCheckBox.addItemListener { updateUiDependencyControls() }
        enableSystemNotificationsCheckBox.addItemListener { updateUiDependencyControls() }
    }

    private fun password(): String? = String(passwordField.password).trim().ifBlank { null }

    private fun selectedBinaryMode(): OpenCodeBinaryMode {
        return if (customBinaryRadioButton.isSelected) OpenCodeBinaryMode.CUSTOM
        else OpenCodeBinaryMode.AUTO
    }

    private fun binaryPath(): String = binaryPathField.text.trim()

    private fun selectedSbxBinaryMode(): OpenCodeBinaryMode {
        return if (customSbxRadioButton.isSelected) OpenCodeBinaryMode.CUSTOM
        else OpenCodeBinaryMode.AUTO
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
        return OpenCodeSettingsState.sanitizeUiZoomPercent(
            (uiZoomSpinner.value as? Number)?.toInt()
                ?: OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT,
        )
    }

    /** Searchable options are built headless: their index must hold labels, not live status. */
    private fun buildingSearchIndex(): Boolean {
        val app = ApplicationManager.getApplication()
        return app.isHeadlessEnvironment && !app.isUnitTestMode
    }

    private fun loadPasswordField() {
        if (buildingSearchIndex()) return
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
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (generation != passwordLoadGeneration.get()) return@invokeLater
                        passwordLoading = false
                        result.fold(
                            onSuccess = { loadedPassword ->
                                savedPassword = loadedPassword
                                val currentFieldValue = password()
                                if (
                                    currentFieldValue == fieldValueAtRequest ||
                                        currentFieldValue == savedPasswordAtRequest
                                ) {
                                    setPasswordText(loadedPassword)
                                }
                            },
                            onFailure = { error ->
                                if (cachedPassword == null) {
                                    passwordLoadError =
                                        "Could not load password from secure storage: ${error.message ?: error::class.java.simpleName}"
                                }
                            },
                        )
                        updatePasswordHint()
                    },
                    uiModality(),
                )
        }
    }

    private fun uiModality(): ModalityState {
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
        copyPasswordButton.apply {
            text = "Copied"
            icon = AllIcons.Actions.Checked
            toolTipText = "Copied"
            accessibleContext.accessibleName = "Copied"
        }
        copyPasswordFeedbackTimer.restart()
    }

    private fun scheduleExecutableProbe() {
        val app = ApplicationManager.getApplication()
        if (panel == null || app.isUnitTestMode || app.isHeadlessEnvironment) return
        probeTimer.restart()
    }

    /**
     * Shows which executables the form would launch, so a wrong path shows before a failed start.
     */
    private fun probeExecutables() {
        if (panel == null) return
        val generation = probeGeneration.incrementAndGet()
        val customOpenCode = customBinaryRadioButton.isSelected
        val customSbx = customSbxRadioButton.isSelected
        val openCodeEntered = binaryPath()
        val sbxEntered = sbxPath()
        // Label-only update: run even while the settings dialog is modal, before it is showing.
        val modality = ModalityState.any()
        binaryStatusLabel.foreground = UIUtil.getContextHelpForeground()
        binaryStatusLabel.text = "Checking…"
        sbxStatusLabel.foreground = UIUtil.getContextHelpForeground()
        sbxStatusLabel.text = "Checking…"
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            // A probe that throws must end as a status line, never as an IDE error report.
            val unknown = OpenCodeExecutableProbe(null, null)
            val openCode = runCatching {
                OpenCodeExecutableProbes.openCode(
                    if (customOpenCode) openCodeEntered
                    else OpenCodeSettingsState.DEFAULT_EXECUTABLE,
                )
            }
                .getOrDefault(unknown)
            val sbx = runCatching {
                OpenCodeExecutableProbes.sbx(
                    if (customSbx) sbxEntered else SbxCli.DEFAULT_EXECUTABLE,
                )
            }
                .getOrDefault(unknown)
            val autoOpenCode =
                if (customOpenCode) OpenCodeServerProtocol.detectExecutablePath() else openCode.path
            val autoSbx =
                if (customSbx)
                    OpenCodeServerProtocol.detectExecutablePath(SbxCli.DEFAULT_EXECUTABLE)
                else sbx.path
            app.invokeLater(
                {
                    if (generation != probeGeneration.get() || panel == null) return@invokeLater
                    detectedOpenCodePath = autoOpenCode
                    detectedSbxPath = autoSbx
                    showStatus(
                        binaryStatusLabel,
                        openCodeExecutableStatus(openCode, customOpenCode, openCodeEntered),
                    )
                    showStatus(sbxStatusLabel, sbxExecutableStatus(sbx, customSbx, sbxEntered))
                    if (sbxExecutableForChecks() != policyCheckedExecutable) checkSbxPolicy()
                    updateRuntimeControls()
                },
                modality,
            )
        }
    }

    private fun showStatus(label: JBLabel, status: ExecutableStatus) {
        label.text = status.text
        label.foreground =
            if (status.problem) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
    }

    /**
     * Rejects a newly entered custom path that cannot run; an unchanged saved one is left alone.
     */
    private fun validateChangedExecutables(settings: OpenCodeSettingsState) {
        if (
            customBinaryRadioButton.isSelected &&
                (settings.binaryModeValue() != OpenCodeBinaryMode.CUSTOM ||
                    binaryPath() != settings.binaryPath.trim())
        ) {
            requireRunnable(binaryPath(), "OpenCode")
        }
        if (
            customSbxRadioButton.isSelected &&
                (settings.sbxBinaryModeValue() != OpenCodeBinaryMode.CUSTOM ||
                    sbxPath() != settings.sbxBinaryPath.trim())
        ) {
            requireRunnable(sbxPath(), "sbx")
        }
    }

    private fun requireRunnable(path: String, name: String) {
        if (path.isEmpty()) {
            throw ConfigurationException("Enter the $name executable path, or choose Auto detect.")
        }
        if (OpenCodeServerProtocol.detectExecutablePath(path) == null) {
            throw ConfigurationException("$name executable not found or not executable: $path")
        }
    }

    private fun consentToSbxPolicy() {
        val settings = OpenCodeSettingsState.getInstance()
        val executable = OpenCodeServerProtocol.resolveExecutableForLaunch(sbxExecutableForChecks())
        // Captured on the EDT: a pooled thread's invokeLater would otherwise wait for Settings to
        // close.
        val modality = ModalityState.stateForComponent(initSbxPolicyButton)
        initSbxPolicyButton.isEnabled = false
        policyCheckGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val existing =
                SbxProcessRunner.run(SbxCli.buildPolicyLsCommand(executable), emptyMap(), 30_000L)
            val initialized = existing.exitCode == 0 && SbxCli.policyIsInitialized(existing.stdout)
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (initialized) {
                            // `sbx policy init` is one-time; never replace a policy set up
                            // elsewhere.
                            settings.sbxNetworkPolicyConsent = true
                            policyStatus = SbxPolicyStatus.PRESENT
                            policyCheckDetail = null
                            Messages.showInfoMessage(
                                panel ?: initSbxPolicyButton,
                                "This computer already has a sandbox network policy. The plugin uses it and does not change the global policy.",
                                "Sandbox Network Policy",
                            )
                            if (panel != null) updateRuntimeControls()
                            return@invokeLater
                        }
                        initializeSbxPolicy(executable, modality)
                    },
                    modality,
                )
        }
    }

    private fun initializeSbxPolicy(executable: String, modality: ModalityState) {
        val confirmed =
            MessageDialogBuilder.yesNo(
                    "Set Up Sandbox Network Policy",
                    "Docker Sandboxes start with no internet. This runs sbx policy init balanced once " +
                        "on this computer: typical AI APIs and package sites are allowed, and everything " +
                        "else stays blocked.\n\n" +
                        "It applies to every sandbox on this machine, including ones outside IntelliJ. " +
                        "The plugin will not change the policy again afterwards.",
                )
                .yesText("Set Up Policy")
                .noText("Cancel")
                .icon(Messages.getWarningIcon())
                .ask(panel)
        if (!confirmed) {
            if (panel != null) updateRuntimeControls()
            return
        }
        val settings = OpenCodeSettingsState.getInstance()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result =
                SbxProcessRunner.run(
                    SbxCli.buildPolicyInitCommand(executable),
                    emptyMap(),
                    60_000L,
                )
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (result.exitCode == 0) {
                            settings.sbxNetworkPolicyConsent = true
                            policyStatus = SbxPolicyStatus.PRESENT
                            policyCheckDetail = null
                        } else {
                            Messages.showErrorDialog(
                                panel ?: initSbxPolicyButton,
                                "sbx policy init failed (exit ${result.exitCode}).\n${result.output.takeLast(2000)}",
                                "Sandbox Network Policy",
                            )
                        }
                        if (panel != null) updateRuntimeControls()
                    },
                    modality,
                )
        }
    }

    private fun updatePasswordHint() {
        if (buildingSearchIndex()) {
            hintLabel.isVisible = false
            return
        }
        val currentPassword = password()
        val loadError = passwordLoadError
        var isError = false
        val hint =
            when {
                passwordLoading && currentPassword == null ->
                    "Loading password from secure storage..."
                loadError != null -> {
                    isError = true
                    loadError
                }
                currentPassword == null && savedPassword == null ->
                    "No password yet; apply settings to generate and store one."
                currentPassword != savedPassword ->
                    "Apply to save the new password. Running OpenCode servers restart with it."
                else -> null
            }
        hintLabel.foreground =
            if (isError) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        hintLabel.text = hint.orEmpty()
        hintLabel.isVisible = hint != null
        copyPasswordButton.isEnabled = currentPassword != null
    }

    private fun updateBinaryControls() {
        binaryPathField.textField.isEnabled = customBinaryRadioButton.isSelected
    }

    private fun updateSbxControls() {
        sbxPathField.textField.isEnabled = customSbxRadioButton.isSelected
    }

    private fun updateRuntimeControls() {
        if (buildingSearchIndex()) {
            sbxPolicyHintLabel.text = ""
            return
        }
        val consented = OpenCodeSettingsState.getInstance().sbxNetworkPolicyConsent
        val sbxFound = OpenCodeServerProtocol.detectExecutablePath(sbxExecutableForChecks()) != null
        val detail = policyCheckDetail?.let { " ($it)" }.orEmpty()
        sbxPolicyHintLabel.text =
            when (policyStatus) {
                SbxPolicyStatus.CHECKING -> "Checking the sandbox network policy…"
                SbxPolicyStatus.PRESENT ->
                    "This computer has a sandbox network policy. The plugin uses it and never changes the global policy."
                SbxPolicyStatus.ABSENT ->
                    "No sandbox network policy yet. Sandboxes cannot reach the internet until you set one up."
                SbxPolicyStatus.UNKNOWN ->
                    if (consented) {
                        "A sandbox network policy was found earlier; it could not be checked now$detail."
                    } else {
                        "Could not check the sandbox network policy$detail. A sandbox checks again when it starts."
                    }
            }
        val policyReady =
            policyStatus == SbxPolicyStatus.PRESENT ||
                (policyStatus != SbxPolicyStatus.ABSENT && consented)
        initSbxPolicyButton.isEnabled =
            sbxFound && !policyReady && policyStatus != SbxPolicyStatus.CHECKING
        initSbxPolicyButton.toolTipText =
            when {
                policyStatus == SbxPolicyStatus.CHECKING -> "Checking the current policy…"
                policyReady -> "This computer already has a sandbox network policy."
                !sbxFound -> "Install sbx first, or choose its Custom path above."
                else ->
                    "Runs Docker's balanced policy once on this computer so sandboxes can reach typical AI APIs and package sites"
            }
        updateSbxControls()
        updateBinaryControls()
    }

    /** The Panel tab only: executables, password and proxy have no safe "default". */
    private fun restorePanelDefaults() {
        val defaults = OpenCodeSettingsState()
        checkBoxSettingBindings
            .filter { it.tab == OpenCodeSettingsTab.PANEL }
            .forEach { it.checkBox.isSelected = it.read(defaults) }
        uiZoomSpinner.value = defaults.uiZoomPercent
        updateUiDependencyControls()
    }

    /**
     * Opens [tab] now, or when the page is created: links from the project page and the failure
     * card land on the tab that holds what they point to.
     */
    internal fun selectTab(tab: OpenCodeSettingsTab) {
        val tabs = panel as? OpenCodeSettingsTabbedPane
        if (tabs == null) pendingTab = tab else tabs.selectedIndex = tab.ordinal
    }

    private fun updateUiDependencyControls() {
        enableCodeNavigationCheckBox.isEnabled = openFileLinksInIdeCheckBox.isSelected
        enablePermissionNotificationActionsCheckBox.isEnabled =
            enableSystemNotificationsCheckBox.isSelected
    }

    /** The project page exists only when the dialog was opened from a project window. */
    private fun openProjectSettings() {
        val component = panel ?: return
        val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(component))
        val target = settings?.find(OpenCodeProjectSettingsConfigurable.ID)
        if (settings != null && target != null) {
            settings.select(target)
        } else {
            Messages.showInfoMessage(
                component,
                "Open Settings from a project window to choose that project's runtime.",
                "Project Runtime Settings",
            )
        }
    }

    companion object {
        private const val PROBE_DELAY_MILLIS = 400

        /** plugin.xml `applicationConfigurable` id. */
        const val ID = "opencode.web.panel.settings"
        private const val SBX_POLICY_DOCS_URL =
            "https://docs.docker.com/ai/sandboxes/security/defaults/"
    }
}
