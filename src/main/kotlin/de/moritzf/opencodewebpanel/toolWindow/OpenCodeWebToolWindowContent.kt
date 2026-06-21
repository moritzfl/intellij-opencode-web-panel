package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
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
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest

class OpenCodeWebToolWindowContent(private val toolWindow: ToolWindow) : Disposable {

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
    private val systemNotifications = OpenCodeSystemNotifications(project, toolWindow, browser, serverManager)
    private val requestHandler = OpenCodeBrowserRequestHandler(serverManager, ideNavigation)
    private val openProjectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scriptScheduler = OpenCodeBrowserScriptScheduler(project, browser, openProjectAlarm)
    private var openProjectScriptScheduled = false
    private var fileLinkScriptScheduled = false
    private var externalLinkScriptScheduled = false
    private var codeNavigationScriptScheduled = false
    private var compactLayoutScriptScheduled = false
    private var ideThemeSyncScriptScheduled = false
    private var projectSwitchPromptSuppressionScriptScheduled = false
    private var systemNotificationBridgeScriptScheduled = false

    @Volatile
    private var disposed = false
    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
            if (frame?.isMain == true) {
                fileLinkScriptScheduled = false
                externalLinkScriptScheduled = false
                codeNavigationScriptScheduled = false
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
            scheduleIdeThemeSyncScript()
            scheduleProjectSwitchPromptSuppressionScript()
            scheduleSystemNotificationBridgeScript()
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
            if (OpenCodeSettingsState.getInstance().enableCodeNavigation) {
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
        updateLifecycleIndicator(serverManager.getLifecycleState())
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeServerLifecycleListener.TOPIC,
            object : OpenCodeServerLifecycleListener {
                override fun stateChanged(state: OpenCodeServerLifecycleState) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!isContentDisposed()) updateLifecycleIndicator(state)
                    }
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            OpenCodeSettingsListener.TOPIC,
            object : OpenCodeSettingsListener {
                override fun uiZoomChanged(zoomPercent: Int) {
                    applyBrowserZoom(zoomPercent)
                    if (OpenCodeServerProtocol.isOpenCodeServerPage(
                            serverManager.getServerUrl(),
                            browser.cefBrowser.url
                        )
                    ) {
                        browser.cefBrowser.reload()
                    }
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

                override fun compactLayoutChanged(enabled: Boolean) {
                    applyCompactLayout(enabled)
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
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            object : LafManagerListener {
                override fun lookAndFeelChanged(source: LafManager) {
                    if (OpenCodeSettingsState.getInstance().syncThemeWithIde) {
                        applyIdeThemeSync(enabled = true)
                    }
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
        OpenCodeFileDropHandler(project, browser, serverManager, ::openCodeProjectDirectory, ::isContentDisposed, this).install()
    }

    private fun installBrowserEditShortcutHandler() {
        OpenCodeBrowserEditShortcutHandler(browser, serverManager, ::isContentDisposed, this).install()
    }

    fun getContent() = contentPanel

    private fun updateLifecycleIndicator(state: OpenCodeServerLifecycleState) {
        lifecycleStatusPanel.update(state)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun retryOpenCodeServerStart() {
        if (isContentDisposed()) return
        lifecycleStatusPanel.setRetryEnabled(false)
        serverManager.stopServer()
        checkAndLoadContent()
    }

    fun checkAndLoadContent() {
        if (isContentDisposed()) return
        serverManager.ensureStarted(
            project,
            openCodeProjectDirectory(),
            callbackActive = { !isContentDisposed() },
            onStarted = { loadProjectPage() },
            onFailed = { showErrorInBrowser() },
        )
    }

    private fun loadProjectPage() {
        if (isContentDisposed()) return
        val serverUrl = serverManager.getServerUrl() ?: return
        val url = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)

        thisLogger().info("Loading OpenCode project page")
        openProjectScriptScheduled = false
        fileLinkScriptScheduled = false
        externalLinkScriptScheduled = false
        compactLayoutScriptScheduled = false
        ideThemeSyncScriptScheduled = false
        projectSwitchPromptSuppressionScriptScheduled = false
        systemNotificationBridgeScriptScheduled = false
        openProjectAlarm.cancelAllRequests()
        applyBrowserZoom()
        browser.loadURL(url)
        scheduleOpenProjectScript()
        scheduleFileLinkScript()
        scheduleExternalLinkScript()
        scheduleIdeThemeSyncScript()
        scheduleProjectSwitchPromptSuppressionScript()
        scheduleSystemNotificationBridgeScript()
    }

    private fun applyBrowserZoom(zoomPercent: Int = OpenCodeSettingsState.getInstance().uiZoomPercent) {
        val zoomPercent = OpenCodeSettingsState.sanitizeUiZoomPercent(zoomPercent)
        browser.cefBrowser.setZoomLevel(OpenCodeServerProtocol.toCefZoomLevel(zoomPercent))
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

        scriptScheduler.schedule(script, rootUrl)
    }

    private fun scheduleFileLinkScript() {
        if (fileLinkScriptScheduled) return
        if (!OpenCodeSettingsState.getInstance().openFileLinksInIde) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildFileLinkHandlerScript(
            openCodeProjectDirectory(),
            enabled = true,
            openFileCallback = openFileLinkQuery.inject("rawHref"),
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
        if (!OpenCodeSettingsState.getInstance().enableCodeNavigation) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = OpenCodeServerProtocol.buildCodeNavigationScript(
            enabled = true,
            openCodeCallback = openCodeReferenceQuery.inject("ref"),
        ) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        codeNavigationScriptScheduled = true

        scriptScheduler.schedule(script, rootUrl) { OpenCodeSettingsState.getInstance().enableCodeNavigation }
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
        if (!enabled) {
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
        if (!enabled) {
            browser.cefBrowser.reload()
            return
        }
        val script = OpenCodeServerProtocol.buildFileLinkHandlerScript(
            openCodeProjectDirectory(),
            enabled = true,
            openFileCallback = openFileLinkQuery.inject("rawHref"),
        ) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        fileLinkScriptScheduled = true
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

    private fun applyCompactLayout(enabled: Boolean) {
        // Toggling requires a page reload — early injection on next load start
        compactLayoutScriptScheduled = false
        val serverUrl = serverManager.getServerUrl() ?: return
        if (OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) {
            browser.cefBrowser.reload()
        }
    }

    private fun showErrorInBrowser() {
        if (isContentDisposed()) return
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
        Disposer.dispose(browser)
    }
}
