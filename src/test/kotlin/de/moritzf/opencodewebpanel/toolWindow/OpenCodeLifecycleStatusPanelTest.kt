package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ui.components.JBLabel
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeLifecycleStatusPanelTest {
    @Test
    fun repeatedOpeningUpdatesDoNotRequestParentRelayout() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel(onRetry = {})

            assertTrue(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = true))
            assertFalse(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = true))
        }
    }

    @Test
    fun hidingTheStripAfterThePagePaintsRequestsParentRelayout() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel(onRetry = {})

            assertTrue(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = true))
            assertTrue(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = false))
            assertFalse(panel.update(OpenCodeServerLifecycleState.RUNNING, pageOpening = false))
        }
    }

    @Test
    fun stoppedHidesTheStripAndRestartingKeepsIt() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel(onRetry = {})

            assertTrue(panel.update(OpenCodeServerLifecycleState.STARTING))
            assertTrue(panel.component.isVisible)
            assertTrue(panel.update(OpenCodeServerLifecycleState.STOPPED))
            assertFalse(panel.component.isVisible)
            assertTrue(panel.update(OpenCodeServerLifecycleState.RESTARTING, stage = "Upgrading OpenCode…"))
            assertTrue(panel.component.isVisible)
        }
    }

    @Test
    fun cancelAndViewLogAppearWhileStarting() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel(onRetry = {})
            assertTrue(panel.update(OpenCodeServerLifecycleState.STARTING))
            assertTrue(panel.component.isVisible)
            assertTrue(panel.update(OpenCodeServerLifecycleState.FAILED, cancelled = true))
            assertTrue(panel.component.isVisible)
        }
    }

    @Test
    fun statusTextDoesNotOverlapButtonsWhenNarrow() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel(onRetry = {})
            panel.update(
                OpenCodeServerLifecycleState.RESTARTING,
                stage = "Creating sandbox…",
                elapsedMillis = 11_000,
            )
            val strip = panel.component
            strip.setSize(360, strip.preferredSize.height.coerceAtLeast(32))
            strip.doLayout()
            val label = strip.components.filterIsInstance<JBLabel>().single()
            val buttonBar = strip.components.filterIsInstance<JPanel>().single { it.componentCount > 0 }
            assertTrue(
                "status text must yield to Cancel / View log",
                label.bounds.x + label.bounds.width <= buttonBar.bounds.x,
            )
        }
    }

    @Test
    fun showingTheRetryButtonRequestsParentRelayout() {
        onEdt {
            val panel = OpenCodeLifecycleStatusPanel(onRetry = {})

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
