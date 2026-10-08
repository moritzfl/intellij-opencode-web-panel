package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.util.Alarm
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptScheduler
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.browser.OpenCodeDocumentStartInjector
import de.moritzf.opencodewebpanel.browser.OpenCodeJsQuery
import de.moritzf.opencodewebpanel.browser.createOpenCodeBrowserBeforeReplacement
import de.moritzf.opencodewebpanel.features.OpenCodeAgentStatusState
import de.moritzf.opencodewebpanel.features.OpenCodeAgentStatusTracker
import de.moritzf.opencodewebpanel.features.OpenCodeCefFileDialogHandler
import de.moritzf.opencodewebpanel.features.OpenCodeChatInputService
import de.moritzf.opencodewebpanel.features.OpenCodeDiffNavigation
import de.moritzf.opencodewebpanel.features.OpenCodeFileDropHandler
import de.moritzf.opencodewebpanel.features.OpenCodeForeignSessionWarning
import de.moritzf.opencodewebpanel.features.OpenCodeIdeNavigation
import de.moritzf.opencodewebpanel.features.OpenCodeInterruptedSessionRecovery
import de.moritzf.opencodewebpanel.features.OpenCodeLocalStorageBridge
import de.moritzf.opencodewebpanel.features.OpenCodePermissionAutoResponder
import de.moritzf.opencodewebpanel.features.OpenCodeReleaseUpdates
import de.moritzf.opencodewebpanel.features.OpenCodeSystemNotifications
import de.moritzf.opencodewebpanel.features.OpenCodeWorkspaceRefreshCoordinator
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeHostPaths
import de.moritzf.opencodewebpanel.server.OpenCodeLifecycleStripModel
import de.moritzf.opencodewebpanel.server.OpenCodeRecoveryNotice
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeSuspendResumeListener
import de.moritzf.opencodewebpanel.server.OpenCodeWireProtocol
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxFailureKind
import de.moritzf.opencodewebpanel.server.SbxOpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.isSuccessfulOpenCodeDocumentLoad
import de.moritzf.opencodewebpanel.server.parkedEmbeddedCenterCard
import de.moritzf.opencodewebpanel.server.shouldApplyPublishedLifecycleState
import de.moritzf.opencodewebpanel.server.shouldHideEmbeddedPage
import de.moritzf.opencodewebpanel.server.shouldShowPageOpeningStatus
import de.moritzf.opencodewebpanel.server.shouldShowStartupError
import de.moritzf.opencodewebpanel.server.shouldTickLifecycleStrip
import de.moritzf.opencodewebpanel.server.visibleRecoveryNotice
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeRestartScope
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeUiSetting
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest

internal class OpenCodeWebToolWindowContent(private val host: OpenCodePanelController) :
    OpenCodePanel {

    private companion object {
        private const val BROWSER_CARD = "browser"
        private const val ERROR_CARD = "error"
        private const val IDLE_CARD = "idle"
        private const val BROWSER_RECOVERY_THROTTLE_MILLIS = 10_000L
        private const val PAGE_LOAD_WATCHDOG_MILLIS =
            OpenCodePageLoadWatchdog.DEFAULT_TIMEOUT_MILLIS
        private const val DOCUMENT_START_INSTALL_TIMEOUT_MILLIS =
            OpenCodePageLoadWatchdog.DOCUMENT_START_INSTALL_TIMEOUT_MILLIS

        // Delay after a project-page load before flushing queued chat input, so the SPA's own
        // drop handlers are installed when the synthetic drop is dispatched.
        private const val PENDING_CHAT_INPUT_FLUSH_DELAY_MILLIS = 1_500
        private const val CHAT_INPUT_ACK_TIMEOUT_MILLIS = 3_000
        private const val VIEWPORT_RASTER_NUDGE_RETRY_MILLIS = 500

        @Volatile private var applicationClosing = false

        /**
         * Application-wide: JCEF is shared, so a callback-channel failure in one panel is a
         * property of the CEF server, not of a single project's tool window.
         */
        @Volatile private var lastCallbackRecoveryAtMillis = 0L

        /**
         * One notification per IDE session, application-wide: Chromium caches the entered
         * credentials for the lifetime of the shared JCEF process, so after the first sign-in later
         * DevTools windows (from any project) no longer prompt.
         */
        @Volatile private var devToolsCredentialsNotified = false
    }

    private val project = host.project
    private val browser = JBCefBrowser()
    private val lifecycleStatusPanel =
        OpenCodeLifecycleStatusPanel(
            onRetry = ::restartOpenCodeServer,
            onViewLog = { openOpenCodeServerLogInEditor(project) },
            onCancel = { serverManager.stopServer() },
        )
    @Suppress("UnstableApiUsage")
    private val stripTickAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lastPanelRecovery: OpenCodeRecoveryNotice? = null
    private val startupErrorPanel = OpenCodeStartupErrorPanel(project, ::restartOpenCodeServer)
    private val centerCardLayout = CardLayout()
    private val idleCard =
        OpenCodeIdleCard(
            onStart = ::restartOpenCodeServer,
            onCancel = { serverManager.stopServer() },
            onViewLog = { openOpenCodeServerLogInEditor(project) },
        )
    private val centerCardPanel =
        JPanel(centerCardLayout).apply {
            add(browser.component, BROWSER_CARD)
            add(startupErrorPanel.component, ERROR_CARD)
            add(idleCard.component, IDLE_CARD)
        }
    private val contentPanel =
        BorderLayoutPanel().apply {
            addToTop(lifecycleStatusPanel.component)
            addToCenter(centerCardPanel)
        }
    private lateinit var openFileLinkQuery: OpenCodeJsQuery
    private lateinit var openCodeReferenceQuery: OpenCodeJsQuery
    private lateinit var openCodeLocalStorageQuery: OpenCodeJsQuery
    private lateinit var openExternalLinkQuery: OpenCodeJsQuery
    private lateinit var browserCursorQuery: OpenCodeJsQuery
    private lateinit var openDiffQuery: OpenCodeJsQuery
    private lateinit var chunkLoadErrorQuery: OpenCodeJsQuery
    private lateinit var chatInputResultQuery: OpenCodeJsQuery
    private lateinit var rendererHeartbeatQuery: OpenCodeJsQuery
    @Volatile private var pageChannelsAttached = false
    private val browserCursorEpoch = AtomicLong()
    private val serverManager = OpenCodeServerBackendRegistry.getInstance().backendFor(project)
    private val ideNavigation =
        OpenCodeIdeNavigation(
            project,
            browser,
            serverManager,
            ::openCodeProjectDirectory,
            this,
            ::openCodeServerDirectory,
        )
    private val diffNavigation =
        OpenCodeDiffNavigation(
            project,
            browser,
            serverManager,
            ::openCodeProjectDirectory,
            ::openCodeServerDirectory,
        )
    private val localStorageBridge =
        OpenCodeLocalStorageBridge(
            browser,
            serverManager,
            syncCallback = { openCodeLocalStorageQuery.inject("payload") },
        )
    private val systemNotifications =
        OpenCodeSystemNotifications(
            project,
            browser,
            serverManager,
            ::openCodeServerDirectory,
            ::navigateFromNotification,
            panelIsInView = { browser.component.isShowing && host.isPanelInView() },
            activatePanel = host::activate,
            this,
        )
    private val requestHandler =
        OpenCodeBrowserRequestHandler(serverManager, ideNavigation, ::recoverFromRendererCrash)
    private val interruptedSessionRecovery =
        OpenCodeInterruptedSessionRecovery(project, serverManager, ::openCodeServerDirectory)
    private val permissionAutoResponder =
        OpenCodePermissionAutoResponder(
            ::openCodeServerDirectory,
            serverManager::getServerUrl,
            serverManager::getServerPassword,
            loadSession = { url, auth, directory, sessionID ->
                OpenCodeServerProtocol.fetchSessionInfo(
                    url,
                    auth,
                    directory,
                    sessionID,
                    wireProtocol = serverManager.getWireProtocol(),
                )
            },
            loadChildren = { url, auth, directory, sessionID ->
                OpenCodeServerProtocol.fetchSessionChildren(
                    url,
                    auth,
                    directory,
                    sessionID,
                    wireProtocol = serverManager.getWireProtocol(),
                )
            },
            loadPending = { url, auth, directory ->
                OpenCodeServerProtocol.fetchPendingRequestsResult(
                    url,
                    auth,
                    OpenCodeServerProtocol.PERMISSION_LIST_PATH,
                    directory,
                    wireProtocol = serverManager.getWireProtocol(),
                )
            },
            reply = { url, auth, directory, sessionID, requestID, response ->
                OpenCodeServerProtocol.replyToPermission(
                    url,
                    auth,
                    directory,
                    sessionID,
                    requestID,
                    response,
                    wireProtocol = serverManager.getWireProtocol(),
                )
            },
            backendId = { serverManager.backendId },
        )
    private var foreignSessionNotification: Notification? = null
    private val foreignSessionWarning =
        OpenCodeForeignSessionWarning(
            enabled = { OpenCodeSettingsState.getInstance().warnForeignSession },
            workspaceDirectory = ::openCodeProjectDirectory,
            loadSession = { sessionID -> loadDisplayedSession(sessionID) },
            guestToHostPrefixes = {
                OpenCodeHostPaths.guestToHostPrefixes(
                    serverManager.backendId,
                    openCodeProjectDirectory(),
                    sandboxWorkspaceDirectory(),
                )
            },
            sandboxGuestPath = { workspace ->
                if (OpenCodeServerBackend.isNative(serverManager.backendId)) null
                else SbxCli.guestBindPath(workspace)
            },
            executeAsync = { task ->
                ApplicationManager.getApplication().executeOnPooledThread(task)
            },
            notify = ::showForeignSessionWarning,
            clearWarning = ::clearForeignSessionWarning,
            onOutline = ::applyForeignSessionOutline,
        )
    @Suppress("UnstableApiUsage")
    private val openProjectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Suppress("UnstableApiUsage")
    private val pageLoadWatchdogAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    // Own alarm: the page-load alarms are cancelled on every navigation, and this request must
    // survive the panel's initial load.
    @Suppress("UnstableApiUsage")
    private val panelRecoveryAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Suppress("UnstableApiUsage")
    private val repaintAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scriptScheduler = OpenCodeBrowserScriptScheduler(project, browser, openProjectAlarm)
    private val repaintScheduler = OpenCodeBrowserScriptScheduler(project, browser, repaintAlarm)
    private val browserFocusSync =
        OpenCodeBrowserFocusSync(
            component = { browser.component },
            isActive = { !isContentDisposed() },
            setBrowserFocus = { browser.cefBrowser.setFocus(it) },
        )
    private var openProjectScriptScheduled = false
    private var panelReplacementScheduled = false
    private val documentStartInjector = OpenCodeDocumentStartInjector(browser)
    private val pageLifecycle = OpenCodePageLifecycle()
    private var cefBrowserCreated = false

    // Bumps on every chunk-failure report from the injected listener; a full-load (onLoadStart)
    // snapshots it so a pre-load error cannot satisfy the fresh document's debounce.
    private val pageLoadChunkFailureGeneration = AtomicLong()

    private val injections =
        OpenCodePanelInjections(
            browser,
            serverManager,
            scriptScheduler,
            ::openCodeServerDirectory,
            query = { setting ->
                when (setting) {
                    OpenCodeUiSetting.FILE_LINK_NAVIGATION -> openFileLinkQuery
                    OpenCodeUiSetting.EXTERNAL_LINK_NAVIGATION -> openExternalLinkQuery
                    OpenCodeUiSetting.CODE_NAVIGATION -> openCodeReferenceQuery
                    OpenCodeUiSetting.DIFF_NAVIGATION -> openDiffQuery
                    OpenCodeUiSetting.BROWSER_CURSOR_MIRROR -> browserCursorQuery
                    OpenCodeUiSetting.CHUNK_LOAD_RECOVERY -> chunkLoadErrorQuery
                    OpenCodeUiSetting.RENDERER_WATCHDOG -> rendererHeartbeatQuery
                    else -> error("No callback channel for $setting")
                }
            },
            resetMirroredBrowserCursor = ::resetMirroredBrowserCursor,
            reloadPage = {
                pageLifecycle.invalidatePendingLoad()
                beginPageLoad(browser.cefBrowser.url)
                browser.cefBrowser.reload()
            },
        )

    private fun allJsQueries(): List<OpenCodeJsQuery> {
        if (!pageChannelsAttached) return emptyList()
        return listOf(
            openFileLinkQuery,
            openCodeReferenceQuery,
            openCodeLocalStorageQuery,
            openExternalLinkQuery,
            browserCursorQuery,
            openDiffQuery,
            chunkLoadErrorQuery,
            chatInputResultQuery,
            rendererHeartbeatQuery,
        )
    }

    private val workspaceRefreshCoordinator =
        OpenCodeWorkspaceRefreshCoordinator(
            project,
            ::sandboxWorkspaceDirectory,
            parentDisposable = this,
            backendId = { serverManager.backendId },
            serverDirectory = ::openCodeServerDirectory,
        )
    // The tracked state also feeds the renderer watchdog's busy stall timeout, so tracking runs
    // even when the badge itself is off; [onAgentStatusChanged] applies the badge only when its
    // own setting is enabled.
    private val agentStatusTracker =
        OpenCodeAgentStatusTracker(
            projectDirectory = ::openCodeServerDirectory,
            enabled = { !isContentDisposed() },
            onStateChanged = ::onAgentStatusChanged,
            serverUrl = serverManager::getServerUrl,
            serverPassword = serverManager::getServerPassword,
            serverGeneration = serverManager::getServerGeneration,
            backendId = { serverManager.backendId },
            wireProtocol = { serverManager.getWireProtocol() },
        )
    private var loadedServerRootUrl: String? = null
    private var pendingServerStartRequest = false
    private var lastBrowserRecoveryAttemptAtMillis = 0L
    private val rendererWatchdog =
        OpenCodeRendererWatchdog(
            parentDisposable = this,
            isActiveContent = { !isContentDisposed() && browser.component.isShowing },
            isAgentBusy = { agentStatusTracker.isBusy() },
            isEnabledInSettings = { OpenCodeSettingsState.getInstance().recoverStalledRenderer },
            isPageReady = {
                OpenCodeRendererWatchdogPolicy.isPageReadyForRendererWatchdog(
                    pageLoadInProgress = pageLifecycle.loadInProgress,
                    pagePainted = pageLifecycle.painted,
                    loadSucceeded = pageLifecycle.loadSucceeded,
                    loadGaveUp = pageLifecycle.gaveUp,
                )
            },
            onReloadPage = { reloadStalledOpenCodePage() },
            onRecreatePanel = {
                ApplicationManager.getApplication().invokeLater {
                    if (isContentDisposed()) return@invokeLater
                    // Share the browser-recovery throttle with renderer-crash recovery so the two
                    // paths cannot interleave into a recreate storm.
                    if (!markBrowserRecoveryAttempt()) return@invokeLater
                    host.replacePanel()
                }
            },
            onGiveUp = {
                ApplicationManager.getApplication().invokeLater {
                    if (isContentDisposed()) return@invokeLater
                    showRendererGiveUpCard()
                }
            },
        )
    private val toolWindowIconSupplier by lazy {
        BadgeIconSupplier(
            IconLoader.getIcon("/icons/opencode.svg", OpenCodeWebToolWindowContent::class.java)
        )
    }

    @Volatile private var disposed = false
    private val loadHandler =
        object : CefLoadHandlerAdapter() {
            override fun onLoadStart(
                browser: CefBrowser?,
                frame: CefFrame?,
                transitionType: CefRequest.TransitionType?,
            ) {
                if (isContentDisposed()) return
                if (frame?.isMain == true) {
                    thisLogger()
                        .info("jcef onLoadStart url=${frame.url} transition=$transitionType")
                    val loadRevision = pageLifecycle.documentStarted()
                    // New document, new chunk-failure budget: the in-page listener re-arms and may
                    // report once more even if the previous page already did.
                    pageLoadChunkFailureGeneration.set(0L)
                    repaintAlarm.cancelAllRequests()
                    // Move the stall clock so an in-flight navigation is not recovered; keep the
                    // recreate budget (process-wide) and this panel's stall count.
                    rendererWatchdog.noteDocumentLoadStarted()
                    if (host.isCurrent(this@OpenCodeWebToolWindowContent)) {
                        OpenCodeChatInputService.getInstance(project).requeueInFlight()
                    }
                    injections.resetScheduled()
                    val serverUrl = serverManager.getServerUrl()
                    val frameUrl = frame.url
                    if (OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) {
                        ApplicationManager.getApplication().invokeLater {
                            if (
                                isContentDisposed() ||
                                    loadRevision != pageLifecycle.documentRevision ||
                                    pageLifecycle.loadSucceeded
                            ) {
                                return@invokeLater
                            }
                            val liveUrl = serverManager.getServerUrl() ?: return@invokeLater
                            if (!OpenCodeServerProtocol.isOpenCodeServerPage(liveUrl, frameUrl))
                                return@invokeLater
                            val sameLoad =
                                pageLifecycle.loadInProgress &&
                                    OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                                        liveUrl,
                                        pageLifecycle.targetUrl,
                                        frameUrl,
                                    )
                            beginPageLoad(frameUrl, resetRetryBudget = !sameLoad)
                            armPageLoadWatchdog(liveUrl)
                        }
                        // Drop any previous open-project delay series so navigations do not stack
                        // injects.
                        // Reset the flag so onLoadEnd can schedule a fresh series for this
                        // document.
                        openProjectAlarm.cancelAllRequests()
                        openProjectScriptScheduled = false
                        localStorageBridge.restore(frame.url)
                        // Seed lastProject (first early feature) before the SPA bundle reads
                        // localStorage — the shared browser profile otherwise keeps the previous
                        // IDE
                        // project's workspace.
                        injections.earlyInjectedFeatures.forEach(injections::injectEarlyFeature)
                        localStorageBridge.installSync(frame.url)
                    }
                }
            }

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (isContentDisposed()) return
                if (frame?.isMain != true) return
                thisLogger().info("jcef onLoadEnd url=${frame.url} status=$httpStatusCode")
                if (!isSuccessfulOpenCodeDocumentLoad(httpStatusCode)) return
                val completedUrl = frame.url
                if (
                    !OpenCodeServerProtocol.isOpenCodeServerPage(
                        serverManager.getServerUrl(),
                        completedUrl,
                    )
                )
                    return
                val completedRevision = pageLifecycle.documentRevision
                ApplicationManager.getApplication().invokeLater {
                    if (isContentDisposed()) return@invokeLater
                    if (completedRevision != pageLifecycle.documentRevision) return@invokeLater
                    noteOpenCodePageVisible(completedUrl)
                    val liveUrl = serverManager.getServerUrl() ?: return@invokeLater
                    if (!OpenCodeServerProtocol.isOpenCodeServerPage(liveUrl, completedUrl))
                        return@invokeLater

                    // JBCef's own focus forwarding is transition-based and can be dropped around
                    // loads (e.g. before native browser init); re-sync so the text caret is
                    // rendered.
                    browserFocusSync.reassertIfFocused()

                    // Restore the mirrored localStorage snapshot again on load end. The restore in
                    // onLoadStart can run before the new origin's V8 context is ready (e.g. when
                    // navigating from about:blank), so this second attempt ensures layout.page is
                    // available for the open-project script.
                    localStorageBridge.restore(completedUrl)
                    scheduleOpenProjectScript()
                    localStorageBridge.installSync(completedUrl)
                    injections.injectedFeatures.forEach(injections::scheduleFeatureScript)
                    injections.scheduleIdeThemeSyncScript()
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
                if (isContentDisposed()) return
                if (frame?.isMain != true) return
                thisLogger().info("jcef onLoadError url=$failedUrl code=$errorCode text=$errorText")
                // ERR_ABORTED fires for ordinary cancelled navigations and must never trigger
                // recovery.
                if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
                if (
                    !OpenCodeServerProtocol.isOpenCodeServerPage(
                        serverManager.getServerUrl(),
                        failedUrl,
                    )
                )
                    return
                ApplicationManager.getApplication().invokeLater {
                    if (!isContentDisposed()) {
                        recoverFromLoadError("${errorCode?.name}: $errorText")
                    }
                }
            }
        }

    init {
        thisLogger().info("jcef panel construct")
        applyHostLook()
        browser.component.addMouseListener(
            object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    val component = e.component ?: return
                    val point = e.point
                    if (
                        point.x >= 0 &&
                            point.y >= 0 &&
                            point.x < component.width &&
                            point.y < component.height
                    )
                        return
                    resetMirroredBrowserCursor()
                }
            }
        )
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        // onAddressChange also fires for the SPA's history-API route changes, which full-load
        // handlers never see.
        browser.jbCefClient.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    url: String?,
                ) {
                    if (isContentDisposed()) return
                    if (frame?.isMain == true) {
                        thisLogger().info("jcef onAddressChange url=$url")
                        pageLifecycle.addressChanged()
                        systemNotifications.browserAddressChanged()
                        prepareDisplayedSessionLineage(url)
                        scheduleBrowserRepaintNudges()
                        ApplicationManager.getApplication().invokeLater {
                            if (!isContentDisposed()) dismissPageOpeningStatus(url)
                        }
                        // CEF OSR can drop Chromium-level focus on SPA redirects/route changes
                        // (CEF #3870), which hides the text caret while typing still works.
                        browserFocusSync.reassertIfFocused()
                    }
                }
            },
            browser.cefBrowser,
        )
        OpenCodeCefFileDialogHandler(project, browser, this)
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
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
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                ApplicationActivationListener.TOPIC,
                object : ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: IdeFrame) {
                        browserFocusSync.reassertIfFocused()
                    }
                },
            )
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                OpenCodeGlobalEventListener.TOPIC,
                agentStatusTracker,
            )
        // Keeps the IDE's files/VCS in sync with the agent's edits, patches, branch changes, and
        // commits. Independent of the agent-status badge, and debounced against event bursts.
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                OpenCodeGlobalEventListener.TOPIC,
                workspaceRefreshCoordinator,
            )
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                OpenCodeGlobalEventListener.TOPIC,
                permissionAutoResponder,
            )
        // Permission/question sections appear in-place, without an address change, so the
        // onAddressChange repaint hook never sees them. Nudge the compositor from the JVM-side
        // event stream instead when such a request targets this panel's directory.
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                OpenCodeGlobalEventListener.TOPIC,
                object : OpenCodeGlobalEventListener {
                    override fun eventReceived(event: OpenCodeGlobalEvent) {
                        if (!OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent(event.type)) return
                        if (isContentDisposed()) return
                        val directory = openCodeServerDirectory() ?: return
                        if (
                            !OpenCodeServerProtocol.isSameFilesystemPath(event.directory, directory)
                        )
                            return
                        val serverUrl = serverManager.getServerUrl() ?: return
                        if (!isBrowserOnOpenCodeServerPage(serverUrl)) return
                        scheduleBrowserRepaintNudges()
                    }
                },
            )
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                OpenCodeServerLifecycleListener.TOPIC,
                object : OpenCodeServerLifecycleListener {
                    override fun stateChanged(
                        state: OpenCodeServerLifecycleState,
                        backendId: String,
                    ) {
                        if (backendId != serverManager.backendId) return
                        ApplicationManager.getApplication().invokeLater {
                            if (isContentDisposed()) return@invokeLater
                            if (
                                !shouldApplyPublishedLifecycleState(
                                    state,
                                    serverManager.getLifecycleState(),
                                )
                            )
                                return@invokeLater
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
        val initialState = serverManager.getLifecycleState()
        updateLifecycleIndicator(initialState)
        when (parkedEmbeddedCenterCard(initialState)) {
            ERROR_CARD -> showErrorInBrowser()
            IDLE_CARD -> {
                showCenterCard(IDLE_CARD)
            }
        }
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                OpenCodeSuspendResumeListener.TOPIC,
                object : OpenCodeSuspendResumeListener {
                    override fun resumedFromSuspend(lastAliveMillis: Long, resumedAtMillis: Long) {
                        interruptedSessionRecovery.onResumedFromSuspend(
                            lastAliveMillis,
                            resumedAtMillis,
                        )
                        // The page's stream cannot have survived the suspend; cut it now so the SPA
                        // reconnects at once rather than after the watchdog's silence budget.
                        forceEventStreamReconnect()
                    }
                },
            )
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                OpenCodeSettingsListener.TOPIC,
                object : OpenCodeSettingsListener {
                    override fun uiZoomChanged(zoomPercent: Int) {
                        // CEF applies zoom-level changes to the live page; reloading would drop the
                        // user's chat draft and scroll position.
                        applyBrowserZoom(zoomPercent)
                    }

                    override fun uiSettingChanged(setting: OpenCodeUiSetting, enabled: Boolean) {
                        when (setting) {
                            OpenCodeUiSetting.FILE_LINK_NAVIGATION ->
                                applyFileLinkNavigation(enabled)
                            OpenCodeUiSetting.EXTERNAL_LINK_NAVIGATION ->
                                injections.applyFeature(injections.externalLinkFeature, enabled)
                            OpenCodeUiSetting.CODE_NAVIGATION ->
                                injections.applyFeature(injections.codeNavigationFeature, enabled)
                            OpenCodeUiSetting.DIFF_NAVIGATION ->
                                injections.applyFeature(injections.diffNavigationFeature, enabled)
                            OpenCodeUiSetting.CHAT_FILE_DROP -> {
                                if (!enabled) {
                                    OpenCodeChatInputService.getInstance(project).discardPending()
                                }
                                val serverUrl = serverManager.getServerUrl()
                                val decision =
                                    OpenCodeInjectedFeaturePolicy.decide(
                                        enabled,
                                        OpenCodeSettingsState.getInstance().enableChatFileDrop,
                                        serverUrl != null &&
                                            isBrowserOnOpenCodeServerPage(serverUrl),
                                        script = null,
                                    )
                                if (decision.action == OpenCodeInjectedFeaturePolicy.Action.RELOAD)
                                    reloadOpenCodePageOrLoad()
                                if (enabled) scheduleFlushPendingChatInput(delayMillis = 0)
                            }
                            OpenCodeUiSetting.COMPACT_LAYOUT -> applyCompactLayout()
                            OpenCodeUiSetting.HIDE_WEBSITE_BUTTON -> applyHideWebsiteButton()
                            OpenCodeUiSetting.PATH_HOVER_PREVIEW -> applyPathHoverPreview()
                            OpenCodeUiSetting.IDE_THEME_SYNC -> applyIdeThemeSync(enabled)
                            OpenCodeUiSetting.PROJECT_SWITCH_PROMPT_SUPPRESSION ->
                                injections.applyFeature(
                                    injections.projectSwitchPromptSuppressionFeature,
                                    enabled,
                                )
                            OpenCodeUiSetting.BROWSER_CURSOR_MIRROR ->
                                injections.applyFeature(injections.cursorMirrorFeature, enabled)
                            OpenCodeUiSetting.EVENT_STREAM_WATCHDOG -> applyEventStreamWatchdog()
                            OpenCodeUiSetting.CHUNK_LOAD_RECOVERY -> applyChunkLoadRecovery()
                            OpenCodeUiSetting.RENDERER_WATCHDOG -> applyRendererWatchdog()
                            OpenCodeUiSetting.AGENT_STATUS_BADGE -> applyAgentStatusBadge(enabled)
                            OpenCodeUiSetting.FOREIGN_SESSION_WARNING -> {
                                if (!isContentDisposed()) {
                                    if (enabled) foreignSessionWarning.recheck()
                                    else foreignSessionWarning.suppress()
                                }
                            }
                        }
                    }

                    override fun serverRestartRequested(scope: OpenCodeRestartScope) {
                        val native = OpenCodeServerBackend.isNative(serverManager.backendId)
                        if (scope == OpenCodeRestartScope.NATIVE && !native) return
                        if (scope == OpenCodeRestartScope.SBX && native) return
                        restartOpenCodeServer()
                        schedulePanelReplacement()
                    }
                },
            )
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                LafManagerListener.TOPIC,
                LafManagerListener {
                    applyHostLook()
                    if (OpenCodeSettingsState.getInstance().syncThemeWithIde) {
                        applyIdeThemeSync(enabled = true)
                    }
                },
            )
        project.messageBus
            .connect(this)
            .subscribe(
                OpenCodeProjectSettingsListener.TOPIC,
                object : OpenCodeProjectSettingsListener {
                    override fun serverRestartRequested() {
                        ApplicationManager.getApplication().invokeLater {
                            if (isContentDisposed()) return@invokeLater
                            restartOpenCodeServer()
                            schedulePanelReplacement()
                        }
                    }

                    override fun serverReloadRequested() {
                        ApplicationManager.getApplication().invokeLater {
                            if (isContentDisposed()) return@invokeLater
                            reloadOpenCodePage()
                        }
                    }
                },
            )
        // OOP JCEF snapshots message routers when creating the native browser. Register before
        // createImmediately or showing the component; late channels stay silent until a reload.
        attachPageChannelsBeforeBrowserCreation()
    }

    /**
     * A panel whose page-to-JVM callbacks could not be created still shows OpenCode, but its IDE
     * integrations (file links, diffs, chat input, chunk-load recovery, …) are dead. The failure is
     * transient while the out-of-process CEF server settles, so recreate the panel once — throttled
     * application-wide so a permanently broken JCEF stack cannot loop.
     */
    private fun attachPageChannelsBeforeBrowserCreation() {
        if (pageChannelsAttached || isContentDisposed()) return
        openFileLinkQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        openCodeReferenceQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        openCodeLocalStorageQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        openExternalLinkQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        browserCursorQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        openDiffQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        chunkLoadErrorQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        chatInputResultQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        rendererHeartbeatQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
        pageChannelsAttached = true
        openFileLinkQuery.addHandler { href ->
            if (OpenCodeSettingsState.getInstance().openFileLinksInIde)
                ideNavigation.openFileLinkInIde(href)
            null
        }
        openExternalLinkQuery.addHandler { href ->
            if (OpenCodeSettingsState.getInstance().openExternalLinksInBrowser)
                ideNavigation.openExternalLinkInBrowser(href)
            null
        }
        browserCursorQuery.addHandler { cssCursor ->
            if (OpenCodeSettingsState.getInstance().mirrorBrowserCursor) {
                val cursorType = OpenCodeBrowserSnippets.awtCursorTypeForCss(cssCursor)
                val epoch = browserCursorEpoch.get()
                ApplicationManager.getApplication().invokeLater {
                    if (isContentDisposed() || epoch != browserCursorEpoch.get()) return@invokeLater
                    if (!OpenCodeSettingsState.getInstance().mirrorBrowserCursor) return@invokeLater
                    applyBrowserCursor(Cursor.getPredefinedCursor(cursorType))
                }
            }
            null
        }
        openCodeReferenceQuery.addHandler { ref ->
            if (OpenCodeSettingsState.getInstance().effectiveCodeNavigationEnabled())
                ideNavigation.openCodeReferenceInIde(ref)
            null
        }
        openDiffQuery.addHandler { payload ->
            if (OpenCodeSettingsState.getInstance().openDiffsInIde) diffNavigation.openDiff(payload)
            null
        }
        chunkLoadErrorQuery.addHandler { payload ->
            ApplicationManager.getApplication().invokeLater {
                if (isContentDisposed()) return@invokeLater
                if (!OpenCodeSettingsState.getInstance().recoverFailedChunkLoads) return@invokeLater
                val serverUrl = serverManager.getServerUrl() ?: return@invokeLater
                val pageUrl = browser.cefBrowser.url
                val onLiveOrigin = OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, pageUrl)
                val loadedOrigin = loadedServerRootUrl
                val onLoadedOrigin =
                    loadedOrigin != null &&
                        OpenCodeServerProtocol.isOpenCodeServerPage(loadedOrigin, pageUrl)
                if (!onLiveOrigin && !onLoadedOrigin) return@invokeLater
                if (pageLoadChunkFailureGeneration.incrementAndGet() != 1L) return@invokeLater
                notePanelRecovery("failed chunk import")
                thisLogger()
                    .warn(
                        "OpenCode page raised a failed chunk import ($payload); verifying server health"
                    )
                serverManager.verifyServerNow(
                    callbackActive = { !isContentDisposed() },
                    onHealthy = { reloadOpenCodePageOrLoad() },
                )
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
        val fileDropHandler =
            OpenCodeFileDropHandler(
                project,
                browser,
                serverManager,
                ::openCodeProjectDirectory,
                { pageLifecycle.browserRevision },
                ::isContentDisposed,
                this,
            )
        fileDropHandler.install()
        OpenCodeBrowserShortcutHandler(browser, serverManager, this, fileDropHandler::paste)
            .install()
        browser.jbCefClient.addContextMenuHandler(
            OpenCodeBrowserContextMenuHandler(
                fileDropHandler::paste,
                fileDropHandler::canBridgePaste,
            ),
            browser.cefBrowser,
        )
        rendererHeartbeatQuery.addHandler { visibility ->
            rendererWatchdog.handleHeartbeat(visibility)
            if (lastPanelRecovery != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (isContentDisposed() || lastPanelRecovery == null) return@invokeLater
                    lastPanelRecovery = null
                    updateLifecycleIndicator()
                }
            }
            null
        }
        thisLogger()
            .info(
                "jcef page channels attached available=${allJsQueries().count { it.isAvailable }}/${allJsQueries().size}"
            )
        if (allJsQueries().any { !it.isAvailable } || !fileDropHandler.isResultChannelAvailable()) {
            scheduleRecoveryFromMissingCallbacks()
        }
        if (
            rendererHeartbeatQuery.isAvailable &&
                OpenCodeSettingsState.getInstance().recoverStalledRenderer
        ) {
            rendererWatchdog.start()
        }
        runCatching { browser.jbCefClient.removeRequestHandler(requestHandler, browser.cefBrowser) }
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
    }

    private fun scheduleRecoveryFromMissingCallbacks() {
        val now = System.currentTimeMillis()
        if (!OpenCodePanelRecoveryPolicy.shouldRecreatePanel(lastCallbackRecoveryAtMillis, now)) {
            thisLogger()
                .warn("OpenCode panel callbacks are unavailable; keeping the degraded panel")
            return
        }
        lastCallbackRecoveryAtMillis = now
        thisLogger().warn("OpenCode panel callbacks could not be created; recreating the panel")
        addAlarmRequest(panelRecoveryAlarm, OpenCodePanelRecoveryPolicy.RETRY_DELAY_MILLIS) {
            if (isContentDisposed()) return@addAlarmRequest
            host.replacePanel()
        }
    }

    /**
     * JCEF OSR can leave stale pixels after large in-page layout changes. Triggered from
     * `onAddressChange` for SPA route changes and from the JVM event stream for permission/question
     * sections (show and dismiss). Uses a compositor hint plus an in-page 1px translate — never a
     * host bounds change, which reallocates the OSR surface and flashes on Windows. Retried after
     * the SPA finishes painting. May be called from any thread.
     */
    private fun scheduleBrowserRepaintNudges() {
        if (isContentDisposed() || repaintAlarm.isDisposed) return
        val nudgedAtUrl = browser.cefBrowser.url
        val serverUrl = serverManager.getServerUrl() ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        repaintAlarm.cancelAllRequests()

        repaintScheduler.scheduleAction(early = true, shouldRun = { !isContentDisposed() }) {
            nudgeBrowserRaster(nudgedAtUrl, rootUrl)
        }
        repaintScheduler.scheduleAt(
            VIEWPORT_RASTER_NUDGE_RETRY_MILLIS,
            shouldRun = { !isContentDisposed() },
        ) {
            nudgeBrowserRaster(nudgedAtUrl, rootUrl)
        }
    }

    private fun nudgeBrowserRaster(nudgedAtUrl: String?, rootUrl: String) {
        if (!stillOnSamePage(nudgedAtUrl)) return
        browser.cefBrowser.notifyScreenInfoChanged()
        browser.component.repaint()
        browser.cefBrowser.executeJavaScript(
            OpenCodeBrowserSnippets.buildViewportRasterNudgeScript(),
            rootUrl,
            0,
        )
    }

    private fun stillOnSamePage(expectedUrl: String?): Boolean {
        if (expectedUrl.isNullOrBlank()) return false
        return expectedUrl == browser.cefBrowser.url
    }

    /**
     * Submits one queued IDE-initiated text and retains it in-flight until the page acknowledges
     * that OpenCode's prompt handler accepted the synthetic paste/drop event.
     */
    override fun dispatchChatBatch(delivery: OpenCodeChatInputService.Delivery): Boolean {
        if (isContentDisposed()) return false
        if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return false
        val serverUrl = serverManager.getServerUrl() ?: return false
        if (!isBrowserOnOpenCodeServerPage(serverUrl)) return false
        // Null when the page-to-JVM channel could not be created (see OpenCodeJsQuery): the text
        // batch is still dispatched, the page just cannot acknowledge it.
        val resultCallback = chatInputResultQuery.inject("batchId + '\\n' + (accepted ? '1' : '0')")
        val script =
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                emptyList(),
                textPlain = listOf(delivery.batch.text),
                enabled = true,
                batchId = delivery.attemptID,
                resultCallback = resultCallback,
            ) ?: return false
        browser.cefBrowser.executeJavaScript(
            script,
            OpenCodeServerProtocol.buildServerRootUrl(serverUrl),
            0,
        )
        val service = OpenCodeChatInputService.getInstance(project)
        if (resultCallback != null) {
            addAlarmRequest(openProjectAlarm, CHAT_INPUT_ACK_TIMEOUT_MILLIS) {
                if (service.retryInFlight(delivery.attemptID)) scheduleFlushPendingChatInput()
            }
        } else {
            service.acknowledge(delivery.attemptID, accepted = true)
        }
        return true
    }

    private fun scheduleFlushPendingChatInput(
        delayMillis: Int = PENDING_CHAT_INPUT_FLUSH_DELAY_MILLIS
    ) {
        val service = OpenCodeChatInputService.getInstance(project)
        addAlarmRequest(openProjectAlarm, delayMillis) { service.dispatchPending() }
    }

    override val component: JComponent
        get() = contentPanel

    override val preferredFocus: JComponent
        get() = browser.component

    override fun onHostChanged() {
        if (isContentDisposed()) return
        runCatching { browser.cefBrowser.notifyScreenInfoChanged() }
        browserFocusSync.reassertIfFocused()
        systemNotifications.browserAddressChanged()
    }

    private fun updateLifecycleIndicator(
        state: OpenCodeServerLifecycleState = serverManager.getLifecycleState()
    ) {
        if (state != OpenCodeServerLifecycleState.RUNNING) {
            resetAgentStatusTracking()
        }
        val sbx = serverManager as? SbxOpenCodeServerBackend
        val starting =
            state == OpenCodeServerLifecycleState.STARTING ||
                state == OpenCodeServerLifecycleState.RESTARTING
        val progress = serverManager.getStartupProgress()
        val logAvailable = OpenCodeSettingsState.getInstance().enableServerLogs
        if (starting || state == OpenCodeServerLifecycleState.STOPPED) {
            idleCard.show(
                state,
                stage = sbx?.startupStage(),
                progress = progress,
                logAvailable = logAvailable,
            )
            showCenterCard(IDLE_CARD)
        } else {
            idleCard.stopProgressAnimation()
        }
        val now = System.currentTimeMillis()
        val storedRecovery = lastPanelRecovery ?: sbx?.lastRecoveryNotice()
        val recovery =
            visibleRecoveryNotice(
                storedRecovery,
                state,
                pageLifecycle.painted,
                pageLifecycle.loadInProgress,
                now,
            )
        if (storedRecovery != null && recovery == null) lastPanelRecovery = null
        val model =
            OpenCodeLifecycleStripModel(
                state = state,
                pageOpening =
                    shouldShowPageOpeningStatus(
                        pageLifecycle.loadInProgress,
                        pageLifecycle.painted,
                    ),
                cancelled = sbx?.lastFailure() == SbxFailureKind.CANCELLED,
                stage = progress?.stage ?: sbx?.startupStage(),
                elapsedMillis = if (starting) progress?.elapsedMillis else null,
                recovery = recovery,
                progress = if (starting) progress else null,
                logAvailable = logAvailable,
            )
        val relayout = lifecycleStatusPanel.update(model, now)
        stripTickAlarm.cancelAllRequests()
        if (shouldTickLifecycleStrip(model)) {
            addAlarmRequest(stripTickAlarm, 1_000) {
                if (!isContentDisposed()) updateLifecycleIndicator()
            }
        }
        if (relayout) {
            contentPanel.revalidate()
            contentPanel.repaint()
        }
    }

    private fun notePanelRecovery(reason: String) {
        lastPanelRecovery = OpenCodeRecoveryNotice(reason, System.currentTimeMillis())
    }

    private fun applyHostLook() {
        val background = UIUtil.getPanelBackground()
        fun paint(component: JComponent) {
            component.isOpaque = true
            component.background = background
        }
        paint(contentPanel)
        paint(centerCardPanel)
        paint(idleCard.component)
        paint(browser.component)
    }

    private fun warnIfOpenCodeVersionIsUnsupported() {
        if (project.isDisposed) return
        OpenCodeReleaseUpdates.checkAndIndicate(project, serverManager.getServerVersion()) {
            if (!isContentDisposed()) host.updateHeading()
        }
        warnIfSandboxCreateIsStale()
        val group =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID) ?: return
        val installedVersion = serverManager.consumeUnsupportedServerVersionWarning()
        if (installedVersion != null) {
            group
                .createNotification(
                    "OpenCode update required",
                    "OpenCode Web Panel requires OpenCode ${OpenCodeServerProtocol.MINIMUM_SUPPORTED_OPENCODE_VERSION} or later. " +
                        "Installed version: ${StringUtil.escapeXmlEntities(installedVersion)}.",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
        if (!serverManager.consumeV2ProtocolWarning()) return
        group
            .createNotification(
                "Permission requests may not appear",
                "This OpenCode version may not show permission requests in the IDE. " +
                    "Update the OpenCode Web Panel plugin when a matching release is available.",
                NotificationType.WARNING,
            )
            .notify(project)
    }

    private fun warnIfSandboxCreateIsStale() {
        if (project.isDisposed) return
        val reasons = serverManager.consumeCreateStaleWarning()
        if (reasons.isEmpty()) return
        val group =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID) ?: return
        val detail = reasons.joinToString("; ")
        val notification =
            group.createNotification(
                "Sandbox settings need Reset",
                "The running sandbox does not have: $detail. Restart keeps the old VM. " +
                    "Reset Sandbox applies current project settings and drops sessions in that VM.",
                NotificationType.WARNING,
            )
        notification.addAction(
            object : NotificationAction("Reset Sandbox") {
                override fun actionPerformed(
                    e: com.intellij.openapi.actionSystem.AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    notification.expire()
                    if (project.isDisposed) return
                    if (!confirmOpenCodeSandboxReset(project)) return
                    requestOpenCodeSandboxReset(project)
                }
            }
        )
        notification.notify(project)
    }

    private fun restartOpenCodeServer() {
        if (isContentDisposed()) return
        if (panelBackendIsStale()) return
        lifecycleStatusPanel.setRetryEnabled(false)
        pendingServerStartRequest = true
        serverManager.restartServer(
            project,
            sandboxWorkspaceDirectory(),
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

    override fun checkAndLoadContent() {
        if (isContentDisposed()) return
        if (panelBackendIsStale()) {
            schedulePanelReplacement()
            return
        }
        pendingServerStartRequest = true
        serverManager.ensureStarted(
            project,
            sandboxWorkspaceDirectory(),
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
     * Reloads the embedded OpenCode web UI (the SPA) in place without touching the server, so a
     * glitchy or stale page can be refreshed without interrupting sessions in this or any other
     * project's panel. Falls back to a full load when nothing valid is currently shown (the server
     * was down or the page was cleared to about:blank).
     */
    fun reloadOpenCodePage() {
        if (isContentDisposed()) return
        val serverUrl = serverManager.getServerUrl()
        if (
            serverUrl == null ||
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
     * snapshot and the page's local/session storage, then reloads. This is the recovery path for a
     * bad persisted value — e.g. a mirrored snapshot that would otherwise be restored on every
     * load, or a seeded project state the SPA can no longer read. The browser profile is shared by
     * every project's panel, so the effect is application-wide by design.
     */
    fun resetOpenCodeWebState() {
        if (isContentDisposed()) return
        OpenCodeSettingsState.getInstance().setLocalStorageSnapshot(serverManager.backendId, "{}")
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
     * Reloads the OpenCode page after the server recovered without this panel's involvement, e.g.
     * an automatic health-check restart or a restart initiated from another project window. Without
     * this, the panel would stay on the idle card installed by [clearStaleBrowserPage].
     */
    private fun reloadContentAfterRecovery(state: OpenCodeServerLifecycleState) {
        if (state != OpenCodeServerLifecycleState.RUNNING) return
        if (pendingServerStartRequest) return
        if (loadedServerRootUrl != null) return
        checkAndLoadContent()
    }

    /**
     * Handles a failed main-frame load of the OpenCode page (e.g. the server died between health
     * checks and the browser shows a connection error). Verifies the server immediately: reloads on
     * a transient failure, or triggers the regular restart recovery right away.
     */
    private fun recoverFromLoadError(reason: String) {
        if (!markBrowserRecoveryAttempt()) return
        notePanelRecovery("page failed to load ($reason)")
        thisLogger().warn("OpenCode page failed to load ($reason); verifying server health")
        serverManager.verifyServerNow(
            callbackActive = { !isContentDisposed() },
            onHealthy = { reloadOpenCodePageOrLoad() },
        )
    }

    /**
     * Recreates the panel after the JCEF renderer process crashed, which otherwise leaves a
     * permanently blank panel. Throttled to avoid recreate loops on repeated crashes. Does not
     * restart the server — Restart OpenCode Server is the heavier hammer.
     */
    private fun recoverFromRendererCrash() {
        ApplicationManager.getApplication().invokeLater {
            if (isContentDisposed()) return@invokeLater
            if (!markBrowserRecoveryAttempt()) return@invokeLater
            thisLogger().warn("OpenCode panel renderer process terminated; recreating panel")
            host.replacePanel()
        }
    }

    /**
     * Dispose this panel and install a fresh JCEF browser. Deferred so a message-bus handler is not
     * disposing itself mid-delivery. Stop→Start must not call this.
     */
    private fun schedulePanelReplacement() {
        if (isContentDisposed() || panelReplacementScheduled) return
        panelReplacementScheduled = true
        // Restart is the hammer: a spent auto-recreate budget must not make the new panel
        // give up on its first stall.
        OpenCodeRendererWatchdog.resetProcessRecreatesAfterStall()
        ApplicationManager.getApplication().invokeLater {
            try {
                if (isContentDisposed()) return@invokeLater
                host.replacePanel()
            } finally {
                panelReplacementScheduled = false
            }
        }
    }

    /** Reload only when CEF is already on the live server page; otherwise a full load. */
    private fun reloadOpenCodePageOrLoad() {
        val serverUrl = serverManager.getServerUrl()
        if (serverUrl != null && isBrowserOnOpenCodeServerPage(serverUrl)) {
            beginPageLoad(browser.cefBrowser.url)
            ensureCefBrowser()
            loadAfterDocumentStartScripts(serverUrl) {
                browser.cefBrowser.reloadIgnoreCache()
                armPageLoadWatchdog(serverUrl)
            }
        } else {
            loadProjectPage()
        }
    }

    /**
     * Watchdog reload for a silently stalled page: plain CEF reload (never `loadHTML`), so no new
     * resource-map entries accumulate and the shared profile stays the same. The in-place reload is
     * the cheap first step before the watchdog escalates to a panel recreation.
     */
    private fun reloadStalledOpenCodePage() {
        if (isContentDisposed()) return
        val serverUrl = serverManager.getServerUrl() ?: return
        notePanelRecovery("stalled renderer heartbeat")
        thisLogger().info("Reloading stalled OpenCode page")
        rendererWatchdog.noteReloadedForStall()
        beginPageLoad(browser.cefBrowser.url)
        ensureCefBrowser()
        loadAfterDocumentStartScripts(serverUrl) {
            browser.cefBrowser.reload()
            armPageLoadWatchdog(serverUrl)
        }
    }

    /**
     * The watchdog gave up after the recreate budget was spent. The panel itself may still be
     * frozen; make the failure card the way out so the user has a Retry instead of a dead view.
     */
    private fun showRendererGiveUpCard() {
        host.showFailure()
    }

    private fun markBrowserRecoveryAttempt(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBrowserRecoveryAttemptAtMillis < BROWSER_RECOVERY_THROTTLE_MILLIS)
            return false
        lastBrowserRecoveryAttemptAtMillis = now
        return true
    }

    private fun loadProjectPage() {
        if (isContentDisposed()) return
        val serverUrl = serverManager.getServerUrl() ?: return
        // Home, never a restored ses_ — OpenCode 2 404s missing ids as "cannot be found".
        // The id-less /server/<key>/session shell mounts chrome but leaves <main> empty.
        thisLogger().info("Loading OpenCode project page")
        thisLogger()
            .info(
                "jcef loadProjectPage backend=${serverManager.backendId} server=$serverUrl current=${safeBrowserUrl()} created=$cefBrowserCreated queries=${allJsQueries().count { it.isAvailable }}/${allJsQueries().size}"
            )
        openProjectScriptScheduled = false
        pageLifecycle.forgetTarget()
        // No pre-load script scheduling here: onLoadStart cancels the alarm and resets the
        // per-page flags anyway, and onLoadStart/onLoadEnd (re)schedule everything for the new
        // document. The resets above only cover the case where the load never starts.
        injections.resetScheduled()
        openProjectAlarm.cancelAllRequests()
        applyBrowserZoom()
        // Events that fired before this panel started caring never reached the tracker.
        agentStatusTracker.seed()

        loadProjectPageAt(serverUrl, sessionId = null)
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
            val current = safeBrowserUrl()
            thisLogger().info("jcef navigate target=$url current=$current")
            if (
                OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                    serverUrl,
                    browser.cefBrowser.url,
                    url,
                )
            ) {
                browser.cefBrowser.reloadIgnoreCache()
            } else {
                browser.loadURL(url)
            }
            thisLogger().info("jcef navigate issued current=${safeBrowserUrl()}")
            armPageLoadWatchdog(serverUrl)
        }
    }

    private fun installDocumentStartScripts(serverUrl: String): CompletableFuture<Boolean> {
        val script = injections.documentStartScript(serverUrl)
        val guarded =
            OpenCodeDocumentStartInjector.guardForOrigin(
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
        val generation = pageLifecycle.invalidatePendingLoad()
        val waitMillis =
            if (documentStartInjector.hasInstalledScript()) {
                DOCUMENT_START_INSTALL_TIMEOUT_MILLIS
            } else {
                OpenCodePageLoadWatchdog.DOCUMENT_START_WAIT_BEFORE_LOAD_MILLIS
            }
        val timeout =
            CompletableFuture.supplyAsync(
                { false },
                CompletableFuture.delayedExecutor(waitMillis, TimeUnit.MILLISECONDS),
            )
        val install = runCatching {
            installDocumentStartScripts(serverUrl)
        }
            .getOrElse { error ->
                thisLogger()
                    .info("Could not start OpenCode document-start install: ${error.message}")
                CompletableFuture.completedFuture(false)
            }
        install
            .applyToEither(timeout) { it }
            .whenComplete { installed, error ->
                if (error != null) {
                    thisLogger().info("OpenCode document-start install failed: ${error.message}")
                } else if (!installed) {
                    thisLogger()
                        .info(
                            "OpenCode document-start script was unavailable; using onLoadStart fallback"
                        )
                }
                ApplicationManager.getApplication().invokeLater {
                    if (
                        isContentDisposed() ||
                            !pageLifecycle.acceptsPendingLoad(
                                generation,
                                cancelIfDocumentRevisionChanges,
                            )
                    )
                        return@invokeLater
                    if (serverManager.getServerUrl() != serverUrl) return@invokeLater
                    val keepCurrent =
                        OpenCodeDocumentStartInjector.shouldKeepCurrentPage(
                            installed = installed == true,
                            hasInstalledScript = documentStartInjector.hasInstalledScript(),
                            currentPageIsOpenCode =
                                OpenCodeServerProtocol.isOpenCodeServerPage(
                                    serverUrl,
                                    browser.cefBrowser.url,
                                ),
                        )
                    if (keepCurrent) {
                        thisLogger()
                            .warn(
                                "Keeping the current OpenCode page because its document-start script could not be replaced"
                            )
                        pageLifecycle.keepCurrentPage()
                        updateLifecycleIndicator()
                        return@invokeLater
                    }
                    pageLifecycle.willNavigate()
                    load()
                }
            }
    }

    private fun ensureCefBrowser() {
        if (!cefBrowserCreated) {
            browser.createImmediately()
            cefBrowserCreated = true
            documentStartInjector.markRendererReady()
        }
    }

    override fun prepareBrowserForReplacement(): CompletableFuture<Unit> {
        cefBrowserCreated = true
        thisLogger()
            .info(
                "jcef prepare replacement queries=${allJsQueries().count { it.isAvailable }}/${allJsQueries().size}"
            )
        return createOpenCodeBrowserBeforeReplacement(browser).thenApply {
            documentStartInjector.markRendererReady()
            thisLogger().info("jcef replacement renderer ready url=${safeBrowserUrl()}")
        }
    }

    private fun safeBrowserUrl(): String {
        return runCatching { browser.cefBrowser.url }.getOrDefault("<unavailable>")
    }

    private fun noteOpenCodePageVisible(pageUrl: String?) {
        val serverUrl = serverManager.getServerUrl()
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, pageUrl)) return
        if (pageLifecycle.painted && !pageLifecycle.loadInProgress) return
        pageLifecycle.visible()
        pageLoadWatchdogAlarm.cancelAllRequests()
        updateLifecycleIndicator()
    }

    /**
     * Hides the Opening strip without treating the document as loaded for the renderer watchdog.
     */
    private fun dismissPageOpeningStatus(pageUrl: String?) {
        val serverUrl = serverManager.getServerUrl()
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, pageUrl)) return
        if (!pageLifecycle.loadInProgress) return
        clearPageLoadWatchdog()
        updateLifecycleIndicator()
    }

    private fun clearPageLoadWatchdog() {
        pageLifecycle.dismissOpening()
        pageLoadWatchdogAlarm.cancelAllRequests()
    }

    private fun beginPageLoad(targetUrl: String? = null, resetRetryBudget: Boolean = true) {
        pageLifecycle.begin(targetUrl, resetRetryBudget)
        updateLifecycleIndicator()
    }

    private fun armPageLoadWatchdog(serverUrl: String) {
        val token = pageLifecycle.armWatchdog()
        // Own alarm: onLoadStart cancels openProjectAlarm. Arming cannot undo onLoadEnd.
        pageLoadWatchdogAlarm.cancelAllRequests()
        addAlarmRequest(pageLoadWatchdogAlarm, PAGE_LOAD_WATCHDOG_MILLIS) {
            when (pageLifecycle.timeout(token)) {
                OpenCodePageLifecycle.Timeout.NONE -> return@addAlarmRequest
                OpenCodePageLifecycle.Timeout.GAVE_UP -> {
                    thisLogger()
                        .warn(
                            "OpenCode page failed to load after ${OpenCodePageLoadWatchdog.MAX_RETRIES} retries"
                        )
                    updateLifecycleIndicator()
                    return@addAlarmRequest
                }
                OpenCodePageLifecycle.Timeout.RETRY -> Unit
            }
            thisLogger()
                .warn(
                    "OpenCode page load timed out; retrying (${pageLifecycle.retryCount}/${OpenCodePageLoadWatchdog.MAX_RETRIES})"
                )
            val liveUrl = serverManager.getServerUrl()
            if (liveUrl != serverUrl) return@addAlarmRequest
            val target =
                OpenCodePageLoadWatchdog.retryTarget(
                    liveUrl,
                    pageLifecycle.targetUrl,
                    browser.cefBrowser.url,
                )
            val stalledDocumentRevision = pageLifecycle.documentRevision
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
        }
    }

    private fun clearStaleBrowserPage(state: OpenCodeServerLifecycleState) {
        if (
            state != OpenCodeServerLifecycleState.STOPPED &&
                state != OpenCodeServerLifecycleState.FAILED &&
                state != OpenCodeServerLifecycleState.RESTARTING
        ) {
            return
        }
        loadedServerRootUrl = null
        pageLifecycle.reset()
        pageLoadWatchdogAlarm.cancelAllRequests()
        openProjectAlarm.cancelAllRequests()
        runCatching { browser.cefBrowser.stopLoad() }
        if (shouldHideEmbeddedPage(state)) {
            idleCard.show(state)
            showCenterCard(IDLE_CARD)
        }
    }

    private fun applyBrowserZoom(
        zoomPercent: Int = OpenCodeSettingsState.getInstance().uiZoomPercent
    ) {
        val zoomPercent = OpenCodeSettingsState.sanitizeUiZoomPercent(zoomPercent)
        browser.cefBrowser.zoomLevel = OpenCodeServerProtocol.toCefZoomLevel(zoomPercent)
    }

    private fun isOpenCodeProjectDestination(frameUrl: String?): Boolean {
        val serverUrl = serverManager.getServerUrl() ?: return false
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) return false
        val projectDirectory = openCodeServerDirectory()?.takeIf { it.isNotBlank() } ?: return true
        // Directoryless routes (/server/<id>/session..., /new-session) do not reveal which
        // project they show, so they are NOT accepted as a destination here: the open-project
        // script must keep running and decide in-page against the SPA's own project state.
        // Blanket-accepting them stranded panels on another project's workspace, e.g. after a
        // project-directory rename or when another IDE project used the shared browser
        // profile last.
        return OpenCodeServerProtocol.isSameFilesystemPath(
            OpenCodeServerProtocol.routeDirectoryFromUrl(frameUrl),
            projectDirectory,
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
        val projectDirectory = openCodeServerDirectory()?.takeIf { it.isNotBlank() } ?: return false
        val projectUrl = OpenCodeServerProtocol.buildProjectUrl(serverUrl, projectDirectory)
        return frameUrl?.trimEnd('/') == projectUrl.trimEnd('/')
    }

    private fun scheduleOpenProjectScript() {
        if (openProjectScriptScheduled) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val projectDirectory = openCodeServerDirectory()?.takeIf { it.isNotBlank() } ?: return
        openProjectScriptScheduled = true
        val script =
            OpenCodeBrowserSnippets.buildOpenProjectScript(projectDirectory, serverUrl) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        scriptScheduler.schedule(script, rootUrl) {
            if (!isBrowserOnOpenCodeServerPage(serverUrl)) return@schedule false
            val frameUrl = browser.cefBrowser.url
            val onProjectRootRoute = isOpenCodeProjectRootRoute(frameUrl)
            val onProjectDestination = isOpenCodeProjectDestination(frameUrl)
            !onProjectDestination || onProjectRootRoute
        }
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
            if (!agentStatusTracker.isCurrentPresentation(state, presentationRevision))
                return@invokeLater
            applyAgentStatusBadgeIcon(state)
        }
    }

    /** Resets the visible badge without touching tracked state; the watchdog still reads it. */
    private fun resetAgentStatusBadge() {
        if (isContentDisposed()) return
        applyAgentStatusBadgeIcon(OpenCodeAgentStatusState.IDLE)
    }

    private fun applyAgentStatusBadgeIcon(state: String) {
        if (host.isCurrent(this)) host.updateAgentStatus(state, toolWindowIconSupplier)
    }

    /** Server stopped or the directory changed — tracked state no longer applies to this panel. */
    private fun resetAgentStatusTracking() {
        if (isContentDisposed()) return
        agentStatusTracker.reset()
        resetAgentStatusBadge()
    }

    /**
     * Enables or disables the visible badge. The tracker's state must survive a disable: the
     * renderer watchdog reads its busy flag to stretch stall timeouts during an agent turn.
     */
    private fun applyAgentStatusBadge(enabled: Boolean) {
        if (!enabled) {
            resetAgentStatusBadge()
            return
        }
        applyAgentStatusBadgeIcon(agentStatusTracker.currentState())
        agentStatusTracker.seed()
    }

    private fun isBrowserOnOpenCodeServerPage(serverUrl: String): Boolean {
        return OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)
    }

    /**
     * Applies the mirrored page cursor to the whole browser component tree: in off-screen rendering
     * the deepest Swing component under the pointer decides the visible cursor, and the platform
     * may have left a stale cursor on it.
     */
    private fun resetMirroredBrowserCursor() {
        browserCursorEpoch.incrementAndGet()
        applyBrowserCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR))
    }

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
        injections.matchMediaPatchFeature.scheduled = false
        if (!enabled) {
            reloadForEarlyFeatureToggle(injections.matchMediaPatchFeature)
            return
        }
        installDocumentStartScripts(serverUrl)
        if (injections.executeIdeThemeSyncScript(serverUrl))
            injections.matchMediaPatchFeature.scheduled = true
    }

    /** Code navigation piggybacks on file-link navigation, so a toggle here re-applies both. */
    private fun applyFileLinkNavigation(enabled: Boolean) {
        injections.codeNavigationFeature.scheduled = false
        injections.applyFeature(injections.fileLinkFeature, enabled)
        if (enabled && OpenCodeSettingsState.getInstance().enableCodeNavigation) {
            injections.applyFeature(injections.codeNavigationFeature, enabled = true)
        }
    }

    private fun navigateFromNotification(targetUrl: String) {
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
        val raw =
            OpenCodeProjectSettingsState.getInstance(project)
                .effectiveOpenCodeDirectory(project.basePath) ?: return null
        return OpenCodeServerProtocol.canonicalOpenCodeDirectory(raw) ?: raw
    }

    private fun sandboxWorkspaceDirectory(): String? {
        return OpenCodeProjectSettingsState.getInstance(project)
            .effectiveProjectDirectory(project.basePath)
    }

    private fun openCodeServerDirectory(): String? {
        return OpenCodeHostPaths.serverDirectory(
            serverManager.backendId,
            openCodeProjectDirectory(),
        )
    }

    /** Opens Chromium's built-in DevTools window for this panel's browser (JBCef built-in). */
    fun openBrowserDevTools() {
        if (isContentDisposed()) return
        browser.openDevtools()
        notifyAboutDevToolsCredentials()
    }

    /**
     * The DevTools window is a separate browser without the panel's auth handlers, so its own
     * server fetches (e.g. source maps) can trigger Chromium's basic-auth prompt. Authenticating it
     * programmatically is a documented dead end — JPMS blocks the reflective browser lookup,
     * out-of-process JCEF hides the DevTools browser from the JVM entirely, and auth-cache priming
     * interfered with the app's own session — so instead tell the user which credentials to enter,
     * with the password one click away.
     */
    private fun notifyAboutDevToolsCredentials() {
        if (devToolsCredentialsNotified) return
        val group =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID) ?: return
        devToolsCredentialsNotified = true
        val notification =
            group.createNotification(
                "Browser DevTools sign-in",
                "If DevTools asks you to sign in, use the username \"${OpenCodeServerProtocol.BASIC_AUTH_USERNAME}\" " +
                    "and the OpenCode server password.",
                NotificationType.INFORMATION,
            )
        serverManager.getServerPassword()?.let { password ->
            notification.addAction(
                NotificationAction.createSimple("Copy password") {
                    CopyPasteManager.getInstance().setContents(StringSelection(password))
                }
            )
        }
        notification.addAction(
            NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, OpenCodeSettingsConfigurable::class.java)
            }
        )
        notification.notify(project)
    }

    private fun applyCompactLayout() {
        // Reload rebuilds all early scripts: both the media-query patch and V2 Home stylesheet.
        reloadForEarlyFeatureToggle(injections.matchMediaPatchFeature)
    }

    private fun applyHideWebsiteButton() {
        // Off → reload so listeners/stylesheets are fully removed (safeguard contract).
        // On → reload so early inject runs before SPA chrome mounts.
        reloadForEarlyFeatureToggle(injections.hideWebsiteButtonFeature)
    }

    private fun applyPathHoverPreview() {
        reloadForEarlyFeatureToggle(injections.pathHoverPreviewFeature)
    }

    private fun forceEventStreamReconnect() {
        if (!injections.eventStreamWatchdogFeature.enabledInSettings()) return
        val serverUrl = serverManager.getServerUrl() ?: return
        ApplicationManager.getApplication().invokeLater {
            if (isContentDisposed()) return@invokeLater
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url))
                return@invokeLater
            browser.cefBrowser.executeJavaScript(
                OpenCodeBrowserSnippets.buildForceEventReconnectScript(),
                OpenCodeServerProtocol.buildServerRootUrl(serverUrl),
                0,
            )
        }
    }

    private fun applyEventStreamWatchdog() {
        // CLI 2.x owns recovery and has no injected patch to install/remove.
        if (serverManager.getWireProtocol() == OpenCodeWireProtocol.V2_CLI) return
        // Off → reload so the patched window.fetch is replaced by the untouched original.
        // On → reload so the patch is in place before the SPA bundle captures window.fetch.
        reloadForEarlyFeatureToggle(injections.eventStreamWatchdogFeature)
    }

    private fun applyChunkLoadRecovery() {
        // Both directions reload: off removes the error listeners entirely (safeguard contract),
        // on puts the listener in place before the SPA bundle can raise the first chunk failure.
        reloadForEarlyFeatureToggle(injections.chunkLoadRecoveryFeature)
    }

    /**
     * Applies a runtime toggle of an early-injected feature. Both directions reload the page: off
     * so previously installed patches are fully removed (safeguard contract, never a "disable"
     * script), on so the script runs before the SPA bundle on the next load start.
     */
    private fun reloadForEarlyFeatureToggle(feature: OpenCodePanelInjections.EarlyInjectedFeature) {
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

    /**
     * Applies a runtime toggle of the renderer watchdog: disabling stops the scheduler and reloads
     * the page so the injected heartbeat is removed (safeguard contract); enabling restarts it on
     * the current heartbeat stream.
     */
    private fun applyRendererWatchdog() {
        if (
            OpenCodeSettingsState.getInstance().recoverStalledRenderer &&
                rendererHeartbeatQuery.isAvailable
        ) {
            rendererWatchdog.start()
        } else {
            rendererWatchdog.stop()
        }
        injections.applyFeature(
            injections.rendererHeartbeatFeature,
            OpenCodeSettingsState.getInstance().recoverStalledRenderer,
        )
    }

    private fun showErrorInBrowser() {
        if (isContentDisposed()) return
        loadedServerRootUrl = null
        pageLifecycle.reset()
        pageLoadWatchdogAlarm.cancelAllRequests()
        updateLifecycleIndicator()
        val sbx = serverManager as? SbxOpenCodeServerBackend
        val failure = sbx?.lastFailure()
        val foreign = failure == SbxFailureKind.FOREIGN_SANDBOX
        val recoveryAction =
            when (failure) {
                SbxFailureKind.EXPOSURE_UNCONFIRMED ->
                    OpenCodeStartupRecoveryAction(
                        "Allow and Start",
                        "Allow the host access listed above for this project's sandbox, then start it",
                    ) {
                        val exposure = sbx.pendingExposure()
                        if (confirmOpenCodeSandboxExposure(project, exposure)) {
                            sbx.acknowledgeExposure(exposure)
                            restartOpenCodeServer()
                        }
                    }
                SbxFailureKind.RECREATE_REQUIRED ->
                    OpenCodeStartupRecoveryAction(
                        "Recreate Sandbox",
                        "Remove this sandbox and create a new one from opencode-sbx.yaml",
                    ) {
                        if (confirmOpenCodeSandboxRecreate(project, sbx.pendingRecreateReasons())) {
                            requestOpenCodeSandboxReset(project, dropGuestOpenCode = false)
                        }
                    }
                else -> null
            }
        startupErrorPanel.showFailure(
            OpenCodeSettingsState.getInstance().executablePath(),
            serverManager.getServerLogFile(),
            offerAutomaticPort = serverManager.offersHostPortControls,
            failureMessage = serverManager.startFailureMessage(),
            recoveryAction = recoveryAction,
            onAdoptForeign =
                if (foreign) {
                    { if (sbx.adoptForeignSandbox()) restartOpenCodeServer() }
                } else {
                    null
                },
            onCreateNewSandbox =
                if (foreign) {
                    {
                        if (confirmDiscardForeignSandbox(project)) {
                            sbx.discardForeignSandbox()
                            restartOpenCodeServer()
                        }
                    }
                } else {
                    null
                },
        )
        showCenterCard(ERROR_CARD)
    }

    private fun showCenterCard(card: String) {
        centerCardLayout.show(centerCardPanel, card)
        centerCardPanel.revalidate()
        centerCardPanel.repaint()
    }

    internal fun dispatchOpenCodeCommand(command: OpenCodeBrowserCommand) {
        OpenCodeBrowserShortcutHandler.dispatch(browser, serverManager, command)
    }

    internal fun currentPageUrl(): String? = browser.cefBrowser.url

    internal fun openCodeServerUrl(): String? = serverManager.getServerUrl()

    internal fun displayedSessionID(): String? {
        return OpenCodeServerProtocol.sessionIdFromUrl(browser.cefBrowser.url)
    }

    internal fun backendId(): String = serverManager.backendId

    private fun panelBackendIsStale(): Boolean {
        return OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId !=
            serverManager.backendId
    }

    /**
     * Resolves the displayed session's lineage as soon as its route appears (full load or SPA
     * navigation), so the Auto-Accept Permissions gear action reflects an inherited parent enable
     * on the first menu open instead of only after the menu's own update kicked the async fetch.
     * Safe to call from CEF handler threads:
     * [prepareSession][OpenCodePermissionAutoResponder.prepareSession] only touches concurrent maps
     * and hops to a pooled thread for the REST walk.
     */
    private fun loadDisplayedSession(sessionID: String): OpenCodeServerProtocol.SessionInfo? {
        val serverUrl = serverManager.getServerUrl() ?: return null
        val directory = openCodeServerDirectory() ?: return null
        val password = serverManager.getServerPassword() ?: return null
        return OpenCodeServerProtocol.fetchSessionInfo(
            serverUrl,
            OpenCodeServerProtocol.buildBasicAuthHeader(password),
            directory,
            sessionID,
            wireProtocol = serverManager.getWireProtocol(),
        )
    }

    private fun showForeignSessionWarning(title: String, content: String) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            publishForeignSessionWarning(title, content)
        } else {
            application.invokeLater { publishForeignSessionWarning(title, content) }
        }
    }

    private fun publishForeignSessionWarning(title: String, content: String) {
        if (isContentDisposed()) return
        foreignSessionNotification?.expire()
        val group =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID) ?: return
        val notification =
            group.createNotification(
                title,
                StringUtil.escapeXmlEntities(content),
                NotificationType.WARNING,
            )
        foreignSessionNotification = notification
        notification.notify(project)
    }

    private fun clearForeignSessionWarning() {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            expireForeignSessionWarning()
        } else {
            application.invokeLater { expireForeignSessionWarning() }
        }
    }

    private fun expireForeignSessionWarning() {
        foreignSessionNotification?.expire()
        foreignSessionNotification = null
    }

    private fun applyForeignSessionOutline(sessionID: String?) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            paintForeignSessionOutline(sessionID)
        } else {
            application.invokeLater { paintForeignSessionOutline(sessionID) }
        }
    }

    private fun paintForeignSessionOutline(sessionID: String?) {
        if (isContentDisposed()) return
        host.component.border =
            if (sessionID == null) {
                null
            } else {
                BorderFactory.createLineBorder(Color(0xFF1744), 2)
            }
        host.component.revalidate()
        host.component.repaint()
    }

    private fun prepareDisplayedSessionLineage(url: String?) {
        val sessionID = OpenCodeServerProtocol.sessionIdFromUrl(url)
        sessionID?.let(permissionAutoResponder::prepareSession)
        foreignSessionWarning.onDisplayedSessionChanged(sessionID)
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
        return disposed || host.isDisposed
    }

    private fun addAlarmRequest(alarm: Alarm, delayMillis: Int, request: () -> Unit) {
        if (isContentDisposed() || alarm.isDisposed) return
        alarm.addRequest(
            { if (!isContentDisposed()) request() },
            delayMillis,
        )
    }

    override fun dispose() {
        disposed = true
        pageLifecycle.reset()
        foreignSessionWarning.suppress()
        permissionAutoResponder.dispose()
        openProjectAlarm.cancelAllRequests()
        pageLoadWatchdogAlarm.cancelAllRequests()
        panelRecoveryAlarm.cancelAllRequests()
        repaintAlarm.cancelAllRequests()
        systemNotifications.dispose()
        if (isApplicationShutdownInProgress()) return
        Disposer.dispose(browser)
    }

    private fun isApplicationShutdownInProgress(): Boolean {
        return applicationClosing || ApplicationManager.getApplication()?.isDisposed == true
    }
}
