package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.objectMember
import de.moritzf.opencodewebpanel.server.stringMember

/**
 * Keeps the IDE's view of the working tree in sync with what the OpenCode agent does, independent
 * of the (cosmetic) agent-status badge. Subscribes to the JVM `/global/event` stream and refreshes
 * the VFS and VCS state of its project directory when the agent edits files, the file watcher
 * fires, the branch changes, or a session returns to idle (the end of a turn, which is the only
 * reliable signal after a plain same-branch `git commit`, since that touches `.git` but no
 * working-tree file).
 *
 * All triggers pass through a [RefreshDebouncer] so a burst of events (many `file.edited` in one
 * turn, chatty `file.watcher.updated`) collapses into a small, bounded number of refreshes.
 *
 * Stream callbacks arrive on the event-reader thread; the debounce decision is synchronized and the
 * actual refresh is handed to a pooled-thread [Alarm], whose task re-dispatches to the EDT inside
 * [OpenCodeVcsRefresh].
 */
internal class OpenCodeWorkspaceRefreshCoordinator(
    project: Project,
    private val projectDirectory: () -> String?,
    parentDisposable: Disposable,
    private val vcsRefresh: OpenCodeVcsRefresh = OpenCodeVcsRefresh(project),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS,
) : OpenCodeGlobalEventListener {

    private val lock = Any()
    private val debouncer = RefreshDebouncer(debounceMillis, maxWaitMillis)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable)

    override fun connected() {
        // Events that occurred while the stream was disconnected are lost, so a commit or edit may
        // have landed unseen; schedule one (debounced) refresh to catch up.
        requestRefresh()
    }

    override fun eventReceived(event: OpenCodeGlobalEvent) {
        if (!triggersRefresh(event)) return
        val directory = projectDirectory() ?: return
        if (!OpenCodeServerProtocol.isSameFilesystemPath(event.directory, directory)) return
        requestRefresh()
    }

    private fun requestRefresh() {
        val now = clockMillis()
        val delay = synchronized(lock) { debouncer.onRequest(now) } ?: return
        // A non-null delay always supersedes any previously scheduled fire (the debounce fire time
        // only moves later within a burst), so replace the pending Alarm request.
        alarm.cancelAllRequests()
        alarm.addRequest(::fire, delay.coerceAtLeast(0))
    }

    private fun fire() {
        synchronized(lock) { debouncer.onFire() }
        vcsRefresh.refreshProjectFiles(projectDirectory())
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 500L
        const val DEFAULT_MAX_WAIT_MILLIS = 4_000L

        private val REFRESH_TRIGGER_TYPES = setOf(
            "file.edited",
            "file.watcher.updated",
            "vcs.branch.updated",
            // Deprecated predecessor of session.status; current servers emit both.
            "session.idle",
        )

        /**
         * Whether an event should nudge an IDE refresh. Working-tree edits and branch updates have
         * dedicated events; a plain same-branch commit produces none of those, so a session
         * returning to idle (the agent's `git commit` shell call finishing) is a trigger too.
         */
        fun triggersRefresh(event: OpenCodeGlobalEvent): Boolean {
            if (event.type in REFRESH_TRIGGER_TYPES) return true
            if (event.type == "session.status") {
                return event.properties.objectMember("status")?.stringMember("type") == "idle"
            }
            return false
        }
    }
}
