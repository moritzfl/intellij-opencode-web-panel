package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.util.concurrent.ConcurrentHashMap

internal data class OpenCodeRecoveryContext(
    val serverUrl: String,
    val password: String,
    val directory: String,
    val generation: Long,
) {
    val authHeader: String = OpenCodeServerProtocol.buildBasicAuthHeader(password)
}

/** Latest-token claim used to keep one recovery pass active per project and trigger. */
internal class OpenCodeRecoveryClaimRegistry {
    private enum class State { READY, IN_PROGRESS, DONE }
    private data class Claim(
        val token: Long,
        val state: State,
        val attemptedSessionIDs: Set<String> = emptySet(),
    )

    private val claims = ConcurrentHashMap<String, Claim>()

    fun reserve(key: String, token: Long): Boolean {
        var reserved = false
        claims.compute(key) { _, current ->
            if (current?.token == token && current.state == State.READY) {
                reserved = true
                current.copy(state = State.IN_PROGRESS)
            } else if (current?.token == token) {
                current
            } else {
                reserved = true
                Claim(token, State.IN_PROGRESS)
            }
        }
        return reserved
    }

    fun complete(key: String, token: Long, completed: Boolean) {
        claims.computeIfPresent(key) { _, current ->
            if (current.token != token || current.state != State.IN_PROGRESS) {
                current
            } else if (completed) {
                current.copy(state = State.DONE)
            } else {
                current.copy(state = State.READY)
            }
        }
    }

    fun markSessionAttempted(key: String, token: Long, sessionID: String): Boolean {
        var marked = false
        claims.computeIfPresent(key) { _, current ->
            if (current.token != token || current.state != State.IN_PROGRESS || sessionID in current.attemptedSessionIDs) {
                current
            } else {
                marked = true
                current.copy(attemptedSessionIDs = current.attemptedSessionIDs + sessionID)
            }
        }
        return marked
    }

    fun attemptedSessionIDs(key: String, token: Long): Set<String> {
        return claims[key]?.takeIf { it.token == token }?.attemptedSessionIDs.orEmpty()
    }
}

/**
 * Resumes recent turns that were interrupted by a server restart or system suspend.
 * Recovery is claimed once per server generation/resume only after a valid session listing;
 * transient list failures therefore remain retryable without overlapping duplicate passes.
 */
internal class OpenCodeInterruptedSessionRecovery internal constructor(
    private val projectDirectory: () -> String?,
    private val enabled: () -> Boolean,
    private val isDisposed: () -> Boolean,
    private val serverUrl: () -> String?,
    private val serverPassword: () -> String?,
    private val serverGeneration: () -> Long,
    private val fetchRecentSessions: (
        context: OpenCodeRecoveryContext,
        maxAgeMillis: Long,
        limit: Int,
    ) -> OpenCodeProtocolResult<List<OpenCodeServerProtocol.SessionSummary>>,
    private val fetchLastMessage: (
        context: OpenCodeRecoveryContext,
        sessionID: String,
    ) -> OpenCodeProtocolResult<String?>,
    private val sendContinuePrompt: (
        context: OpenCodeRecoveryContext,
        sessionID: String,
    ) -> OpenCodeProtocolResult<Unit>,
    private val executeAsync: ((() -> Unit) -> Unit),
    private val sleep: (Long) -> Unit,
    private val generationClaims: OpenCodeRecoveryClaimRegistry,
    private val suspendClaims: OpenCodeRecoveryClaimRegistry,
) {
    constructor(
        project: Project,
        serverManager: SharedOpenCodeServerManager,
        projectDirectory: () -> String?,
    ) : this(
        projectDirectory = projectDirectory,
        enabled = { OpenCodeSettingsState.getInstance().autoContinueInterruptedSessions },
        isDisposed = { project.isDisposed },
        serverUrl = serverManager::getServerUrl,
        serverPassword = serverManager::getServerPassword,
        serverGeneration = serverManager::getServerGeneration,
        fetchRecentSessions = { context, maxAgeMillis, limit ->
            OpenCodeServerProtocol.fetchRecentSessionsResult(
                context.serverUrl,
                context.authHeader,
                context.directory,
                maxAgeMillis = maxAgeMillis,
                limit = limit,
            )
        },
        fetchLastMessage = { context, sessionID ->
            OpenCodeServerProtocol.fetchLastMessageJsonResult(
                context.serverUrl,
                context.authHeader,
                sessionID,
            )
        },
        sendContinuePrompt = { context, sessionID ->
            OpenCodeServerProtocol.sendContinuePromptResult(
                context.serverUrl,
                context.authHeader,
                sessionID,
            )
        },
        executeAsync = { task -> ApplicationManager.getApplication().executeOnPooledThread(task) },
        sleep = Thread::sleep,
        generationClaims = sharedGenerationClaims,
        suspendClaims = sharedSuspendClaims,
    )

    companion object {
        private val sharedGenerationClaims = OpenCodeRecoveryClaimRegistry()
        private val sharedSuspendClaims = OpenCodeRecoveryClaimRegistry()

        @Volatile
        private var lastSuspendResume: SuspendResume? = null

        private const val SUSPEND_RECOVERY_FRESHNESS_MILLIS = 10 * 60 * 1000L
        private const val COMPLETED_AFTER_SLACK_MILLIS = 5_000L
        private const val CHECK_INTERVAL_MILLIS = OpenCodeServerProtocol.CHECK_INTERVAL_SECONDS * 1000L
        private const val SEVERED_SETTLE_POLL_INTERVAL_MILLIS = 20_000L
        private const val SEVERED_SETTLE_POLL_ATTEMPTS = 6
        private const val RESTART_RECOVERY_RETRY_BACKOFF_MILLIS = 2_000L
        private const val RESTART_RECOVERY_ATTEMPTS = 3
        private const val SESSION_FETCH_LIMIT = 100
    }

    private data class SuspendResume(val lastAliveMillis: Long, val resumedAtMillis: Long)

    /** Safe after every page load; only one valid pass runs per server generation and project. */
    fun checkAndContinue() {
        val context = captureContext() ?: return
        val claimKey = OpenCodeServerProtocol.filesystemPathKey(context.directory) ?: return
        if (!generationClaims.reserve(claimKey, context.generation)) return

        executeAsync {
            var completed = false
            try {
                completed = recoverAfterRestart(context, claimKey)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                thisLogger().warn("Failed to check for interrupted OpenCode sessions", e)
            } finally {
                generationClaims.complete(claimKey, context.generation, completed)
            }
        }
    }

    /** Runs once per suspend/resume and polls pre-sleep unsettled turns until they settle. */
    fun onResumedFromSuspend(lastAliveMillis: Long, resumedAtMillis: Long) {
        lastSuspendResume = SuspendResume(lastAliveMillis, resumedAtMillis)
        val context = captureContext() ?: return
        val claimKey = OpenCodeServerProtocol.filesystemPathKey(context.directory) ?: return
        if (!suspendClaims.reserve(claimKey, resumedAtMillis)) return
        val createdBeforeMillis = lastAliveMillis + CHECK_INTERVAL_MILLIS
        val completedAfterMillis = resumedAtMillis - CHECK_INTERVAL_MILLIS - COMPLETED_AFTER_SLACK_MILLIS

        executeAsync {
            var completed = false
            try {
                if (!stillEligible(context)) return@executeAsync
                val sessions = fetchSessionsWithRetry(
                    context,
                    OpenCodeServerProtocol.RECENT_SESSION_WINDOW_MILLIS + (resumedAtMillis - lastAliveMillis),
                    "list suspend-recovery sessions",
                ) ?: return@executeAsync
                val attempted = suspendClaims.attemptedSessionIDs(claimKey, resumedAtMillis)
                var candidates = sessions.filter { it.parentID == null && it.id !in attempted }.map { it.id }
                var attempt = 0
                while (candidates.isNotEmpty() && attempt <= SEVERED_SETTLE_POLL_ATTEMPTS) {
                    if (!stillEligible(context)) return@executeAsync
                    if (attempt > 0) {
                        sleep(SEVERED_SETTLE_POLL_INTERVAL_MILLIS)
                        if (!stillEligible(context)) return@executeAsync
                    }
                    attempt++
                    val remaining = mutableListOf<String>()
                    for (sessionID in candidates) {
                        if (!stillEligible(context)) return@executeAsync
                        when (val result = fetchLastMessage(context, sessionID)) {
                            is OpenCodeProtocolResult.Failure -> {
                                logFailure("fetch suspend-recovery message", sessionID, result)
                                remaining.add(sessionID)
                            }
                            is OpenCodeProtocolResult.Success -> {
                                val lastMessage = result.value ?: continue
                                if (OpenCodeServerProtocol.isSuspendSeveredLastMessage(
                                        lastMessage,
                                        createdBeforeMillis,
                                        completedAfterMillis,
                                    )
                                ) {
                                    if (!stillEligible(context)) return@executeAsync
                                    if (!suspendClaims.markSessionAttempted(claimKey, resumedAtMillis, sessionID)) {
                                        continue
                                    }
                                    thisLogger().info(
                                        "OpenCode session $sessionID was severed by a system suspend; " +
                                            "sending continuation prompt",
                                    )
                                    when (val sendResult = sendContinuePrompt(context, sessionID)) {
                                        is OpenCodeProtocolResult.Success -> Unit
                                        is OpenCodeProtocolResult.Failure -> {
                                            logFailure("continue suspend-severed session", sessionID, sendResult)
                                        }
                                    }
                                } else if (OpenCodeServerProtocol.isUnsettledTurnFromBefore(
                                        lastMessage,
                                        createdBeforeMillis,
                                    )
                                ) {
                                    remaining.add(sessionID)
                                }
                            }
                        }
                    }
                    candidates = remaining
                }
                completed = candidates.isEmpty() && stillEligible(context)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                thisLogger().warn("Failed to check for suspend-severed OpenCode sessions", e)
            } finally {
                suspendClaims.complete(claimKey, resumedAtMillis, completed)
            }
        }
    }

    private fun recoverAfterRestart(context: OpenCodeRecoveryContext, claimKey: String): Boolean {
        val sessions = fetchSessionsWithRetry(
            context,
            OpenCodeServerProtocol.RECENT_SESSION_WINDOW_MILLIS + recentSuspendGapMillis(),
            "list recent sessions",
        ) ?: return false
        val attempted = generationClaims.attemptedSessionIDs(claimKey, context.generation)
        var candidates = sessions.filter { it.parentID == null && it.id !in attempted }.map { it.id }
        var attempt = 0
        while (candidates.isNotEmpty() && attempt < RESTART_RECOVERY_ATTEMPTS) {
            if (!stillEligible(context)) return false
            if (attempt > 0) {
                sleep(RESTART_RECOVERY_RETRY_BACKOFF_MILLIS * attempt)
                if (!stillEligible(context)) return false
            }
            attempt++
            val remaining = mutableListOf<String>()
            for (sessionID in candidates) {
                if (!stillEligible(context)) return false
                val lastMessage = when (val result = fetchLastMessage(context, sessionID)) {
                    is OpenCodeProtocolResult.Success -> result.value ?: continue
                    is OpenCodeProtocolResult.Failure -> {
                        logFailure("fetch last message", sessionID, result)
                        remaining.add(sessionID)
                        continue
                    }
                }
                if (!OpenCodeServerProtocol.isInterruptedLastMessage(lastMessage)) continue
                if (!stillEligible(context)) return false
                if (!generationClaims.markSessionAttempted(claimKey, context.generation, sessionID)) continue
                thisLogger().info("OpenCode session $sessionID was interrupted; sending continuation prompt")
                when (val result = sendContinuePrompt(context, sessionID)) {
                    is OpenCodeProtocolResult.Success -> Unit
                    is OpenCodeProtocolResult.Failure -> logFailure("continue session", sessionID, result)
                }
            }
            candidates = remaining
        }
        return candidates.isEmpty() && stillEligible(context)
    }

    private fun fetchSessionsWithRetry(
        context: OpenCodeRecoveryContext,
        maxAgeMillis: Long,
        action: String,
    ): List<OpenCodeServerProtocol.SessionSummary>? {
        repeat(RESTART_RECOVERY_ATTEMPTS) { attempt ->
            if (!stillEligible(context)) return null
            when (val result = fetchRecentSessions(
                context,
                maxAgeMillis,
                SESSION_FETCH_LIMIT,
            )) {
                is OpenCodeProtocolResult.Success -> return result.value
                is OpenCodeProtocolResult.Failure -> logFailure(action, null, result)
            }
            if (attempt + 1 < RESTART_RECOVERY_ATTEMPTS) {
                if (!stillEligible(context)) return null
                sleep(RESTART_RECOVERY_RETRY_BACKOFF_MILLIS * (attempt + 1))
            }
        }
        return null
    }

    private fun captureContext(): OpenCodeRecoveryContext? {
        if (!enabled() || isDisposed()) return null
        val directory = projectDirectory()?.takeIf { it.isNotBlank() } ?: return null
        val url = serverUrl() ?: return null
        val password = serverPassword() ?: return null
        val generation = serverGeneration().takeIf { it > 0L } ?: return null
        return OpenCodeRecoveryContext(url, password, directory, generation)
    }

    private fun stillEligible(context: OpenCodeRecoveryContext): Boolean {
        if (!enabled() || isDisposed()) return false
        if (serverGeneration() != context.generation || serverUrl() != context.serverUrl) return false
        if (serverPassword() != context.password) return false
        return OpenCodeServerProtocol.isSameFilesystemPath(projectDirectory(), context.directory)
    }

    private fun logFailure(
        action: String,
        sessionID: String?,
        failure: OpenCodeProtocolResult.Failure,
    ) {
        val status = failure.statusCode?.let { ", HTTP $it" }.orEmpty()
        val session = sessionID?.let { " for $it" }.orEmpty()
        thisLogger().warn("Failed to $action$session (${failure.kind}$status)")
    }

    private fun recentSuspendGapMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val suspend = lastSuspendResume ?: return 0L
        if (nowMillis - suspend.resumedAtMillis > SUSPEND_RECOVERY_FRESHNESS_MILLIS) return 0L
        return (suspend.resumedAtMillis - suspend.lastAliveMillis).coerceAtLeast(0L)
    }
}
