package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.WindowInfo
import com.intellij.openapi.wm.impl.DesktopLayout
import com.intellij.openapi.wm.impl.ToolWindowManagerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeToolWindowPlacementTest {
    private val legacyUnderNotifications = OpenCodeStripePlace(ToolWindowAnchor.RIGHT, 1, true)
    private val appendedAtTheEnd = OpenCodeStripePlace(ToolWindowAnchor.RIGHT, 7, true)

    @Test
    fun theNewButtonTakesThePlaceTheOldIdHolds() {
        assertEquals(
            legacyUnderNotifications,
            OpenCodeToolWindowPlacement.target(legacyUnderNotifications, appendedAtTheEnd, false),
        )
        val leftStripe = OpenCodeStripePlace(ToolWindowAnchor.LEFT, 3, true)
        assertEquals(
            "Another stripe follows too",
            leftStripe,
            OpenCodeToolWindowPlacement.target(leftStripe, appendedAtTheEnd, false),
        )
    }

    @Test
    fun nothingMovesWithoutAReasonToMove() {
        assertNull(
            "No old place (new project, or the old version never ran here)",
            OpenCodeToolWindowPlacement.target(null, appendedAtTheEnd, false),
        )
        assertNull(
            "Another plugin's OpenCode window owns that place",
            OpenCodeToolWindowPlacement.target(legacyUnderNotifications, appendedAtTheEnd, true),
        )
        assertNull(
            "The old button had been removed from the stripe",
            OpenCodeToolWindowPlacement.target(
                legacyUnderNotifications.copy(showStripeButton = false),
                appendedAtTheEnd,
                false,
            ),
        )
        assertNull(
            "Already at or above the old place",
            OpenCodeToolWindowPlacement.target(
                legacyUnderNotifications,
                legacyUnderNotifications.copy(order = 0),
                false,
            ),
        )
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
        val info = DesktopLayout::class.java.getMethod("getInfo", String::class.java)
        assertTrue(WindowInfo::class.java.isAssignableFrom(info.returnType))
    }
}
