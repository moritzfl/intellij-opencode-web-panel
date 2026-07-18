package de.moritzf.opencodewebpanel.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeChatInputServiceTest {
    @Test
    fun dispatchesInOrderAndWaitsForAcknowledgement() {
        val service = OpenCodeChatInputService()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
        service.setDispatcher { delivery -> submitted += delivery; true }

        assertTrue(service.send(listOf("first", "second")))
        assertEquals(listOf("first"), submitted.map { it.batch.text })
        assertEquals(2, service.queuedCount())

        assertTrue(service.acknowledge(submitted[0].attemptID, accepted = true))
        assertEquals(listOf("first", "second"), submitted.map { it.batch.text })
        assertEquals(1, service.queuedCount())

        assertTrue(service.acknowledge(submitted[1].attemptID, accepted = true))
        assertEquals(0, service.queuedCount())
    }

    @Test
    fun rejectedOrTimedOutBatchIsRequeuedWithoutDuplication() {
        val service = OpenCodeChatInputService()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
        service.setDispatcher { delivery -> submitted += delivery; true }
        service.send(listOf("text"))
        val first = submitted.single()

        assertTrue(service.acknowledge(first.attemptID, accepted = false))
        assertEquals(1, service.queuedCount())
        assertFalse(service.acknowledge(first.attemptID, accepted = true))

        assertTrue(service.dispatchPending())
        val second = submitted.last()
        assertEquals(first.batch.id, second.batch.id)
        assertFalse(first.attemptID == second.attemptID)
        assertTrue(service.retryInFlight(second.attemptID))
        assertEquals(1, service.queuedCount())
    }

    @Test
    fun staleCallbackAndTimeoutCannotAffectRetriedAttempt() {
        val service = OpenCodeChatInputService()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
        service.setDispatcher { delivery -> submitted += delivery; true }
        service.send(listOf("text"))
        val first = submitted.single()
        assertTrue(service.retryInFlight(first.attemptID))
        assertTrue(service.dispatchPending())
        val second = submitted.last()

        assertFalse(service.acknowledge(first.attemptID, accepted = true))
        assertFalse(service.retryInFlight(first.attemptID))
        assertEquals(1, service.queuedCount())
        assertTrue(service.acknowledge(second.attemptID, accepted = true))
        assertEquals(0, service.queuedCount())
    }

    @Test
    fun removingDispatcherRequeuesInFlightBatch() {
        val service = OpenCodeChatInputService()
        service.setDispatcher { true }
        service.send(listOf("text"))

        service.setDispatcher(null)

        assertEquals(1, service.queuedCount())
        assertFalse(service.dispatchPending())
    }
}
