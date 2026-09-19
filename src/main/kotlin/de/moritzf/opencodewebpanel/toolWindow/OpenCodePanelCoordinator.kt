package de.moritzf.opencodewebpanel.toolWindow

import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities

/** Keeps one project panel attached while hosts come and go. All methods are EDT-only. */
internal class OpenCodePanelCoordinator(
    private val panelComponent: Component,
    private val disposePanel: () -> Unit,
    private val parkingContainer: Container,
) {
    private data class Placement(
        val container: Container,
        val placeholder: Component,
    )

    private val placements = linkedMapOf<String, Placement>()
    private var activePlacement: String? = null
    private var generation = 0L
    private var disposed = false

    fun registerPlacement(id: String, container: Container, placeholder: Component) {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        require(id !in placements) { "Placement already registered: $id" }
        require(placements.values.none { it.container === container }) {
            "Container already registered"
        }
        placements[id] = Placement(container, placeholder)
        render()
    }

    fun place(id: String) {
        requireEdt()
        val placementGeneration = beginPlacement(id)
        completePlacement(id, placementGeneration)
    }

    fun beginPlacement(id: String): Long {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        check(placements.containsKey(id)) { "Unknown placement: $id" }
        return ++generation
    }

    fun completePlacement(id: String, placementGeneration: Long): Boolean {
        requireEdt()
        if (disposed || generation != placementGeneration || !placements.containsKey(id)) return false
        activePlacement = id
        render()
        return true
    }

    fun park() {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        generation++
        activePlacement = null
        render()
        parkingContainer.removeAll()
        parkingContainer.add(panelComponent)
        parkingContainer.revalidate()
        parkingContainer.repaint()
    }

    fun dispose() {
        requireEdt()
        if (disposed) return
        disposed = true
        disposePanel()
    }

    private fun render() {
        placements.forEach { (id, placement) ->
            val component = if (id == activePlacement) panelComponent else placement.placeholder
            placement.container.removeAll()
            placement.container.add(component)
            placement.container.revalidate()
            placement.container.repaint()
        }
    }

    private fun requireEdt() {
        check(SwingUtilities.isEventDispatchThread()) {
            "OpenCodePanelCoordinator must be used on the EDT"
        }
    }
}
