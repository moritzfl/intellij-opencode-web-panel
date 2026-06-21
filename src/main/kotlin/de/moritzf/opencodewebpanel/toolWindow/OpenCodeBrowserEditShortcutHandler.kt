package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent

internal class OpenCodeBrowserEditShortcutHandler(
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val isDisposed: () -> Boolean,
    private val parentDisposable: Disposable,
) {
    fun install() {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (isDisposed()) return@KeyEventDispatcher false
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), browser.cefBrowser.url)) {
                return@KeyEventDispatcher false
            }
            if (!isFocusInsideBrowser()) return@KeyEventDispatcher false

            when (shortcutAction(event)) {
                EditShortcutAction.UNDO -> browser.cefBrowser.editFrame().undo()
                EditShortcutAction.REDO -> browser.cefBrowser.editFrame().redo()
                null -> return@KeyEventDispatcher false
            }
            true
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        Disposer.register(parentDisposable) {
            focusManager.removeKeyEventDispatcher(dispatcher)
        }
    }

    private fun isFocusInsideBrowser(): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return browser.component == focusOwner || browser.component.isAncestorOf(focusOwner)
    }

    internal companion object {
        fun shortcutAction(event: KeyEvent): EditShortcutAction? {
            if (event.isAltDown) return null
            if (event.isMetaDown == event.isControlDown) return null
            val keyCode = event.keyCode
            return when {
                keyCode == KeyEvent.VK_Z && !event.isShiftDown -> EditShortcutAction.UNDO
                keyCode == KeyEvent.VK_Z && event.isShiftDown -> EditShortcutAction.REDO
                keyCode == KeyEvent.VK_Y && !event.isShiftDown -> EditShortcutAction.REDO
                else -> null
            }
        }
    }
}

private fun org.cef.browser.CefBrowser.editFrame() = focusedFrame ?: mainFrame

internal enum class EditShortcutAction {
    UNDO,
    REDO,
}
