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
