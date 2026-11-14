package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpServer
import de.moritzf.opencodewebpanel.configuration.OpenCodeBinaryMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the Host CLI start loop end to end, with a local endpoint standing in for OpenCode's
 * health check. The test logger turns any `Logger.error` into a failure: the plugin's error handler
 * would have offered it to the user as a Marketplace exception report.
 */
class SharedOpenCodeServerManagerStartTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private var health: HttpServer? = null

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("ocwp-start").toRealPath()
    }

    override fun tearDown() {
        try {
            OpenCodeServerBackendRegistry.getInstance().stopAllNativeBackends()
            health?.stop(0)
            directory.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testLauncherThatExitsBeforeTheServerPrintsItsAddressStillStarts() {
        val url = startHealthServer()
        useExecutable("opencode")
        // Windows semantics: the launcher has exited, but the server it spawned holds the output
        // pipe and prints its address after the first poll. A real script cannot model this on
        // Unix, where the JDK closes the pipe as soon as the launched process exits.
        val output = HeldOpenOutput()
        val launcher = ExitedLauncher(output)
        val manager = SharedOpenCodeServerManager(directory.toString()) { launcher }
        // Disposed in reverse: stop the manager before its output ends.
        Disposer.register(testRootDisposable) { output.close() }
        Disposer.register(testRootDisposable, manager)
        AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(
                { output.print("opencode server listening on $url") },
                1_500,
                TimeUnit.MILLISECONDS,
            )

        assertEquals("started", awaitStart(manager))
        assertEquals(url, manager.getServerUrl())
    }

    private fun useExecutable(path: String) {
        val settings =
            OpenCodeSettingsState().apply {
                binaryMode = OpenCodeBinaryMode.CUSTOM.name
                binaryPath = path
                enableServerLogs = false
                waitForIntellijMcpServer = false
            }
        ApplicationManager.getApplication()
            .replaceService(OpenCodeSettingsState::class.java, settings, testRootDisposable)
    }

    private fun awaitStart(manager: SharedOpenCodeServerManager): String {
        val result = AtomicReference<String>()
        ApplicationManager.getApplication().executeOnPooledThread {
            manager.ensureStarted(
                project,
                directory.toString(),
                { true },
                { result.set("started") },
                { result.set("failed") },
            )
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (result.get() == null && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        return result.get() ?: "no result within 30s"
    }

    /** Blocks like a pipe whose writer is still alive, until [close]. */
    private class HeldOpenOutput : InputStream() {
        private val lines = LinkedBlockingQueue<ByteArray>()
        private var current = ByteArray(0)
        private var position = 0

        fun print(line: String) {
            lines.put("$line\n".toByteArray())
        }

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position == current.size) {
                current = lines.take()
                position = 0
                if (current.isEmpty()) return -1
            }
            val count = minOf(len, current.size - position)
            System.arraycopy(current, position, b, off, count)
            position += count
            return count
        }

        override fun close() {
            lines.put(ByteArray(0))
        }
    }

    private class ExitedLauncher(private val output: InputStream) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = output

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun isAlive(): Boolean = false

        override fun destroy() = Unit
    }

    private fun startHealthServer(): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path in setOf("/api/health", "/global/health")) {
                val body = """{"healthy":true}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
        server.start()
        health = server
        return "http://127.0.0.1:${server.address.port}"
    }
}
