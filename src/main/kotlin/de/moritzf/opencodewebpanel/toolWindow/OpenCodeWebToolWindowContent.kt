package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptScheduler
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.browser.OpenCodeDocumentStartInjector
import de.moritzf.opencodewebpanel.features.OpenCodeAgentStatusState
import de.moritzf.opencodewebpanel.features.OpenCodeAgentStatusTracker
import de.moritzf.opencodewebpanel.features.OpenCodeChatInputService
import de.moritzf.opencodewebpanel.features.OpenCodeDiffNavigation
import de.moritzf.opencodewebpanel.features.OpenCodeFileDropHandler
import de.moritzf.opencodewebpanel.features.OpenCodeIdeNavigation
import de.moritzf.opencodewebpanel.features.OpenCodeInterruptedSessionRecovery
import de.moritzf.opencodewebpanel.features.OpenCodeLocalStorageBridge
import de.moritzf.opencodewebpanel.features.OpenCodePermissionAutoResponder
import de.moritzf.opencodewebpanel.features.OpenCodeSystemNotifications
import de.moritzf.opencodewebpanel.features.OpenCodeWorkspaceRefreshCoordinator
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeSuspendResumeListener
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.server.isSuccessfulOpenCodeDocumentLoad
import de.moritzf.opencodewebpanel.server.shouldApplyPublishedLifecycleState
import de.moritzf.opencodewebpanel.server.shouldHideEmbeddedPage
import de.moritzf.opencodewebpanel.server.shouldShowStartupError
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeUiSetting
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

class OpenCodeWebToolWindowContent(private val toolWindow: ToolWindow) : Disposable {

    private companion object {
        private const val BROWSER_CARD = "browser"
        private const val ERROR_CARD = "error"
        private const val IDLE_CARD = "idle"
        private const val BROWSER_RECOVERY_THROTTLE_MILLIS = 10_000L
        private const val PAGE_LOAD_WATCHDOG_MILLIS = OpenCodePageLoadWatchdog.DEFAULT_TIMEOUT_MILLIS
        private const val DOCUMENT_START_INSTALL_TIMEOUT_MILLIS = 20_000L

        // Delay after a project-page load before flushing queued chat input, so the SPA's own
        // drop handlers are installed when the synthetic drop is dispatched.
        private const val PENDING_CHAT_INPUT_FLUSH_DELAY_MILLIS = 1_500
        private const val CHAT_INPUT_ACK_TIMEOUT_MILLIS = 3_000

        // How long the 1 px repaint-recovery resize is held before restoring the real bounds,
        // so CEF's asynchronous view-rect query observes the transient size.
        private const val COMPONENT_SIZE_NUDGE_RESTORE_DELAY_MILLIS = 100

        @Volatile
        private var applicationClosing = false

        /**
         * One notification per IDE session, application-wide: Chromium caches the entered
         * credentials for the lifetime of the shared JCEF process, so after the first sign-in
         * later DevTools windows (from any project) no longer prompt.
         */
        @Volatile
        private var devToolsCredentialsNotified = false
    }

    private val project = toolWindow.project
    private val browser = JBCefBrowser()
    private val lifecycleStatusPanel = OpenCodeLifecycleStatusPanel(::restartOpenCodeServer)
    private val startupErrorPanel = OpenCodeStartupErrorPanel(project, ::restartOpenCodeServer)
    private val centerCardLayout = CardLayout()
    private val centerCardPanel = JPanel(centerCardLayout).apply {
        isOpaque = false
        add(browser.component, BROWSER_CARD)
        add(startupErrorPanel.component, ERROR_CARD)
        add(JPanel().apply { isOpaque = false }, IDLE_CARD)
    }
    private val contentPanel = BorderLayoutPanel().apply {
        isOpaque = false
        addToTop(lifecycleStatusPanel.component)
        addToCenter(centerCardPanel)
    }
    private val openFileLinkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openCodeReferenceQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openCodeLocalStorageQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openExternalLinkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val browserCursorQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openDiffQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val chatInputResultQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val serverManager = SharedOpenCodeServerManager.getInstance()
    private val ideNavigation = OpenCodeIdeNavigation(project, browser, serverManager, ::openCodeProjectDirectory, this)
    private val diffNavigation = OpenCodeDiffNavigation(project, browser, serverManager, ::openCodeProjectDirectory)
    private val localStorageBridge = OpenCodeLocalStorageBridge(
        browser,
        serverManager,
        syncCallback = { openCodeLocalStorageQuery.inject("payload") },
    )
    private val systemNotifications = OpenCodeSystemNotifications(
        project,
        toolWindow,
        browser,
        serverManager,
        ::openCodeProjectDirectory,
        ::navigateFromNotification,
        this,
    )
    private val requestHandler = OpenCodeBrowserRequestHandler(serverManager, ideNavigation, ::recoverFromRendererCrash)
    private val interruptedSessionRecovery = OpenCodeInterruptedSessionRecovery(project, serverManager, ::openCodeProjectDirectory)
    private val permissionAutoResponder = OpenCodePermissionAutoResponder(
        ::openCodeProjectDirectory,
        serverManager::getServerUrl,
        serverManager::getServerPassword,
    )
    private val openProjectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val pageLoadWatchdogAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val repaintAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val componentSizeRestoreAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scriptScheduler = OpenCodeBrowserScriptScheduler(project, browser, openProjectAlarm)
    private val repaintScheduler = OpenCodeBrowserScriptScheduler(project, browser, repaintAlarm)
    private val browserFocusSync = OpenCodeBrowserFocusSync(
        component = { browser.component },
        isActive = { !isContentDisposed() },
        setBrowserFocus = { browser.cefBrowser.setFocus(it) },
    )
    private val componentSizeNudger = OpenCodeComponentSizeNudger(
        component = browser.component,
        isActive = { !isContentDisposed() },
        scheduleRestore = { action ->
            componentSizeRestoreAlarm.addRequest({ action() }, COMPONENT_SIZE_NUDGE_RESTORE_DELAY_MILLIS)
        },
        afterGrow = {
            browser.component.validate()
            browser.component.repaint()
        },
        afterRestore = {
            browser.component.validate()
            browser.component.revalidate()
            browser.component.repaint()
            browser.cefBrowser.notifyScreenInfoChanged()
        },
    )
    private var openProjectScriptScheduled = false
    /** Most-recent session resolved for the current page load (avoids a second REST fetch). */
    private var pendingMostRecentSessionId: String? = null
    /**
     * One-shot boot intent: navigate to [pendingMostRecentSessionId] only for the load started by
     * [loadProjectPage]. Cleared once the target session is open so later SPA navigations only
     * re-seed project state and never yank the user back to the startup conversation.
     */
    private var pendingOpenMostRecentConversation = false
    private val loadIntent = OpenCodeLoadIntent()
    private val documentStartInjector = OpenCodeDocumentStartInjector(browser)
    private var mostRecentSessionLookupInFlight = false
    @Volatile
    private var mainDocumentLoadSucceeded = false
    private var pageLoadInProgress = false
    private var pageLoadStartedAtMillis = 0L
    private var pageLoadTargetUrl: String? = null
    private var pageLoadWatchdogGeneration = 0L
    private var pageLoadRetryCount = 0
    private var cefBrowserCreated = false
    private var pendingBrowserLoadGeneration = 0L

    @Volatile
    private var mainDocumentLoadRevision = 0L
    @Volatile
    private var browserDocumentRevision = 0L

    /**
     * A UI-behavior enhancement injected into the OpenCode page as JavaScript. Instances bundle
     * the setting gate, the script builder, and the per-page-load "already scheduled" flag so
     * scheduling and setting toggles can be handled generically for every feature.
     */
    private class InjectedFeature(
        val enabledInSettings: () -> Boolean,
        val buildScript: () -> String?,
        /** Extra cleanup before the page reload that removes a disabled feature. */
        val onDisable: () -> Unit = {},
    ) {
        var scheduled = false
    }

    /**
     * A UI-behavior enhancement that must run before the SPA bundle executes. Registered with
     * Chromium document-start before navigation, then injected again from `onLoadStart` (and
     * retried on the early delay series) in case document-start was unavailable. Builders are
     * re-invoked on every attempt so scripts can embed current state (e.g. the IDE theme); they
     * must be idempotent in-page. Instances share one scheduling routine ([injectEarlyFeature])
     * and one reset point so per-feature flag drift is impossible.
     */
    private class EarlyInjectedFeature(
        val enabledInSettings: () -> Boolean = { true },
        val buildScript: (serverUrl: String) -> String?,
    ) {
        var scheduled = false
    }

    private val openProjectSeedFeature = EarlyInjectedFeature(
        buildScript = { serverUrl ->
            openCodeProjectDirectory()?.takeIf { it.isNotBlank() }?.let { projectDirectory ->
                // Pass the resolved boot target so OpenCode's own `lastProjectSession` pointer is
                // already correct when the SPA bundle reads localStorage. Seeding it only after
                // load lets the bundle bootstrap onto a stale pointer first, which shows the
                // wrong conversation until the post-load navigate corrects it. The navigation
                // itself stays a post-load step (window.location.assign needs a live document).
                OpenCodeBrowserSnippets.buildOpenProjectScript(
                    projectDirectory,
                    serverUrl,
                    openMostRecentConversation = pendingOpenMostRecentConversation,
                    mostRecentSessionId = pendingMostRecentSessionId,
                    navigate = false,
                )
            }
        },
    )
    private val ideThemeSyncFeature = EarlyInjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().syncThemeWithIde },
        buildScript = {
            OpenCodeBrowserSnippets.buildIdeThemeSyncScript(
                enabled = OpenCodeSettingsState.getInstance().syncThemeWithIde,
                dark = isIdeDarkTheme(),
            )
        },
    )
    private val compactLayoutFeature = EarlyInjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().forceCompactLayout },
        buildScript = { OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true) },
    )
    private val hideWebsiteButtonFeature = EarlyInjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().hideWebsiteButton },
        buildScript = { OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = true) },
    )
    private val eventStreamWatchdogFeature = EarlyInjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().recoverStalledEventStream },
        buildScript = { OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true) },
    )

    /** Injection order matters: the project seed must precede everything else. */
    private val earlyInjectedFeatures = listOf(
        openProjectSeedFeature,
        ideThemeSyncFeature,
        compactLayoutFeature,
        hideWebsiteButtonFeature,
        eventStreamWatchdogFeature,
    )

    private val fileLinkFeature = InjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().openFileLinksInIde },
        buildScript = {
            OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
                openCodeProjectDirectory(),
                enabled = true,
                openFileCallback = openFileLinkQuery.inject("rawHref + '\\n' + directory"),
            )
        },
    )
    private val externalLinkFeature = InjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().openExternalLinksInBrowser },
        buildScript = {
            OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(
                enabled = true,
                openExternalCallback = openExternalLinkQuery.inject("href"),
            )
        },
    )
    private val codeNavigationFeature = InjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().effectiveCodeNavigationEnabled() },
        buildScript = {
            OpenCodeBrowserSnippets.buildCodeNavigationScript(
                enabled = true,
                openCodeCallback = openCodeReferenceQuery.inject("ref"),
            )
        },
    )
    private val diffNavigationFeature = InjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().openDiffsInIde },
        buildScript = {
            OpenCodeBrowserSnippets.buildDiffNavigationScript(
                enabled = true,
                openDiffCallback = openDiffQuery.inject("messageID + '\\n' + filePath"),
            )
        },
    )
    private val filePasteSuppressionFeature = InjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().enableChatFileDrop },
        buildScript = { OpenCodeBrowserSnippets.buildFilePasteSuppressionScript(enabled = true) },
    )
    private val projectSwitchPromptSuppressionFeature = InjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().suppressProjectSwitchPrompts },
        buildScript = { OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = true) },
    )
    private val cursorMirrorFeature = InjectedFeature(
        enabledInSettings = { OpenCodeSettingsState.getInstance().mirrorBrowserCursor },
        buildScript = {
            OpenCodeBrowserSnippets.buildCursorMirrorScript(
                enabled = true,
                cursorCallback = browserCursorQuery.inject("payload"),
            )
        },
        onDisable = { applyBrowserCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)) },
    )
    private val injectedFeatures = listOf(
        fileLinkFeature,
        externalLinkFeature,
        codeNavigationFeature,
        diffNavigationFeature,
        filePasteSuppressionFeature,
        projectSwitchPromptSuppressionFeature,
        cursorMirrorFeature,
    )
    private val workspaceRefreshCoordinator = OpenCodeWorkspaceRefreshCoordinator(
        project,
        ::openCodeProjectDirectory,
        parentDisposable = this,
    )
    private val agentStatusTracker = OpenCodeAgentStatusTracker(
        projectDirectory = ::openCodeProjectDirectory,
        enabled = { !isContentDisposed() && OpenCodeSettingsState.getInstance().showAgentStatusBadge },
        onStateChanged = ::onAgentStatusChanged,
        serverUrl = serverManager::getServerUrl,
        serverPassword = serverManager::getServerPassword,
        serverGeneration = serverManager::getServerGeneration,
    )
    private var loadedServerRootUrl: String? = null
    private var pendingServerStartRequest = false
    private var lastBrowserRecoveryAttemptAtMillis = 0L
    private val toolWindowIconSupplier by lazy {
        BadgeIconSupplier(IconLoader.getIcon("/icons/opencode.svg", OpenCodeWebToolWindowContent::class.java))
    }

    @Volatile
    private var disposed = false
    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
            if (frame?.isMain == true) {
                val loadRevision = ++mainDocumentLoadRevision
                browserDocumentRevision++
                mainDocumentLoadSucceeded = false
                repaintAlarm.cancelAllRequests()
                OpenCodeChatInputService.getInstance(project).requeueInFlight()
                injectedFeatures.forEach { it.scheduled = false }
                earlyInjectedFeatures.forEach { it.scheduled = false }
                val serverUrl = serverManager.getServerUrl()
                val frameUrl = frame.url
                if (OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) {
                    ApplicationManager.getApplication().invokeLater {
                        if (isContentDisposed() || loadRevision != mainDocumentLoadRevision || mainDocumentLoadSucceeded) {
                            return@invokeLater
                        }
                        val liveUrl = serverManager.getServerUrl() ?: return@invokeLater
                        if (!OpenCodeServerProtocol.isOpenCodeServerPage(liveUrl, frameUrl)) return@invokeLater
                        val sameLoad = pageLoadInProgress &&
                            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(liveUrl, pageLoadTargetUrl, frameUrl)
                        beginPageLoad(frameUrl, resetRetryBudget = !sameLoad)
                        armPageLoadWatchdog(liveUrl)
                    }
                    // Drop any previous open-project delay series so navigations do not stack injects.
                    // Reset the flag so onLoadEnd can schedule a fresh series for this document.
                    openProjectAlarm.cancelAllRequests()
                    openProjectScriptScheduled = false
                    localStorageBridge.restore(frame.url)
                    // Seed lastProject (first early feature) before the SPA bundle reads
                    // localStorage — the shared browser profile otherwise keeps the previous IDE
                    // project's workspace.
                    earlyInjectedFeatures.forEach(::injectEarlyFeature)
                    localStorageBridge.installSync(frame.url)
                }
            }
        }

        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain != true) return
            if (!isSuccessfulOpenCodeDocumentLoad(httpStatusCode)) return
            val completedUrl = frame.url
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), completedUrl)) return
            val completedRevision = mainDocumentLoadRevision
            ApplicationManager.getApplication().invokeLater {
                if (isContentDisposed() || completedRevision != mainDocumentLoadRevision) return@invokeLater
                val liveUrl = serverManager.getServerUrl() ?: return@invokeLater
                if (!OpenCodeServerProtocol.isOpenCodeServerPage(liveUrl, completedUrl)) return@invokeLater
                val targetUrl = pageLoadTargetUrl
                if (pageLoadInProgress && targetUrl != null &&
                    !OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(liveUrl, completedUrl, targetUrl)
                ) {
                    return@invokeLater
                }

                // JBCef's own focus forwarding is transition-based and can be dropped around
                // loads (e.g. before native browser init); re-sync so the text caret is rendered.
                browserFocusSync.reassertIfFocused()
                mainDocumentLoadSucceeded = true
                pageLoadWatchdogGeneration++
                pageLoadWatchdogAlarm.cancelAllRequests()
                pageLoadRetryCount = 0
                pageLoadInProgress = false
                pageLoadStartedAtMillis = 0L
                pageLoadTargetUrl = null
                updateLifecycleIndicator()

                // Restore the mirrored localStorage snapshot again on load end. The restore in
                // onLoadStart can run before the new origin's V8 context is ready (e.g. when
                // navigating from about:blank), so this second attempt ensures layout.page is
                // available for the open-project script.
                localStorageBridge.restore(completedUrl)
                scheduleOpenProjectScript()
                localStorageBridge.installSync(completedUrl)
                injectedFeatures.forEach(::scheduleFeatureScript)
                scheduleIdeThemeSyncScript()
                scheduleFlushPendingChatInput()
                interruptedSessionRecovery.checkAndContinue()
                prepareDisplayedSessionLineage(completedUrl)
            }
        }

        override fun onLoadError(
            browser: CefBrowser?,
            frame: CefFrame?,
            errorCode: CefLoadHandler.ErrorCode?,
            errorText: String?,
            failedUrl: String?,
        ) {
            if (frame?.isMain != true) return
            // ERR_ABORTED fires for ordinary cancelled navigations and must never trigger recovery.
            if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), failedUrl)) return
            ApplicationManager.getApplication().invokeLater {
                if (!isContentDisposed()) {
                    recoverFromLoadError("${errorCode?.name}: $errorText")
                }
            }
        }
    }

    init {
        openFileLinkQuery.addHandler { href ->
            if (OpenCodeSettingsState.getInstance().openFileLinksInIde) {
                ideNavigation.openFileLinkInIde(href)
            }
            null
        }
        openExternalLinkQuery.addHandler { href ->
            if (OpenCodeSettingsState.getInstance().openExternalLinksInBrowser) {
                ideNavigation.openExternalLinkInBrowser(href)
            }
            null
        }
        browserCursorQuery.addHandler { cssCursor ->
            if (OpenCodeSettingsState.getInstance().mirrorBrowserCursor) {
                val cursorType = OpenCodeBrowserSnippets.awtCursorTypeForCss(cssCursor)
                ApplicationManager.getApplication().invokeLater {
                    if (!isContentDisposed() && OpenCodeSettingsState.getInstance().mirrorBrowserCursor) {
                        applyBrowserCursor(Cursor.getPredefinedCursor(cursorType))
                    }
                }
            }
            null
        }
        openCodeReferenceQuery.addHandler { ref ->
            if (OpenCodeSettingsState.getInstance().effectiveCodeNavigationEnabled()) {
                ideNavigation.openCodeReferenceInIde(ref)
            }
            null
        }
        openDiffQuery.addHandler { payload ->
            if (OpenCodeSettingsState.getInstance().openDiffsInIde) {
                diffNavigation.openDiff(payload)
            }
            null
        }
        chatInputResultQuery.addHandler { payload ->
            val lines = payload.lineSequence().toList()
            val attemptID = lines.getOrNull(0).orEmpty()
            val accepted = lines.getOrNull(1) == "1"
            ApplicationManager.getApplication().invokeLater {
                if (isContentDisposed()) return@invokeLater
                val service = OpenCodeChatInputService.getInstance(project)
                if (!service.acknowledge(attemptID, accepted)) return@invokeLater
                if (!accepted) scheduleFlushPendingChatInput()
            }
            null
        }
        openCodeLocalStorageQuery.addHandler { snapshot ->
            localStorageBridge.sync(snapshot)
            null
        }
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        browser.jbCefClient.addContextMenuHandler(OpenCodeBrowserContextMenuHandler(), browser.cefBrowser)
        // onAddressChange also fires for the SPA's history-API route changes, which full-load
        // handlers never see.
        browser.jbCefClient.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(cefBrowser: CefBrowser?, frame: CefFrame?, url: String?) {
                    if (frame?.isMain == true) {
                        browserDocumentRevision++
                        systemNotifications.browserAddressChanged()
                        prepareDisplayedSessionLineage(url)
                        scheduleBrowserRepaintNudges()
                        // CEF OSR can drop Chromium-level focus on SPA redirects/route changes
                        // (CEF #3870), which hides the text caret while typing still works.
                        browserFocusSync.reassertIfFocused()
                    }
                }
            },
            browser.cefBrowser,
        )
        OpenCodeFileDropHandler(
            project,
            browser,
            serverManager,
            ::openCodeProjectDirectory,
            { browserDocumentRevision },
            ::isContentDisposed,
            this,
        ).install()
        OpenCodeBrowserShortcutHandler(browser, serverManager, this).install()
        OpenCodeChatInputService.getInstance(project).setDispatcher(::dispatchChatBatch)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AppLifecycleListener.TOPIC,
            object : AppLifecycleListener {
                override fun appClosing() {
                    applicationClosing = true
                }
            },
        )
        // Re-activating the IDE window does not emit a component-level focus transition when
        // focus never left the browser, so JBCef never re-tells Chromium it is focused and the
        // text caret stays hidden until the user clicks elsewhere and back.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    browserFocusSync.reassertIfFocused()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeGlobalEventListener.TOPIC,
            agentStatusTracker,
        )
        // Keeps the IDE's files/VCS in sync with the agent's edits, patches, branch changes, and
        // commits. Independent of the agent-status badge, and debounced against event bursts.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeGlobalEventListener.TOPIC,
            workspaceRefreshCoordinator,
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeGlobalEventListener.TOPIC,
            permissionAutoResponder,
        )
        // Permission/question sections appear in-place, without an address change, so the
        // onAddressChange repaint hook never sees them. Nudge the compositor from the JVM-side
        // event stream instead when such a request targets this panel's directory.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeGlobalEventListener.TOPIC,
            object : OpenCodeGlobalEventListener {
                override fun eventReceived(event: OpenCodeGlobalEvent) {
                    if (event.type != "permission.asked" && event.type != "question.asked") return
                    if (isContentDisposed()) return
                    val directory = openCodeProjectDirectory() ?: return
                    if (!OpenCodeServerProtocol.isSameFilesystemPath(event.directory, directory)) return
                    val serverUrl = serverManager.getServerUrl() ?: return
                    if (!isBrowserOnOpenCodeServerPage(serverUrl)) return
                    scheduleBrowserRepaintNudges()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeServerLifecycleListener.TOPIC,
            object : OpenCodeServerLifecycleListener {
                override fun stateChanged(state: OpenCodeServerLifecycleState) {
                    ApplicationManager.getApplication().invokeLater {
                        if (isContentDisposed()) return@invokeLater
                        if (!shouldApplyPublishedLifecycleState(state, serverManager.getLifecycleState())) return@invokeLater
                        clearStaleBrowserPage(state)
                        updateLifecycleIndicator(state)
                        if (shouldShowStartupError(state)) {
                            showErrorInBrowser()
                            return@invokeLater
                        }
                        reloadContentAfterRecovery(state)
                    }
                }
            },
        )
        updateLifecycleIndicator(serverManager.getLifecycleState())
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeSuspendResumeListener.TOPIC,
            object : OpenCodeSuspendResumeListener {
                override fun resumedFromSuspend(lastAliveMillis: Long, resumedAtMillis: Long) {
                    interruptedSessionRecovery.onResumedFromSuspend(lastAliveMillis, resumedAtMillis)
                    // The page's stream cannot have survived the suspend; cut it now so the SPA
                    // reconnects at once rather than after the watchdog's silence budget.
                    forceEventStreamReconnect()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeSettingsListener.TOPIC,
            object : OpenCodeSettingsListener {
                override fun uiZoomChanged(zoomPercent: Int) {
                    // CEF applies zoom-level changes to the live page; reloading would drop the
                    // user's chat draft and scroll position.
                    applyBrowserZoom(zoomPercent)
                }

                override fun uiSettingChanged(setting: OpenCodeUiSetting, enabled: Boolean) {
                    when (setting) {
                        OpenCodeUiSetting.FILE_LINK_NAVIGATION -> applyFileLinkNavigation(enabled)
                        OpenCodeUiSetting.EXTERNAL_LINK_NAVIGATION -> applyFeature(externalLinkFeature, enabled)
                        OpenCodeUiSetting.CODE_NAVIGATION -> applyFeature(codeNavigationFeature, enabled)
                        OpenCodeUiSetting.DIFF_NAVIGATION -> applyFeature(diffNavigationFeature, enabled)
                        OpenCodeUiSetting.CHAT_FILE_DROP -> {
                            applyFeature(filePasteSuppressionFeature, enabled)
                            if (enabled) scheduleFlushPendingChatInput(delayMillis = 0)
                        }
                        OpenCodeUiSetting.COMPACT_LAYOUT -> applyCompactLayout()
                        OpenCodeUiSetting.HIDE_WEBSITE_BUTTON -> applyHideWebsiteButton()
                        OpenCodeUiSetting.IDE_THEME_SYNC -> applyIdeThemeSync(enabled)
                        OpenCodeUiSetting.PROJECT_SWITCH_PROMPT_SUPPRESSION -> applyFeature(projectSwitchPromptSuppressionFeature, enabled)
                        OpenCodeUiSetting.BROWSER_CURSOR_MIRROR -> applyFeature(cursorMirrorFeature, enabled)
                        OpenCodeUiSetting.EVENT_STREAM_WATCHDOG -> applyEventStreamWatchdog()
                        OpenCodeUiSetting.AGENT_STATUS_BADGE -> applyAgentStatusBadge(enabled)
                    }
                }

                override fun serverRestartRequested() {
                    restartOpenCodeServer()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                if (OpenCodeSettingsState.getInstance().syncThemeWithIde) {
                    applyIdeThemeSync(enabled = true)
                }
            },
        )
        project.messageBus.connect(this).subscribe(
            OpenCodeProjectSettingsListener.TOPIC,
            object : OpenCodeProjectSettingsListener {
                override fun projectDirectoryChanged(directory: String?) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!isContentDisposed()) applyOpenCodeProjectDirectoryChange()
                    }
                }
            },
        )
    }

    /**
     * JCEF's off-screen rendering occasionally leaves stale-frame artifacts after large DOM
     * repaints: Chromium repaints only the dirty region while the previous content's pixels stay
     * in the composited buffer. Triggered from `onAddressChange` for SPA route changes and from
     * the JVM event stream for permission/question sections, which render without a route change.
     * `notifyScreenInfoChanged` makes Chromium re-query the screen and re-composite the full
     * surface. Retried over the next few seconds because the SPA keeps painting for a moment
     * after the trigger. May be called from any thread; the alarm runs the nudges on the EDT.
     */
    private fun scheduleBrowserRepaintNudges() {
        val nudgedAtUrl = browser.cefBrowser.url
        val serverUrl = serverManager.getServerUrl() ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        repaintAlarm.cancelAllRequests()

        repaintScheduler.scheduleAction(early = true, shouldRun = { !isContentDisposed() }) {
            if (!stillOnSamePage(nudgedAtUrl)) return@scheduleAction
            browser.cefBrowser.notifyScreenInfoChanged()
            browser.component.repaint()
        }
        // If the composite-level nudge was not enough, force the SPA to re-layout and, on the
        // later attempts, wiggle the Swing component size. A 1 px resize causes CEF to reallocate
        // the off-screen backing surface, which clears the mismatched-frame state that a plain
        // repaint sometimes cannot recover.
        repaintScheduler.scheduleAt(500, shouldRun = { !isContentDisposed() }) {
            if (!stillOnSamePage(nudgedAtUrl)) return@scheduleAt
            browser.cefBrowser.executeJavaScript("window.dispatchEvent(new Event('resize'))", rootUrl, 0)
        }
        repaintScheduler.scheduleAt(1500, shouldRun = { !isContentDisposed() }) {
            if (!stillOnSamePage(nudgedAtUrl)) return@scheduleAt
            componentSizeNudger.nudge()
        }
        repaintScheduler.scheduleAt(3000, shouldRun = { !isContentDisposed() }) {
            if (!stillOnSamePage(nudgedAtUrl)) return@scheduleAt
            componentSizeNudger.nudge()
        }
    }

    private fun stillOnSamePage(expectedUrl: String?): Boolean {
        if (expectedUrl.isNullOrBlank()) return false
        return expectedUrl == browser.cefBrowser.url
    }

    /**
     * Submits one queued IDE-initiated text and retains it in-flight until the page acknowledges
     * that OpenCode's prompt handler accepted the synthetic paste/drop event.
     */
    private fun dispatchChatBatch(delivery: OpenCodeChatInputService.Delivery): Boolean {
        if (isContentDisposed()) return false
        if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return false
        val serverUrl = serverManager.getServerUrl() ?: return false
        if (!isBrowserOnOpenCodeServerPage(serverUrl)) return false
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf(delivery.batch.text),
            enabled = true,
            batchId = delivery.attemptID,
            resultCallback = chatInputResultQuery.inject("batchId + '\\n' + (accepted ? '1' : '0')"),
        ) ?: return false
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        openProjectAlarm.addRequest(
            {
                if (isContentDisposed()) return@addRequest
                val service = OpenCodeChatInputService.getInstance(project)
                if (service.retryInFlight(delivery.attemptID)) scheduleFlushPendingChatInput()
            },
            CHAT_INPUT_ACK_TIMEOUT_MILLIS,
        )
        return true
    }

    private fun scheduleFlushPendingChatInput(delayMillis: Int = PENDING_CHAT_INPUT_FLUSH_DELAY_MILLIS) {
        val service = OpenCodeChatInputService.getInstance(project)
        openProjectAlarm.addRequest(
            {
                if (isContentDisposed()) return@addRequest
                service.dispatchPending()
            },
            delayMillis,
        )
    }

    fun getContent() = contentPanel

    private fun updateLifecycleIndicator(state: OpenCodeServerLifecycleState = serverManager.getLifecycleState()) {
        if (state != OpenCodeServerLifecycleState.RUNNING) {
            resetAgentStatusBadge()
        }
        lifecycleStatusPanel.update(state, pageOpening = pageLoadInProgress)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun warnIfOpenCodeVersionIsUnsupported() {
        if (project.isDisposed) return
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
            ?: return
        val installedVersion = serverManager.consumeUnsupportedServerVersionWarning()
        if (installedVersion != null) {
            group.createNotification(
                "OpenCode update required",
                "OpenCode Web Panel requires OpenCode ${OpenCodeServerProtocol.MINIMUM_SUPPORTED_OPENCODE_VERSION} or later. " +
                    "Installed version: ${StringUtil.escapeXmlEntities(installedVersion)}.",
                NotificationType.WARNING,
            ).notify(project)
        }
        if (!serverManager.consumeV2ProtocolWarning()) return
        group.createNotification(
            "Permission requests may not appear",
            "This OpenCode version may not show permission requests in the IDE. " +
                "Update the OpenCode Web Panel plugin when a matching release is available.",
            NotificationType.WARNING,
        ).notify(project)
    }

    private fun restartOpenCodeServer() {
        if (isContentDisposed()) return
        lifecycleStatusPanel.setRetryEnabled(false)
        pendingServerStartRequest = true
        serverManager.restartServer(
            project,
            openCodeProjectDirectory(),
            callbackActive = { !isContentDisposed() },
            onStarted = {
                pendingServerStartRequest = false
                warnIfOpenCodeVersionIsUnsupported()
                loadProjectPage()
            },
            onFailed = {
                pendingServerStartRequest = false
                if (shouldShowStartupError(serverManager.getLifecycleState())) showErrorInBrowser()
            },
        )
    }

    fun checkAndLoadContent() {
        if (isContentDisposed()) return
        pendingServerStartRequest = true
        serverManager.ensureStarted(
            project,
            openCodeProjectDirectory(),
            callbackActive = { !isContentDisposed() },
            onStarted = {
                pendingServerStartRequest = false
                warnIfOpenCodeVersionIsUnsupported()
                loadProjectPage()
            },
            onFailed = {
                pendingServerStartRequest = false
                if (shouldShowStartupError(serverManager.getLifecycleState())) showErrorInBrowser()
            },
        )
    }

    /**
     * Reloads the embedded OpenCode web UI (the SPA) in place without touching the shared server,
     * so a glitchy or stale page can be refreshed without interrupting sessions in this or any
     * other project's panel. Falls back to a full load when nothing valid is currently shown (the
     * server was down or the page was cleared to about:blank).
     */
    fun reloadOpenCodePage() {
        if (isContentDisposed()) return
        cancelStartupNavigation()
        val serverUrl = serverManager.getServerUrl()
        if (serverUrl == null ||
            loadedServerRootUrl == null ||
            !OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)
        ) {
            checkAndLoadContent()
            return
        }
        thisLogger().info("Reloading OpenCode page")
        applyBrowserZoom()
        beginPageLoad(browser.cefBrowser.url)
        ensureCefBrowser()
        loadAfterDocumentStartScripts(serverUrl) {
            browser.cefBrowser.reload()
            armPageLoadWatchdog(serverUrl)
        }
    }

    /**
     * Escape hatch for corrupted OpenCode web state: clears the IDE-side mirrored localStorage
     * snapshot and the page's local/session storage, then reloads. This is the recovery path for
     * a bad persisted value — e.g. a mirrored snapshot that would otherwise be restored on every
     * load, or a seeded project state the SPA can no longer read. The browser profile is shared
     * by every project's panel, so the effect is application-wide by design.
     */
    fun resetOpenCodeWebState() {
        if (isContentDisposed()) return
        OpenCodeSettingsState.getInstance().openCodeLocalStorageSnapshot = "{}"
        val serverUrl = serverManager.getServerUrl()
        if (serverUrl != null && isBrowserOnOpenCodeServerPage(serverUrl)) {
            browser.cefBrowser.executeJavaScript(
                OpenCodeBrowserSnippets.buildClearOpenCodeWebStateScript(),
                OpenCodeServerProtocol.buildServerRootUrl(serverUrl),
                0,
            )
        }
        reloadOpenCodePage()
    }

    /**
     * Reloads the OpenCode page after the shared server recovered without this panel's involvement,
     * e.g. an automatic health-check restart or a restart initiated from another project window.
     * Without this, the panel would stay on the idle card installed by [clearStaleBrowserPage].
     */
    private fun reloadContentAfterRecovery(state: OpenCodeServerLifecycleState) {
        if (state != OpenCodeServerLifecycleState.RUNNING) return
        if (pendingServerStartRequest) return
        if (loadedServerRootUrl != null) return
        checkAndLoadContent()
    }

    /**
     * Handles a failed main-frame load of the OpenCode page (e.g. the server died between health
     * checks and the browser shows a connection error). Verifies the server immediately: reloads
     * on a transient failure, or triggers the regular restart recovery right away.
     */
    private fun recoverFromLoadError(reason: String) {
        if (!markBrowserRecoveryAttempt()) return
        thisLogger().warn("OpenCode page failed to load ($reason); verifying server health")
        serverManager.verifyServerNow(
            callbackActive = { !isContentDisposed() },
            onHealthy = { reloadOpenCodePageOrLoad() },
        )
    }

    /**
     * Reloads the page after the JCEF renderer process crashed, which otherwise leaves a
     * permanently blank panel. Throttled to avoid reload loops on repeated crashes.
     */
    private fun recoverFromRendererCrash() {
        ApplicationManager.getApplication().invokeLater {
            if (isContentDisposed()) return@invokeLater
            if (!markBrowserRecoveryAttempt()) return@invokeLater
            thisLogger().warn("OpenCode panel renderer process terminated; reloading page")
            reloadOpenCodePageOrLoad()
        }
    }

    /** Reload only when CEF is already on the live server page; otherwise a full load. */
    private fun reloadOpenCodePageOrLoad() {
        val serverUrl = serverManager.getServerUrl()
        if (serverUrl != null && isBrowserOnOpenCodeServerPage(serverUrl)) {
            beginPageLoad(browser.cefBrowser.url)
            ensureCefBrowser()
            loadAfterDocumentStartScripts(serverUrl) {
                browser.cefBrowser.reload()
                armPageLoadWatchdog(serverUrl)
            }
        } else {
            loadProjectPage()
        }
    }

    private fun markBrowserRecoveryAttempt(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBrowserRecoveryAttemptAtMillis < BROWSER_RECOVERY_THROTTLE_MILLIS) return false
        lastBrowserRecoveryAttemptAtMillis = now
        return true
    }

    private fun loadProjectPage() {
        if (isContentDisposed()) return
        val serverUrl = serverManager.getServerUrl() ?: return
        val serverGeneration = serverManager.getServerGeneration()
        val loadToken = loadIntent.begin()
        // Boot on the native 1.18 server session route (/server/<serverKey>/session[/<id>]), not the
        // legacy project directory route (/<encodedDir>/session). Cold-loading the bare directory
        // route crashes opencode 1.18.x's application error boundary. Prefer a concrete session id
        // when "open most recent" is on — the SPA route requires :id; the bare .../session URL is
        // only a temporary shell until the open-project script navigates.
        thisLogger().info("Loading OpenCode project page")
        openProjectScriptScheduled = false
        pendingMostRecentSessionId = null
        pendingOpenMostRecentConversation = false
        mostRecentSessionLookupInFlight = false
        pageLoadTargetUrl = null
        // No pre-load script scheduling here: onLoadStart cancels the alarm and resets the
        // per-page flags anyway, and onLoadStart/onLoadEnd (re)schedule everything for the new
        // document. The resets above only cover the case where the load never starts.
        injectedFeatures.forEach { it.scheduled = false }
        earlyInjectedFeatures.forEach { it.scheduled = false }
        openProjectAlarm.cancelAllRequests()
        applyBrowserZoom()
        // Events that fired before this panel started caring never reached the tracker.
        agentStatusTracker.seed()

        val openMostRecent = OpenCodeSettingsState.getInstance().openMostRecentConversationOnStartup
        val projectDirectory = openCodeProjectDirectory()?.takeIf { it.isNotBlank() }
        pendingOpenMostRecentConversation = openMostRecent
        // Paint immediately. Waiting for the session listing used to leave the panel blank for
        // the whole REST round-trip (and every paginated follow-up). The listing still runs in
        // parallel and navigates once, if it finishes with a parent session id.
        if (openMostRecent && projectDirectory != null) {
            mostRecentSessionLookupInFlight = true
            ApplicationManager.getApplication().executeOnPooledThread {
                val sessionId = fetchMostRecentSessionId(serverUrl, projectDirectory)
                ApplicationManager.getApplication().invokeLater {
                    if (isContentDisposed()) return@invokeLater
                    if (!loadIntent.accepts(
                            token = loadToken,
                            initialServerGeneration = serverGeneration,
                            currentServerGeneration = serverManager.getServerGeneration(),
                            initialServerUrl = serverUrl,
                            currentServerUrl = serverManager.getServerUrl(),
                            initialDirectory = projectDirectory,
                            currentDirectory = openCodeProjectDirectory(),
                            stillEnabled = OpenCodeSettingsState.getInstance().openMostRecentConversationOnStartup,
                        )
                    ) {
                        if (loadIntent.isCurrent(loadToken)) {
                            mostRecentSessionLookupInFlight = false
                            pendingOpenMostRecentConversation = false
                        }
                        return@invokeLater
                    }
                    mostRecentSessionLookupInFlight = false
                    pendingMostRecentSessionId = sessionId
                    if (sessionId == null) {
                        pendingOpenMostRecentConversation = false
                        return@invokeLater
                    }
                    applyResolvedStartupSession()
                }
            }
        }
        loadProjectPageAt(serverUrl, sessionId = null)
    }

    private fun applyResolvedStartupSession() {
        if (!pendingOpenMostRecentConversation || pendingMostRecentSessionId == null) return
        openProjectScriptScheduled = false
        if (mainDocumentLoadSucceeded && isBrowserOnOpenCodeServerPage(serverManager.getServerUrl() ?: return)) {
            scheduleOpenProjectScript()
        }
    }

    private fun loadProjectPageAt(serverUrl: String, sessionId: String?) {
        if (isContentDisposed()) return
        val url = OpenCodeServerProtocol.buildServerSessionUrl(serverUrl, sessionId)
        loadedServerRootUrl = url
        showCenterCard(BROWSER_CARD)
        beginPageLoad(url)
        ensureCefBrowser()
        loadAfterDocumentStartScripts(serverUrl) {
            // Same host:port after Stop (fixed port) is a CEF no-op if we only loadURL again.
            // reloadIgnoreCache retries the dead document; a new port takes the loadURL path.
            if (OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(serverUrl, browser.cefBrowser.url, url)) {
                browser.cefBrowser.reloadIgnoreCache()
            } else {
                browser.loadURL(url)
            }
            armPageLoadWatchdog(serverUrl)
        }
    }

    private fun installDocumentStartScripts(serverUrl: String): CompletableFuture<Boolean> {
        val script = buildString {
            earlyInjectedFeatures.forEach { feature ->
                if (!feature.enabledInSettings()) return@forEach
                val built = feature.buildScript(serverUrl)
                if (!built.isNullOrBlank()) {
                    append(built)
                    append('\n')
                }
            }
        }
        val guarded = OpenCodeDocumentStartInjector.guardForOrigin(
            script,
            OpenCodeServerProtocol.buildServerRootUrl(serverUrl),
        )
        return documentStartInjector.installAsync(guarded)
    }

    private fun loadAfterDocumentStartScripts(
        serverUrl: String,
        cancelIfDocumentRevisionChanges: Long? = null,
        load: () -> Unit,
    ) {
        val generation = ++pendingBrowserLoadGeneration
        val timeout = CompletableFuture.supplyAsync(
            { false },
            CompletableFuture.delayedExecutor(DOCUMENT_START_INSTALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )
        installDocumentStartScripts(serverUrl).applyToEither(timeout) { it }.whenComplete { installed, error ->
            if (error != null) {
                thisLogger().info("Could not prepare OpenCode document-start scripts: ${error.message}")
            } else if (!installed) {
                thisLogger().info("OpenCode document-start script was unavailable; using onLoadStart fallback")
            }
            ApplicationManager.getApplication().invokeLater {
                if (isContentDisposed() || generation != pendingBrowserLoadGeneration) return@invokeLater
                if (serverManager.getServerUrl() != serverUrl) return@invokeLater
                if (cancelIfDocumentRevisionChanges != null &&
                    (mainDocumentLoadRevision != cancelIfDocumentRevisionChanges || mainDocumentLoadSucceeded)
                ) {
                    return@invokeLater
                }
                if (!installed && documentStartInjector.hasInstalledScript()) {
                    thisLogger().warn("Keeping the current OpenCode page because its document-start script could not be replaced")
                    pageLoadInProgress = false
                    pageLoadStartedAtMillis = 0L
                    pageLoadTargetUrl = null
                    updateLifecycleIndicator()
                    return@invokeLater
                }
                mainDocumentLoadSucceeded = false
                load()
            }
        }
    }

    private fun ensureCefBrowser() {
        if (cefBrowserCreated) return
        browser.createImmediately()
        cefBrowserCreated = true
    }

    private fun beginPageLoad(targetUrl: String? = null, resetRetryBudget: Boolean = true) {
        mainDocumentLoadSucceeded = false
        pageLoadInProgress = true
        if (targetUrl != null) pageLoadTargetUrl = targetUrl
        if (resetRetryBudget) {
            pageLoadRetryCount = 0
            pageLoadStartedAtMillis = System.currentTimeMillis()
        } else if (pageLoadStartedAtMillis == 0L) {
            pageLoadStartedAtMillis = System.currentTimeMillis()
        }
        updateLifecycleIndicator()
    }

    private fun armPageLoadWatchdog(serverUrl: String) {
        val token = ++pageLoadWatchdogGeneration
        // Own alarm: onLoadStart cancels openProjectAlarm. Do not flip
        // mainDocumentLoadSucceeded here — a late arm must not undo onLoadEnd.
        pageLoadWatchdogAlarm.cancelAllRequests()
        pageLoadWatchdogAlarm.addRequest(
            {
                if (isContentDisposed() || token != pageLoadWatchdogGeneration) return@addRequest
                val elapsed = if (pageLoadStartedAtMillis == 0L) 0L else System.currentTimeMillis() - pageLoadStartedAtMillis
                if (!OpenCodePageLoadWatchdog.shouldRetry(mainDocumentLoadSucceeded, pageLoadRetryCount, elapsed)) {
                    if (!mainDocumentLoadSucceeded && pageLoadRetryCount >= OpenCodePageLoadWatchdog.MAX_RETRIES) {
                        thisLogger().warn("OpenCode page failed to load after ${OpenCodePageLoadWatchdog.MAX_RETRIES} retries")
                        pageLoadInProgress = false
                        pageLoadStartedAtMillis = 0L
                        updateLifecycleIndicator()
                    }
                    return@addRequest
                }
                pageLoadRetryCount++
                thisLogger().warn("OpenCode page load timed out; retrying ($pageLoadRetryCount/${OpenCodePageLoadWatchdog.MAX_RETRIES})")
                val liveUrl = serverManager.getServerUrl()
                if (liveUrl != serverUrl) return@addRequest
                val target = OpenCodePageLoadWatchdog.retryTarget(
                    liveUrl,
                    pageLoadTargetUrl,
                    browser.cefBrowser.url,
                )
                val stalledDocumentRevision = mainDocumentLoadRevision
                beginPageLoad(target, resetRetryBudget = false)
                ensureCefBrowser()
                loadAfterDocumentStartScripts(
                    liveUrl,
                    cancelIfDocumentRevisionChanges = stalledDocumentRevision,
                ) {
                    // JBCefBrowser coalesces duplicate requested URLs. The timed-out request is
                    // still recorded as loading, so retry through raw CEF after cancelling it.
                    browser.cefBrowser.stopLoad()
                    browser.cefBrowser.loadURL(target)
                    armPageLoadWatchdog(liveUrl)
                }
            },
            PAGE_LOAD_WATCHDOG_MILLIS,
        )
    }

    private fun clearStaleBrowserPage(state: OpenCodeServerLifecycleState) {
        if (state != OpenCodeServerLifecycleState.STOPPED &&
            state != OpenCodeServerLifecycleState.FAILED &&
            state != OpenCodeServerLifecycleState.RESTARTING
        ) {
            return
        }
        loadedServerRootUrl = null
        cancelStartupNavigation()
        pendingBrowserLoadGeneration++
        pageLoadWatchdogGeneration++
        pageLoadWatchdogAlarm.cancelAllRequests()
        pageLoadInProgress = false
        pageLoadStartedAtMillis = 0L
        pageLoadTargetUrl = null
        openProjectAlarm.cancelAllRequests()
        if (shouldHideEmbeddedPage(state)) {
            showCenterCard(IDLE_CARD)
        }
    }

    private fun applyBrowserZoom(zoomPercent: Int = OpenCodeSettingsState.getInstance().uiZoomPercent) {
        val zoomPercent = OpenCodeSettingsState.sanitizeUiZoomPercent(zoomPercent)
        browser.cefBrowser.zoomLevel = OpenCodeServerProtocol.toCefZoomLevel(zoomPercent)
    }

    private fun isOpenCodeProjectDestination(frameUrl: String?): Boolean {
        val serverUrl = serverManager.getServerUrl() ?: return false
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) return false
        val projectDirectory = openCodeProjectDirectory()?.takeIf { it.isNotBlank() } ?: return true
        // Directoryless routes (/server/<id>/session..., /new-session) do not reveal which
        // project they show, so they are NOT accepted as a destination here: the open-project
        // script must keep running and decide in-page against the SPA's own project state.
        // Blanket-accepting them stranded panels on another project's workspace, e.g. after a
        // project-directory rename or when another IDE project used the shared browser
        // profile last.
        return OpenCodeServerProtocol.isSameFilesystemPath(
            OpenCodeServerProtocol.routeDirectoryFromUrl(frameUrl),
            projectDirectory
        )
    }

    /**
     * True when the browser is on a session-less project shell (legacy `/encodedDir/session` or
     * 1.18 `/server/<key>/session` without an id). The open-project script must keep running there
     * so it can seed the project and navigate to a concrete session when one is known.
     */
    private fun isOpenCodeProjectRootRoute(frameUrl: String?): Boolean {
        val serverUrl = serverManager.getServerUrl() ?: return false
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) return false
        if (OpenCodeServerProtocol.sessionIdFromUrl(frameUrl) != null) return false
        val path = runCatching { java.net.URI(frameUrl).path?.trimEnd('/') }.getOrNull().orEmpty()
        if (path.endsWith("/session") && path.contains("/server/")) return true
        val projectDirectory = openCodeProjectDirectory()?.takeIf { it.isNotBlank() } ?: return false
        val projectUrl = OpenCodeServerProtocol.buildProjectUrl(serverUrl, projectDirectory)
        return frameUrl?.trimEnd('/') == projectUrl.trimEnd('/')
    }

    private fun scheduleOpenProjectScript() {
        if (openProjectScriptScheduled) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val projectDirectory = openCodeProjectDirectory()?.takeIf { it.isNotBlank() } ?: return
        openProjectScriptScheduled = true

        // One-shot boot intent only — do not re-read the setting on every SPA navigation, or a
        // later full page load would yank the user back to the startup conversation.
        val openMostRecent = pendingOpenMostRecentConversation
        val pendingId = pendingMostRecentSessionId?.takeIf { openMostRecent }
        if (!OpenCodeStartupNavigation.shouldKeepNavigateIntent(
                openMostRecent,
                pendingId,
                mostRecentSessionLookupInFlight,
            )
        ) {
            pendingOpenMostRecentConversation = false
            scheduleOpenProjectScript(
                serverUrl,
                projectDirectory,
                openMostRecentConversation = false,
                mostRecentSessionId = null,
            )
            return
        }
        if (pendingId == null) {
            // Listing still in flight: seed now, navigate when the id arrives.
            scheduleOpenProjectScript(
                serverUrl,
                projectDirectory,
                openMostRecentConversation = false,
                mostRecentSessionId = null,
            )
            return
        }
        scheduleOpenProjectScript(
            serverUrl,
            projectDirectory,
            openMostRecentConversation = true,
            mostRecentSessionId = pendingId,
        )
    }

    private fun fetchMostRecentSessionId(serverUrl: String, projectDirectory: String): String? {
        val password = serverManager.getServerPassword() ?: return null
        return OpenCodeServerProtocol.fetchRecentSessions(
            serverUrl,
            OpenCodeServerProtocol.buildBasicAuthHeader(password),
            projectDirectory,
            maxAgeMillis = Long.MAX_VALUE,
            // The listing is creation-ordered (see fetchRecentSessions); a large window keeps a
            // long-running conversation findable even after many later-created subagent sessions.
            limit = 100,
        )
            .filter { it.parentID == null } // never navigate to a subagent child session
            .maxByOrNull { it.updatedMillis }
            ?.id
    }

    private fun scheduleOpenProjectScript(
        serverUrl: String,
        projectDirectory: String,
        openMostRecentConversation: Boolean,
        mostRecentSessionId: String?,
    ) {
        val script = OpenCodeBrowserSnippets.buildOpenProjectScript(
            projectDirectory,
            serverUrl,
            openMostRecentConversation,
            mostRecentSessionId,
        ) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)

        scriptScheduler.schedule(script, rootUrl) {
            if (!isBrowserOnOpenCodeServerPage(serverUrl)) return@schedule false
            val frameUrl = browser.cefBrowser.url
            // Navigate series: stop (and clear boot intent) once the target session is open so
            // later SPA navigations only re-seed. Seed-only series keep running on directoryless
            // routes / project roots that still need lastProject binding.
            if (openMostRecentConversation && mostRecentSessionId != null) {
                val onTarget = OpenCodeServerProtocol.sessionIdFromUrl(frameUrl) == mostRecentSessionId
                if (onTarget) {
                    pendingOpenMostRecentConversation = false
                    pendingMostRecentSessionId = null
                    return@schedule false
                }
                return@schedule true
            }
            val onProjectRootRoute = isOpenCodeProjectRootRoute(frameUrl)
            val onProjectDestination = isOpenCodeProjectDestination(frameUrl)
            !onProjectDestination || onProjectRootRoute
        }
    }

    /**
     * Injects [feature] from `onLoadStart`: executes the script immediately (before the SPA
     * bundle runs) and retries on the early delay series in case the first attempt ran before
     * the new document's V8 context was ready. The builder is re-invoked per attempt so it
     * always reflects current IDE state. The open-project seed must be first: post-load injects
     * alone race the SPA's first read of the shared browser profile and can leave the panel
     * bound to another IDE project's workspace.
     */
    private fun injectEarlyFeature(feature: EarlyInjectedFeature) {
        if (feature.scheduled) return
        if (!feature.enabledInSettings()) return
        val serverUrl = serverManager.getServerUrl() ?: return
        val script = feature.buildScript(serverUrl) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        feature.scheduled = true
        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        scriptScheduler.scheduleAction(
            early = true,
            shouldRun = { feature.enabledInSettings() && isBrowserOnOpenCodeServerPage(serverUrl) },
        ) {
            feature.buildScript(serverUrl)?.let { browser.cefBrowser.executeJavaScript(it, rootUrl, 0) }
        }
    }

    /** Schedules [feature]'s script for retried injection into the current page load. */
    private fun scheduleFeatureScript(feature: InjectedFeature) {
        if (feature.scheduled) return
        if (!feature.enabledInSettings()) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = feature.buildScript() ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        feature.scheduled = true

        // These scripts install document-level listeners and carry their own idempotence guards;
        // no DOM target needs to exist first. Install immediately so a fast first click/paste after
        // load cannot slip through, then retain retries for SPA/browser timing resilience.
        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        scriptScheduler.schedule(script, rootUrl) {
            feature.enabledInSettings() && isBrowserOnOpenCodeServerPage(serverUrl)
        }
    }

    /**
     * Applies a runtime toggle of [feature]: injects the script when enabled, or reloads the
     * page when disabled so previously installed listeners and patches are fully removed
     * (per the safeguard contract, never a "disable" script).
     */
    private fun applyFeature(feature: InjectedFeature, enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        val decision = OpenCodeInjectedFeaturePolicy.decide(
            enabled = enabled,
            enabledInSettings = feature.enabledInSettings(),
            onOpenCodePage = isBrowserOnOpenCodeServerPage(serverUrl),
            script = if (enabled && feature.enabledInSettings()) feature.buildScript() else null,
        )
        if (decision.clearScheduled) feature.scheduled = false
        when (decision.action) {
            OpenCodeInjectedFeaturePolicy.Action.NONE -> return
            OpenCodeInjectedFeaturePolicy.Action.RELOAD -> {
                feature.onDisable()
                pendingBrowserLoadGeneration++
                beginPageLoad(browser.cefBrowser.url)
                browser.cefBrowser.reload()
            }
            OpenCodeInjectedFeaturePolicy.Action.INJECT -> {
                val script = decision.script ?: return
                browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
                if (decision.markScheduled) feature.scheduled = true
            }
        }
    }

    private fun scheduleIdeThemeSyncScript() {
        if (ideThemeSyncFeature.scheduled) return
        if (!OpenCodeSettingsState.getInstance().syncThemeWithIde) return

        val serverUrl = serverManager.getServerUrl() ?: return
        ideThemeSyncFeature.scheduled = true

        scriptScheduler.scheduleAction(
            shouldRun = { OpenCodeSettingsState.getInstance().syncThemeWithIde && isBrowserOnOpenCodeServerPage(serverUrl) },
            action = { executeIdeThemeSyncScript(serverUrl) },
        )
    }

    /**
     * Applies a status transition reported by [agentStatusTracker] to the tool-window badge. Runs
     * on the event-stream reader thread or a pooled thread; the badge update dispatches to the EDT
     * itself. File/VCS refresh is handled separately by [workspaceRefreshCoordinator] so it works
     * even when the badge is disabled.
     */
    private fun onAgentStatusChanged(state: String, presentationRevision: Long) {
        ApplicationManager.getApplication().invokeLater {
            if (isContentDisposed()) return@invokeLater
            if (!OpenCodeSettingsState.getInstance().showAgentStatusBadge) return@invokeLater
            if (!agentStatusTracker.isCurrentPresentation(state, presentationRevision)) return@invokeLater
            toolWindow.setIcon(
                when (state) {
                    OpenCodeAgentStatusState.ATTENTION -> toolWindowIconSupplier.warningIcon
                    OpenCodeAgentStatusState.BUSY -> toolWindowIconSupplier.liveIndicatorIcon
                    else -> toolWindowIconSupplier.originalIcon
                },
            )
        }
    }

    private fun resetAgentStatusBadge() {
        if (isContentDisposed()) return
        agentStatusTracker.reset()
        toolWindow.setIcon(toolWindowIconSupplier.originalIcon)
    }

    /**
     * The badge is fed from the Kotlin-side event stream, so toggling it needs no page
     * reload: disabling clears the badge, enabling re-seeds the tracker from the REST API.
     */
    private fun applyAgentStatusBadge(enabled: Boolean) {
        resetAgentStatusBadge()
        if (enabled) agentStatusTracker.seed()
    }

    private fun isBrowserOnOpenCodeServerPage(serverUrl: String): Boolean {
        return OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)
    }

    /**
     * Applies the mirrored page cursor to the whole browser component tree: in off-screen
     * rendering the deepest Swing component under the pointer decides the visible cursor,
     * and the platform may have left a stale cursor on it.
     */
    private fun applyBrowserCursor(cursor: Cursor) {
        fun apply(component: Component) {
            component.cursor = cursor
            if (component is Container) component.components.forEach(::apply)
        }
        apply(browser.component)
    }

    private fun applyIdeThemeSync(enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        ideThemeSyncFeature.scheduled = false
        if (!enabled) {
            beginPageLoad(browser.cefBrowser.url)
            ensureCefBrowser()
            loadAfterDocumentStartScripts(serverUrl) {
                browser.cefBrowser.reload()
                armPageLoadWatchdog(serverUrl)
            }
            return
        }
        installDocumentStartScripts(serverUrl)
        if (executeIdeThemeSyncScript(serverUrl)) ideThemeSyncFeature.scheduled = true
    }

    /** Code navigation piggybacks on file-link navigation, so a toggle here re-applies both. */
    private fun applyFileLinkNavigation(enabled: Boolean) {
        codeNavigationFeature.scheduled = false
        applyFeature(fileLinkFeature, enabled)
        if (enabled && OpenCodeSettingsState.getInstance().enableCodeNavigation) {
            applyFeature(codeNavigationFeature, enabled = true)
        }
    }

    private fun applyOpenCodeProjectDirectoryChange() {
        cancelStartupNavigation()
        openProjectScriptScheduled = false
        openProjectSeedFeature.scheduled = false
        fileLinkFeature.scheduled = false
        openProjectAlarm.cancelAllRequests()
        // The badge state belongs to the previous directory; loadProjectPage re-seeds.
        resetAgentStatusBadge()
        checkAndLoadContent()
    }

    private fun navigateFromNotification(targetUrl: String) {
        cancelStartupNavigation()
        openProjectScriptScheduled = false
        openProjectAlarm.cancelAllRequests()
        val serverUrl = serverManager.getServerUrl() ?: return
        beginPageLoad(targetUrl)
        ensureCefBrowser()
        loadAfterDocumentStartScripts(serverUrl) {
            browser.loadURL(targetUrl)
            armPageLoadWatchdog(serverUrl)
        }
    }

    private fun openCodeProjectDirectory(): String? {
        return OpenCodeProjectSettingsState.getInstance(project).effectiveProjectDirectory(project.basePath)
    }

    /** Opens Chromium's built-in DevTools window for this panel's browser (JBCef built-in). */
    fun openBrowserDevTools() {
        if (isContentDisposed()) return
        browser.openDevtools()
        notifyAboutDevToolsCredentials()
    }

    /**
     * The DevTools window is a separate browser without the panel's auth handlers, so its own
     * server fetches (e.g. source maps) can trigger Chromium's basic-auth prompt. Authenticating
     * it programmatically is a documented dead end — JPMS blocks the reflective browser lookup,
     * out-of-process JCEF hides the DevTools browser from the JVM entirely, and auth-cache
     * priming interfered with the app's own session — so instead tell the user which
     * credentials to enter, with the password one click away.
     */
    private fun notifyAboutDevToolsCredentials() {
        if (devToolsCredentialsNotified) return
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
            ?: return
        devToolsCredentialsNotified = true
        val notification = group.createNotification(
            "Browser DevTools sign-in",
            "If DevTools asks you to sign in, use the username \"${OpenCodeServerProtocol.BASIC_AUTH_USERNAME}\" " +
                "and the OpenCode server password.",
            NotificationType.INFORMATION,
        )
        serverManager.getServerPassword()?.let { password ->
            notification.addAction(
                NotificationAction.createSimple("Copy password") {
                    CopyPasteManager.getInstance().setContents(StringSelection(password))
                },
            )
        }
        notification.addAction(
            NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCodeSettingsConfigurable::class.java)
            },
        )
        notification.notify(project)
    }

    private fun executeIdeThemeSyncScript(serverUrl: String): Boolean {
        val script = OpenCodeBrowserSnippets.buildIdeThemeSyncScript(
            enabled = OpenCodeSettingsState.getInstance().syncThemeWithIde,
            dark = isIdeDarkTheme(),
        ) ?: return false
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        return true
    }

    private fun isIdeDarkTheme(): Boolean {
        return LafManager.getInstance().currentUIThemeLookAndFeel?.isDark == true
    }

    private fun applyCompactLayout() {
        // Toggling requires a page reload — early injection on next load start
        reloadForEarlyFeatureToggle(compactLayoutFeature)
    }

    private fun applyHideWebsiteButton() {
        // Off → reload so listeners/stylesheets are fully removed (safeguard contract).
        // On → reload so early inject runs before SPA chrome mounts.
        reloadForEarlyFeatureToggle(hideWebsiteButtonFeature)
    }

    private fun forceEventStreamReconnect() {
        if (!OpenCodeSettingsState.getInstance().recoverStalledEventStream) return
        val serverUrl = serverManager.getServerUrl() ?: return
        ApplicationManager.getApplication().invokeLater {
            if (isContentDisposed()) return@invokeLater
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return@invokeLater
            browser.cefBrowser.executeJavaScript(
                OpenCodeBrowserSnippets.buildForceEventReconnectScript(),
                OpenCodeServerProtocol.buildServerRootUrl(serverUrl),
                0,
            )
        }
    }

    private fun applyEventStreamWatchdog() {
        // Off → reload so the patched window.fetch is replaced by the untouched original.
        // On → reload so the patch is in place before the SPA bundle captures window.fetch.
        reloadForEarlyFeatureToggle(eventStreamWatchdogFeature)
    }

    /**
     * Applies a runtime toggle of an early-injected feature. Both directions reload the page:
     * off so previously installed patches are fully removed (safeguard contract, never a
     * "disable" script), on so the script runs before the SPA bundle on the next load start.
     */
    private fun reloadForEarlyFeatureToggle(feature: EarlyInjectedFeature) {
        feature.scheduled = false
        val serverUrl = serverManager.getServerUrl() ?: return
        if (OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) {
            beginPageLoad(browser.cefBrowser.url)
            ensureCefBrowser()
            loadAfterDocumentStartScripts(serverUrl) {
                browser.cefBrowser.reload()
                armPageLoadWatchdog(serverUrl)
            }
        }
    }

    private fun showErrorInBrowser() {
        if (isContentDisposed()) return
        loadedServerRootUrl = null
        pendingBrowserLoadGeneration++
        pageLoadInProgress = false
        pageLoadStartedAtMillis = 0L
        pageLoadTargetUrl = null
        updateLifecycleIndicator()
        startupErrorPanel.showFailure(
            OpenCodeSettingsState.getInstance().executablePath(),
            serverManager.getServerLogFile(),
        )
        showCenterCard(ERROR_CARD)
    }

    private fun showCenterCard(card: String) {
        centerCardLayout.show(centerCardPanel, card)
        centerCardPanel.revalidate()
        centerCardPanel.repaint()
    }

    private fun cancelStartupNavigation() {
        loadIntent.invalidate()
        mostRecentSessionLookupInFlight = false
        pendingOpenMostRecentConversation = false
        pendingMostRecentSessionId = null
    }

    internal fun displayedSessionID(): String? {
        return OpenCodeServerProtocol.sessionIdFromUrl(browser.cefBrowser.url)
    }

    /**
     * Resolves the displayed session's lineage as soon as its route appears (full load or SPA
     * navigation), so the Auto-Accept Permissions gear action reflects an inherited parent
     * enable on the first menu open instead of only after the menu's own update kicked the
     * async fetch. Safe to call from CEF handler threads: [prepareSession][OpenCodePermissionAutoResponder.prepareSession]
     * only touches concurrent maps and hops to a pooled thread for the REST walk.
     */
    private fun prepareDisplayedSessionLineage(url: String?) {
        OpenCodeServerProtocol.sessionIdFromUrl(url)?.let(permissionAutoResponder::prepareSession)
    }

    /** Session-scoped and includes subagent children through their parent lineage. */
    internal fun isPermissionAutoAcceptEnabled(): Boolean {
        val sessionID = displayedSessionID() ?: return false
        permissionAutoResponder.prepareSession(sessionID)
        return permissionAutoResponder.isEffectivelyEnabled(sessionID)
    }

    internal fun setPermissionAutoAcceptEnabled(enabled: Boolean) {
        displayedSessionID()?.let { permissionAutoResponder.setEffectivelyEnabled(it, enabled) }
    }

    internal fun canTogglePermissionAutoAccept(): Boolean {
        // Enabled whenever a session is displayed. Toggling is valid without a resolved
        // lineage: a session's own override wins over any inherited one, and the lineage is
        // prepared proactively on navigation so the check state reflects parent enables.
        // Gating on isLineagePrepared blocked the action until the menu's own update had
        // triggered (and a round-trip had finished) the async lineage fetch — the menu had
        // to be opened twice.
        val sessionID = displayedSessionID() ?: return false
        permissionAutoResponder.prepareSession(sessionID)
        return true
    }

    private fun isContentDisposed(): Boolean {
        return disposed || project.isDisposed
    }

    override fun dispose() {
        disposed = true
        pendingBrowserLoadGeneration++
        permissionAutoResponder.dispose()
        if (!project.isDisposed) {
            OpenCodeChatInputService.getInstance(project).setDispatcher(null)
        }
        openProjectAlarm.cancelAllRequests()
        pageLoadWatchdogAlarm.cancelAllRequests()
        repaintAlarm.cancelAllRequests()
        componentSizeRestoreAlarm.cancelAllRequests()
        systemNotifications.dispose()
        if (isApplicationShutdownInProgress()) return
        Disposer.dispose(browser)
    }

    private fun isApplicationShutdownInProgress(): Boolean {
        return applicationClosing || ApplicationManager.getApplication()?.isDisposed == true
    }
}
