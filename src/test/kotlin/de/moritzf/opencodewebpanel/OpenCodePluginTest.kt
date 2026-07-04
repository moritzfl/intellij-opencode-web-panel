package de.moritzf.opencodewebpanel

import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeWebToolWindowFactoryImpl
import com.intellij.openapi.project.DumbAware
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class OpenCodePluginTest : BasePlatformTestCase() {

    fun testToolWindowFactoryIsAvailableDuringIndexing() {
        assertTrue(DumbAware::class.java.isAssignableFrom(OpenCodeWebToolWindowFactoryImpl::class.java))
    }

    fun testSharedOpenCodeServerManagerIsApplicationScoped() {
        assertSame(SharedOpenCodeServerManager.getInstance(), SharedOpenCodeServerManager.getInstance())
    }

    fun testPluginDescriptorRegistersRightSidebarToolWindowAndSharedServerManager() {
        val pluginXml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()

        assertTrue(pluginXml.contains("<id>de.moritzf.opencodewebpanel</id>"))
        assertTrue(pluginXml.contains("<name>OpenCode Web Panel</name>"))
        assertTrue(pluginXml.contains("id=\"OpenCode Web Panel\""))
        assertTrue(pluginXml.contains("anchor=\"right\""))
        assertTrue(pluginXml.contains("icon=\"/icons/opencode.svg\""))
        assertTrue(pluginXml.contains("factoryClass=\"de.moritzf.opencodewebpanel.toolWindow.OpenCodeWebToolWindowFactoryImpl\""))
        assertTrue(pluginXml.contains("applicationService"))
        assertTrue(pluginXml.contains("serviceImplementation=\"de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager\""))
        assertTrue(pluginXml.contains("applicationConfigurable"))
        assertTrue(pluginXml.contains("instance=\"de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable\""))
        assertTrue(pluginXml.contains("projectConfigurable"))
        assertTrue(pluginXml.contains("instance=\"de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsConfigurable\""))
        assertTrue(pluginXml.contains("notificationGroup"))
        assertTrue(pluginXml.contains("displayType=\"BALLOON\""))
        assertFalse(pluginXml.contains("postStartupActivity"))
    }

    fun testSettingsConfigurableIsRegistered() {
        val pluginXml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()

        assertTrue(pluginXml.contains("displayName=\"OpenCode Web Panel\""))
        assertEquals("OpenCode Web Panel", OpenCodeSettingsConfigurable().displayName)
        assertEquals("OpenCode Web Panel", OpenCodeProjectSettingsConfigurable(project).displayName)
    }

    fun testSharedServerManagerStopsServerAndClearsLifecycleState() {
        val service = SharedOpenCodeServerManager.getInstance()
        val process = RecordingProcess()
        val future = RecordingFuture()
        service.setServerProcess(process)
        service.setServerRunning(true)
        service.setServerUrl("http://127.0.0.1:60482")
        service.setServerPassword("secret-password")
        service.setCheckScheduledFuture(future)

        service.stopServer()

        assertTrue(process.destroyed)
        assertTrue(future.cancelled)
        assertFalse(service.isServerRunning())
        assertEquals(OpenCodeServerLifecycleState.STOPPED, service.getLifecycleState())
        assertNull(service.getServerProcess())
        assertNull(service.getServerUrl())
        assertNull(service.getServerPassword())
        assertNull(service.getCheckScheduledFuture())
    }

    fun testSharedServerManagerForceKillsStubbornServerProcess() {
        val service = SharedOpenCodeServerManager.getInstance()
        val process = StubbornProcess()
        service.setServerProcess(process)
        service.setServerRunning(true)
        service.setServerUrl("http://127.0.0.1:60482")
        service.setServerPassword("secret-password")

        service.stopServer()

        assertTrue(process.destroyed)
        assertTrue(process.forceDestroyed)
        assertFalse(process.isAlive)
        assertNull(service.getServerProcess())
    }

    fun testSharedServerManagerTracksManualRunningLifecycleState() {
        val service = SharedOpenCodeServerManager.getInstance()

        service.stopServer()
        assertEquals(OpenCodeServerLifecycleState.STOPPED, service.getLifecycleState())

        service.setServerRunning(true)
        assertEquals(OpenCodeServerLifecycleState.RUNNING, service.getLifecycleState())

        service.setServerRunning(false)
        assertEquals(OpenCodeServerLifecycleState.STOPPED, service.getLifecycleState())
    }

    override fun tearDown() {
        try {
            SharedOpenCodeServerManager.getInstance().stopServer()
        } finally {
            super.tearDown()
        }
    }

    private class RecordingProcess : Process() {
        var destroyed = false
            private set
        private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("Process is still alive")
            return 0
        }

        override fun destroy() {
            destroyed = true
            alive = false
        }

        override fun isAlive(): Boolean = alive
    }

    private class StubbornProcess : Process() {
        var destroyed = false
            private set
        var forceDestroyed = false
            private set
        private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return !alive
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("Process is still alive")
            return 0
        }

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            forceDestroyed = true
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    private class RecordingFuture : ScheduledFuture<Unit> {
        var cancelled = false
            private set

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled = true
            return true
        }

        override fun isCancelled(): Boolean = cancelled

        override fun isDone(): Boolean = cancelled

        override fun get(): Unit = Unit

        override fun get(timeout: Long, unit: TimeUnit): Unit = Unit

        override fun getDelay(unit: TimeUnit): Long = 0

        override fun compareTo(other: java.util.concurrent.Delayed): Int = 0
    }
}
