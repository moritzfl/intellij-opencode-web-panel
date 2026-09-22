package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntellijMcpServerStartupTest {

    class FakeMcpService(
        private val running: Boolean,
        private val url: String? = null,
    ) {
        fun isRunning(): Boolean = running

        fun getServerSseUrl(): String? = url
    }

    @Test
    fun intellijMcpServerStatusUsesRuntimeState() {
        val runningStatus = IntellijMcpServerStartup.statusForRuntimeState(
            enabled = true,
            service = FakeMcpService(true, "http://127.0.0.1:64342/sse"),
        )
        val stoppedStatus = IntellijMcpServerStartup.statusForRuntimeState(
            enabled = true,
            service = FakeMcpService(false),
        )
        val disabledStatus = IntellijMcpServerStartup.statusForRuntimeState(enabled = false, service = null)
        val unavailableStatus = IntellijMcpServerStartup.statusForRuntimeState(enabled = null, service = null)

        assertEquals(IntellijMcpServerStartupState.ENABLED, runningStatus.state)
        assertEquals("IntelliJ MCP server is running at http://127.0.0.1:64342/sse", runningStatus.message)
        assertEquals(IntellijMcpServerStartupState.ENABLED_NOT_RUNNING, stoppedStatus.state)
        assertEquals(IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED, disabledStatus.state)
        assertEquals(IntellijMcpServerStartupState.UNAVAILABLE, unavailableStatus.state)
    }

    @Test
    fun intellijMcpServerWaitsOnlyWhenEnabledButNotRunning() {
        assertTrue(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                    "not running",
                ),
            ),
        )
        assertFalse(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED,
                    "running",
                ),
            ),
        )
        assertFalse(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED,
                    "disabled",
                ),
            ),
        )
        assertFalse(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.UNAVAILABLE,
                    "unavailable",
                ),
            ),
        )
        assertFalse(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                    "not running",
                ),
                enabled = false,
            ),
        )
    }

    @Test
    fun intellijMcpServerWaitStopsWhenServerStarts() {
        var now = 0L
        var checks = 0
        val sleeps = mutableListOf<Long>()

        val result = IntellijMcpServerStartup.waitUntilReady(
            stillWaiting = {
                checks += 1
                // Initial check plus one polled check still waiting; the server is up on the third.
                checks <= 2
            },
            nowMillis = { now },
            sleepMillis = { millis ->
                sleeps += millis
                now += millis
            },
            timeoutMillis = 2_000L,
            pollIntervalMillis = 500L,
        )

        assertEquals(IntellijMcpServerWaitResult.READY, result)
        assertEquals(listOf(500L, 500L), sleeps)
    }

    @Test
    fun intellijMcpServerWaitTimesOut() {
        var now = 0L
        val sleeps = mutableListOf<Long>()

        val result = IntellijMcpServerStartup.waitUntilReady(
            stillWaiting = { true },
            nowMillis = { now },
            sleepMillis = { millis ->
                sleeps += millis
                now += millis
            },
            timeoutMillis = 1_000L,
            pollIntervalMillis = 400L,
        )

        assertEquals(IntellijMcpServerWaitResult.TIMED_OUT, result)
        assertEquals(listOf(400L, 400L, 200L), sleeps)
    }

    @Test
    fun intellijMcpServerWaitStopsWhenSettingIsDisabled() {
        var now = 0L
        var enabled = true
        val sleeps = mutableListOf<Long>()

        val result = IntellijMcpServerStartup.waitUntilReady(
            stillWaiting = {
                IntellijMcpServerStartup.shouldWaitFor(
                    IntellijMcpServerStartupStatus(IntellijMcpServerStartupState.ENABLED_NOT_RUNNING, "not running"),
                    enabled,
                )
            },
            nowMillis = { now },
            sleepMillis = { millis ->
                sleeps += millis
                now += millis
                enabled = false
            },
            timeoutMillis = 2_000L,
            pollIntervalMillis = 500L,
        )

        assertEquals(IntellijMcpServerWaitResult.READY, result)
        assertEquals(listOf(500L), sleeps)
    }

}
