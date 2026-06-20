package com.github.xausky.opencodewebui.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefAuthCallback
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

class OpenCodeWebToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowContent = OpenCodeWebToolWindowContent(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            val content = ContentFactory.getInstance().createContent(toolWindowContent.getContent(), null, false)
            content.setDisposer(toolWindowContent)
            toolWindow.contentManager.addContent(content)
            toolWindowContent.checkAndLoadContent()
        }
    }

    override fun shouldBeAvailable(project: Project) = true

    class OpenCodeWebToolWindowContent(toolWindow: ToolWindow) : Disposable {

        private val project = toolWindow.project
        private val browser = JBCefBrowser()
        private val serverManager = SharedOpenCodeServerManager.getInstance()
        private val openProjectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
        private var openProjectScriptScheduled = false
        private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
            override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
                val password = serverManager.getServerPassword() ?: return false
                val requestUrl = request?.url ?: return false
                if (OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverManager.getServerUrl(), requestUrl)) {
                    request.setHeaderByName("Authorization", OpenCodeServerProtocol.buildBasicAuthHeader(password), true)
                }
                return false
            }
        }
        private val authHandler = object : CefRequestHandlerAdapter() {
            override fun getResourceRequestHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                isNavigation: Boolean,
                isDownload: Boolean,
                requestInitiator: String?,
                disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler {
                return resourceRequestHandler
            }

            override fun getAuthCredentials(
                browser: CefBrowser?,
                originUrl: String?,
                isProxy: Boolean,
                host: String?,
                port: Int,
                realm: String?,
                scheme: String?,
                callback: CefAuthCallback?,
            ): Boolean {
                val password = serverManager.getServerPassword() ?: return false
                if (!OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverManager.getServerUrl(), isProxy, host, port)) {
                    return false
                }
                callback?.Continue(OpenCodeServerProtocol.BASIC_AUTH_USERNAME, password)
                return callback != null
            }
        }
        private val loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                if (httpStatusCode !in 200..399) return
                if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), frame.url)) return

                scheduleOpenProjectScript()
            }
        }
        init {
            browser.jbCefClient.addRequestHandler(authHandler, browser.cefBrowser)
            browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        }

        fun getContent() = browser.component

        fun checkAndLoadContent() {
            serverManager.ensureStarted(
                project,
                project.basePath,
                onStarted = { loadProjectPage() },
                onFailed = { showErrorInBrowser() },
            )
        }

        private fun loadProjectPage() {
            val serverUrl = serverManager.getServerUrl() ?: return
            val url = OpenCodeServerProtocol.buildAuthenticatedServerRootUrl(serverUrl, serverManager.getServerPassword())

            thisLogger().info("Loading OpenCode project page")
            openProjectScriptScheduled = false
            openProjectAlarm.cancelAllRequests()
            browser.loadURL(url)
            scheduleOpenProjectScript()
        }

        private fun scheduleOpenProjectScript() {
            if (openProjectScriptScheduled) return

            val serverUrl = serverManager.getServerUrl() ?: return
            val script = OpenCodeServerProtocol.buildOpenProjectScript(project.basePath, serverUrl) ?: return
            val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
            openProjectScriptScheduled = true

            listOf(250, 750, 1500, 3000, 5000, 8000, 12000).forEach { delayMillis ->
                openProjectAlarm.addRequest(
                    {
                        if (!project.isDisposed) {
                            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                        }
                    },
                    delayMillis,
                )
            }
        }

        private fun showErrorInBrowser() {
            val html = """
                <html>
                <body style="background-color: #2B2B2B; color: #A9B7C6; font-family: sans-serif; padding: 20px;">
                    <h2>Failed to start OpenCode server</h2>
                    <p>Please make sure 'opencode' is installed and available in your PATH.</p>
                    <p>Run the following command to start the server manually:</p>
                    <pre style="background: #3C3F41; padding: 10px; border-radius: 4px;">opencode serve --hostname 127.0.0.1 --port 0 --print-logs</pre>
                </body>
                </html>
            """.trimIndent()
            browser.loadHTML(html)
        }

        override fun dispose() {
            openProjectAlarm.cancelAllRequests()
            Disposer.dispose(browser)
        }
    }
}
