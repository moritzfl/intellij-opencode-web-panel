package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState

/**
 * After the OpenCode server restarts or recovers, checks recent sessions for the
 * project directory. If the last assistant message in any session shows signs of a
 * crashed turn (missing completion time or unsettled tools), automatically sends a
 * continuation prompt so the agent resumes without manual intervention.
 *
 * Only sessions updated within [OpenCodeServerProtocol.RECENT_SESSION_WINDOW_MILLIS]
 * before the check are considered, so long-idle conversations are not resumed.
 *
 * Disabled when [OpenCodeSettingsState.autoContinueInterruptedSessions] is false.
 */
internal class OpenCodeInterruptedSessionRecovery(
    private val project: Project,
    private val serverManager: SharedOpenCodeServerManager,
    private val projectDirectory: () -> String?,
) {
    /**
     * Runs the recovery check on a background thread. Safe to call after each page load;
     * returns quickly when the setting is disabled or there is no server URL.
     */
    fun checkAndContinue() {
        if (!OpenCodeSettingsState.getInstance().autoContinueInterruptedSessions) return
        val directory = projectDirectory() ?: return
        val serverUrl = serverManager.getServerUrl() ?: return
        val password = serverManager.getServerPassword() ?: return
        val authHeader = OpenCodeServerProtocol.buildBasicAuthHeader(password)

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
                val sessions = OpenCodeServerProtocol.fetchRecentSessions(serverUrl, authHeader, directory)
                if (sessions.isEmpty()) return@executeOnPooledThread
                for (session in sessions) {
                    val lastMessage = OpenCodeServerProtocol.fetchLastMessageJson(serverUrl, authHeader, session.id)
                        ?: continue
                    if (!OpenCodeServerProtocol.isInterruptedAssistantMessage(lastMessage)) continue
                    thisLogger().info("OpenCode session ${session.id} was interrupted; sending continuation prompt")
                    OpenCodeServerProtocol.sendContinuePrompt(serverUrl, authHeader, session.id)
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to check for interrupted OpenCode sessions", e)
            }
        }
    }
}