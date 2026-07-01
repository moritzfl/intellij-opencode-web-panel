package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

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

            zoomShortcutAction(event)?.let { action ->
                when (action) {
                    ZoomShortcutAction.ZOOM_IN -> OpenCodeZoom.apply(OpenCodeZoom::zoomedIn)
                    ZoomShortcutAction.ZOOM_OUT -> OpenCodeZoom.apply(OpenCodeZoom::zoomedOut)
                    ZoomShortcutAction.ZOOM_RESET -> OpenCodeZoom.apply { OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT }
                }
                return@KeyEventDispatcher true
            }

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
            val keymap = KeymapManager.getInstance().activeKeymap
            return shortcutAction(
                event,
                undoShortcuts = keymap.getShortcuts(IdeActions.ACTION_UNDO),
                redoShortcuts = keymap.getShortcuts(IdeActions.ACTION_REDO),
            )
        }

        fun shortcutAction(
            event: KeyEvent,
            undoShortcuts: Array<Shortcut>,
            redoShortcuts: Array<Shortcut>,
        ): EditShortcutAction? {
            return when {
                undoShortcuts.matches(event) -> EditShortcutAction.UNDO
                redoShortcuts.matches(event) -> EditShortcutAction.REDO
                else -> null
            }
        }

        /** Browser-style zoom chords: Cmd/Ctrl with plus, minus, or zero. */
        fun zoomShortcutAction(event: KeyEvent): ZoomShortcutAction? {
            if (!(event.isMetaDown || event.isControlDown) || event.isAltDown) return null
            return when (event.keyCode) {
                KeyEvent.VK_PLUS, KeyEvent.VK_ADD, KeyEvent.VK_EQUALS -> ZoomShortcutAction.ZOOM_IN
                KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> ZoomShortcutAction.ZOOM_OUT
                KeyEvent.VK_0, KeyEvent.VK_NUMPAD0 -> ZoomShortcutAction.ZOOM_RESET
                else -> null
            }
        }

        private fun Array<Shortcut>.matches(event: KeyEvent): Boolean {
            val keyStroke = KeyStroke.getKeyStrokeForEvent(event)
            return any { shortcut ->
                shortcut is KeyboardShortcut &&
                    shortcut.firstKeyStroke == keyStroke &&
                    shortcut.secondKeyStroke == null
            }
        }
    }
}

private fun org.cef.browser.CefBrowser.editFrame() = focusedFrame ?: mainFrame

internal enum class EditShortcutAction {
    UNDO,
    REDO,
}

internal enum class ZoomShortcutAction {
    ZOOM_IN,
    ZOOM_OUT,
    ZOOM_RESET,
}
