package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
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
 * console, network) through Chromium's own tooling. Paste uses the same IDE clipboard bridge
 * as the component-local shortcut; all other commands keep Chromium's native behavior.
 */
internal class OpenCodeBrowserContextMenuHandler(
    private val paste: (() -> Unit)? = null,
    private val canBridgePaste: () -> Boolean = { false },
) : CefContextMenuHandlerAdapter() {

    override fun onBeforeContextMenu(
        browser: CefBrowser?,
        frame: CefFrame?,
        params: CefContextMenuParams?,
        model: CefMenuModel?,
    ) {
        model?.remove(CefMenuModel.MenuId.MENU_ID_VIEW_SOURCE)
        // Chromium can think the clipboard is empty on Wayland even when the IDE can read it.
        // Keep Paste reachable; the action handles an empty/unsupported IDE clipboard natively.
        if (paste != null && canBridgePaste() && params?.isEditable == true) {
            model?.setEnabled(CefMenuModel.MenuId.MENU_ID_PASTE, true)
        }
    }

    override fun onContextMenuCommand(
        browser: CefBrowser?,
        frame: CefFrame?,
        params: CefContextMenuParams?,
        commandId: Int,
        eventFlags: Int,
    ): Boolean {
        if (commandId != CefMenuModel.MenuId.MENU_ID_PASTE || paste == null) return false
        ApplicationManager.getApplication().invokeLater { paste.invoke() }
        return true
    }
}
