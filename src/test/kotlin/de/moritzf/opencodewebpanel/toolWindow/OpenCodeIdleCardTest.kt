package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import javax.swing.JButton
import javax.swing.SwingUtilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeIdleCardTest {
    @Test
    fun stoppedStateShowsStartButton() {
        onEdt {
            val card = OpenCodeIdleCard {}
            card.show(OpenCodeServerLifecycleState.STOPPED)
            val start = startButton(card)
            assertTrue(start.isVisible)
            assertTrue(start.isEnabled)
        }
    }

    @Test
    fun restartingStateHidesStartButton() {
        onEdt {
            val card = OpenCodeIdleCard {}
            card.show(OpenCodeServerLifecycleState.RESTARTING)
            assertFalse(startButton(card).isVisible)
        }
    }

    private fun startButton(card: OpenCodeIdleCard): JButton {
        fun walk(container: java.awt.Container): JButton? {
            container.components.forEach { child ->
                if (child is JButton) return child
                if (child is java.awt.Container) walk(child)?.let { return it }
            }
            return null
        }
        return checkNotNull(walk(card.component))
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait { block() }
        }
    }
}
