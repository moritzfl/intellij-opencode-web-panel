package de.moritzf.opencodewebpanel.features

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeDropWindowFocusRecoveryTest {
    private class Fixture {
        var eligible = true
        var restored = 0
        val requests = mutableListOf<Runnable>()
        val nativeReplies = mutableListOf<(Boolean) -> Unit>()
        val recovery =
            OpenCodeDropWindowFocusRecovery(
                isEligible = { eligible },
                checkNativeFocus = { nativeReplies += it },
                restoreFocus = {
                    restored++
                    eligible = false
                },
                schedule = { request, _ -> requests += request },
            )
    }

    @Test
    fun repairsLateWindowFocusLossAfterTheFirstCheckWasHealthy() {
        val fixture = Fixture()
        fixture.eligible = false
        fixture.recovery.afterDrop()
        fixture.requests[0].run()
        assertEquals(0, fixture.nativeReplies.size)

        // The native screenshot/Space handoff delivers windowLostFocus later.
        fixture.eligible = true
        fixture.requests[1].run()
        fixture.nativeReplies.single()(true)
        assertEquals(1, fixture.restored)
    }

    @Test
    fun neverActivatesAnApplicationOrWindowThatIsNotNativelyFocused() {
        val fixture = Fixture()
        fixture.recovery.afterDrop()
        fixture.requests.forEach { it.run() }
        fixture.nativeReplies.forEach { it(false) }
        assertEquals(0, fixture.restored)
    }

    @Test
    fun staleNativeReplyCannotRestoreAfterFocusPlacementOrLifetimeChanged() {
        val fixture = Fixture()
        fixture.recovery.afterDrop()
        fixture.requests[0].run()
        fixture.eligible = false
        fixture.nativeReplies.single()(true)
        fixture.requests[1].run()
        assertEquals(0, fixture.restored)
        assertEquals(1, fixture.nativeReplies.size)
    }

    @Test
    fun successfulRecoveryPreventsAnotherFocusRequest() {
        val fixture = Fixture()
        fixture.recovery.afterDrop()
        // Even if both native checks are pending, the later reply must recheck eligibility.
        fixture.requests.forEach { it.run() }
        fixture.nativeReplies.forEach { it(true) }
        assertEquals(1, fixture.restored)
    }
}
