package de.moritzf.opencodewebpanel.toolWindow

import java.awt.Component
import java.awt.Rectangle

/** Performs one guarded 1 px resize and restores only bounds that still belong to that nudge. */
internal class OpenCodeComponentSizeNudger(
    private val component: Component,
    private val isActive: () -> Boolean,
    private val scheduleRestore: (action: () -> Unit) -> Unit,
    private val afterGrow: () -> Unit,
    private val afterRestore: () -> Unit,
) {
    private var originalBounds: Rectangle? = null
    private var nudgedBounds: Rectangle? = null

    fun nudge(): Boolean {
        if (!isActive() || originalBounds != null) return false
        val original = component.bounds
        if (original.width <= 0 || original.height <= 0) return false
        val nudged = Rectangle(original.x, original.y, original.width + 1, original.height)
        originalBounds = original
        nudgedBounds = nudged
        component.bounds = nudged
        afterGrow()
        scheduleRestore(::restore)
        return true
    }

    private fun restore() {
        val original = originalBounds ?: return
        val expectedNudged = nudgedBounds ?: return
        originalBounds = null
        nudgedBounds = null
        if (!isActive() || component.bounds != expectedNudged) return
        component.bounds = original
        afterRestore()
    }
}
