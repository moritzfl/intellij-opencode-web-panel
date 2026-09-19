package de.moritzf.opencodewebpanel.toolWindow

import java.awt.Component
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePanelCoordinatorTest {
    @Test
    fun keepsOneLiveComponentInTheActivePlacement() {
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

    @Test
    fun inactivePlacementsShowPlaceholdersWhenTheActivePlacementChanges() {
        val panel = TestPanel()
        val toolWindow = TestPlacement("tool-window")
        val firstEditor = TestPlacement("editor-1")
        val secondEditor = TestPlacement("editor-2")
        val coordinator = coordinator(panel)
        listOf(toolWindow, firstEditor, secondEditor).forEach {
            coordinator.registerPlacement(it.id, it.container, it.placeholder)
        }

        coordinator.place(firstEditor.id)
        coordinator.place(secondEditor.id)

        assertSame(toolWindow.placeholder, toolWindow.container.singleChild())
        assertSame(firstEditor.placeholder, firstEditor.container.singleChild())
        assertSame(panel.component, secondEditor.container.singleChild())
    }

    @Test
    fun stalePlacementGenerationCannotStealTheComponentBack() {
        val panel = TestPanel()
        val toolWindow = TestPlacement("tool-window")
        val editor = TestPlacement("editor")
        val coordinator = coordinator(panel)
        coordinator.registerPlacement(toolWindow.id, toolWindow.container, toolWindow.placeholder)
        coordinator.registerPlacement(editor.id, editor.container, editor.placeholder)

        val staleGeneration = coordinator.beginPlacement(editor.id)
        val currentGeneration = coordinator.beginPlacement(toolWindow.id)

        assertFalse(coordinator.completePlacement(editor.id, staleGeneration))
        assertSame(editor.placeholder, editor.container.singleChild())
        assertTrue(coordinator.completePlacement(toolWindow.id, currentGeneration))
        assertSame(panel.component, toolWindow.container.singleChild())
    }

    @Test
    fun parkingKeepsThePanelAliveWithoutAnActiveHost() {
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

    @Test
    fun projectDisposalDisposesTheSharedPanelExactlyOnce() {
        val panel = TestPanel()
        val coordinator = coordinator(panel)

        coordinator.dispose()
        coordinator.dispose()

        assertEquals(1, panel.disposeCount)
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

    private class TestPlacement(val id: String) {
        val container = JPanel()
        val placeholder = JPanel()
    }

    private fun JPanel.singleChild(): Component {
        assertEquals(1, componentCount)
        return getComponent(0)
    }
}
