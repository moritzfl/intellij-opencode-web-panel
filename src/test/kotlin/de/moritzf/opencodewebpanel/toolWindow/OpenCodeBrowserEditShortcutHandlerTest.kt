package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel

class OpenCodeBrowserEditShortcutHandlerTest {
    @Test
    fun shortcutActionDetectsUndo() {
        assertEquals(EditShortcutAction.UNDO, shortcutAction(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK))
        assertEquals(EditShortcutAction.UNDO, shortcutAction(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun shortcutActionDetectsRedo() {
        assertEquals(
            EditShortcutAction.REDO,
            shortcutAction(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )
        assertEquals(
            EditShortcutAction.REDO,
            shortcutAction(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )
        assertEquals(EditShortcutAction.REDO, shortcutAction(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun shortcutActionIgnoresAmbiguousOrUnmodifiedKeys() {
        assertNull(shortcutAction(KeyEvent.VK_Z, 0))
        assertNull(shortcutAction(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK or InputEvent.CTRL_DOWN_MASK))
        assertNull(shortcutAction(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK or InputEvent.ALT_DOWN_MASK))
        assertNull(shortcutAction(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK))
    }

    private fun shortcutAction(keyCode: Int, modifiers: Int): EditShortcutAction? {
        return OpenCodeBrowserEditShortcutHandler.shortcutAction(
            KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED),
        )
    }
}
