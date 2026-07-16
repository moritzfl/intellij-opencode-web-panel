package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptScheduler
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.features.OpenCodeAgentStatusState
import de.moritzf.opencodewebpanel.features.OpenCodeAgentStatusTracker
import de.moritzf.opencodewebpanel.features.OpenCodeChatInputService
import de.moritzf.opencodewebpanel.features.OpenCodeDiffNavigation
import de.moritzf.opencodewebpanel.features.OpenCodeFileDropHandler
import de.moritzf.opencodewebpanel.features.OpenCodeIdeNavigation
import de.moritzf.opencodewebpanel.features.OpenCodeInterruptedSessionRecovery
import de.moritzf.opencodewebpanel.features.OpenCodeLocalStorageBridge
import de.moritzf.opencodewebpanel.features.OpenCodeSystemNotifications
import de.moritzf.opencodewebpanel.features.OpenCodeWorkspaceRefreshCoordinator
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeSuspendResumeListener
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState
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
import javax.swing.JPanel

class OpenCodeWebToolWindowContent(private val toolWindow: ToolWindow) : Disposable {

    private companion object {
        private const val BROWSER_CARD = "browser"
        private const val ERROR_CARD = "error"
        private const val BROWSER_RECOVERY_THROTTLE_MILLIS = 10_000L

        // Delay after a project-page load before flushing queued chat input, so the SPA's own
        // drop handlers are installed when the synthetic drop is dispatched.
        private const val PENDING_CHAT_INPUT_FLUSH_DELAY_MILLIS = 1_500

        // How long the 1 px repaint-recovery resize is held before restoring the real bounds,
        // so CEF's asynchronous view-rect query observes the transient size.
        private const val COMPONENT_SIZE_NUDGE_RESTORE_DELAY_MILLIS = 100

        @Volatile
        private var applicationClosing = false
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
    private val serverManager = SharedOpenCodeServerManager.getInstance()
    private val ideNavigation = OpenCodeIdeNavigation(project, browser, serverManager, ::openCodeProjectDirectory, this)
    private val diffNavigation = OpenCodeDiffNavigation(project, browser, serverManager, ::openCodeProjectDirectory)
    private val localStorageBridge = OpenCodeLocalStorageBridge(
        browser,
        serverManager,
        syncCallback = { openCodeLocalStorageQuery.inject("payload") },
    )
    private val systemNotifications = OpenCodeSystemNotifications(project, toolWindow, browser, serverManager, ::openCodeProjectDirectory, this)
    private val requestHandler = OpenCodeBrowserRequestHandler(serverManager, ideNavigation, ::recoverFromRendererCrash)
    private val interruptedSessionRecovery = OpenCodeInterruptedSessionRecovery(project, serverManager, ::openCodeProjectDirectory)
    private val openProjectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scriptScheduler = OpenCodeBrowserScriptScheduler(project, browser, openProjectAlarm)
    private var openProjectScriptScheduled = false
    /** Most-recent session resolved for the current page load (avoids a second REST fetch). */
    private var pendingMostRecentSessionId: String? = null
    private var pendingOpenMostRecentConversation = false
    private var compactLayoutScriptScheduled = false
    private var ideThemeSyncScriptScheduled = false
    private var hideWebsiteButtonScriptScheduled = false

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
        serverManager,
        ::openCodeProjectDirectory,
        enabled = { !isContentDisposed() && OpenCodeSettingsState.getInstance().showAgentStatusBadge },
        onStateChanged = ::onAgentStatusChanged,
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
                injectedFeatures.forEach { it.scheduled = false }
                compactLayoutScriptScheduled = false
                ideThemeSyncScriptScheduled = false
                hideWebsiteButtonScriptScheduled = false
                if (OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), frame.url)) {
                    // Drop any previous open-project delay series so navigations do not stack injects.
                    // Reset the flag so onLoadEnd can schedule a fresh series for this document.
                    openProjectAlarm.cancelAllRequests()
                    openProjectScriptScheduled = false
                    localStorageBridge.restore(frame.url)
                    localStorageBridge.installSync(frame.url)
                    injectIdeThemeSyncEarly()
                    injectCompactLayoutEarly()
                    injectHideWebsiteButtonEarly()
                }
            }
        }

        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain != true) return
            if (httpStatusCode !in 200..399) return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), frame.url)) return

            // Restore the mirrored localStorage snapshot again on load end. The restore in
            // onLoadStart can run before the new origin's V8 context is ready (e.g. when
            // navigating from about:blank), so this second attempt ensures layout.page is
            // available for the open-project script.
            localStorageBridge.restore(frame.url)
            scheduleOpenProjectScript()
            localStorageBridge.installSync(frame.url)
            injectedFeatures.forEach(::scheduleFeatureScript)
            scheduleIdeThemeSyncScript()
            scheduleFlushPendingChatInput()
            interruptedSessionRecovery.checkAndContinue()
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
                    if (!isContentDisposed()) applyBrowserCursor(Cursor.getPredefinedCursor(cursorType))
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
        openCodeLocalStorageQuery.addHandler { snapshot ->
            localStorageBridge.sync(snapshot)
            null
        }
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        // onAddressChange also fires for the SPA's history-API route changes, which full-load
        // handlers never see.
        browser.jbCefClient.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(cefBrowser: CefBrowser?, frame: CefFrame?, url: String?) {
                    if (frame?.isMain == true) scheduleBrowserRepaintNudges()
                }
            },
            browser.cefBrowser,
        )
        OpenCodeFileDropHandler(project, browser, serverManager, ::openCodeProjectDirectory, ::isContentDisposed, this).install()
        OpenCodeBrowserEditShortcutHandler(browser, serverManager, ::isContentDisposed, this).install()
        OpenCodeChatInputService.getInstance(project).setDispatcher(::dispatchChatTexts)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AppLifecycleListener.TOPIC,
            object : AppLifecycleListener {
                override fun appClosing() {
                    applicationClosing = true
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
                        if (!isContentDisposed()) {
                            clearStaleBrowserPage(state)
                            updateLifecycleIndicator(state)
                            reloadContentAfterRecovery(state)
                        }
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
                        OpenCodeUiSetting.CHAT_FILE_DROP -> applyFeature(filePasteSuppressionFeature, enabled)
                        OpenCodeUiSetting.COMPACT_LAYOUT -> applyCompactLayout()
                        OpenCodeUiSetting.IDE_THEME_SYNC -> applyIdeThemeSync(enabled)
                        OpenCodeUiSetting.PROJECT_SWITCH_PROMPT_SUPPRESSION -> applyFeature(projectSwitchPromptSuppressionFeature, enabled)
                        OpenCodeUiSetting.BROWSER_CURSOR_MIRROR -> applyFeature(cursorMirrorFeature, enabled)
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

        scriptScheduler.scheduleAction(early = true, shouldRun = { !isContentDisposed() }) {
            if (!stillOnSamePage(nudgedAtUrl)) return@scheduleAction
            browser.cefBrowser.notifyScreenInfoChanged()
            browser.component.repaint()
        }
        // If the composite-level nudge was not enough, force the SPA to re-layout and, on the
        // later attempts, wiggle the Swing component size. A 1 px resize causes CEF to reallocate
        // the off-screen backing surface, which clears the mismatched-frame state that a plain
        // repaint sometimes cannot recover.
        scriptScheduler.scheduleAt(500, shouldRun = { !isContentDisposed() }) {
            if (!stillOnSamePage(nudgedAtUrl)) return@scheduleAt
            browser.cefBrowser.executeJavaScript("window.dispatchEvent(new Event('resize'))", rootUrl, 0)
        }
        scriptScheduler.scheduleAt(1500, shouldRun = { !isContentDisposed() }) {
            if (!stillOnSamePage(nudgedAtUrl)) return@scheduleAt
            nudgeComponentSize()
        }
        scriptScheduler.scheduleAt(3000, shouldRun = { !isContentDisposed() }) {
            if (!stillOnSamePage(nudgedAtUrl)) return@scheduleAt
            nudgeComponentSize()
        }
    }

    private fun stillOnSamePage(expectedUrl: String?): Boolean {
        if (expectedUrl.isNullOrBlank()) return false
        return expectedUrl == browser.cefBrowser.url
    }

    /**
     * Grows the browser wrapper by one pixel, holds that size briefly, then restores it. CEF
     * consumes resizes asynchronously (the view rect is queried later from its own thread), so
     * the transient size must survive past this EDT cycle or CEF never observes a change and the
     * off-screen surface is not reallocated. For the same reason the grow step must not
     * revalidate — the layout manager would reassert the old bounds immediately — but it must
     * validate synchronously so the inner CEF component actually resizes with the wrapper.
     */
    private fun nudgeComponentSize() {
        ApplicationManager.getApplication().invokeLater {
            if (isContentDisposed()) return@invokeLater
            val component = browser.component
            val bounds = component.bounds
            if (bounds.width <= 0 || bounds.height <= 0) return@invokeLater
            component.setBounds(bounds.x, bounds.y, bounds.width + 1, bounds.height)
            component.validate()
            component.repaint()
            scriptScheduler.scheduleAt(COMPONENT_SIZE_NUDGE_RESTORE_DELAY_MILLIS, shouldRun = { !isContentDisposed() }) {
                component.setBounds(bounds)
                component.validate()
                // Reconcile with any parent resize that happened while the nudge was held.
                component.revalidate()
                component.repaint()
                browser.cefBrowser.notifyScreenInfoChanged()
            }
        }
    }

    /**
     * Sends IDE-initiated texts (file references, code snippets) into the chat through the same
     * drop mechanism used for drag-and-drop and paste. Returns false when the page is not ready,
     * in which case the texts stay queued in [OpenCodeChatInputService].
     */
    private fun dispatchChatTexts(texts: List<String>): Boolean {
        if (isContentDisposed()) return false
        if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return false
        val serverUrl = serverManager.getServerUrl() ?: return false
        if (!isBrowserOnOpenCodeServerPage(serverUrl)) return false
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(emptyList(), texts, enabled = true) ?: return false
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        return true
    }

    private fun scheduleFlushPendingChatInput() {
        val service = OpenCodeChatInputService.getInstance(project)
        openProjectAlarm.addRequest(
            {
                if (isContentDisposed()) return@addRequest
                val texts = service.takePending()
                if (texts.isNotEmpty() && !dispatchChatTexts(texts)) {
                    // Page changed in the meantime; requeue for the next successful load.
                    service.send(texts)
                }
            },
            PENDING_CHAT_INPUT_FLUSH_DELAY_MILLIS,
        )
    }

    fun getContent() = contentPanel

    private fun updateLifecycleIndicator(state: OpenCodeServerLifecycleState) {
        if (state != OpenCodeServerLifecycleState.RUNNING) {
            resetAgentStatusBadge()
        }
        lifecycleStatusPanel.update(state)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun warnIfOpenCodeVersionIsUnsupported() {
        if (project.isDisposed) return
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
            ?: return
        val installedVersion = serverManager.consumeUnsupportedServerVersionWarning() ?: return
        group.createNotification(
            "OpenCode update required",
            "OpenCode Web Panel requires OpenCode ${OpenCodeServerProtocol.MINIMUM_SUPPORTED_OPENCODE_VERSION} or later. " +
                "Installed version: ${StringUtil.escapeXmlEntities(installedVersion)}.",
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
                showErrorInBrowser()
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
                showErrorInBrowser()
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
        browser.cefBrowser.reload()
    }

    /**
     * Reloads the OpenCode page after the shared server recovered without this panel's involvement,
     * e.g. an automatic health-check restart or a restart initiated from another project window.
     * Without this, the panel would stay on the blank page installed by [clearStaleBrowserPage].
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
            onHealthy = { browser.cefBrowser.reload() },
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
            browser.cefBrowser.reload()
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
        // Boot on the native 1.18 server session route (/server/<serverKey>/session[/<id>]), not the
        // legacy project directory route (/<encodedDir>/session). Cold-loading the bare directory
        // route crashes opencode 1.18.x's application error boundary. Prefer a concrete session id
        // when "open most recent" is on — the SPA route requires :id; the bare .../session URL is
        // only a temporary shell until the open-project script navigates.
        thisLogger().info("Loading OpenCode project page")
        showCenterCard(BROWSER_CARD)
        openProjectScriptScheduled = false
        pendingMostRecentSessionId = null
        pendingOpenMostRecentConversation = false
        injectedFeatures.forEach { it.scheduled = false }
        compactLayoutScriptScheduled = false
        ideThemeSyncScriptScheduled = false
        openProjectAlarm.cancelAllRequests()
        applyBrowserZoom()
        injectedFeatures.forEach(::scheduleFeatureScript)
        scheduleIdeThemeSyncScript()
        // Events that fired before this panel started caring never reached the tracker.
        agentStatusTracker.seed()

        val openMostRecent = OpenCodeSettingsState.getInstance().openMostRecentConversationOnStartup
        val projectDirectory = openCodeProjectDirectory()?.takeIf { it.isNotBlank() }
        pendingOpenMostRecentConversation = openMostRecent
        if (openMostRecent && projectDirectory != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val sessionId = fetchMostRecentSessionId(serverUrl, projectDirectory)
                ApplicationManager.getApplication().invokeLater {
                    if (isContentDisposed()) return@invokeLater
                    pendingMostRecentSessionId = sessionId
                    loadProjectPageAt(serverUrl, sessionId)
                }
            }
            return
        }
        loadProjectPageAt(serverUrl, sessionId = null)
    }

    private fun loadProjectPageAt(serverUrl: String, sessionId: String?) {
        if (isContentDisposed()) return
        val url = OpenCodeServerProtocol.buildServerSessionUrl(serverUrl, sessionId)
        loadedServerRootUrl = url
        // Open-project inject is scheduled from onLoadEnd so it is not stacked with a pre-load series.
        browser.loadURL(url)
    }

    private fun clearStaleBrowserPage(state: OpenCodeServerLifecycleState) {
        if (state != OpenCodeServerLifecycleState.STOPPED &&
            state != OpenCodeServerLifecycleState.FAILED &&
            state != OpenCodeServerLifecycleState.RESTARTING
        ) {
            return
        }
        val previousServerRootUrl = loadedServerRootUrl ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(previousServerRootUrl, browser.cefBrowser.url)) return

        loadedServerRootUrl = null
        openProjectAlarm.cancelAllRequests()
        browser.loadURL("about:blank")
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

        val openMostRecent = pendingOpenMostRecentConversation ||
            OpenCodeSettingsState.getInstance().openMostRecentConversationOnStartup
        if (!openMostRecent) {
            scheduleOpenProjectScript(serverUrl, projectDirectory, openMostRecentConversation = false, mostRecentSessionId = null)
            return
        }
        // Prefer the id resolved for this page load (boot already fetched it). Only re-fetch when
        // a plain reload left the pending id unset (e.g. user turned the setting on mid-session).
        val pendingId = pendingMostRecentSessionId
        if (pendingId != null || pendingOpenMostRecentConversation) {
            scheduleOpenProjectScript(serverUrl, projectDirectory, openMostRecentConversation = true, mostRecentSessionId = pendingId)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessionId = fetchMostRecentSessionId(serverUrl, projectDirectory)
            ApplicationManager.getApplication().invokeLater {
                if (isContentDisposed()) return@invokeLater
                pendingMostRecentSessionId = sessionId
                // loadStart may have cleared the flag for a newer document; onLoadEnd will reschedule.
                if (!openProjectScriptScheduled) return@invokeLater
                scheduleOpenProjectScript(serverUrl, projectDirectory, openMostRecentConversation = true, mostRecentSessionId = sessionId)
            }
        }
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
            // Once the target session is open, further delayed injects only re-seed lastProject.
            // Keep running on the id-less shell and on legacy directory destinations that still
            // need the script; stop once we are on the requested session (or any session when we
            // were only seeding).
            if (openMostRecentConversation && mostRecentSessionId != null) {
                return@schedule OpenCodeServerProtocol.sessionIdFromUrl(frameUrl) != mostRecentSessionId
            }
            val onProjectRootRoute = isOpenCodeProjectRootRoute(frameUrl)
            val onProjectDestination = isOpenCodeProjectDestination(frameUrl)
            !onProjectDestination || onProjectRootRoute
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
        if (ideThemeSyncScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().syncThemeWithIde) return

        val serverUrl = serverManager.getServerUrl() ?: return
        ideThemeSyncScriptScheduled = true

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
    private fun onAgentStatusChanged(state: String) {
        updateAgentStatusBadge(state)
    }

    /**
     * Overlays the tool window icon with a live indicator while the agent works and a warning
     * while it awaits input, so the panel is glanceable even when collapsed.
     */
    private fun updateAgentStatusBadge(state: String) {
        ApplicationManager.getApplication().invokeLater {
            if (isContentDisposed()) return@invokeLater
            if (!OpenCodeSettingsState.getInstance().showAgentStatusBadge) return@invokeLater
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
        ideThemeSyncScriptScheduled = false
        if (!enabled) {
            browser.cefBrowser.reload()
            return
        }
        if (executeIdeThemeSyncScript(serverUrl)) ideThemeSyncScriptScheduled = true
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
        openProjectScriptScheduled = false
        fileLinkFeature.scheduled = false
        openProjectAlarm.cancelAllRequests()
        // The badge state belongs to the previous directory; loadProjectPage re-seeds.
        resetAgentStatusBadge()
        checkAndLoadContent()
    }

    private fun openCodeProjectDirectory(): String? {
        return OpenCodeProjectSettingsState.getInstance(project).effectiveProjectDirectory(project.basePath)
    }

    private fun injectCompactLayoutEarly() {
        if (compactLayoutScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().forceCompactLayout) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        compactLayoutScriptScheduled = true

        // Inject immediately (onLoadStart — before SPA bundle executes)
        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        // Re-inject on delays in case the early injection ran before JS context was ready
        scriptScheduler.schedule(script, rootUrl, early = true) {
            OpenCodeSettingsState.getInstance().forceCompactLayout && isBrowserOnOpenCodeServerPage(serverUrl)
        }
    }

    private fun injectHideWebsiteButtonEarly() {
        if (hideWebsiteButtonScriptScheduled) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = true) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        hideWebsiteButtonScriptScheduled = true

        // Inject immediately (onLoadStart — before SPA bundle executes)
        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        // Re-inject on delays in case the early injection ran before JS context was ready
        scriptScheduler.schedule(script, rootUrl, early = true) {
            isBrowserOnOpenCodeServerPage(serverUrl)
        }
    }

    private fun injectIdeThemeSyncEarly() {
        if (ideThemeSyncScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().syncThemeWithIde) return

        val serverUrl = serverManager.getServerUrl() ?: return
        ideThemeSyncScriptScheduled = true

        executeIdeThemeSyncScript(serverUrl)
        scriptScheduler.scheduleAction(
            early = true,
            shouldRun = { OpenCodeSettingsState.getInstance().syncThemeWithIde && isBrowserOnOpenCodeServerPage(serverUrl) },
            action = { executeIdeThemeSyncScript(serverUrl) },
        )
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
        compactLayoutScriptScheduled = false
        val serverUrl = serverManager.getServerUrl() ?: return
        if (OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) {
            browser.cefBrowser.reload()
        }
    }

    private fun showErrorInBrowser() {
        if (isContentDisposed()) return
        loadedServerRootUrl = null
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

    private fun isContentDisposed(): Boolean {
        return disposed || project.isDisposed
    }

    override fun dispose() {
        disposed = true
        if (!project.isDisposed) {
            OpenCodeChatInputService.getInstance(project).setDispatcher(null)
        }
        openProjectAlarm.cancelAllRequests()
        systemNotifications.dispose()
        if (isApplicationShutdownInProgress()) return
        Disposer.dispose(browser)
    }

    private fun isApplicationShutdownInProgress(): Boolean {
        return applicationClosing || ApplicationManager.getApplication()?.isDisposed == true
    }
}
