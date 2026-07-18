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

        claims.complete("/project", 1L, validSessionList = false)

        assertEquals(false, claims.reserve("/project", 2L))
        claims.complete("/project", 2L, validSessionList = false)
        assertEquals(true, claims.reserve("/project", 2L))
    }

    private class Harness {
        var enabled = true
        var disposed = false
        var directory = "/project"
        var serverUrl = "http://127.0.0.1:4096"
        var password = "pw"
        var generation = 1L
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
