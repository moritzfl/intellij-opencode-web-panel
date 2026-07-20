package de.moritzf.opencodewebpanel.features

import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeInterruptedSessionRecoveryTest {
    @Test
    fun staleCompletionCannotReleaseNewerClaim() {
        val claims = OpenCodeRecoveryClaimRegistry()
        assertEquals(true, claims.reserve("/project", 1L))
        assertEquals(true, claims.reserve("/project", 2L))

        claims.complete("/project", 1L, completed = false)

        assertEquals(false, claims.reserve("/project", 2L))
        claims.complete("/project", 2L, completed = false)
        assertEquals(true, claims.reserve("/project", 2L))
    }

    private class Harness {
        var enabled = true
        var disposed = false
        var directory = "/project"
        var serverUrl = "http://127.0.0.1:4096"
        var password = "pw"
        var generation = 1L
        var serverStartedAtMillis = Long.MAX_VALUE
        var listCalls = 0
        var messageCalls = 0
        val sent = mutableListOf<String>()
        val sessionResults = ArrayDeque<OpenCodeProtocolResult<List<OpenCodeServerProtocol.SessionSummary>>>()
        var fetchMessage: (String) -> OpenCodeProtocolResult<String?> = {
            OpenCodeProtocolResult.Success("""{"type":"user"}""")
        }
        var sendResult: OpenCodeProtocolResult<Unit> = OpenCodeProtocolResult.Success(Unit)
        var onSleep: () -> Unit = {}

        fun recovery(): OpenCodeInterruptedSessionRecovery {
            return OpenCodeInterruptedSessionRecovery(
                projectDirectory = { directory },
                enabled = { enabled },
                isDisposed = { disposed },
                serverUrl = { serverUrl },
                serverPassword = { password },
                serverGeneration = { generation },
                serverGenerationStartedAtMillis = { serverStartedAtMillis },
                fetchRecentSessions = { _, _, _ ->
                    listCalls++
                    sessionResults.removeFirst()
                },
                fetchLastMessage = { _, sessionID ->
                    messageCalls++
                    fetchMessage(sessionID)
                },
                sendContinuePrompt = { _, sessionID ->
                    sent.add(sessionID)
                    sendResult
                },
                executeAsync = { task -> task() },
                sleep = { onSleep() },
                generationClaims = OpenCodeRecoveryClaimRegistry(),
                suspendClaims = OpenCodeRecoveryClaimRegistry(),
            )
        }

        fun sessions(vararg ids: String): OpenCodeProtocolResult<List<OpenCodeServerProtocol.SessionSummary>> {
            return OpenCodeProtocolResult.Success(ids.map { OpenCodeServerProtocol.SessionSummary(it, 1L) })
        }
    }

    @Test
    fun failedSessionListLeavesGenerationRetryableThenValidRunIsDone() {
        val harness = Harness()
        harness.sessionResults.add(OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 503))
        harness.sessionResults.add(harness.sessions("ses_1"))
        val recovery = harness.recovery()

        recovery.checkAndContinue()
        recovery.checkAndContinue()
        recovery.checkAndContinue()

        assertEquals(2, harness.listCalls)
        assertEquals(listOf("ses_1"), harness.sent)
    }

    @Test
    fun exhaustedListRetriesReleaseClaimForLaterCheck() {
        val harness = Harness()
        repeat(3) {
            harness.sessionResults.add(OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 503))
        }
        harness.sessionResults.add(harness.sessions("ses_1"))
        val recovery = harness.recovery()

        recovery.checkAndContinue()
        assertEquals(3, harness.listCalls)
        assertEquals(emptyList<String>(), harness.sent)

        recovery.checkAndContinue()
        assertEquals(4, harness.listCalls)
        assertEquals(listOf("ses_1"), harness.sent)
    }

    @Test
    fun restartRecoveryRetainsCandidateAfterTransientMessageFailure() {
        val harness = Harness()
        harness.sessionResults.add(harness.sessions("ses_1"))
        harness.fetchMessage = {
            if (harness.messageCalls == 1) {
                OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 503)
            } else {
                OpenCodeProtocolResult.Success("""{"type":"user"}""")
            }
        }
        val recovery = harness.recovery()

        recovery.checkAndContinue()

        assertEquals(2, harness.messageCalls)
        assertEquals(listOf("ses_1"), harness.sent)
    }

    @Test
    fun turnStartedOnLiveServerIsNeverContinued() {
        // Fresh-start race: the user's first prompt lands while the recovery pass is still
        // running; its unanswered user message must not be mistaken for a restart casualty.
        val harness = Harness()
        harness.serverStartedAtMillis = 1_000L
        harness.sessionResults.add(harness.sessions("ses_live", "ses_dead"))
        harness.fetchMessage = { sessionID ->
            if (sessionID == "ses_live") {
                OpenCodeProtocolResult.Success("""{"type":"user","time":{"created":2000}}""")
            } else {
                OpenCodeProtocolResult.Success("""{"type":"user","time":{"created":500}}""")
            }
        }
        val recovery = harness.recovery()

        recovery.checkAndContinue()

        assertEquals(listOf("ses_dead"), harness.sent)
    }

    @Test
    fun eligibilityAbortReleasesGenerationClaim() {
        val harness = Harness()
        harness.sessionResults.add(harness.sessions("ses_1"))
        harness.sessionResults.add(harness.sessions("ses_1"))
        var firstFetch = true
        harness.fetchMessage = {
            if (firstFetch) {
                firstFetch = false
                harness.enabled = false
                OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 503)
            } else {
                OpenCodeProtocolResult.Success("""{"type":"user"}""")
            }
        }
        val recovery = harness.recovery()

        recovery.checkAndContinue()
        harness.enabled = true
        recovery.checkAndContinue()

        assertEquals(2, harness.listCalls)
        assertEquals(listOf("ses_1"), harness.sent)
    }

    @Test
    fun incompleteMixedRunNeverReplaysAnAttemptedContinuation() {
        val harness = Harness()
        harness.sessionResults.add(harness.sessions("ses_attempted", "ses_retry"))
        harness.sessionResults.add(harness.sessions("ses_attempted", "ses_retry"))
        val fetches = mutableMapOf<String, Int>()
        harness.fetchMessage = { sessionID ->
            val count = (fetches[sessionID] ?: 0) + 1
            fetches[sessionID] = count
            if (sessionID == "ses_retry" && count <= 3) {
                OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 503)
            } else {
                OpenCodeProtocolResult.Success("""{"type":"user"}""")
            }
        }
        // A 500 is ambiguous: the server may have accepted the write before failing the response.
        harness.sendResult = OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 500)
        val recovery = harness.recovery()

        recovery.checkAndContinue()
        recovery.checkAndContinue()

        assertEquals(2, harness.listCalls)
        assertEquals(listOf("ses_attempted", "ses_retry"), harness.sent)
        assertEquals(1, fetches["ses_attempted"])
    }

    @Test
    fun failedContinuationPostIsNotRepeatedForSameGeneration() {
        for (status in listOf(409, 500)) {
            val harness = Harness()
            harness.sessionResults.add(harness.sessions("ses_$status"))
            harness.sendResult = OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, status)
            val recovery = harness.recovery()

            recovery.checkAndContinue()
            recovery.checkAndContinue()

            assertEquals("HTTP $status", listOf("ses_$status"), harness.sent)
            assertEquals("HTTP $status", 1, harness.listCalls)
        }
    }

    @Test
    fun suspendPollingRetainsCandidateAfterTransientMessageFailure() {
        val harness = Harness()
        harness.sessionResults.add(harness.sessions("ses_1"))
        harness.fetchMessage = {
            if (harness.messageCalls == 1) {
                OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 503)
            } else {
                OpenCodeProtocolResult.Success(
                    """{"type":"assistant","time":{"created":900000,"completed":2000000},"error":{"name":"FetchError"}}""",
                )
            }
        }
        val recovery = harness.recovery()

        recovery.onResumedFromSuspend(lastAliveMillis = 1_000_000L, resumedAtMillis = 2_000_000L)

        assertEquals(2, harness.messageCalls)
        assertEquals(listOf("ses_1"), harness.sent)
    }

    @Test
    fun suspendRecoveryRetriesTransientSessionListFailure() {
        val harness = Harness()
        harness.sessionResults.add(OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 503))
        harness.sessionResults.add(harness.sessions("ses_1"))
        harness.fetchMessage = {
            OpenCodeProtocolResult.Success(
                """{"type":"assistant","time":{"created":900000,"completed":2000000},"error":{"name":"FetchError"}}""",
            )
        }
        val recovery = harness.recovery()

        recovery.onResumedFromSuspend(lastAliveMillis = 1_000_000L, resumedAtMillis = 2_000_003L)

        assertEquals(2, harness.listCalls)
        assertEquals(listOf("ses_1"), harness.sent)
    }

    @Test
    fun disablingDuringSuspendPollStopsFurtherFetchesAndWrites() {
        val harness = Harness()
        harness.sessionResults.add(harness.sessions("ses_1"))
        harness.fetchMessage = {
            OpenCodeProtocolResult.Success("""{"type":"assistant","time":{"created":900000}}""")
        }
        harness.onSleep = { harness.enabled = false }
        val recovery = harness.recovery()

        recovery.onResumedFromSuspend(lastAliveMillis = 1_000_000L, resumedAtMillis = 2_000_001L)

        assertEquals(1, harness.messageCalls)
        assertEquals(emptyList<String>(), harness.sent)
    }

    @Test
    fun serverRestartBeforeSuspendWriteCancelsContinuation() {
        val harness = Harness()
        harness.sessionResults.add(harness.sessions("ses_1"))
        harness.fetchMessage = {
            harness.generation++
            OpenCodeProtocolResult.Success(
                """{"type":"assistant","time":{"created":900000,"completed":2000000},"error":{"name":"FetchError"}}""",
            )
        }
        val recovery = harness.recovery()

        recovery.onResumedFromSuspend(lastAliveMillis = 1_000_000L, resumedAtMillis = 2_000_002L)

        assertEquals(1, harness.messageCalls)
        assertEquals(emptyList<String>(), harness.sent)
    }
}
