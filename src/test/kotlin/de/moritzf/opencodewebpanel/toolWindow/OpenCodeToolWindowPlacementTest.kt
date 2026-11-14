package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.WindowInfo
import com.intellij.openapi.wm.impl.DesktopLayout
import com.intellij.openapi.wm.impl.ToolWindowManagerImpl
import com.intellij.openapi.wm.impl.WindowInfoImpl
import de.moritzf.opencodewebpanel.features.OPEN_CODE_TOOL_WINDOW_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeToolWindowPlacementTest {
    private val root = "root"
    private val legacyUnderNotifications =
        OpenCodeStripePlace(ToolWindowAnchor.RIGHT, 1, true, root, split = false)
    private val appendedAtTheEnd =
        OpenCodeStripePlace(ToolWindowAnchor.RIGHT, 7, true, root, split = false, last = true)

    @Test
    fun theNewButtonTakesThePlaceTheOldIdHolds() {
        assertEquals(
            legacyUnderNotifications,
            OpenCodeToolWindowPlacement.target(legacyUnderNotifications, appendedAtTheEnd, false),
        )
        val leftStripe = legacyUnderNotifications.copy(anchor = ToolWindowAnchor.LEFT, order = 3)
        assertEquals(
            "Another stripe follows too",
            leftStripe,
            OpenCodeToolWindowPlacement.target(leftStripe, appendedAtTheEnd, false),
        )
    }

    @Test
    fun nothingMovesWithoutAReasonToMove() {
        fun target(legacy: OpenCodeStripePlace?, current: OpenCodeStripePlace?, inUse: Boolean) =
            OpenCodeToolWindowPlacement.target(legacy, current, inUse)

        assertNull("No old place (new project)", target(null, appendedAtTheEnd, false))
        assertNull(
            "Another plugin's OpenCode window owns that place",
            target(legacyUnderNotifications, appendedAtTheEnd, true),
        )
        assertNull(
            "The old button had been removed from the stripe",
            target(
                legacyUnderNotifications.copy(showStripeButton = false),
                appendedAtTheEnd,
                false,
            ),
        )
        assertNull(
            "Already at or above the old place",
            target(legacyUnderNotifications, appendedAtTheEnd.copy(order = 0), false),
        )
        assertNull(
            "Dragged below the old place under 2.9.x: no longer last",
            target(legacyUnderNotifications, appendedAtTheEnd.copy(order = 3, last = false), false),
        )
        assertNull(
            "Dragged to another stripe under 2.9.x",
            target(
                legacyUnderNotifications,
                appendedAtTheEnd.copy(anchor = ToolWindowAnchor.LEFT),
                false,
            ),
        )
        assertNull(
            "A side tool's order counts in another group",
            target(legacyUnderNotifications.copy(split = true), appendedAtTheEnd, false),
        )
        assertNull(
            "Another pane",
            target(legacyUnderNotifications.copy(paneId = "other"), appendedAtTheEnd, false),
        )
    }

    @Test
    fun theLastPlaceCountsEveryEntryOfThatStripe() {
        val infos =
            listOf(
                info("Notifications", ToolWindowAnchor.RIGHT, 0),
                info(OpenCodeToolWindowPlacement.LEGACY_ID, ToolWindowAnchor.RIGHT, 1),
                info("Maven", ToolWindowAnchor.RIGHT, 2),
                info(OPEN_CODE_TOOL_WINDOW_ID, ToolWindowAnchor.RIGHT, 3),
                info("Project", ToolWindowAnchor.LEFT, 9),
            )
        val current = OpenCodeToolWindowPlacement.placeOf(infos, OPEN_CODE_TOOL_WINDOW_ID)!!
        assertTrue(current.last)
        assertEquals("root", current.paneId)
        assertFalse(
            OpenCodeToolWindowPlacement.placeOf(infos, OpenCodeToolWindowPlacement.LEGACY_ID)!!
                .last,
        )
        assertEquals(
            1,
            OpenCodeToolWindowPlacement.target(
                    OpenCodeToolWindowPlacement.placeOf(
                        infos,
                        OpenCodeToolWindowPlacement.LEGACY_ID,
                    ),
                    current,
                    false,
                )
                ?.order,
        )
        val movedUp = infos.map {
            if (it.id == "Maven") info("Maven", ToolWindowAnchor.RIGHT, 4) else it
        }
        assertFalse(OpenCodeToolWindowPlacement.placeOf(movedUp, OPEN_CODE_TOOL_WINDOW_ID)!!.last)
    }

    @Test
    fun theDefaultAnchorIsTheOnePluginXmlDeclares() {
        val pluginXml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()
        val toolWindow = Regex("<toolWindow\\b[^>]*>").find(pluginXml)!!.value
        val anchor = Regex("""\banchor="([^"]+)"""").find(toolWindow)!!.groupValues[1]
        assertEquals(ToolWindowAnchor.fromText(anchor), OpenCodeToolWindowPlacement.DEFAULT_ANCHOR)
    }

    @Test
    fun thePlatformStillHasTheMembersCalledReflectively() {
        ToolWindowManagerImpl::class
            .java
            .getMethod(
                "setToolWindowAnchor",
                String::class.java,
                ToolWindowAnchor::class.java,
                Int::class.javaPrimitiveType,
            )
        val layout = ToolWindowManagerImpl::class.java.getMethod("getLayout")
        assertEquals(DesktopLayout::class.java, layout.returnType)
        val infos = DesktopLayout::class.java.getMethod("getInfos")
        assertTrue(Map::class.java.isAssignableFrom(infos.returnType))
        assertTrue(WindowInfo::class.java.isAssignableFrom(WindowInfoImpl::class.java))
    }

    private fun info(id: String, anchor: ToolWindowAnchor, order: Int): WindowInfo =
        WindowInfoImpl().apply {
            this.id = id
            this.anchor = anchor
            this.order = order
        }
}
