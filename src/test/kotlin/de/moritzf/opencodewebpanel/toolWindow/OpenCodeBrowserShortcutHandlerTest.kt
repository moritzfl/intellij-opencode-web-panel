package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeBrowserShortcutHandlerTest {
    @Test
    fun everyOpenCodeCommandResolvesDefaultKeybinds() {
        OpenCodeBrowserCommand.entries.forEach { command ->
            val keybinds = OpenCodeBrowserShortcutHandler.resolveOpenCodeKeybinds(command, null)
            assertTrue(command.name, keybinds.newLayout.isNotEmpty())
            assertTrue(command.name, keybinds.classic.isNotEmpty())
        }
    }

    @Test
    fun newSessionUsesLayoutSpecificOpenCodeCommands() {
        val keybinds = OpenCodeBrowserShortcutHandler.resolveOpenCodeKeybinds(OpenCodeBrowserCommand.NEW_SESSION, null)

        assertEquals(listOf("mod+t"), keybinds.newLayout)
        assertEquals(listOf("mod+shift+s"), keybinds.classic)
    }

    @Test
    fun customOpenCodeKeybindsOverrideEachLayoutDefault() {
        val snapshot = """{"settings.v3":"{\"keybinds\":{\"tab.new\":\"alt+t\",\"session.new\":\"alt+s\"}}"}"""

        assertTrue(
            OpenCodeBrowserShortcutHandler.resolveOpenCodeKeybinds(OpenCodeBrowserCommand.NEW_SESSION, snapshot) ==
                OpenCodeResolvedKeybinds(newLayout = listOf("alt+t"), classic = listOf("alt+s")),
        )
    }

    @Test
    fun customOpenCodeKeybindOverridesDefault() {
        val snapshot = """{"settings.v3":"{\"keybinds\":{\"agent.cycle\":\"alt+j\"}}"}"""

        assertTrue(
            OpenCodeBrowserShortcutHandler.resolveOpenCodeKeybinds(OpenCodeBrowserCommand.CYCLE_AGENT, snapshot) ==
                OpenCodeResolvedKeybinds(newLayout = listOf("alt+j"), classic = listOf("alt+j")),
        )
    }

    @Test
    fun disabledOpenCodeKeybindDisablesBridgeCommand() {
        val snapshot = """{"settings.v3":"{\"keybinds\":{\"agent.cycle\":\"none\"}}"}"""

        assertTrue(
            OpenCodeBrowserShortcutHandler.resolveOpenCodeKeybinds(OpenCodeBrowserCommand.CYCLE_AGENT, snapshot) ==
                OpenCodeResolvedKeybinds(newLayout = emptyList(), classic = emptyList()),
        )
    }

    @Test
    fun malformedSnapshotFallsBackToOpenCodeDefault() {
        assertTrue(
            OpenCodeBrowserShortcutHandler.resolveOpenCodeKeybinds(OpenCodeBrowserCommand.CYCLE_AGENT, "not-json") ==
                OpenCodeResolvedKeybinds(newLayout = listOf("mod+."), classic = listOf("mod+.")),
        )
    }

    @Test
    fun composerCommandsAreEnabledOnlyOnOpenCodeComposerRoutes() {
        val serverUrl = "http://127.0.0.1:4141"

        assertTrue(
            OpenCodeBrowserShortcutHandler.isCommandAvailable(
                OpenCodeBrowserCommand.CHOOSE_MODEL,
                serverUrl,
                "$serverUrl/server/key/session/ses_123",
            ),
        )
        assertTrue(
            OpenCodeBrowserShortcutHandler.isCommandAvailable(
                OpenCodeBrowserCommand.CHOOSE_MODEL,
                serverUrl,
                "$serverUrl/new-session?draftId=draft",
            ),
        )
        assertFalse(
            OpenCodeBrowserShortcutHandler.isCommandAvailable(
                OpenCodeBrowserCommand.CHOOSE_MODEL,
                serverUrl,
                "$serverUrl/",
            ),
        )
        assertFalse(
            OpenCodeBrowserShortcutHandler.isCommandAvailable(
                OpenCodeBrowserCommand.CHOOSE_MODEL,
                serverUrl,
                "https://example.com/session/ses_123",
            ),
        )
        assertTrue(
            OpenCodeBrowserShortcutHandler.isCommandAvailable(
                OpenCodeBrowserCommand.NEW_SESSION,
                serverUrl,
                "$serverUrl/",
            ),
        )
    }

    @Test
    fun shortcutDispatchScriptSelectsLayoutAndDispatchesCancelableKeyboardEvent() {
        val script = OpenCodeBrowserSnippets.buildShortcutDispatchScript(
            newLayoutKeybinds = listOf("mod+t"),
            classicKeybinds = listOf("mod+shift+s"),
        )!!

        assertTrue(script.contains("titlebar-v2"))
        assertTrue(script.contains("new KeyboardEvent('keydown'"))
        assertTrue(script.contains("navigator.platform"))
        assertTrue(script.contains("event.defaultPrevented"))
        assertTrue(script.contains("'mod+t'"))
        assertTrue(script.contains("'mod+shift+s'"))
        assertFalse(script.contains("sendKeyEvent"))
    }

    @Test
    fun shortcutDispatchScriptEscapesQuoteKeybindsAndMapsNamedKeys() {
        val script = OpenCodeBrowserSnippets.buildShortcutDispatchScript(
            newLayoutKeybinds = listOf("mod+'"),
            classicKeybinds = listOf("escape"),
        )!!

        assertTrue(script.contains("""'mod+\''"""))
        assertTrue(script.contains("'escape'"))
        assertTrue(script.contains("escape: 'Escape'"))
        assertTrue(script.contains("\"'\": 'Quote'"))
    }

    @Test
    fun shortcutDispatchScriptIsMissingWithoutBindings() {
        assertNull(OpenCodeBrowserSnippets.buildShortcutDispatchScript(emptyList(), emptyList()))
    }
}
