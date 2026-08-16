package de.moritzf.opencodewebpanel.jcef

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback HTTP stand-in for `opencode serve`: Basic auth on every route, a tiny HTML app,
 * and a `/global/event` SSE that can stall. Not OpenCode — enough to exercise JCEF + our
 * request/watchdog scripts the same way the panel talks to a real server.
 */
internal class OpenCodeJcefTestServer(
    private val username: String = "opencode",
    private val password: String = "testpw123",
    private val stallEventStream: Boolean = false,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "OpenCode-JCEF-Test-Server").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(true)
    val requestPaths = CopyOnWriteArrayList<String>()
    val unauthorizedCount = AtomicInteger()
    val eventStreamCount = AtomicInteger()

    val port: Int = serverSocket.localPort
    val origin: String = "http://127.0.0.1:$port"
    val expectedAuthorization: String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

    init {
        executor.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    executor.execute { handle(socket) }
                } catch (_: Exception) {
                    if (!running.get()) return@execute
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val headerBytes = readHeaders(input) ?: return
            val headers = String(headerBytes, StandardCharsets.ISO_8859_1)
            val requestLine = headers.lineSequence().firstOrNull().orEmpty()
            val path = requestLine.substringAfter(' ', "").substringBefore(' ').substringBefore('?')
            requestPaths.add(path)
            val authorization = headers.lineSequence()
                .firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
            val output = client.getOutputStream()
            if (authorization != expectedAuthorization) {
                unauthorizedCount.incrementAndGet()
                writeResponse(output, 401, "text/plain", "unauthorized")
                return
            }
            when {
                path == "/global/event" || path == "/event" || path == "/api/event" -> {
                    eventStreamCount.incrementAndGet()
                    writeSse(output)
                }
                path == "/" || path == "/session" || path.startsWith("/server/") -> {
                    writeResponse(output, 200, "text/html; charset=utf-8", APP_HTML)
                }
                path == "/api/health" || path == "/global/health" -> {
                    writeResponse(output, 200, "application/json", """{"healthy":true,"version":"1.18.10"}""")
                }
                else -> writeResponse(output, 404, "text/plain", "missing")
            }
        }
    }

    private fun writeSse(output: OutputStream) {
        val preamble = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n"
        output.write(preamble.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        if (stallEventStream) {
            Thread.sleep(120_000)
            return
        }
        repeat(6) { index ->
            if (!running.get()) return
            val payload = """{"directory":"/tmp","payload":{"id":"evt_$index","type":"server.heartbeat","properties":{}}}"""
            output.write("data: $payload\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
            Thread.sleep(10_000)
        }
    }

    private fun writeResponse(output: OutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else if (status == 401) "Unauthorized" else "Not Found"
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readHeaders(input: java.io.InputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        var last = 0
        var count = 0
        while (true) {
            val next = input.read()
            if (next < 0) return if (buffer.size() == 0) null else buffer.toByteArray()
            buffer.write(next)
            if (last == '\r'.code && next == '\n'.code) {
                count++
                if (count == 2) return buffer.toByteArray()
            } else if (next != '\r'.code) {
                count = 0
            }
            last = next
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    companion object {
        const val MARKER_ID = "opencode-jcef-harness"
        val APP_HTML = """
            <!doctype html>
            <html><head><title>OpenCode</title></head>
            <body>
              <div id="$MARKER_ID">ready</div>
              <script>
                window.__opencodeJcefLoaded = true;
                window.__opencodeJcefFetchIsFunction = typeof fetch === 'function';
                window.__opencodeJcefWatchdog = !!window.__opencodeIntellijEventWatchdogInstalled;
              </script>
            </body></html>
        """.trimIndent()
    }
}
