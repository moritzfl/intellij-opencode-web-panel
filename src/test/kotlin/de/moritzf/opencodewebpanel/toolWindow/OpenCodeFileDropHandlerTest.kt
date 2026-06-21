package de.moritzf.opencodewebpanel.toolWindow

import org.cef.misc.EventFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.KeyEvent

class OpenCodeFileDropHandlerTest {
    @Test
    fun isPasteShortcutAcceptsCommandOrControlV() {
        assertTrue(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_V, EventFlags.EVENTFLAG_COMMAND_DOWN))
        assertTrue(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_V, EventFlags.EVENTFLAG_CONTROL_DOWN))
        assertTrue(OpenCodeFileDropHandler.isPasteShortcut(0, EventFlags.EVENTFLAG_COMMAND_DOWN, 'v', 'v'))
    }

    @Test
    fun isPasteShortcutRejectsNonPasteKeys() {
        assertFalse(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_C, EventFlags.EVENTFLAG_COMMAND_DOWN))
        assertFalse(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_V, EventFlags.EVENTFLAG_ALT_DOWN))
        assertFalse(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_V, EventFlags.EVENTFLAG_NONE))
        assertFalse(
            OpenCodeFileDropHandler.isPasteShortcut(
                KeyEvent.VK_V,
                EventFlags.EVENTFLAG_COMMAND_DOWN or EventFlags.EVENTFLAG_CONTROL_DOWN,
            ),
        )
    }
}
