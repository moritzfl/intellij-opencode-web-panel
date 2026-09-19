package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.util.ui.components.BorderLayoutPanel

/** Stable tool-window host; the coordinator owns the shared panel and its lifetime. */
internal class OpenCodeToolWindowShell(
    private val coordinator: OpenCodePanelCoordinator,
    private val placementId: String,
    host: OpenCodePanelHost,
) : Disposable {
    val component = BorderLayoutPanel()

    private val placeholder = OpenCodePanelPlaceholder(
        title = "OpenCode is open in an editor",
        message = "Reclaim the shared panel in the tool window.",
        actionText = "Show in Tool Window",
    ) {
        host.activate(component) { coordinator.place(placementId) }
    }.component

    init {
        coordinator.registerPlacement(placementId, component, placeholder, host)
    }

    fun activateIfUnoccupied(): Boolean = coordinator.placeIfUnoccupied(placementId)

    override fun dispose() {
        if (coordinator.isPlacementRegistered(placementId)) {
            coordinator.unregisterPlacement(placementId)
        }
    }
}
