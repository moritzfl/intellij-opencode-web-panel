package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.BadgeIconSupplier
import de.moritzf.opencodewebpanel.features.OpenCodeChatInputService
import java.awt.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.ClassRule
import org.junit.Test

class OpenCodePanelCoordinatorTest {
    companion object {
        @ClassRule
        @JvmField
        val application = ApplicationRule()
    }

    @Test
    fun keepsOneLiveComponentInTheActivePlacement() {
        onEdt {
            val panel = TestPanel()
            val toolWindow = TestPlacement("tool-window")
            val editor = TestPlacement("editor")
            val coordinator = coordinator(panel)
            coordinator.registerPlacement(toolWindow.id, toolWindow.container, toolWindow.placeholder)
            coordinator.registerPlacement(editor.id, editor.container, editor.placeholder)

            coordinator.place(toolWindow.id)

            assertSame(panel.component, toolWindow.container.singleChild())
            assertSame(editor.placeholder, editor.container.singleChild())
            assertEquals(1, listOf(toolWindow, editor).count { host ->
                host.container.components.any { child -> child === panel.component }
            })
        }
    }

    @Test
    fun transfersFromToolWindowToEditorAndRefreshesBothContainers() {
        onEdt {
            val panel = TestPanel()
            val toolWindow = TrackingPlacement("tool-window")
            val editor = TrackingPlacement("editor")
            val coordinator = coordinator(panel)
            coordinator.registerPlacement(toolWindow.id, toolWindow.container, toolWindow.placeholder)
            coordinator.registerPlacement(editor.id, editor.container, editor.placeholder)

            coordinator.place(toolWindow.id)
            val previousToolWindowRepaints = toolWindow.repaintCount
            val previousToolWindowRevalidations = toolWindow.revalidateCount
            val previousEditorRepaints = editor.repaintCount
            val previousEditorRevalidations = editor.revalidateCount

            coordinator.place(editor.id)

            assertSame(panel.component, editor.container.singleChild())
            assertSame(toolWindow.placeholder, toolWindow.container.singleChild())
            assertTrue(toolWindow.repaintCount > previousToolWindowRepaints)
            assertTrue(toolWindow.revalidateCount > previousToolWindowRevalidations)
            assertTrue(editor.repaintCount > previousEditorRepaints)
            assertTrue(editor.revalidateCount > previousEditorRevalidations)
        }
    }

    @Test
    fun movingSharedPanelBetweenHostsDoesNotRequeueItsInFlightChatBatch() {
        onEdt {
            val service = OpenCodeChatInputService()
            val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
            val panel = TestPanel()
            service.setDispatcher(panel, { delivery -> submitted += delivery; true })
            val toolWindow = TestPlacement("tool-window")
            val editor = TestPlacement("editor")
            val coordinator = OpenCodePanelCoordinator(
                panelComponent = panel.component,
                disposePanel = {
                    service.setDispatcher(panel, null)
                    panel.dispose()
                },
                parkingContainer = JPanel(),
            )
            coordinator.registerPlacement(toolWindow.id, toolWindow.container, toolWindow.placeholder)
            coordinator.registerPlacement(editor.id, editor.container, editor.placeholder)

            try {
                assertTrue(service.send(listOf("text")))
                val delivery = submitted.single()

                coordinator.place(toolWindow.id)
                coordinator.place(editor.id)

                assertEquals(listOf("text"), submitted.map { it.batch.text })
                assertTrue(service.acknowledge(delivery.attemptID, accepted = true))
                assertEquals(0, service.queuedCount())
            } finally {
                service.setDispatcher(panel, null)
                coordinator.dispose()
            }
        }
    }

    @Test
    fun inactivePlacementsShowPlaceholdersWhenTheActivePlacementChanges() {
        onEdt {
            val panel = TestPanel()
            val toolWindow = TrackingPlacement("tool-window")
            val firstEditor = TrackingPlacement("editor-1")
            val secondEditor = TrackingPlacement("editor-2")
            val coordinator = coordinator(panel)
            listOf(toolWindow, firstEditor, secondEditor).forEach {
                coordinator.registerPlacement(it.id, it.container, it.placeholder)
            }

            coordinator.place(firstEditor.id)
            val previousRepaints = listOf(toolWindow, firstEditor, secondEditor).map { it.repaintCount }
            val previousRevalidations = listOf(toolWindow, firstEditor, secondEditor).map { it.revalidateCount }
            coordinator.place(secondEditor.id)

            assertSame(toolWindow.placeholder, toolWindow.container.singleChild())
            assertSame(firstEditor.placeholder, firstEditor.container.singleChild())
            assertSame(panel.component, secondEditor.container.singleChild())
            listOf(toolWindow, firstEditor, secondEditor).forEachIndexed { index, placement ->
                assertTrue(placement.repaintCount > previousRepaints[index])
                assertTrue(placement.revalidateCount > previousRevalidations[index])
            }
        }
    }

    @Test
    fun hostOperationsFollowTheActivePlacement() {
        onEdt {
            val panel = TestPanel()
            val firstHost = TrackingHost()
            val secondHost = TrackingHost()
            val first = TestPlacement("first")
            val second = TestPlacement("second")
            val coordinator = coordinator(panel)
            coordinator.registerPlacement(first.id, first.container, first.placeholder, firstHost)
            coordinator.registerPlacement(second.id, second.container, second.placeholder, secondHost)
            val icons = BadgeIconSupplier(
                IconLoader.getIcon("/icons/opencode.svg", OpenCodeWebToolWindowContent::class.java),
            )

            coordinator.place(first.id)
            coordinator.isActive(panel.component)
            coordinator.isPanelInView(panel.component)
            coordinator.activate(panel.component) {}
            coordinator.updateHeading()
            coordinator.updateAgentStatus("busy", icons)

            assertEquals(1, firstHost.activeCalls)
            assertEquals(1, firstHost.inViewCalls)
            assertEquals(1, firstHost.activateCalls)
            assertEquals(1, firstHost.headingCalls)
            assertEquals(1, firstHost.statusCalls)
            assertEquals(0, secondHost.totalCalls())

            coordinator.place(second.id)
            coordinator.isActive(panel.component)
            coordinator.isPanelInView(panel.component)
            coordinator.activate(panel.component) {}
            coordinator.updateHeading()
            coordinator.updateAgentStatus("idle", icons)

            assertEquals(5, firstHost.totalCalls())
            assertEquals(1, secondHost.activeCalls)
            assertEquals(1, secondHost.inViewCalls)
            assertEquals(1, secondHost.activateCalls)
            assertEquals(1, secondHost.headingCalls)
            assertEquals(1, secondHost.statusCalls)
        }
    }

    @Test
    fun notificationActivationFollowsTheActivePlacement() {
        onEdt {
            val panel = TestPanel()
            val firstHost = TrackingHost()
            val secondHost = TrackingHost()
            val first = TestPlacement("first")
            val second = TestPlacement("second")
            val coordinator = coordinator(panel)
            coordinator.registerPlacement(first.id, first.container, first.placeholder, firstHost)
            coordinator.registerPlacement(second.id, second.container, second.placeholder, secondHost)
            val activated = mutableListOf<String>()
            val activatePanel: ((() -> Unit) -> Unit) = { action -> coordinator.activate(panel.component, action) }

            coordinator.place(first.id)
            activatePanel { activated += first.id }
            coordinator.place(second.id)
            activatePanel { activated += second.id }

            assertEquals(listOf("first", "second"), activated)
            assertEquals(1, firstHost.activateCalls)
            assertEquals(1, secondHost.activateCalls)
        }
    }

    @Test
    fun failureCardStaysInTheActivePlacement() {
        onEdt {
            val panel = TestPanel()
            val first = TestPlacement("first")
            val second = TestPlacement("second")
            val coordinator = coordinator(panel)
            coordinator.registerPlacement(first.id, first.container, first.placeholder, TrackingHost())
            coordinator.registerPlacement(second.id, second.container, second.placeholder, TrackingHost())

            coordinator.place(first.id)
            coordinator.showFailure()
            assertFalse(first.container.singleChild() === first.placeholder)
            assertSame(second.placeholder, second.container.singleChild())

            coordinator.place(second.id)

            assertSame(first.placeholder, first.container.singleChild())
            assertFalse(second.container.singleChild() === second.placeholder)
        }
    }

    @Test
    fun replacementUsesThePlacementThatIsActiveWhenTheSuccessorIsReady() {
        val readiness = CompletableFuture<Unit>()
        val previous = ReplacementPanel()
        val installed = CountDownLatch(1)
        val successor = ReplacementPanel(readiness, onOpened = installed::countDown)
        lateinit var coordinator: OpenCodePanelCoordinator
        val first = TestPlacement("first")
        val second = TestPlacement("second")

        onEdt {
            coordinator = OpenCodePanelCoordinator(previous, JPanel()) { successor }
            coordinator.registerPlacement(first.id, first.container, first.placeholder, testHost())
            coordinator.registerPlacement(second.id, second.container, second.placeholder, testHost())
            coordinator.place(first.id)
            coordinator.replacePanel()
            coordinator.place(second.id)
        }

        readiness.complete(Unit)
        ApplicationManager.getApplication().invokeAndWait { }
        assertTrue("successor completion timed out", installed.await(5, TimeUnit.SECONDS))
        onEdt {
            assertSame(successor.component, second.container.singleChild())
            assertSame(first.placeholder, first.container.singleChild())
            assertTrue(previous.disposed)
            assertEquals(listOf<String?>(null), successor.openedSessions)
            assertEquals(1, successor.placementTransfers)
        }
    }

    @Test
    fun replacementHandsChatToReadySuccessorAfterRetainingPredecessor() {
        val service = OpenCodeChatInputService()
        val readiness = CompletableFuture<Unit>()
        val events = mutableListOf<String>()
        val submitted = mutableListOf<OpenCodeChatInputService.Delivery>()
        val previous = ReplacementPanel(
            name = "previous",
            chatService = service,
            submitted = submitted,
            events = events,
            isActive = { true },
        )
        val successor = ReplacementPanel(
            name = "successor",
            readiness = readiness,
            chatService = service,
            submitted = submitted,
            events = events,
            isActive = { false },
        )
        val first = TestPlacement("first")
        lateinit var coordinator: OpenCodePanelCoordinator

        onEdt {
            coordinator = OpenCodePanelCoordinator(previous, JPanel()) { successor }
            coordinator.registerPlacement(first.id, first.container, first.placeholder, testHost())
            coordinator.place(first.id)
            assertTrue(service.send(listOf("text")))
            events.clear()
        }
        val previousAttempt = submitted.single()

        onEdt {
            coordinator.replacePanel()
            assertFalse(previous.disposed)
            assertSame(previous.component, first.container.singleChild())
        }

        readiness.complete(Unit)
        ApplicationManager.getApplication().invokeAndWait { }

        onEdt {
            assertTrue(previous.disposed)
            assertSame(successor.component, first.container.singleChild())
            assertEquals(listOf("successor-transfer", "previous-dispose", "successor-open"), events)
        }

        val successorAttempt = submitted.last()
        assertFalse(previousAttempt.attemptID == successorAttempt.attemptID)
        assertFalse(service.acknowledge(previousAttempt.attemptID, accepted = true))
        assertTrue(service.acknowledge(successorAttempt.attemptID, accepted = true))
        assertEquals(0, service.queuedCount())
        onEdt { coordinator.dispose() }
    }

    @Test
    fun failedSuccessorLeavesThePredecessorAttachedAndUsable() {
        val readiness = CompletableFuture<Unit>()
        val previous = ReplacementPanel()
        val cleanedUp = CountDownLatch(1)
        val successor = ReplacementPanel(readiness, onDisposed = cleanedUp::countDown)
        val first = TestPlacement("first")
        lateinit var coordinator: OpenCodePanelCoordinator

        onEdt {
            coordinator = OpenCodePanelCoordinator(previous, JPanel()) { successor }
            coordinator.registerPlacement(first.id, first.container, first.placeholder, testHost())
            coordinator.place(first.id)
            coordinator.replacePanel()
        }

        readiness.completeExceptionally(IllegalStateException("renderer did not start"))
        ApplicationManager.getApplication().invokeAndWait { }
        assertTrue("failed successor cleanup timed out", cleanedUp.await(5, TimeUnit.SECONDS))
        onEdt {
            assertSame(previous.component, first.container.singleChild())
            assertFalse(previous.disposed)
            assertTrue(successor.disposed)
        }
    }

    @Test
    fun stalePlacementGenerationCannotStealTheComponentBack() {
        onEdt {
            val panel = TestPanel()
            val toolWindow = TestPlacement("tool-window")
            val editor = TestPlacement("editor")
            val coordinator = coordinator(panel)
            coordinator.registerPlacement(toolWindow.id, toolWindow.container, toolWindow.placeholder)
            coordinator.registerPlacement(editor.id, editor.container, editor.placeholder)

            coordinator.place(toolWindow.id)
            assertSame(panel.component, toolWindow.container.singleChild())

            val staleGeneration = coordinator.beginPlacement(editor.id)
            val currentGeneration = coordinator.beginPlacement(toolWindow.id)

            assertFalse(coordinator.completePlacement(editor.id, staleGeneration))
            assertSame(panel.component, toolWindow.container.singleChild())
            assertSame(editor.placeholder, editor.container.singleChild())
            assertTrue(coordinator.completePlacement(toolWindow.id, currentGeneration))
            assertSame(panel.component, toolWindow.container.singleChild())
        }
    }

    @Test
    fun parkingKeepsThePanelAliveWithoutAnActiveHost() {
        onEdt {
            val panel = TestPanel()
            val toolWindow = TestPlacement("tool-window")
            val parking = JPanel()
            val coordinator = coordinator(panel, parking)
            coordinator.registerPlacement(toolWindow.id, toolWindow.container, toolWindow.placeholder)
            coordinator.place(toolWindow.id)

            coordinator.park()

            assertSame(panel.component, parking.singleChild())
            assertSame(toolWindow.placeholder, toolWindow.container.singleChild())
            assertFalse(panel.disposed)
        }
    }

    @Test
    fun unregisteringAHostParksButDoesNotDisposeTheSharedPanel() {
        onEdt {
            val panel = TestPanel()
            val toolWindow = TestPlacement("tool-window")
            val parking = JPanel()
            val coordinator = coordinator(panel, parking)
            coordinator.registerPlacement(toolWindow.id, toolWindow.container, toolWindow.placeholder)
            coordinator.place(toolWindow.id)

            coordinator.unregisterPlacement(toolWindow.id)

            assertSame(panel.component, parking.singleChild())
            assertFalse(panel.disposed)
        }
    }

    @Test
    fun releasingAnActiveEditorTransfersToTheToolWindowBeforeUnregisteringIt() {
        onEdt {
            val panel = TestPanel()
            val firstEditor = TestPlacement("editor-1")
            val activeEditor = TrackingPlacement("editor-2")
            val toolWindow = TrackingPlacement("tool-window")
            val coordinator = coordinator(panel)
            listOf(firstEditor, activeEditor, toolWindow).forEach {
                coordinator.registerPlacement(it.id, it.container, it.placeholder)
            }

            coordinator.place(activeEditor.id)
            val previousEditorRepaints = activeEditor.repaintCount
            val previousEditorRevalidations = activeEditor.revalidateCount
            val previousRepaints = toolWindow.repaintCount
            val previousRevalidations = toolWindow.revalidateCount
            val events = mutableListOf<String>()
            coordinator.releaseEditorPlacement(activeEditor.id, toolWindow.id) {
                events += "transfer-complete"
                assertTrue(coordinator.isPlacementRegistered(activeEditor.id))
                assertSame(panel.component, toolWindow.container.singleChild())
            }
            events += "release-returned"

            assertSame(panel.component, toolWindow.container.singleChild())
            assertSame(firstEditor.placeholder, firstEditor.container.singleChild())
            assertFalse(coordinator.isPlacementRegistered(activeEditor.id))
            assertEquals(listOf("transfer-complete", "release-returned"), events)
            assertTrue(activeEditor.repaintCount > previousEditorRepaints)
            assertTrue(activeEditor.revalidateCount > previousEditorRevalidations)
            assertTrue(toolWindow.repaintCount > previousRepaints)
            assertTrue(toolWindow.revalidateCount > previousRevalidations)
        }
    }

    @Test
    fun releasingAnActiveEditorParksWhenTheToolWindowIsUnavailable() {
        onEdt {
            val panel = TestPanel()
            val editor = TestPlacement("editor")
            val parking = JPanel()
            val coordinator = coordinator(panel, parking)
            coordinator.registerPlacement(editor.id, editor.container, editor.placeholder)
            coordinator.place(editor.id)

            val events = mutableListOf<String>()
            coordinator.releaseEditorPlacement(editor.id, "tool-window") {
                events += "park-complete"
                assertTrue(coordinator.isPlacementRegistered(editor.id))
                assertSame(panel.component, parking.singleChild())
            }
            events += "release-returned"

            assertSame(panel.component, parking.singleChild())
            assertFalse(coordinator.isPlacementRegistered(editor.id))
            assertEquals(listOf("park-complete", "release-returned"), events)
        }
    }

    @Test
    fun toolWindowShellLeavesEditorPanelMountedWhenAlreadyOccupied() {
        onEdt {
            val panel = TestPanel()
            val editor = TestPlacement("editor")
            val parking = JPanel()
            val coordinator = coordinator(panel, parking)
            coordinator.registerPlacement(editor.id, editor.container, editor.placeholder)
            coordinator.place(editor.id)

            val shell = OpenCodeToolWindowShell(coordinator, "tool-window", testHost())

            assertFalse(shell.activateIfUnoccupied())
            assertSame(panel.component, editor.container.singleChild())
            assertFalse(shell.component.getComponent(0) === panel.component)
            shell.dispose()
            assertFalse(panel.disposed)
        }
    }

    @Test
    fun inactiveToolWindowShellKeepsEditorHostOwnership() {
        onEdt {
            val panel = TestPanel()
            var editorActivated = false
            var toolWindowActivated = false
            val editor = TestPlacement("editor")
            val parking = JPanel()
            val coordinator = coordinator(panel, parking)
            val editorHost = testHost { editorActivated = true }
            val toolWindowHost = testHost { toolWindowActivated = true }
            coordinator.registerPlacement(editor.id, editor.container, editor.placeholder, editorHost)
            coordinator.place(editor.id)

            val shell = OpenCodeToolWindowShell(coordinator, "tool-window", toolWindowHost)

            assertFalse(shell.activateIfUnoccupied())
            var panelForCalled = false
            selectOpenCodeToolWindowPanel(
                shellActivated = false,
                existingPanel = coordinator::panel,
                createPanel = {
                    panelForCalled = true
                    coordinator.panelForActivePlacement("tool-window", sessionId = null)
                },
            )
            assertFalse(panelForCalled)
            coordinator.activate(JPanel()) {}
            assertTrue(editorActivated)
            assertFalse(toolWindowActivated)
            shell.dispose()
        }
    }

    @Test
    fun toolWindowShellActivatesParkedPanelAndDisposalOnlyUnregistersIt() {
        onEdt {
            val panel = TestPanel()
            val parking = JPanel()
            val coordinator = coordinator(panel, parking)
            coordinator.park()
            val shell = OpenCodeToolWindowShell(coordinator, "tool-window", testHost())

            assertTrue(shell.activateIfUnoccupied())
            assertSame(panel.component, shell.component.singleChild())
            shell.dispose()
            assertSame(panel.component, parking.singleChild())
            assertFalse(panel.disposed)
        }
    }

    @Test
    fun parkingInvalidatesPendingPlacement() {
        onEdt {
            val panel = TestPanel()
            val toolWindow = TestPlacement("tool-window")
            val parking = JPanel()
            val coordinator = coordinator(panel, parking)
            coordinator.registerPlacement(toolWindow.id, toolWindow.container, toolWindow.placeholder)

            val pendingGeneration = coordinator.beginPlacement(toolWindow.id)
            coordinator.park()

            assertFalse(coordinator.completePlacement(toolWindow.id, pendingGeneration))
            assertSame(panel.component, parking.singleChild())
            assertSame(toolWindow.placeholder, toolWindow.container.singleChild())
        }
    }

    @Test
    fun rejectsDuplicatePlacementIdsAndContainers() {
        onEdt {
            val panel = TestPanel()
            val first = TestPlacement("first")
            val second = TestPlacement("second")
            val coordinator = coordinator(panel)
            coordinator.registerPlacement(first.id, first.container, first.placeholder)

            assertThrows(IllegalArgumentException::class.java) {
                coordinator.registerPlacement(first.id, second.container, second.placeholder)
            }
            assertThrows(IllegalArgumentException::class.java) {
                coordinator.registerPlacement(second.id, first.container, second.placeholder)
            }

            coordinator.place(first.id)
            assertSame(panel.component, first.container.singleChild())
        }
    }

    @Test
    fun projectDisposalDisposesTheSharedPanelExactlyOnce() {
        onEdt {
            val panel = TestPanel()
            val coordinator = coordinator(panel)

            coordinator.dispose()
            coordinator.dispose()

            assertEquals(1, panel.disposeCount)
        }
    }

    private fun coordinator(panel: TestPanel, parking: JPanel = JPanel()) = OpenCodePanelCoordinator(
        panelComponent = panel.component,
        disposePanel = panel::dispose,
        parkingContainer = parking,
    )

    private class TestPanel {
        val component = JPanel()
        var disposeCount = 0
            private set
        val disposed: Boolean
            get() = disposeCount > 0

        fun dispose() {
            disposeCount++
        }
    }

    private class ReplacementPanel(
        private val readiness: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit),
        private val onOpened: () -> Unit = {},
        private val onDisposed: () -> Unit = {},
        private val name: String = "panel",
        private val chatService: OpenCodeChatInputService? = null,
        private val submitted: MutableList<OpenCodeChatInputService.Delivery>? = null,
        private val events: MutableList<String>? = null,
        private val isActive: () -> Boolean = { true },
    ) : OpenCodePanelHandle {
        override val component = JPanel()
        var disposed = false
        var placementTransfers = 0
        val openedSessions = mutableListOf<String?>()

        init {
            chatService?.setDispatcher(
                this,
                { delivery -> submitted?.add(delivery); true },
                isActive = isActive,
            )
        }

        override fun prepareBrowserForReplacement(): CompletableFuture<Unit> = readiness

        override fun openSession(sessionId: String?) {
            openedSessions += sessionId
            events?.add("$name-open")
            onOpened()
        }

        override fun onPlacementTransferred() {
            placementTransfers++
            events?.add("$name-transfer")
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            events?.add("$name-dispose")
            chatService?.setDispatcher(this, null)
            onDisposed()
        }
    }

    private open class TestPlacement(val id: String) {
        open val container = JPanel()
        val placeholder = JPanel()
    }

    private class TrackingPlacement(id: String) : TestPlacement(id) {
        private val trackingContainer = TrackingPanel()
        override val container: JPanel = trackingContainer
        val repaintCount: Int
            get() = trackingContainer.repaintCount
        val revalidateCount: Int
            get() = trackingContainer.revalidateCount
    }

    private class TrackingPanel : JPanel() {
        var repaintCount = 0
        var revalidateCount = 0

        override fun repaint() {
            repaintCount++
            super.repaint()
        }

        override fun revalidate() {
            revalidateCount++
            super.revalidate()
        }
    }

    private fun testHost(onActivate: () -> Unit = {}) = object : OpenCodePanelHost {
        override val project: com.intellij.openapi.project.Project
            get() = error("Test host project is not used")

        override fun isDisposed(): Boolean = false

        override fun activate(component: javax.swing.JComponent, action: () -> Unit) {
            onActivate()
            action()
        }

        override fun replacePanel() = Unit

        override fun showFailure() = Unit
    }

    private class TrackingHost : OpenCodePanelHost {
        override val project: com.intellij.openapi.project.Project
            get() = error("Test host project is not used")

        var activeCalls = 0
        var inViewCalls = 0
        var activateCalls = 0
        var headingCalls = 0
        var statusCalls = 0

        override fun isDisposed(): Boolean = false

        override fun isActive(component: javax.swing.JComponent): Boolean {
            activeCalls++
            return true
        }

        override fun isPanelInView(component: javax.swing.JComponent): Boolean {
            inViewCalls++
            return true
        }

        override fun activate(component: javax.swing.JComponent, action: () -> Unit) {
            activateCalls++
            action()
        }

        override fun replacePanel() = Unit

        override fun showFailure() = Unit

        override fun updateHeading() {
            headingCalls++
        }

        override fun updateAgentStatus(state: String, icons: BadgeIconSupplier) {
            statusCalls++
        }

        fun totalCalls(): Int = activeCalls + inViewCalls + activateCalls + headingCalls + statusCalls
    }

    private fun JPanel.singleChild(): Component {
        assertEquals(1, componentCount)
        return getComponent(0)
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait { block() }
        }
    }
}
