package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeStartupProgress
import javax.swing.JTextArea
import javax.swing.JProgressBar
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

    @Test
    fun startupShowsLiveActivityAndActionsWithoutMarkupFromCliOutput() {
        onEdt {
            var cancelled = false
            var logOpened = false
            val card = OpenCodeIdleCard(onCancel = { cancelled = true }, onViewLog = { logOpened = true }, onStart = {})
            card.show(OpenCodeServerLifecycleState.STARTING, progress = OpenCodeStartupProgress(
                "Creating sandbox…", "Downloading images may take several minutes.", 95_000, 95_000, 2_000,
                600_000, listOf("<html>CLI output must stay plain text", "Downloaded image layer"),
            ))
            val parts = descendants(card.component)
            assertTrue(parts.filterIsInstance<JTextArea>().any { it.text.contains("Downloaded image layer") })
            assertTrue(parts.filterIsInstance<JTextArea>().any { it.text.contains("several minutes") })
            assertTrue(parts.filterIsInstance<JProgressBar>().single().isIndeterminate)
            parts.filterIsInstance<JButton>().single { it.text == "Cancel" }.doClick()
            parts.filterIsInstance<JButton>().single { it.text == "Open full log" }.doClick()
            assertTrue(cancelled)
            assertTrue(logOpened)
            assertTrue(labels(card).any { it.startsWith("Elapsed 1m 35s") })
            card.stopProgressAnimation()
            assertFalse(parts.filterIsInstance<JProgressBar>().single().isIndeterminate)
            card.show(OpenCodeServerLifecycleState.STARTING, logAvailable = false)
            assertFalse(parts.filterIsInstance<JButton>().single { it.text == "Open full log" }.isEnabled)
            card.show(OpenCodeServerLifecycleState.STOPPED)
            assertFalse(parts.filterIsInstance<JProgressBar>().single().isVisible)
        }
    }

    @Test
    fun progressCardUsesAvailableWidthAndKeepsActionsWithinNarrowPanels() {
        onEdt {
            for (width in listOf(320, 720)) {
                val card = OpenCodeIdleCard {}
                card.show(OpenCodeServerLifecycleState.STARTING)
                card.component.setSize(width, 600)
                fun layout(container: java.awt.Container) {
                    container.doLayout()
                    container.components.filterIsInstance<java.awt.Container>().forEach(::layout)
                }
                layout(card.component)
                val parts = descendants(card.component)
                val log = parts.filterIsInstance<JTextArea>().single { it.rows == 7 }
                assertTrue("Log must use available width", log.width >= width.coerceAtMost(620) - 64)
                for (button in parts.filterIsInstance<JButton>().filter { it.isVisible }) {
                    val bounds = SwingUtilities.convertRectangle(button.parent, button.bounds, card.component)
                    assertTrue("${button.text} must fit", bounds.x >= 0 && bounds.x + bounds.width <= width)
                }
            }
        }
    }

    private fun descendants(container: java.awt.Container): List<java.awt.Component> =
        container.components.flatMap { listOf(it) + if (it is java.awt.Container) descendants(it) else emptyList() }

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
