package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowInfo
import com.intellij.openapi.wm.safeToolWindowPaneId

/**
 * A stripe button's place in a project's tool window layout. [last]: no other entry of the same
 * pane and anchor, registered or not, has a higher order, which is where IntelliJ appends a new id.
 */
internal data class OpenCodeStripePlace(
    val anchor: ToolWindowAnchor,
    val order: Int,
    val showStripeButton: Boolean,
    val paneId: String,
    val split: Boolean,
    val last: Boolean = false,
)

/**
 * 2.9.0 moved the tool window from the id `OpenCode`, which other OpenCode plugins register too, to
 * `OpenCodeWebPanel`. IntelliJ keeps a stripe button's place per id, so every project that had
 * placed the old button got the new one appended to the end of its stripe. Once per project, the
 * new button takes the place the old id still holds in the layout, unless it no longer sits where
 * IntelliJ appended it: a place the user chose under 2.9.x stays.
 *
 * The platform has no public API to read or set a stripe position: the layout and
 * `setToolWindowAnchor(id, anchor, order)` live on the internal `ToolWindowManagerImpl`. They are
 * called reflectively; any mismatch leaves the button where it is.
 * `OpenCodeToolWindowPlacementTest` fails when the platform renames them.
 */
internal object OpenCodeToolWindowPlacement {
    const val LEGACY_ID = "OpenCode"
    /** plugin.xml `anchor`: where IntelliJ put the button for the new id. */
    val DEFAULT_ANCHOR: ToolWindowAnchor = ToolWindowAnchor.RIGHT
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
        // Moved since 2.9.0: the user chose this place.
        if (current.anchor != DEFAULT_ANCHOR || !current.last) return null
        // Orders count within one pane, and side tools form their own group on a stripe.
        if (legacy.paneId != current.paneId || legacy.split != current.split) return null
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
                val infos = layoutInfos(manager)
                val place =
                    target(
                        placeOf(infos, LEGACY_ID),
                        placeOf(infos, toolWindow.id),
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

    private fun layoutInfos(manager: ToolWindowManager): Collection<WindowInfo> {
        val layout = manager.javaClass.getMethod("getLayout").invoke(manager) ?: return emptyList()
        val infos = layout.javaClass.getMethod("getInfos").invoke(layout) as? Map<*, *>
        return infos?.values?.filterIsInstance<WindowInfo>().orEmpty()
    }

    internal fun placeOf(infos: Collection<WindowInfo>, id: String): OpenCodeStripePlace? {
        val info = infos.firstOrNull { it.id == id } ?: return null
        val paneId = info.safeToolWindowPaneId
        val last =
            infos
                .filter { it.anchor == info.anchor && it.safeToolWindowPaneId == paneId }
                .all { it.order <= info.order }
        return OpenCodeStripePlace(
            info.anchor,
            info.order,
            info.isShowStripeButton,
            paneId,
            info.isSplit,
            last,
        )
    }
}
