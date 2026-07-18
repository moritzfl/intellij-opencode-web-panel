package de.moritzf.opencodewebpanel.toolWindow

import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeComponentSizeNudgerTest {
    @Test
    fun overlappingNudgesGrowOnceAndRestoreOriginalBounds() {
        onEdt {
            val panel = JPanel().apply { bounds = Rectangle(10, 20, 300, 200) }
            val restores = mutableListOf<() -> Unit>()
            var grows = 0
            var restored = 0
            val nudger = OpenCodeComponentSizeNudger(
                component = panel,
                isActive = { true },
                scheduleRestore = restores::add,
                afterGrow = { grows++ },
                afterRestore = { restored++ },
            )

            assertTrue(nudger.nudge())
            assertFalse(nudger.nudge())
            assertEquals(Rectangle(10, 20, 301, 200), panel.bounds)
            assertEquals(1, restores.size)

            restores.single()()

            assertEquals(Rectangle(10, 20, 300, 200), panel.bounds)
            assertEquals(1, grows)
            assertEquals(1, restored)
        }
    }

    @Test
    fun parentResizeIsNeverOverwrittenByDelayedRestore() {
        onEdt {
            val panel = JPanel().apply { bounds = Rectangle(0, 0, 300, 200) }
            val restores = mutableListOf<() -> Unit>()
            var restored = 0
            val nudger = OpenCodeComponentSizeNudger(
                component = panel,
                isActive = { true },
                scheduleRestore = restores::add,
                afterGrow = {},
                afterRestore = { restored++ },
            )

            assertTrue(nudger.nudge())
            panel.bounds = Rectangle(0, 0, 640, 480)
            restores.removeFirst()()

            assertEquals(Rectangle(0, 0, 640, 480), panel.bounds)
            assertEquals(0, restored)
            assertTrue(nudger.nudge())
        }
    }

    @Test
    fun zeroSizedOrInactiveComponentsAreNotNudged() {
        onEdt {
            var active = false
            val panel = JPanel().apply { bounds = Rectangle(0, 0, 300, 200) }
            val restores = mutableListOf<() -> Unit>()
            val nudger = OpenCodeComponentSizeNudger(
                component = panel,
                isActive = { active },
                scheduleRestore = restores::add,
                afterGrow = {},
                afterRestore = {},
            )

            assertFalse(nudger.nudge())
            active = true
            panel.setSize(0, 200)
            assertFalse(nudger.nudge())
            assertTrue(restores.isEmpty())
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait { block() }
        }
    }
}
