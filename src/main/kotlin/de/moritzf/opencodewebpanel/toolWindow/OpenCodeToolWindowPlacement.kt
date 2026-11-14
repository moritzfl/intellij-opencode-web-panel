package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowInfo

/** A stripe button's place in a project's tool window layout. */
internal data class OpenCodeStripePlace(
    val anchor: ToolWindowAnchor,
    val order: Int,
    val showStripeButton: Boolean,
)

/**
 * 2.9.0 moved the tool window from the id `OpenCode`, which other OpenCode plugins register too, to
 * `OpenCodeWebPanel`. IntelliJ keeps a stripe button's place per id, so every project that had
 * placed the old button got the new one appended to the end of its stripe. Once per project, the
 * new button takes the place the old id still holds in the layout.
 *
 * The platform has no public API to read or set a stripe position: the layout and
 * `setToolWindowAnchor(id, anchor, order)` live on the internal `ToolWindowManagerImpl`. They are
 * called reflectively; any mismatch leaves the button where it is.
 * `OpenCodeToolWindowPlacementTest` fails when the platform renames them.
 */
internal object OpenCodeToolWindowPlacement {
    const val LEGACY_ID = "OpenCode"
    private const val PLACE_RESTORED_PROPERTY = "OpenCodeWebPanel.stripePlaceRestored"

    /** Where the new button goes, or null to leave it. */
    fun target(
        legacy: OpenCodeStripePlace?,
        current: OpenCodeStripePlace?,
        legacyIdInUse: Boolean,
    ): OpenCodeStripePlace? {
        // Another plugin's `OpenCode` window owns that place now.
        if (legacyIdInUse || legacy == null || current == null) return null
        if (!legacy.showStripeButton || legacy.order < 0) return null
        if (legacy.anchor == current.anchor && current.order in 0..legacy.order) return null
        return legacy
    }

    /**
     * Runs after registration, once per project; later moves by the user are left alone. Never
     * throws: a failed migration must not break the tool window's registration.
     */
    fun migrateOnce(toolWindow: ToolWindow) {
        runCatching { scheduleMigration(toolWindow) }
            .onFailure {
                thisLogger().info("Could not restore the OpenCode tool window's place", it)
            }
    }

    private fun scheduleMigration(toolWindow: ToolWindow) {
        val project = toolWindow.project
        if (project.isDefault) return
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(PLACE_RESTORED_PROPERTY)) return
        val manager = ToolWindowManager.getInstance(project)
        manager.invokeLater {
            if (project.isDisposed || toolWindow.isDisposed) return@invokeLater
            properties.setValue(PLACE_RESTORED_PROPERTY, true)
            runCatching {
                val place =
                    target(
                        placeOf(manager, LEGACY_ID),
                        placeOf(manager, toolWindow.id),
                        legacyIdInUse = manager.getToolWindow(LEGACY_ID) != null,
                    ) ?: return@runCatching
                manager.javaClass
                    .getMethod(
                        "setToolWindowAnchor",
                        String::class.java,
                        ToolWindowAnchor::class.java,
                        Int::class.javaPrimitiveType,
                    )
                    .invoke(manager, toolWindow.id, place.anchor, place.order)
                thisLogger().info("Moved the OpenCode tool window to the place of its pre-2.9.0 id")
            }
                .onFailure {
                    thisLogger().info("Could not restore the OpenCode tool window's place", it)
                }
        }
    }

    private fun placeOf(manager: ToolWindowManager, id: String): OpenCodeStripePlace? {
        val layout = manager.javaClass.getMethod("getLayout").invoke(manager) ?: return null
        val info =
            layout.javaClass.getMethod("getInfo", String::class.java).invoke(layout, id)
                as? WindowInfo ?: return null
        return OpenCodeStripePlace(info.anchor, info.order, info.isShowStripeButton)
    }
}
