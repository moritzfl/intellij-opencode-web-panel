package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager

/**
 * Re-asserts Chromium-level focus for the embedded browser.
 *
 * JBCef forwards Swing focus to `CefBrowser.setFocus` only on component focus *transitions*,
 * and that signal can be lost: the native call is silently dropped while the browser is not yet
 * initialized, upstream CEF loses off-screen-rendering focus during in-page redirects
 * (CEF issue #3870), and no transition fires at all when the IDE window is re-activated with
 * focus already sitting on the browser component. Chromium keeps delivering key events to the
 * focused DOM node but hides the text caret while it believes the browser host is unfocused, so
 * a missed `setFocus(true)` shows up as "typing works but the caret is invisible".
 *
 * [reassertIfFocused] is cheap and safe to call on every load, route change, and IDE activation:
 * it forces `setFocus(true)` only when the browser component actually owns Swing focus, so it
 * can never steal keyboard input from the rest of the IDE.
 */
internal class OpenCodeBrowserFocusSync(
    private val component: () -> Component,
    private val isActive: () -> Boolean,
    private val setBrowserFocus: (Boolean) -> Unit,
    private val focusOwner: () -> Component? = {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner
    },
    private val runOnUiThread: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
) {

    /** May be called from any thread; the focus check and re-assert run on the EDT. */
    fun reassertIfFocused() {
        runOnUiThread {
            if (!isActive()) return@runOnUiThread
            if (!isBrowserFocusOwner()) return@runOnUiThread
            setBrowserFocus(true)
        }
    }

    private fun isBrowserFocusOwner(): Boolean {
        val owner = focusOwner() ?: return false
        val root = component()
        return owner === root || (root is Container && root.isAncestorOf(owner))
    }
}
