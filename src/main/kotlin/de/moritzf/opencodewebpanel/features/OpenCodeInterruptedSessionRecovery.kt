package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.util.concurrent.ConcurrentHashMap

/**
 * After the OpenCode server restarts or recovers, checks recent sessions for the
 * project directory. If the last message in any session shows signs of a crashed turn
 * (an unanswered user prompt, a missing completion time, or unsettled tools),
 * automatically sends a continuation prompt so the agent resumes without manual
 * intervention.
 *
 * Only sessions updated within [OpenCodeServerProtocol.RECENT_SESSION_WINDOW_MILLIS]
 * before the check are considered, so long-idle conversations are not resumed. When the
 * machine recently resumed from a system suspend, the window is widened by the suspend
 * gap: the sessions' `time.updated` stamps predate the sleep, and comparing them against
 * the post-wake clock would otherwise exclude exactly the sessions the sleep interrupted.
 *
 * The check runs at most once per server process and project directory: an assistant
 * turn that is still in progress is indistinguishable from a crashed one by its message
 * JSON, so re-checking on every page load (panel reopen, renderer crash, route reload)
 * would send spurious continuation prompts to a healthy running session. Right after a
 * server process starts, no turn can be running on it, so that is the one safe moment.
 *
 * [onResumedFromSuspend] covers the complementary case where the server *survived* a
 * system suspend (no restart, so no new generation): a turn that was streaming when the
 * machine fell asleep loses its provider connection and settles with an error on wake.
 * Such turns are recognized purely by their timestamps — started before the sleep,
 * error-settled after the wake — which cannot match a running turn or a user stop, so
 * this pass is safe on a live server.
 *
 * Disabled when [OpenCodeSettingsState.autoContinueInterruptedSessions] is false.
 */
internal class OpenCodeInterruptedSessionRecovery(
    private val project: Project,
    private val serverManager: SharedOpenCodeServerManager,
    private val projectDirectory: () -> String?,
) {
    companion object {
        // Keyed by project directory, valued with the server generation already checked.
        // Static so reopening the tool window does not reset the once-per-server-process
        // guarantee; the map stays tiny (one entry per project directory).
        private val checkedGenerationsByDirectory = ConcurrentHashMap<String, Long>()

        // Keyed by project directory, valued with the resume timestamp already handled, so
        // multiple panels of the same project run the suspend pass only once per resume.
        private val handledSuspendResumesByDirectory = ConcurrentHashMap<String, Long>()

        // Latest suspend/resume the periodic checker reported; generation-based checks
        // shortly after a resume widen their session window across the suspend gap.
        @Volatile
        private var lastSuspendResume: SuspendResume? = null

        private const val SUSPEND_RECOVERY_FRESHNESS_MILLIS = 10 * 60 * 1000L
        private const val COMPLETED_AFTER_SLACK_MILLIS = 5_000L
        private const val CHECK_INTERVAL_MILLIS = OpenCodeServerProtocol.CHECK_INTERVAL_SECONDS * 1000L

        // A severed turn settles only once the server notices the dead provider connection,
        // which can lag the wake by minutes when the socket dies without an RST. Poll such
        // still-unsettled turns for a bounded time instead of deciding at wake instant.
        private const val SEVERED_SETTLE_POLL_INTERVAL_MILLIS = 20_000L
        private const val SEVERED_SETTLE_POLL_ATTEMPTS = 6

        // The session listing is creation-ordered and includes subagent child sessions (see
        // OpenCodeServerProtocol.fetchRecentSessions), so a burst of later-created children
        // could push a still-active parent out of a small page.
        private const val SESSION_FETCH_LIMIT = 100
    }

    private data class SuspendResume(val lastAliveMillis: Long, val resumedAtMillis: Long)

    /**
     * Runs the recovery check on a background thread. Safe to call after each page load;
     * only the first call after a new server process starts actually checks the sessions.
     */
    fun checkAndContinue() {
        if (!OpenCodeSettingsState.getInstance().autoContinueInterruptedSessions) return
        val directory = projectDirectory() ?: return
        val serverUrl = serverManager.getServerUrl() ?: return
        val password = serverManager.getServerPassword() ?: return
        val generation = serverManager.getServerGeneration()
        if (generation == 0L) return
        if (checkedGenerationsByDirectory.put(directory, generation) == generation) return
        val authHeader = OpenCodeServerProtocol.buildBasicAuthHeader(password)

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
                val sessions = OpenCodeServerProtocol.fetchRecentSessions(
                    serverUrl,
                    authHeader,
                    directory,
                    maxAgeMillis = OpenCodeServerProtocol.RECENT_SESSION_WINDOW_MILLIS + recentSuspendGapMillis(),
                    limit = SESSION_FETCH_LIMIT,
                )
                if (sessions.isEmpty()) return@executeOnPooledThread
                for (session in sessions) {
                    // Subagent child sessions are driven by their parent turn; prompting them
                    // directly would start work no parent is waiting for.
                    if (session.parentID != null) continue
                    val lastMessage = OpenCodeServerProtocol.fetchLastMessageJson(serverUrl, authHeader, session.id)
                        ?: continue
                    if (!OpenCodeServerProtocol.isInterruptedLastMessage(lastMessage)) continue
                    thisLogger().info("OpenCode session ${session.id} was interrupted; sending continuation prompt")
                    OpenCodeServerProtocol.sendContinuePrompt(serverUrl, authHeader, session.id)
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to check for interrupted OpenCode sessions", e)
            }
        }
    }

    /**
     * Runs the suspend-severed recovery pass after the machine resumed from sleep while the
     * server kept running. Sends a continuation prompt to sessions whose last turn started
     * before the sleep and settled with an error after the wake. Runs once per resume event
     * and project directory.
     */
    fun onResumedFromSuspend(lastAliveMillis: Long, resumedAtMillis: Long) {
        lastSuspendResume = SuspendResume(lastAliveMillis, resumedAtMillis)
        if (!OpenCodeSettingsState.getInstance().autoContinueInterruptedSessions) return
        val directory = projectDirectory() ?: return
        val serverUrl = serverManager.getServerUrl() ?: return
        val password = serverManager.getServerPassword() ?: return
        if (handledSuspendResumesByDirectory.put(directory, resumedAtMillis) == resumedAtMillis) return
        val authHeader = OpenCodeServerProtocol.buildBasicAuthHeader(password)
        // The sleep began at most one check interval after the last periodic run, and — on
        // platforms where the monotonic clock pauses during sleep — the wake happened at most
        // one interval before the detection. Bound the turn's timestamps accordingly.
        val createdBeforeMillis = lastAliveMillis + CHECK_INTERVAL_MILLIS
        val completedAfterMillis = resumedAtMillis - CHECK_INTERVAL_MILLIS - COMPLETED_AFTER_SLACK_MILLIS

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
                val sessions = OpenCodeServerProtocol.fetchRecentSessions(
                    serverUrl,
                    authHeader,
                    directory,
                    maxAgeMillis = OpenCodeServerProtocol.RECENT_SESSION_WINDOW_MILLIS + (resumedAtMillis - lastAliveMillis),
                    limit = SESSION_FETCH_LIMIT,
                )
                var candidates = sessions.filter { it.parentID == null }.map { it.id }
                var attempt = 0
                while (candidates.isNotEmpty() && attempt <= SEVERED_SETTLE_POLL_ATTEMPTS && !project.isDisposed) {
                    if (attempt > 0) Thread.sleep(SEVERED_SETTLE_POLL_INTERVAL_MILLIS)
                    attempt++
                    candidates = candidates.filter { sessionID ->
                        // A fetch failure means the server went away; the generation-based
                        // recovery owns the restart case, so drop the candidate here.
                        val lastMessage = OpenCodeServerProtocol.fetchLastMessageJson(serverUrl, authHeader, sessionID)
                            ?: return@filter false
                        if (OpenCodeServerProtocol.isSuspendSeveredLastMessage(lastMessage, createdBeforeMillis, completedAfterMillis)) {
                            thisLogger().info("OpenCode session $sessionID was severed by a system suspend; sending continuation prompt")
                            OpenCodeServerProtocol.sendContinuePrompt(serverUrl, authHeader, sessionID)
                            return@filter false
                        }
                        // Keep polling turns from before the sleep that have not settled yet:
                        // the server may not have noticed the dead provider connection.
                        OpenCodeServerProtocol.isUnsettledTurnFromBefore(lastMessage, createdBeforeMillis)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                thisLogger().warn("Failed to check for suspend-severed OpenCode sessions", e)
            }
        }
    }

    private fun recentSuspendGapMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val suspend = lastSuspendResume ?: return 0L
        if (nowMillis - suspend.resumedAtMillis > SUSPEND_RECOVERY_FRESHNESS_MILLIS) return 0L
        return (suspend.resumedAtMillis - suspend.lastAliveMillis).coerceAtLeast(0L)
    }
}
