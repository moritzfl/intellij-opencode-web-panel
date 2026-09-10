package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import javax.swing.JButton
import javax.swing.JLabel
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

    @Test
    fun restartingStateShowsUpgradeStage() {
        onEdt {
            val card = OpenCodeIdleCard {}
            card.show(OpenCodeServerLifecycleState.RESTARTING, stage = "Downloading 50%")
            assertTrue(labels(card).any { it == "Downloading 50%" })
        }
    }

    private fun labels(card: OpenCodeIdleCard): List<String> {
        val found = mutableListOf<String>()
        fun walk(container: java.awt.Container) {
            container.components.forEach { child ->
                if (child is JLabel) found += child.text.orEmpty()
                if (child is java.awt.Container) walk(child)
            }
        }
        walk(card.component)
        return found
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
