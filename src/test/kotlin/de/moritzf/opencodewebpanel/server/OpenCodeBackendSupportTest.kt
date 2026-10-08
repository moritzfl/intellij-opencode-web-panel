package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeBackendSupportTest {
    @Test
    fun warningHistoryIsBackendLocalAndOnlyConsumedWhenApplicable() {
        val first = OpenCodeBackendWarnings()
        val second = OpenCodeBackendWarnings()
        assertNull(first.consumeUnsupportedVersion(null))
        assertNull(first.consumeUnsupportedVersion("2.0.18"))
        assertEquals("1.17.0", first.consumeUnsupportedVersion(" 1.17.0 "))
        assertNull(first.consumeUnsupportedVersion("1.17.0"))
        assertEquals("1.17.0", second.consumeUnsupportedVersion("1.17.0"))
        assertFalse(first.consumeEmbeddedV2(OpenCodeWireProtocol.V2_CLI))
        assertTrue(first.consumeEmbeddedV2(OpenCodeWireProtocol.V1_18_EMBEDDED_V2))
        assertFalse(first.consumeEmbeddedV2(OpenCodeWireProtocol.V1_18_EMBEDDED_V2))
        assertTrue(second.consumeEmbeddedV2(OpenCodeWireProtocol.V1_18_EMBEDDED_V2))
    }

    @Test
    fun callbacksRecheckEligibilityAfterDispatch() {
        val queued = mutableListOf<() -> Unit>()
        val delivered = mutableListOf<String>()
        var active = true
        val closed =
            OpenCodeStartCallback(
                { active },
                { delivered += "closed" },
                { delivered += "closed failure" },
            )
        val live =
            OpenCodeStartCallback({ true }, { delivered += "started" }, { delivered += "failed" })
        notifyOpenCodeStartCallbacks(listOf(closed, live), true, queued::add)
        active = false
        queued.removeFirst()()
        notifyOpenCodeStartCallbacks(listOf(live), false, queued::add)
        queued.removeFirst()()
        assertEquals(listOf("started", "failed"), delivered)
    }

    @Test
    fun versionProbeCannotPublishIntoAnotherProcessEvenOnReusedPort() {
        val target =
            OpenCodeServerConnection(
                "http://127.0.0.1:4096",
                "secret",
                null,
                OpenCodeWireProtocol.UNKNOWN,
                1,
                100,
            )
        assertFalse(target.sameProcess(null))
        assertFalse(target.sameProcess(target.copy(generation = 2)))
        assertFalse(target.sameProcess(target.copy(password = "rotated")))
        assertFalse(target.sameProcess(target.copy(url = "http://127.0.0.1:4097")))
        assertTrue(target.sameProcess(target.copy(version = "2.0.18")))
        assertFalse(target.toString().contains("secret"))
    }
}
