package de.moritzf.opencodewebpanel.toolWindow

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter

/**
 * Removes the context menu's built-in "View Page Source" entry.
 *
 * Chromium's handling for it opens the source in a popup browser, but JBCef never attaches
 * popup browsers to a window, so the entry silently does nothing. Instead of reimplementing
 * it, the gear menu's "Open Browser DevTools" provides full page inspection (elements,
 * console, network) through Chromium's own tooling. All other menu entries stay untouched.
 */
internal class OpenCodeBrowserContextMenuHandler : CefContextMenuHandlerAdapter() {

    override fun onBeforeContextMenu(
        browser: CefBrowser?,
        frame: CefFrame?,
        params: CefContextMenuParams?,
        model: CefMenuModel?,
    ) {
        model?.remove(CefMenuModel.MenuId.MENU_ID_VIEW_SOURCE)
    }
}
