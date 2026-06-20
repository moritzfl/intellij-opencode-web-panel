package com.github.xausky.opencodewebui

import com.github.xausky.opencodewebui.toolWindow.OpenCodeWebToolWindowFactory
import com.github.xausky.opencodewebui.toolWindow.SharedOpenCodeServerManager
import com.intellij.openapi.project.DumbAware
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class OpenCodePluginTest : BasePlatformTestCase() {

    fun testToolWindowFactoryIsAvailableDuringIndexing() {
        assertTrue(DumbAware::class.java.isAssignableFrom(OpenCodeWebToolWindowFactory::class.java))
    }

    fun testSharedOpenCodeServerManagerIsApplicationScoped() {
        assertSame(SharedOpenCodeServerManager.getInstance(), SharedOpenCodeServerManager.getInstance())
    }

    fun testPluginDescriptorRegistersRightSidebarToolWindowAndSharedServerManager() {
        val pluginXml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()

        assertTrue(pluginXml.contains("id=\"OpenCodeWeb\""))
        assertTrue(pluginXml.contains("anchor=\"right\""))
        assertTrue(pluginXml.contains("icon=\"/icons/opencode.svg\""))
        assertTrue(pluginXml.contains("displayName=\"OpenCodeWeb\""))
        assertTrue(pluginXml.contains("factoryClass=\"com.github.xausky.opencodewebui.toolWindow.OpenCodeWebToolWindowFactory\""))
        assertTrue(pluginXml.contains("applicationService"))
        assertTrue(pluginXml.contains("serviceImplementation=\"com.github.xausky.opencodewebui.toolWindow.SharedOpenCodeServerManager\""))
        assertFalse(pluginXml.contains("postStartupActivity"))
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
        assertNull(service.getServerProcess())
        assertNull(service.getServerUrl())
        assertNull(service.getServerPassword())
        assertNull(service.getCheckScheduledFuture())
    }

    override fun tearDown() {
        try {
            SharedOpenCodeServerManager.getInstance().stopServer()
        } finally {
            super.tearDown()
        }
    }

    override fun getTestDataPath() = "src/test/testData/rename"

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
