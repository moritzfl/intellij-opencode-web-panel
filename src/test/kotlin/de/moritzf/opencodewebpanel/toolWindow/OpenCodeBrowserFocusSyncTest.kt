package de.moritzf.opencodewebpanel.toolWindow

import java.awt.Component
import javax.swing.JPanel
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
}
