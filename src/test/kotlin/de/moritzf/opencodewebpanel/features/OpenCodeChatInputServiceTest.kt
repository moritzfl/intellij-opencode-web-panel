package de.moritzf.opencodewebpanel.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeChatInputServiceTest {
    @Test
    fun dispatchesInOrderAndWaitsForAcknowledgement() {
        val service = OpenCodeChatInputService()
        val submitted = mutableListOf<OpenCodeChatInputService.Batch>()
        service.setDispatcher { batch -> submitted += batch; true }

        assertTrue(service.send(listOf("first", "second")))
        assertEquals(listOf("first"), submitted.map { it.text })
        assertEquals(2, service.queuedCount())

        assertTrue(service.acknowledge(submitted[0].id, accepted = true))
        assertEquals(listOf("first", "second"), submitted.map { it.text })
        assertEquals(1, service.queuedCount())

        assertTrue(service.acknowledge(submitted[1].id, accepted = true))
        assertEquals(0, service.queuedCount())
    }

    @Test
    fun rejectedOrTimedOutBatchIsRequeuedWithoutDuplication() {
        val service = OpenCodeChatInputService()
        val submitted = mutableListOf<OpenCodeChatInputService.Batch>()
        service.setDispatcher { batch -> submitted += batch; true }
        service.send(listOf("text"))
        val id = submitted.single().id

        assertTrue(service.acknowledge(id, accepted = false))
        assertEquals(1, service.queuedCount())
        assertFalse(service.acknowledge(id, accepted = true))

        assertTrue(service.dispatchPending())
        assertEquals(listOf(id, id), submitted.map { it.id })
        assertTrue(service.retryInFlight(id))
        assertEquals(1, service.queuedCount())
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
