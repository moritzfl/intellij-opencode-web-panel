package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.features.OpenCodeIdeNavigation
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefAuthCallback
import org.cef.handler.CefRequestHandler.TerminationStatus
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

internal class OpenCodeBrowserRequestHandler(
    private val serverManager: SharedOpenCodeServerManager,
    private val ideNavigation: OpenCodeIdeNavigation,
    private val onRenderProcessCrash: () -> Unit = {},
) : CefRequestHandlerAdapter() {
    private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
            val password = serverManager.getServerPassword() ?: return false
            val requestUrl = request?.url ?: return false
            // Gate on server readiness, not launcher process liveness: on Windows the launcher
            // exits after spawning the real server while the HTTP endpoint stays up.
            if (serverManager.isServerReadyForAuth() &&
                OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverManager.getServerUrl(), requestUrl)
            ) {
                request.setHeaderByName("Authorization", OpenCodeServerProtocol.buildBasicAuthHeader(password), true)
            }
            return false
        }
    }

    override fun onBeforeBrowse(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        userGesture: Boolean,
        isRedirect: Boolean,
    ): Boolean {
        val requestUrl = request?.url ?: return false
        if (!OpenCodeServerProtocol.isOpenFileLinkRequest(requestUrl)) return false
        if (OpenCodeSettingsState.getInstance().openFileLinksInIde) {
            ideNavigation.openFileLinkInIde(
                OpenCodeServerProtocol.openFileLinkHref(requestUrl),
                OpenCodeServerProtocol.openFileLinkBase(requestUrl),
            )
        }
        return true
    }

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
        if (!serverManager.isServerReadyForAuth()) return false
        if (!OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverManager.getServerUrl(), isProxy, host, port)) {
            return false
        }
        callback?.Continue(OpenCodeServerProtocol.BASIC_AUTH_USERNAME, password)
        return callback != null
    }

    override fun onRenderProcessTerminated(browser: CefBrowser?, status: TerminationStatus?) {
        onRenderProcessCrash()
    }
}
