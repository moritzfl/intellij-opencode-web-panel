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
    fun discardPendingDropsQueueAndIgnoresStaleAck() {
        val service = OpenCodeChatInputService()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
        service.setDispatcher { delivery -> submitted += delivery; true }
        service.send(listOf("first", "second"))
        val first = submitted.single()

        service.discardPending()

        assertEquals(0, service.queuedCount())
        assertFalse(service.acknowledge(first.attemptID, accepted = true))
        assertEquals(0, service.queuedCount())
        assertTrue(service.dispatchPending())
        assertEquals(0, service.queuedCount())
        assertEquals(listOf("first"), submitted.map { it.batch.text })
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

    @Test
    fun activeDispatcherWinsWhenMultiplePanelsShareAProject() {
        val service = OpenCodeChatInputService()
        val submitted = mutableListOf<String>()
        val toolWindow = Any()
        val editor = Any()
        service.setDispatcher(toolWindow, { submitted += "tool"; true }, isActive = { false })
        service.setDispatcher(editor, { submitted += "editor"; true }, isActive = { true })

        assertTrue(service.send(listOf("text")))
        assertEquals(listOf("editor"), submitted)
    }

    @Test
    fun removingInFlightOwnerImmediatelyHandsBatchToAnotherPanel() {
        val service = OpenCodeChatInputService()
        val toolWindow = Any()
        val editor = Any()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()

        service.setDispatcher(toolWindow, { delivery -> submitted += delivery; true }, isActive = { false })
        service.setDispatcher(editor, { delivery -> submitted += delivery; true }, isActive = { true })

        assertTrue(service.send(listOf("text")))
        val first = submitted.single()

        service.setDispatcher(editor, null)

        assertEquals(listOf("text", "text"), submitted.map { it.batch.text })
        val second = submitted.last()
        assertEquals(first.batch.id, second.batch.id)
        assertFalse(first.attemptID == second.attemptID)
        assertFalse(service.acknowledge(first.attemptID, accepted = true))
        assertTrue(service.acknowledge(second.attemptID, accepted = true))
        assertEquals(0, service.queuedCount())
    }

    @Test
    fun hostTransferKeepsAnInFlightBatchWithTheSharedPanel() {
        val service = OpenCodeChatInputService()
        val panel = Any()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
        service.setDispatcher(panel, { delivery -> submitted += delivery; true })

        assertTrue(service.send(listOf("text")))
        val delivery = submitted.single()

        // A placement change does not remove or replace the panel dispatcher.
        assertEquals(1, service.queuedCount())
        assertTrue(service.acknowledge(delivery.attemptID, accepted = true))
        assertEquals(listOf("text"), submitted.map { it.batch.text })
        assertEquals(0, service.queuedCount())
    }

    @Test
    fun staleAcknowledgementAfterBrowserReplacementCannotCompleteSuccessorAttempt() {
        val service = OpenCodeChatInputService()
        val previousBrowser = Any()
        val successorBrowser = Any()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
        service.setDispatcher(previousBrowser, { delivery -> submitted += delivery; true })

        assertTrue(service.send(listOf("text")))
        val previousAttempt = submitted.single()
        service.setDispatcher(successorBrowser, { delivery -> submitted += delivery; true })
        service.setDispatcher(previousBrowser, null)

        val successorAttempt = submitted.last()
        assertEquals(previousAttempt.batch.id, successorAttempt.batch.id)
        assertFalse(previousAttempt.attemptID == successorAttempt.attemptID)
        assertFalse(service.acknowledge(previousAttempt.attemptID, accepted = true))
        assertEquals(1, service.queuedCount())
        assertTrue(service.acknowledge(successorAttempt.attemptID, accepted = true))
        assertEquals(0, service.queuedCount())
    }

    @Test
    fun projectDisposalClearsQueuedAndInFlightChat() {
        val service = OpenCodeChatInputService()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
        service.setDispatcher { delivery -> submitted += delivery; true }

        assertTrue(service.send(listOf("first", "second")))
        val first = submitted.single()

        service.dispose()

        assertEquals(0, service.queuedCount())
        assertFalse(service.acknowledge(first.attemptID, accepted = true))
        assertFalse(service.send(listOf("after disposal")))
        assertEquals(listOf("first"), submitted.map { it.batch.text })
    }
}
