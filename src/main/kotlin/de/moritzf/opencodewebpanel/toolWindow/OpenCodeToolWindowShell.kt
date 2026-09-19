package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.JPanel

/** Stable tool-window host; the coordinator owns the shared panel and its lifetime. */
internal class OpenCodeToolWindowShell(
    private val coordinator: OpenCodePanelCoordinator,
    private val placementId: String,
    host: OpenCodePanelHost,
) : Disposable {
    val component = BorderLayoutPanel()

    private val placeholder = JPanel()

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
