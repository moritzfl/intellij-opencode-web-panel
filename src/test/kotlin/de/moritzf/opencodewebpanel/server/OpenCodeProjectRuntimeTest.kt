package de.moritzf.opencodewebpanel.server

import com.intellij.mock.MockProject
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectDirectoryMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectSettingsState
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

class OpenCodeProjectRuntimeTest {
    companion object {
        @ClassRule @JvmField val application = ApplicationRule()
    }

    @get:Rule val disposable = DisposableRule()

    @Test
    fun absentPanelUsesCurrentBackendAndConfiguredDirectory() {
        val project = project()
        val calls = mutableListOf<String>()
        var active: (() -> Boolean)? = null
        fun backend(name: String) = backend { args ->
            assertSame(project, args[0])
            assertEquals("/configured/project", args[1])
            @Suppress("UNCHECKED_CAST")
            active = args[2] as () -> Boolean
            calls += name
        }
        var selected = backend("native")
        val runtime = OpenCodeProjectRuntime(project) { selected }
        runtime.restart()
        selected = backend("sandbox")
        runtime.restart()
        assertEquals(listOf("native", "sandbox"), calls)
        val callback = requireNotNull(active)
        assertTrue(callback())
        Disposer.dispose(project)
        assertFalse(callback())
        runtime.restart()
        assertEquals(2, calls.size)
    }

    @Test
    fun missingOrDisposedPanelFallsBackButSupersededOwnerCannotUnbindCurrentPanel() {
        val calls = mutableListOf<String>()
        val runtime = OpenCodeProjectRuntime(project()) { backend { calls += "backend" } }
        val old = Disposer.newDisposable().also { Disposer.register(disposable.disposable, it) }
        val current = Disposer.newDisposable().also { Disposer.register(disposable.disposable, it) }
        runtime.bindPanelRestart(old) {
            calls += "old"
            true
        }
        runtime.bindPanelRestart(current) {
            calls += "current"
            true
        }
        Disposer.dispose(old)
        runtime.restart()
        assertEquals(listOf("current"), calls)
        Disposer.dispose(current)
        runtime.restart()
        assertEquals(listOf("current", "backend"), calls)
        runtime.bindPanelRestart(disposable.disposable) { false }
        runtime.restart()
        assertEquals(listOf("current", "backend", "backend"), calls)
    }

    private fun project(): MockProject =
        MockProject(null, disposable.disposable).apply {
            registerService(
                OpenCodeProjectSettingsState::class.java,
                OpenCodeProjectSettingsState().apply {
                    portImportedFromApplication = true
                    projectDirectoryMode = OpenCodeProjectDirectoryMode.CUSTOM.name
                    openCodeProjectDirectory = "/configured/project"
                },
            )
        }

    private fun backend(onRestart: (Array<out Any?>) -> Unit): OpenCodeServerBackend =
        Proxy.newProxyInstance(
            OpenCodeServerBackend::class.java.classLoader,
            arrayOf(OpenCodeServerBackend::class.java),
        ) { _, method, args ->
            check(method.name == "restartServer") { "Unexpected backend call: $method" }
            onRestart(args)
            null
        } as OpenCodeServerBackend
}
