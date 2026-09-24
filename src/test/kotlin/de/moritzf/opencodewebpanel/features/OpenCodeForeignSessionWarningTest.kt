package de.moritzf.opencodewebpanel.features

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SbxCli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class OpenCodeForeignSessionWarningTest {
    @get:Rule val temp = TemporaryFolder()
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
    fun windowsGuestPathMappingWithoutGitKeepsTheExactCwd() {
        val root = temp.newFolder("outside-git").toPath().toRealPath()
        val workdir = Files.createDirectory(root.resolve("app"))
        Files.createDirectory(root.resolve("Other"))
        val guestRoot = SbxCli.guestBindPath(root.toString())
        val prefixes = listOf(guestRoot to root.toString())
        assertFalse(OpenCodeForeignSessionPolicy.isForeign("$guestRoot/app", workdir.toString(), prefixes))
        assertTrue(OpenCodeForeignSessionPolicy.isForeign(guestRoot, workdir.toString(), prefixes))
        assertTrue(OpenCodeForeignSessionPolicy.isForeign("$guestRoot/Other", workdir.toString(), prefixes))
    }

    @Test
    fun sandboxWorkspaceSessionDoesNotPaintARedBorderOnWindows() {
        val root = temp.newFolder("repo").toPath().toRealPath()
        Files.createDirectory(root.resolve(".git"))
        val workdir = Files.createDirectory(root.resolve("app"))
        val linked = Files.createDirectory(root.resolve("linked-worktree"))
        Files.writeString(linked.resolve(".git"), "gitdir: ../.git/worktrees/linked-worktree\n")
        val guestRoot = SbxCli.guestBindPath(root.toString())
        val outlines = mutableListOf<String?>()
        val local = OpenCodeForeignSessionWarning(
            enabled = { true },
            workspaceDirectory = { workdir.toString() },
            loadSession = { id ->
                val directory = when (id) {
                    "ses_here" -> "$guestRoot/app"
                    "ses_root" -> guestRoot
                    else -> "$guestRoot/linked-worktree"
                }
                info(id, directory, id)
            },
            guestToHostPrefixes = { listOf(guestRoot to root.toString()) },
            sandboxGuestPath = { "$guestRoot/app" },
            executeAsync = { it.run() },
            notify = { _, _ -> },
            clearWarning = {},
            onOutline = { outlines.add(it) },
        )

        local.onDisplayedSessionChanged("ses_here")
        local.onDisplayedSessionChanged("ses_root")
        local.onDisplayedSessionChanged("ses_linked")

        assertEquals(listOf(null, null, null, "ses_linked"), outlines)
        assertFalse(OpenCodeForeignSessionPolicy.isForeign("$guestRoot/app", root.toString(), listOf(guestRoot to root.toString())))
        assertTrue(OpenCodeForeignSessionPolicy.isForeign("$guestRoot/linked-worktree", root.toString(), listOf(guestRoot to root.toString())))
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
    fun foreignSelectionOutlinesThatSessionAndClearsWhenLeft() {
        val outlines = mutableListOf<String?>()
        val outlined = OpenCodeForeignSessionWarning(
            enabled = { true },
            workspaceDirectory = { workspace },
            loadSession = { sessions[it] },
            executeAsync = { it.run() },
            notify = { _, _ -> },
            clearWarning = {},
            onOutline = { outlines.add(it) },
        )
        sessions["ses_other"] = info("ses_other", "/Users/me/other", "Other")
        sessions["ses_here"] = info("ses_here", workspace, "Here")

        outlined.onDisplayedSessionChanged("ses_other")
        outlined.onDisplayedSessionChanged("ses_other")
        outlined.onDisplayedSessionChanged("ses_here")

        assertEquals(listOf(null, "ses_other", "ses_other", null), outlines)
    }

    @Test
    fun suppressClearsTheOutline() {
        val outlines = mutableListOf<String?>()
        sessions["ses_other"] = info("ses_other", "/Users/me/other", "Other")
        val pending = mutableListOf<Runnable>()
        val deferred = OpenCodeForeignSessionWarning(
            enabled = { true },
            workspaceDirectory = { workspace },
            loadSession = { sessions[it] },
            executeAsync = { pending.add(it) },
            notify = { _, _ -> },
            clearWarning = {},
            onOutline = { outlines.add(it) },
        )

        deferred.onDisplayedSessionChanged("ses_other")
        deferred.suppress()
        pending.forEach { it.run() }

        assertEquals(listOf(null, null), outlines)
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
