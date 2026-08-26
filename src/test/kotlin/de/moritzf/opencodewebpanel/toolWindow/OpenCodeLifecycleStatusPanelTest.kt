package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import javax.swing.SwingUtilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeLifecycleStatusPanelTest {
    @Test
    fun repeatedOpeningUpdatesDoNotRequestParentRelayout() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel {}

            assertTrue(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = true))
            assertFalse(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = true))
        }
    }

    @Test
    fun hidingTheStripAfterThePagePaintsRequestsParentRelayout() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel {}

            assertTrue(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = true))
            assertTrue(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = false))
            assertFalse(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = false))
        }
    }

    @Test
    fun stoppedAndRestartingHideTheStrip() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel {}

            assertTrue(panel.update(OpenCodeServerLifecycleState.STARTING))
            assertTrue(panel.component.isVisible)
            assertTrue(panel.update(OpenCodeServerLifecycleState.STOPPED))
            assertFalse(panel.component.isVisible)
            panel.update(OpenCodeServerLifecycleState.RESTARTING)
            assertFalse(panel.component.isVisible)
        }
    }

    @Test
    fun showingTheRetryButtonRequestsParentRelayout() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel {}

            assertTrue(panel.update(OpenCodeServerLifecycleState.STARTING))
            assertTrue(panel.update(OpenCodeServerLifecycleState.FAILED))
            assertFalse(panel.update(OpenCodeServerLifecycleState.FAILED))
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
