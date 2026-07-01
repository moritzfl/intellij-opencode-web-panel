package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

class OpenCodeBrowserEditShortcutHandlerTest {
    @Test
    fun shortcutActionDetectsUndo() {
        assertEquals(
            EditShortcutAction.UNDO,
            shortcutAction(
                KeyEvent.VK_Z,
                InputEvent.META_DOWN_MASK,
                undoShortcuts = shortcuts(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK),
            ),
        )
    }

    @Test
    fun shortcutActionDetectsRedo() {
        assertEquals(
            EditShortcutAction.REDO,
            shortcutAction(
                KeyEvent.VK_Z,
                InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
                redoShortcuts = shortcuts(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            ),
        )
    }

    @Test
    fun shortcutActionUsesProvidedIdeShortcuts() {
        assertEquals(
            EditShortcutAction.REDO,
            shortcutAction(
                KeyEvent.VK_Y,
                InputEvent.META_DOWN_MASK,
                redoShortcuts = shortcuts(KeyEvent.VK_Y, InputEvent.META_DOWN_MASK),
            ),
        )
        assertNull(
            shortcutAction(
                KeyEvent.VK_Y,
                InputEvent.META_DOWN_MASK,
                redoShortcuts = shortcuts(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            ),
        )
    }

    @Test
    fun shortcutActionIgnoresKeysOutsideIdeShortcuts() {
        assertNull(shortcutAction(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK))
    }

    @Test
    fun zoomShortcutsUseBrowserStyleChords() {
        assertEquals(ZoomShortcutAction.ZOOM_IN, zoomShortcutAction(KeyEvent.VK_PLUS, InputEvent.META_DOWN_MASK))
        assertEquals(ZoomShortcutAction.ZOOM_IN, zoomShortcutAction(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK))
        assertEquals(ZoomShortcutAction.ZOOM_IN, zoomShortcutAction(KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK))
        assertEquals(ZoomShortcutAction.ZOOM_OUT, zoomShortcutAction(KeyEvent.VK_MINUS, InputEvent.META_DOWN_MASK))
        assertEquals(ZoomShortcutAction.ZOOM_OUT, zoomShortcutAction(KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK))
        assertEquals(ZoomShortcutAction.ZOOM_RESET, zoomShortcutAction(KeyEvent.VK_0, InputEvent.META_DOWN_MASK))
        assertEquals(ZoomShortcutAction.ZOOM_RESET, zoomShortcutAction(KeyEvent.VK_NUMPAD0, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun zoomShortcutsRequireModifierAndRejectAltChords() {
        assertNull(zoomShortcutAction(KeyEvent.VK_PLUS, 0))
        assertNull(zoomShortcutAction(KeyEvent.VK_MINUS, InputEvent.ALT_DOWN_MASK))
        assertNull(zoomShortcutAction(KeyEvent.VK_PLUS, InputEvent.META_DOWN_MASK or InputEvent.ALT_DOWN_MASK))
        assertNull(zoomShortcutAction(KeyEvent.VK_1, InputEvent.META_DOWN_MASK))
    }

    private fun zoomShortcutAction(keyCode: Int, modifiers: Int): ZoomShortcutAction? {
        return OpenCodeBrowserEditShortcutHandler.zoomShortcutAction(
            KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED),
        )
    }

    private fun shortcutAction(
        keyCode: Int,
        modifiers: Int,
        undoShortcuts: Array<Shortcut> = emptyArray(),
        redoShortcuts: Array<Shortcut> = emptyArray(),
    ): EditShortcutAction? {
        return OpenCodeBrowserEditShortcutHandler.shortcutAction(
            KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED),
            undoShortcuts = undoShortcuts,
            redoShortcuts = redoShortcuts,
        )
    }

    private fun shortcuts(keyCode: Int, modifiers: Int): Array<Shortcut> {
        return arrayOf(KeyboardShortcut(KeyStroke.getKeyStroke(keyCode, modifiers), null))
    }
}
