package de.moritzf.opencodewebpanel.jcef

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefAuthCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

/** Same job as the panel's [de.moritzf.opencodewebpanel.toolWindow.OpenCodeBrowserRequestHandler]: Basic auth on every resource. */
internal class OpenCodeJcefAuthHandler(
    private val authorization: String,
    private val username: String = "opencode",
    private val password: String = "testpw123",
) : CefRequestHandlerAdapter() {
    private val resourceHandler = object : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
            request?.setHeaderByName("Authorization", authorization, true)
            return false
        }
    }

    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler = resourceHandler

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
        callback?.Continue(username, password)
        return callback != null
    }
}
