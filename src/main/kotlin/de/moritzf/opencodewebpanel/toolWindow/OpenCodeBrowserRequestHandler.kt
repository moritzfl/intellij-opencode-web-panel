package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefAuthCallback
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

internal class OpenCodeBrowserRequestHandler(
    private val serverManager: SharedOpenCodeServerManager,
    private val ideNavigation: OpenCodeIdeNavigation,
) : CefRequestHandlerAdapter() {
    private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
            val password = serverManager.getServerPassword() ?: return false
            val requestUrl = request?.url ?: return false
            if (serverManager.getServerProcess()?.isAlive == true &&
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
        if (serverManager.getServerProcess()?.isAlive != true) return false
        if (!OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverManager.getServerUrl(), isProxy, host, port)) {
            return false
        }
        callback?.Continue(OpenCodeServerProtocol.BASIC_AUTH_USERNAME, password)
        return callback != null
    }
}
