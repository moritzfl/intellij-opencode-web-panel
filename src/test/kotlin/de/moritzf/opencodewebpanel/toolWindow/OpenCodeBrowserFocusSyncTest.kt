package de.moritzf.opencodewebpanel.toolWindow

import java.awt.Component
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeBrowserFocusSyncTest {

    private class Recorder {
        val calls = mutableListOf<Boolean>()
    }

    private fun sync(
        component: Component,
        focusOwner: Component?,
        active: Boolean = true,
        recorder: Recorder,
    ) = OpenCodeBrowserFocusSync(
        component = { component },
        isActive = { active },
        setBrowserFocus = { recorder.calls.add(it) },
        focusOwner = { focusOwner },
        runOnUiThread = { it.run() },
    )

    @Test
    fun reassertsChromiumFocusWhenBrowserComponentOwnsFocus() {
        val component = JPanel()
        val recorder = Recorder()

        sync(component, focusOwner = component, recorder = recorder).reassertIfFocused()

        assertEquals(listOf(true), recorder.calls)
    }

    @Test
    fun reassertsChromiumFocusWhenFocusOwnerIsDescendantOfBrowserComponent() {
        val component = JPanel()
        val child = JPanel()
        component.add(child)
        val recorder = Recorder()

        sync(component, focusOwner = child, recorder = recorder).reassertIfFocused()

        assertEquals(listOf(true), recorder.calls)
    }

    @Test
    fun doesNothingWhenFocusIsElsewhere() {
        val recorder = Recorder()

        sync(JPanel(), focusOwner = JPanel(), recorder = recorder).reassertIfFocused()

        assertEquals(emptyList<Boolean>(), recorder.calls)
    }

    @Test
    fun doesNothingWithoutFocusOwner() {
        val recorder = Recorder()

        sync(JPanel(), focusOwner = null, recorder = recorder).reassertIfFocused()

        assertEquals(emptyList<Boolean>(), recorder.calls)
    }

    @Test
    fun doesNothingWhenDisposed() {
        val component = JPanel()
        val recorder = Recorder()

        sync(component, focusOwner = component, active = false, recorder = recorder).reassertIfFocused()

        assertEquals(emptyList<Boolean>(), recorder.calls)
    }

    @Test
    fun queriesTheCoordinatorForTheCurrentPlacementBeforeSyncingFocus() {
        onEdt {
            val component = JPanel()
            val first = FocusHost()
            val second = FocusHost()
            val coordinator = OpenCodePanelCoordinator(
                panelComponent = component,
                disposePanel = {},
                parkingContainer = JPanel(),
            )
            coordinator.registerPlacement("first", JPanel(), JPanel(), first)
            coordinator.registerPlacement("second", JPanel(), JPanel(), second)
            val recorder = Recorder()
            val sync = OpenCodeBrowserFocusSync(
                component = { component },
                isActive = { coordinator.isActive(component) },
                setBrowserFocus = { recorder.calls.add(it) },
                focusOwner = { component },
                runOnUiThread = { it.run() },
            )

            coordinator.place("first")
            sync.reassertIfFocused()
            coordinator.place("second")
            sync.reassertIfFocused()

            assertEquals(listOf(true, true), recorder.calls)
            assertEquals(1, first.activeCalls)
            assertEquals(1, second.activeCalls)
        }
    }

    private class FocusHost : OpenCodePanelHost {
        override val project: com.intellij.openapi.project.Project
            get() = error("Test host project is not used")

        var activeCalls = 0

        override fun isDisposed(): Boolean = false

        override fun isActive(component: javax.swing.JComponent): Boolean {
            activeCalls++
            return true
        }

        override fun replacePanel() = Unit

        override fun showFailure() = Unit
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }
}
