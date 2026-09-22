package de.moritzf.opencodewebpanel.features

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeForeignSessionWarningTest {
    private val workspace = "/Users/me/web-ui"
    private var enabled = true
    private val sessions = mutableMapOf<String, OpenCodeServerProtocol.SessionInfo>()
    private val loaded = mutableListOf<String>()
    private val warnings = mutableListOf<String>()
    private var clears = 0
    private var prefixes = emptyList<Pair<String, String>>()
    private var guestPath: String? = null

    private val warning = OpenCodeForeignSessionWarning(
        enabled = { enabled },
        workspaceDirectory = { workspace },
        loadSession = { sessionID ->
            loaded.add(sessionID)
            sessions[sessionID]
        },
        guestToHostPrefixes = { prefixes },
        sandboxGuestPath = { guestPath },
        executeAsync = { it.run() },
        notify = { _, content -> warnings.add(content) },
        clearWarning = { clears += 1 },
    )

    @Test
    fun sameFolderIsNotForeign() {
        assertFalse(OpenCodeForeignSessionPolicy.isForeign(workspace, workspace))
        assertFalse(OpenCodeForeignSessionPolicy.isForeign("$workspace/", workspace))
        assertFalse(OpenCodeForeignSessionPolicy.isForeign("C:/proj", "C:\\proj"))
    }

    @Test
    fun differentFolderAndSubdirectoryAreForeign() {
        assertTrue(OpenCodeForeignSessionPolicy.isForeign("/Users/me/other", workspace))
        assertTrue(OpenCodeForeignSessionPolicy.isForeign("$workspace/packages/app", workspace))
    }

    @Test
    fun unknownDirectoryIsNotAMismatch() {
        assertFalse(OpenCodeForeignSessionPolicy.isForeign(null, workspace))
        assertFalse(OpenCodeForeignSessionPolicy.isForeign("", workspace))
        assertFalse(OpenCodeForeignSessionPolicy.isForeign("/Users/me/other", null))
        assertFalse(OpenCodeForeignSessionPolicy.isForeign("   ", workspace))
    }

    @Test
    fun sandboxGuestPathCountsAsTheWorkspace() {
        assertFalse(
            OpenCodeForeignSessionPolicy.isForeign(
                "/c/Users/me/proj",
                "C:/Users/me/proj",
                sandboxGuestPath = "/c/Users/me/proj",
            ),
        )
        assertTrue(
            OpenCodeForeignSessionPolicy.isForeign(
                "/c/Users/me/other",
                "C:/Users/me/proj",
                sandboxGuestPath = "/c/Users/me/proj",
            ),
        )
    }

    @Test
    fun guestPrefixTranslationCountsAsTheWorkspace() {
        val prefixes = listOf("/c/Users/me/proj" to "C:/Users/me/proj")
        assertFalse(
            OpenCodeForeignSessionPolicy.isForeign(
                "/C/Users/me/proj",
                "C:/Users/me/proj",
                guestToHostPrefixes = prefixes,
            ),
        )
        assertEquals(
            "C:/Users/me/proj/pkg",
            OpenCodeForeignSessionPolicy.translateGuestPath("/c/Users/me/proj/pkg", prefixes),
        )
        assertTrue(
            OpenCodeForeignSessionPolicy.isForeign(
                "/c/Users/me/proj/pkg",
                "C:/Users/me/proj",
                guestToHostPrefixes = prefixes,
            ),
        )
    }

    @Test
    fun messageNamesBothDirectories() {
        val message = OpenCodeForeignSessionPolicy.message("Fix the build", "/Users/me/other", workspace)
        assertTrue(message.contains("\"Fix the build\""))
        assertTrue(message.contains("/Users/me/other"))
        assertTrue(message.contains(workspace))
        assertTrue(message.contains("other"))
        assertTrue(message.contains("web-ui"))
    }

    @Test
    fun untitledConversationStillNamesTheDirectories() {
        val message = OpenCodeForeignSessionPolicy.message("  ", "/tmp/other", "/tmp/web-ui")
        assertTrue(message.startsWith("This conversation belongs to"))
    }

    @Test
    fun foreignSelectionWarnsOnceUntilLeftAndReselected() {
        sessions["ses_other"] = info("ses_other", "/Users/me/other", "Other chat")

        warning.onDisplayedSessionChanged("ses_other")
        warning.onDisplayedSessionChanged("ses_other")

        assertEquals(listOf("ses_other"), loaded)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Other chat"))

        warning.onDisplayedSessionChanged(null)
        warning.onDisplayedSessionChanged("ses_other")

        assertEquals(2, loaded.size)
        assertEquals(2, warnings.size)
    }

    @Test
    fun workspaceSessionClearsWithoutWarning() {
        sessions["ses_here"] = info("ses_here", workspace, "Here")

        warning.onDisplayedSessionChanged("ses_here")

        assertTrue(warnings.isEmpty())
        assertEquals(1, clears)
    }

    @Test
    fun disabledSettingDoesNotLoad() {
        enabled = false
        sessions["ses_other"] = info("ses_other", "/Users/me/other", "Other")

        warning.onDisplayedSessionChanged("ses_other")

        assertTrue(loaded.isEmpty())
        assertTrue(warnings.isEmpty())
        assertEquals(1, clears)
    }

    @Test
    fun missingDirectoryDoesNotWarn() {
        sessions["ses_unknown"] = OpenCodeServerProtocol.SessionInfo("Untitled", parentID = null, id = "ses_unknown")

        warning.onDisplayedSessionChanged("ses_unknown")

        assertTrue(warnings.isEmpty())
        assertEquals(1, clears)
    }

    @Test
    fun staleLookupDoesNotWarnAfterTheUserLeaves() {
        sessions["ses_other"] = info("ses_other", "/Users/me/other", "Other")
        val pending = mutableListOf<Runnable>()
        val deferred = OpenCodeForeignSessionWarning(
            enabled = { true },
            workspaceDirectory = { workspace },
            loadSession = { sessions[it] },
            executeAsync = { pending.add(it) },
            notify = { _, content -> warnings.add(content) },
            clearWarning = { clears += 1 },
        )

        deferred.onDisplayedSessionChanged("ses_other")
        deferred.onDisplayedSessionChanged("ses_here")
        pending.forEach { it.run() }

        assertTrue(warnings.isEmpty())
    }

    @Test
    fun optInRechecksTheSessionAlreadyOnScreen() {
        enabled = false
        sessions["ses_other"] = info("ses_other", "/Users/me/other", "Other")
        warning.onDisplayedSessionChanged("ses_other")
        assertTrue(loaded.isEmpty())

        enabled = true
        warning.recheck()

        assertEquals(listOf("ses_other"), loaded)
        assertEquals(1, warnings.size)
    }

    @Test
    fun suppressDropsAnInFlightWarning() {
        sessions["ses_other"] = info("ses_other", "/Users/me/other", "Other")
        val pending = mutableListOf<Runnable>()
        val deferred = OpenCodeForeignSessionWarning(
            enabled = { enabled },
            workspaceDirectory = { workspace },
            loadSession = { sessions[it] },
            executeAsync = { pending.add(it) },
            notify = { _, content -> warnings.add(content) },
            clearWarning = { clears += 1 },
        )

        deferred.onDisplayedSessionChanged("ses_other")
        deferred.suppress()
        pending.forEach { it.run() }

        assertTrue(warnings.isEmpty())
    }

    private fun info(id: String, directory: String, title: String): OpenCodeServerProtocol.SessionInfo {
        return OpenCodeServerProtocol.SessionInfo(title, parentID = null, id = id, directory = directory)
    }
}
