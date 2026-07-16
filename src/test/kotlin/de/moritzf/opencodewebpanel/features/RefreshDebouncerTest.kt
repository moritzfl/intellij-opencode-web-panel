package de.moritzf.opencodewebpanel.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshDebouncerTest {

    @Test
    fun firstRequestSchedulesAfterDebounce() {
        val debouncer = RefreshDebouncer(debounceMillis = 500, maxWaitMillis = 4000)
        assertFalse(debouncer.isPending())
        assertEquals(500L, debouncer.onRequest(1_000))
        assertTrue(debouncer.isPending())
    }

    @Test
    fun burstReschedulesTrailingUntilItPauses() {
        val debouncer = RefreshDebouncer(debounceMillis = 500, maxWaitMillis = 4000)
        // Burst opens at t=0, fire planned for t=500.
        assertEquals(500L, debouncer.onRequest(0))
        // Each event within the window pushes the fire out by a fresh debounce delay.
        assertEquals(500L, debouncer.onRequest(200)) // fire now planned for t=700
        assertEquals(500L, debouncer.onRequest(400)) // fire now planned for t=900
        // No event for a debounce interval: the last scheduled fire (t=900) stands and fires.
        debouncer.onFire()
        assertFalse(debouncer.isPending())
    }

    @Test
    fun repeatedRequestAtSameInstantDoesNotReschedule() {
        val debouncer = RefreshDebouncer(debounceMillis = 500, maxWaitMillis = 4000)
        assertEquals(500L, debouncer.onRequest(100))
        // Same timestamp => identical target => caller keeps its existing timer.
        assertNull(debouncer.onRequest(100))
        assertNull(debouncer.onRequest(100))
    }

    @Test
    fun continuousStreamIsCappedAtMaxWait() {
        val debounceMillis = 500L
        val maxWaitMillis = 4000L
        val debouncer = RefreshDebouncer(debounceMillis, maxWaitMillis)
        val burstStart = 10_000L
        assertEquals(debounceMillis, debouncer.onRequest(burstStart))

        // Fire an event every 100ms; the planned fire time must never exceed burstStart+maxWait.
        var now = burstStart + 100
        var lastPlannedFire = burstStart + debounceMillis
        var sawCap = false
        while (now <= burstStart + maxWaitMillis + 2_000) {
            val delay = debouncer.onRequest(now)
            if (delay != null) {
                val plannedFire = now + delay
                assertTrue(
                    "planned fire $plannedFire must not exceed cap ${burstStart + maxWaitMillis}",
                    plannedFire <= burstStart + maxWaitMillis,
                )
                assertTrue("fire time must be monotonic", plannedFire >= lastPlannedFire)
                lastPlannedFire = plannedFire
                if (plannedFire == burstStart + maxWaitMillis) sawCap = true
            } else {
                // Once capped, further same-burst events keep the capped timer (null).
                assertTrue("null only expected at/after the cap", sawCap)
            }
            now += 100
        }
        assertTrue("stream should have reached the max-wait cap", sawCap)
        assertEquals(burstStart + maxWaitMillis, lastPlannedFire)
    }

    @Test
    fun capReachedReturnsNullForLaterSameBurstEvents() {
        val debouncer = RefreshDebouncer(debounceMillis = 500, maxWaitMillis = 1000)
        assertEquals(500L, debouncer.onRequest(0)) // fire t=500
        assertEquals(500L, debouncer.onRequest(200)) // fire t=700
        assertEquals(300L, debouncer.onRequest(700)) // min(1200, cap 1000) => t=1000
        // Already capped at t=1000; later events in the same burst do not reschedule.
        assertNull(debouncer.onRequest(800))
        assertNull(debouncer.onRequest(950))
    }

    @Test
    fun newBurstStartsAfterFire() {
        val debouncer = RefreshDebouncer(debounceMillis = 500, maxWaitMillis = 4000)
        debouncer.onRequest(0)
        debouncer.onRequest(300)
        debouncer.onFire()
        assertFalse(debouncer.isPending())
        // A later request opens a brand-new burst with a fresh cap anchored at its own start.
        assertEquals(500L, debouncer.onRequest(10_000))
        assertEquals(500L, debouncer.onRequest(10_200)) // t=10_700, well under the new cap
        assertTrue(debouncer.isPending())
    }

    @Test
    fun invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RefreshDebouncer(debounceMillis = 0, maxWaitMillis = 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RefreshDebouncer(debounceMillis = 2000, maxWaitMillis = 1000)
        }
    }
}
