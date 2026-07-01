package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest

class OpenCodeWebToolWindowContent(toolWindow: ToolWindow) : Disposable {

    private companion object {
        private const val BROWSER_RECOVERY_THROTTLE_MILLIS = 10_000L

        @Volatile
        private var applicationClosing = false
    }

    private val project = toolWindow.project
    private val browser = JBCefBrowser()
    private val lifecycleStatusPanel = OpenCodeLifecycleStatusPanel(::retryOpenCodeServerStart)
    private val contentPanel = BorderLayoutPanel().apply {
        isOpaque = false
        addToTop(lifecycleStatusPanel.component)
        addToCenter(browser.component)
    }
    private val openFileLinkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openCodeReferenceQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openCodeLocalStorageQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val systemNotificationQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openExternalLinkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val serverManager = SharedOpenCodeServerManager.getInstance()
    private val ideNavigation = OpenCodeIdeNavigation(project, browser, serverManager, ::openCodeProjectDirectory, this)
    private val localStorageBridge = OpenCodeLocalStorageBridge(
        browser,
        serverManager,
        syncCallback = { openCodeLocalStorageQuery.inject("payload") },
    )
    private val systemNotifications = OpenCodeSystemNotifications(project, toolWindow, browser, serverManager, ::openCodeProjectDirectory)
    private val requestHandler = OpenCodeBrowserRequestHandler(serverManager, ideNavigation, ::recoverFromRendererCrash)
    private val openProjectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scriptScheduler = OpenCodeBrowserScriptScheduler(project, browser, openProjectAlarm)
    private var openProjectScriptScheduled = false
    private var fileLinkScriptScheduled = false
    private var externalLinkScriptScheduled = false
    private var codeNavigationScriptScheduled = false
    private var filePasteSuppressionScriptScheduled = false
    private var compactLayoutScriptScheduled = false
    private var ideThemeSyncScriptScheduled = false
    private var projectSwitchPromptSuppressionScriptScheduled = false
    private var systemNotificationBridgeScriptScheduled = false
    private var hidingBrowserUntilProjectLoads = false
    private var loadedServerRootUrl: String? = null
    private var pendingServerStartRequest = false
    private var lastBrowserRecoveryAttemptAtMillis = 0L

    @Volatile
    private var disposed = false
    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
            if (frame?.isMain == true) {
                fileLinkScriptScheduled = false
                externalLinkScriptScheduled = false
                codeNavigationScriptScheduled = false
                filePasteSuppressionScriptScheduled = false
                compactLayoutScriptScheduled = false
                ideThemeSyncScriptScheduled = false
                projectSwitchPromptSuppressionScriptScheduled = false
                systemNotificationBridgeScriptScheduled = false
                localStorageBridge.restore(frame.url)
                localStorageBridge.installSync(frame.url)
                injectIdeThemeSyncEarly()
                injectCompactLayoutEarly()
            }
        }

        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain != true) return
            if (httpStatusCode !in 200..399) return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), frame.url)) return

            scheduleOpenProjectScript()
            localStorageBridge.installSync(frame.url)
            scheduleFileLinkScript()
            scheduleExternalLinkScript()
            scheduleCodeNavigationScript()
            scheduleFilePasteSuppressionScript()
            scheduleIdeThemeSyncScript()
            scheduleProjectSwitchPromptSuppressionScript()
            scheduleSystemNotificationBridgeScript()
            revealBrowserIfProjectReady(frame.url)
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
        openCodeReferenceQuery.addHandler { ref ->
            if (OpenCodeSettingsState.getInstance().effectiveCodeNavigationEnabled()) {
                ideNavigation.openCodeReferenceInIde(ref)
            }
            null
        }
        openCodeLocalStorageQuery.addHandler { snapshot ->
            localStorageBridge.sync(snapshot)
            null
        }
        systemNotificationQuery.addHandler { payload ->
            if (OpenCodeSettingsState.getInstance().enableSystemNotifications) {
                systemNotifications.show(payload)
            }
            null
        }
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        installFileDropTransferHandler()
        installBrowserEditShortcutHandler()
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AppLifecycleListener.TOPIC,
            object : AppLifecycleListener {
                override fun appClosing() {
                    applicationClosing = true
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
            OpenCodeSettingsListener.TOPIC,
            object : OpenCodeSettingsListener {
                override fun uiZoomChanged(zoomPercent: Int) {
                    // CEF applies zoom-level changes to the live page; reloading would drop the
                    // user's chat draft and scroll position.
                    applyBrowserZoom(zoomPercent)
                }

                override fun hideBrowserUntilProjectLoadsChanged(enabled: Boolean) {
                    applyHideBrowserUntilProjectLoads(enabled)
                }

                override fun fileLinkNavigationChanged(enabled: Boolean) {
                    applyFileLinkNavigation(enabled)
                }

                override fun externalLinkNavigationChanged(enabled: Boolean) {
                    applyExternalLinkNavigation(enabled)
                }

                override fun codeNavigationChanged(enabled: Boolean) {
                    applyCodeNavigation(enabled)
                }

                override fun chatFileDropChanged(enabled: Boolean) {
                    applyFilePasteSuppression(enabled)
                }

                override fun compactLayoutChanged(enabled: Boolean) {
                    applyCompactLayout()
                }

                override fun ideThemeSyncChanged(enabled: Boolean) {
                    applyIdeThemeSync(enabled)
                }

                override fun projectSwitchPromptSuppressionChanged(enabled: Boolean) {
                    applyProjectSwitchPromptSuppression(enabled)
                }

                override fun systemNotificationsChanged(enabled: Boolean) {
                    applySystemNotifications(enabled)
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

    private fun installFileDropTransferHandler() {
        OpenCodeFileDropHandler(
            project,
            browser,
            serverManager,
            ::openCodeProjectDirectory,
            ::isContentDisposed,
            this
        ).install()
    }

    private fun installBrowserEditShortcutHandler() {
        OpenCodeBrowserEditShortcutHandler(browser, serverManager, ::isContentDisposed, this).install()
    }

    fun getContent() = contentPanel

    private fun updateLifecycleIndicator(state: OpenCodeServerLifecycleState) {
        if (state == OpenCodeServerLifecycleState.RUNNING && hidingBrowserUntilProjectLoads) {
            lifecycleStatusPanel.showProgress("Opening OpenCode project...")
            contentPanel.revalidate()
            contentPanel.repaint()
            return
        }
        lifecycleStatusPanel.update(state)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun retryOpenCodeServerStart() {
        restartOpenCodeServer()
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
                loadProjectPage()
            },
            onFailed = {
                pendingServerStartRequest = false
                showErrorInBrowser()
            },
        )
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
        val url = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)

        thisLogger().info("Loading OpenCode project page")
        loadedServerRootUrl = url
        openProjectScriptScheduled = false
        fileLinkScriptScheduled = false
        externalLinkScriptScheduled = false
        compactLayoutScriptScheduled = false
        ideThemeSyncScriptScheduled = false
        projectSwitchPromptSuppressionScriptScheduled = false
        systemNotificationBridgeScriptScheduled = false
        openProjectAlarm.cancelAllRequests()
        applyBrowserZoom()
        startHidingBrowserUntilProjectLoads()
        browser.loadURL(url)
        scheduleOpenProjectScript()
        scheduleFileLinkScript()
        scheduleExternalLinkScript()
        scheduleFilePasteSuppressionScript()
        scheduleIdeThemeSyncScript()
        scheduleProjectSwitchPromptSuppressionScript()
        scheduleSystemNotificationBridgeScript()
    }

    private fun startHidingBrowserUntilProjectLoads() {
        if (!OpenCodeSettingsState.getInstance().hideBrowserUntilProjectLoads) {
            hidingBrowserUntilProjectLoads = false
            browser.component.isVisible = true
            return
        }
        hidingBrowserUntilProjectLoads = true
        browser.component.isVisible = false
        lifecycleStatusPanel.showProgress("Opening OpenCode project...")
        openProjectAlarm.addRequest(
            {
                if (hidingBrowserUntilProjectLoads && !isContentDisposed()) {
                    hidingBrowserUntilProjectLoads = false
                    browser.component.isVisible = true
                    lifecycleStatusPanel.update(serverManager.getLifecycleState())
                    contentPanel.revalidate()
                    contentPanel.repaint()
                }
            },
            15_000,
        )
        contentPanel.revalidate()
        contentPanel.repaint()
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
        hidingBrowserUntilProjectLoads = false
        openProjectAlarm.cancelAllRequests()
        browser.component.isVisible = true
        browser.loadURL("about:blank")
    }

    private fun revealBrowserIfProjectReady(frameUrl: String?) {
        if (!hidingBrowserUntilProjectLoads) return
        if (!isOpenCodeProjectDestination(frameUrl)) return
        hidingBrowserUntilProjectLoads = false
        browser.component.isVisible = true
        lifecycleStatusPanel.update(serverManager.getLifecycleState())
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun isOpenCodeProjectDestination(frameUrl: String?): Boolean {
        val serverUrl = serverManager.getServerUrl() ?: return false
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) return false
        val projectDirectory = openCodeProjectDirectory()?.takeIf { it.isNotBlank() } ?: return true
        return OpenCodeServerProtocol.isSameFilesystemPath(
            OpenCodeServerProtocol.routeDirectoryFromUrl(frameUrl),
            projectDirectory
        )
    }

    private fun applyHideBrowserUntilProjectLoads(enabled: Boolean) {
        if (!enabled) {
            hidingBrowserUntilProjectLoads = false
            browser.component.isVisible = true
            lifecycleStatusPanel.update(serverManager.getLifecycleState())
            contentPanel.revalidate()
            contentPanel.repaint()
            return
        }
        if (OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), browser.cefBrowser.url) &&
            !isOpenCodeProjectDestination(browser.cefBrowser.url)
        ) {
            startHidingBrowserUntilProjectLoads()
        }
    }

    private fun applyBrowserZoom(zoomPercent: Int = OpenCodeSettingsState.getInstance().uiZoomPercent) {
        val zoomPercent = OpenCodeSettingsState.sanitizeUiZoomPercent(zoomPercent)
        browser.cefBrowser.zoomLevel = OpenCodeServerProtocol.toCefZoomLevel(zoomPercent)
    }

    private fun scheduleOpenProjectScript() {
        if (openProjectScriptScheduled) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val settings = OpenCodeSettingsState.getInstance()
        val script = OpenCodeServerProtocol.buildOpenProjectScript(
            openCodeProjectDirectory(),
            serverUrl,
            settings.openMostRecentConversationOnStartup,
        ) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        openProjectScriptScheduled = true

        scriptScheduler.schedule(script, rootUrl) { !isOpenCodeProjectDestination(browser.cefBrowser.url) }
    }

    private fun scheduleFileLinkScript() {
        if (fileLinkScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().openFileLinksInIde) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildFileLinkHandlerScript(
            openCodeProjectDirectory(),
            enabled = true,
            openFileCallback = openFileLinkQuery.inject("rawHref + '\\n' + directory"),
        ) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        fileLinkScriptScheduled = true

        scriptScheduler.schedule(script, rootUrl) { OpenCodeSettingsState.getInstance().openFileLinksInIde }
    }

    private fun scheduleExternalLinkScript() {
        if (externalLinkScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().openExternalLinksInBrowser) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildExternalLinkHandlerScript(
            enabled = true,
            openExternalCallback = openExternalLinkQuery.inject("href"),
        ) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        externalLinkScriptScheduled = true

        scriptScheduler.schedule(script, rootUrl) {
            OpenCodeSettingsState.getInstance().openExternalLinksInBrowser &&
                    OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)
        }
    }

    private fun scheduleCodeNavigationScript() {
        if (codeNavigationScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().effectiveCodeNavigationEnabled()) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildCodeNavigationScript(
            enabled = true,
            openCodeCallback = openCodeReferenceQuery.inject("ref"),
        ) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        codeNavigationScriptScheduled = true

        scriptScheduler.schedule(script, rootUrl) { OpenCodeSettingsState.getInstance().effectiveCodeNavigationEnabled() }
    }

    private fun scheduleProjectSwitchPromptSuppressionScript() {
        if (projectSwitchPromptSuppressionScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().suppressProjectSwitchPrompts) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildProjectSwitchPromptSuppressionScript(enabled = true) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        projectSwitchPromptSuppressionScriptScheduled = true

        scriptScheduler.schedule(script, rootUrl) { OpenCodeSettingsState.getInstance().suppressProjectSwitchPrompts }
    }

    private fun scheduleFilePasteSuppressionScript() {
        if (filePasteSuppressionScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildFilePasteSuppressionScript(enabled = true) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        filePasteSuppressionScriptScheduled = true

        scriptScheduler.schedule(script, rootUrl) { OpenCodeSettingsState.getInstance().enableChatFileDrop }
    }

    private fun scheduleIdeThemeSyncScript() {
        if (ideThemeSyncScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().syncThemeWithIde) return

        val serverUrl = serverManager.getServerUrl() ?: return
        ideThemeSyncScriptScheduled = true

        scriptScheduler.scheduleAction(
            shouldRun = { OpenCodeSettingsState.getInstance().syncThemeWithIde },
            action = { executeIdeThemeSyncScript(serverUrl) },
        )
    }

    private fun scheduleSystemNotificationBridgeScript() {
        if (systemNotificationBridgeScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().enableSystemNotifications) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildSystemNotificationBridgeScript(
            enabled = true,
            notificationCallback = systemNotificationQuery.inject("payload"),
        ) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        systemNotificationBridgeScriptScheduled = true

        scriptScheduler.schedule(script, rootUrl) {
            OpenCodeSettingsState.getInstance().enableSystemNotifications &&
                    OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)
        }
    }

    private fun applyCodeNavigation(enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        codeNavigationScriptScheduled = false
        if (!enabled || !OpenCodeSettingsState.getInstance().openFileLinksInIde) {
            browser.cefBrowser.reload()
            return
        }
        val script = OpenCodeServerProtocol.buildCodeNavigationScript(
            enabled = true,
            openCodeCallback = openCodeReferenceQuery.inject("ref"),
        ) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        codeNavigationScriptScheduled = true
    }

    private fun applyFilePasteSuppression(enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        filePasteSuppressionScriptScheduled = false
        if (!enabled) {
            browser.cefBrowser.reload()
            return
        }
        val script = OpenCodeServerProtocol.buildFilePasteSuppressionScript(enabled = true) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        filePasteSuppressionScriptScheduled = true
    }

    private fun applyProjectSwitchPromptSuppression(enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        projectSwitchPromptSuppressionScriptScheduled = false
        if (!enabled) {
            browser.cefBrowser.reload()
            return
        }
        val script = OpenCodeServerProtocol.buildProjectSwitchPromptSuppressionScript(enabled = true) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        projectSwitchPromptSuppressionScriptScheduled = true
    }

    private fun applySystemNotifications(enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        systemNotificationBridgeScriptScheduled = false
        if (!enabled) {
            browser.cefBrowser.reload()
            return
        }
        val script = OpenCodeServerProtocol.buildSystemNotificationBridgeScript(
            enabled = true,
            notificationCallback = systemNotificationQuery.inject("payload"),
        ) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        systemNotificationBridgeScriptScheduled = true
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

    private fun applyFileLinkNavigation(enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        fileLinkScriptScheduled = false
        codeNavigationScriptScheduled = false
        if (!enabled) {
            browser.cefBrowser.reload()
            return
        }
        val script = OpenCodeServerProtocol.buildFileLinkHandlerScript(
            openCodeProjectDirectory(),
            enabled = true,
            openFileCallback = openFileLinkQuery.inject("rawHref + '\\n' + directory"),
        ) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        fileLinkScriptScheduled = true
        if (OpenCodeSettingsState.getInstance().enableCodeNavigation) applyCodeNavigation(enabled = true)
    }

    private fun applyOpenCodeProjectDirectoryChange() {
        openProjectScriptScheduled = false
        fileLinkScriptScheduled = false
        openProjectAlarm.cancelAllRequests()
        checkAndLoadContent()
    }

    private fun openCodeProjectDirectory(): String? {
        return OpenCodeProjectSettingsState.getInstance(project).effectiveProjectDirectory(project.basePath)
    }

    private fun applyExternalLinkNavigation(enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        externalLinkScriptScheduled = false
        if (!enabled) {
            browser.cefBrowser.reload()
            return
        }
        val script = OpenCodeServerProtocol.buildExternalLinkHandlerScript(
            enabled = true,
            openExternalCallback = openExternalLinkQuery.inject("href"),
        ) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        externalLinkScriptScheduled = true
    }

    private fun injectCompactLayoutEarly() {
        if (compactLayoutScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().forceCompactLayout) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildCompactLayoutScript(enabled = true) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        compactLayoutScriptScheduled = true

        // Inject immediately (onLoadStart — before SPA bundle executes)
        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        // Re-inject on delays in case the early injection ran before JS context was ready
        scriptScheduler.scheduleEarly(script, rootUrl) { OpenCodeSettingsState.getInstance().forceCompactLayout }
    }

    private fun injectIdeThemeSyncEarly() {
        if (ideThemeSyncScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().syncThemeWithIde) return

        val serverUrl = serverManager.getServerUrl() ?: return
        ideThemeSyncScriptScheduled = true

        executeIdeThemeSyncScript(serverUrl)
        scriptScheduler.scheduleEarlyAction(
            shouldRun = { OpenCodeSettingsState.getInstance().syncThemeWithIde },
            action = { executeIdeThemeSyncScript(serverUrl) },
        )
    }

    private fun executeIdeThemeSyncScript(serverUrl: String): Boolean {
        val script = OpenCodeServerProtocol.buildIdeThemeSyncScript(
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
        hidingBrowserUntilProjectLoads = false
        browser.component.isVisible = true
        browser.loadHTML(
            OpenCodeServerProtocol.buildStartupErrorPageHtml(
                OpenCodeSettingsState.getInstance().executablePath()
            )
        )
    }

    private fun isContentDisposed(): Boolean {
        return disposed || project.isDisposed
    }

    override fun dispose() {
        disposed = true
        openProjectAlarm.cancelAllRequests()
        systemNotifications.dispose()
        if (isApplicationShutdownInProgress()) return
        Disposer.dispose(browser)
    }

    private fun isApplicationShutdownInProgress(): Boolean {
        return applicationClosing || ApplicationManager.getApplication()?.isDisposed == true
    }
}
