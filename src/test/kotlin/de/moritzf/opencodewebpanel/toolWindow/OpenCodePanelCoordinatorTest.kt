package de.moritzf.opencodewebpanel.toolWindow

import java.awt.Component
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenCodePanelCoordinatorTest {
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
                    coordinator.panelForActivePlacement("tool-window", toolWindowHost, sessionId = null)
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
