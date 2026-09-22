package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.diagnostic.thisLogger
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in warning when the displayed conversation's directory is not this panel's workspace.
 *
 * Fires on a session change (SPA route, including a restored tab), not on every address
 * flicker of the same `ses_`. Does not block navigation. A missing directory or a failed
 * lookup is not a mismatch. Exact folder match only — a subdirectory is a different cwd.
 * Symlink spellings and sandbox guest paths count as the same folder.
 */
internal class OpenCodeForeignSessionWarning(
    private val enabled: () -> Boolean,
    private val workspaceDirectory: () -> String?,
    private val loadSession: (sessionID: String) -> OpenCodeServerProtocol.SessionInfo?,
    private val guestToHostPrefixes: () -> List<Pair<String, String>> = { emptyList() },
    private val sandboxGuestPath: (workspace: String) -> String? = { null },
    private val executeAsync: (Runnable) -> Unit,
    private val notify: (title: String, content: String) -> Unit,
    private val clearWarning: () -> Unit,
) {
    private val generation = AtomicLong()

    @Volatile
    private var displayedSessionID: String? = null

    fun onDisplayedSessionChanged(sessionID: String?, force: Boolean = false) {
        if (!force && sessionID == displayedSessionID) return
        displayedSessionID = sessionID
        val token = generation.incrementAndGet()
        // Drop the previous warning immediately. A new one is posted only after the lookup
        // confirms this session is outside the workspace.
        clearWarning()
        if (!enabled() || sessionID == null || !OpenCodeServerProtocol.isSessionId(sessionID)) return
        val workspace = workspaceDirectory()?.takeIf { it.isNotBlank() } ?: return
        executeAsync {
            if (!stillCurrent(token)) return@executeAsync
            val info = runCatching { loadSession(sessionID) }
                .onFailure { error ->
                    thisLogger().debug("Could not load session $sessionID to check its directory", error)
                }
                .getOrNull()
            if (!stillCurrent(token)) return@executeAsync
            val prefixes = guestToHostPrefixes()
            val guestPath = sandboxGuestPath(workspace)
            if (!stillCurrent(token)) return@executeAsync
            val directory = info?.directory
            if (!OpenCodeForeignSessionPolicy.isForeign(directory, workspace, prefixes, guestPath)) return@executeAsync
            if (!stillCurrent(token)) return@executeAsync
            notify(
                OpenCodeForeignSessionPolicy.TITLE,
                OpenCodeForeignSessionPolicy.message(info?.title.orEmpty(), directory!!, workspace),
            )
        }
    }

    /** Re-evaluate the session already on screen, for example after the user opts in. */
    fun recheck() {
        onDisplayedSessionChanged(displayedSessionID, force = true)
    }

    /** Drop an in-flight check and the current warning. The displayed id is kept. */
    fun suppress() {
        generation.incrementAndGet()
        clearWarning()
    }

    private fun stillCurrent(token: Long): Boolean = token == generation.get() && enabled()
}

internal object OpenCodeForeignSessionPolicy {
    const val TITLE = "Conversation is outside this workspace"

    fun isForeign(
        sessionDirectory: String?,
        workspaceDirectory: String?,
        guestToHostPrefixes: List<Pair<String, String>> = emptyList(),
        sandboxGuestPath: String? = null,
    ): Boolean {
        val session = sessionDirectory?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val workspace = workspaceDirectory?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (sameFolder(session, workspace)) return false
        if (sandboxGuestPath != null && sameFolder(session, sandboxGuestPath)) return false
        val translated = translateGuestPath(session, guestToHostPrefixes)
        return translated == null || !sameFolder(translated, workspace)
    }

    fun message(sessionTitle: String, sessionDirectory: String, workspaceDirectory: String): String {
        val label = sessionTitle.trim().takeIf { it.isNotEmpty() }?.let { "\"$it\"" } ?: "This conversation"
        val sessionName = OpenCodeServerProtocol.projectDisplayName(sessionDirectory)
        val workspaceName = OpenCodeServerProtocol.projectDisplayName(workspaceDirectory)
        return "$label belongs to $sessionName ($sessionDirectory), not the current workspace " +
            "$workspaceName ($workspaceDirectory). The agent runs in that directory."
    }

    internal fun translateGuestPath(path: String, prefixes: List<Pair<String, String>>): String? {
        val posix = path.replace('\\', '/').trimEnd('/')
        if (posix.isEmpty()) return null
        val match = prefixes.firstOrNull { (guest, _) ->
            val prefix = guest.replace('\\', '/').trimEnd('/')
            prefix.isNotEmpty() && pathHasPrefix(posix, prefix)
        } ?: return null
        val guest = match.first.replace('\\', '/').trimEnd('/')
        val host = match.second.replace('\\', '/').trimEnd('/')
        val suffix = if (posix.length == guest.length) "" else posix.substring(guest.length)
        return host + suffix
    }

    private fun sameFolder(first: String, second: String): Boolean {
        return OpenCodeServerProtocol.isSameFilesystemPath(first, second)
    }

    private fun pathHasPrefix(path: String, prefix: String): Boolean {
        val ignoreCase = isWindowsGuestPrefix(prefix) || isWindowsHostPrefix(prefix)
        return path.equals(prefix, ignoreCase) || path.startsWith("$prefix/", ignoreCase)
    }

    private fun isWindowsGuestPrefix(prefix: String): Boolean {
        return prefix.length >= 3 && prefix[0] == '/' && prefix[1].isLetter() && prefix[2] == '/'
    }

    private fun isWindowsHostPrefix(prefix: String): Boolean {
        return prefix.length >= 3 && prefix[0].isLetter() && prefix[1] == ':' && prefix[2] == '/'
    }
}
