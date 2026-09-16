package de.moritzf.opencodewebpanel.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OpenCodeGlobalEventStreamTest {

    @Test
    fun sseBlockDataExtractsSingleDataLine() {
        assertEquals("{\"a\":1}", OpenCodeGlobalEventStream.sseBlockData("data: {\"a\":1}"))
    }

    @Test
    fun sseBlockDataJoinsMultipleDataLines() {
        assertEquals(
            "{\"a\":\n1}",
            OpenCodeGlobalEventStream.sseBlockData("data: {\"a\":\ndata: 1}"),
        )
    }

    @Test
    fun sseBlockDataIgnoresNonDataFields() {
        assertEquals(
            "payload",
            OpenCodeGlobalEventStream.sseBlockData(": comment\nevent: message\nid: 7\ndata: payload"),
        )
    }

    @Test
    fun sseBlockDataIsNullWithoutData() {
        assertNull(OpenCodeGlobalEventStream.sseBlockData(""))
        assertNull(OpenCodeGlobalEventStream.sseBlockData(": keep-alive"))
        assertNull(OpenCodeGlobalEventStream.sseBlockData("event: message\nid: 7"))
        assertNull(OpenCodeGlobalEventStream.sseBlockData("data:"))
        assertNull(OpenCodeGlobalEventStream.sseBlockData("data:   "))
    }

    @Test
    fun parseGlobalEventReadsDirectoryTypeIdAndProperties() {
        val event = OpenCodeGlobalEventStream.parseGlobalEvent(
            """
            {
              "directory": "/tmp/project",
              "payload": {
                "id": "evt_1",
                "type": "session.status",
                "properties": {"sessionID": "ses_1", "status": {"type": "busy"}}
              }
            }
            """.trimIndent(),
        )!!

        assertEquals("/tmp/project", event.directory)
        assertEquals("session.status", event.type)
        assertEquals("evt_1", event.recordId)
        assertEquals("ses_1", event.properties.get("sessionID").asString)
        assertEquals("busy", event.properties.getAsJsonObject("status").get("type").asString)
    }

    @Test
    fun parseGlobalEventRejectsMissingIdOrProperties() {
        assertNull(
            OpenCodeGlobalEventStream.parseGlobalEvent(
                """{"directory":"/tmp/project","payload":{"type":"session.idle","properties":{}}}""",
            ),
        )
        assertNull(
            OpenCodeGlobalEventStream.parseGlobalEvent(
                """{"directory":"/tmp/project","payload":{"id":"evt_1","type":"session.idle"}}""",
            ),
        )
    }

    @Test
    fun boundedLineReaderSplitsAllLineTerminators() {
        val reader = BoundedLineReader("a\nb\r\nc\rd\n\ne".reader(), 100)
        assertEquals("a", reader.readLine())
        assertEquals("b", reader.readLine())
        assertEquals("c", reader.readLine())
        assertEquals("d", reader.readLine())
        assertEquals("", reader.readLine())
        assertEquals("e", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun boundedLineReaderReturnsTrailingLineWithoutTerminator() {
        val reader = BoundedLineReader("tail".reader(), 100)
        assertEquals("tail", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun boundedLineReaderFailsInsteadOfBufferingAnEndlessLine() {
        val reader = BoundedLineReader("x".repeat(101).reader(), 100)
        try {
            reader.readLine()
            fail("expected IOException for an oversized line")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message.orEmpty().contains("exceeded"))
        }
    }

    @Test
    fun parseCliEventReadsIdTypeDataAndLocationDirectory() {
        val event = OpenCodeGlobalEventStream.parseCliEvent(
            """
            {
              "id": "evt_1",
              "created": 1,
              "type": "permission.asked",
              "location": {"directory": "/tmp/project"},
              "data": {"id": "per_1", "sessionID": "ses_1", "action": "external_directory"}
            }
            """.trimIndent(),
            fallbackDirectory = "/fallback",
        )!!
        assertEquals("/tmp/project", event.directory)
        assertEquals("permission.asked", event.type)
        assertEquals("evt_1", event.recordId)
        assertEquals("per_1", event.properties.get("id").asString)
        assertEquals("ses_1", event.properties.get("sessionID").asString)
    }

    @Test
    fun parseCliEventUsesFallbackDirectoryForServerConnected() {
        val event = OpenCodeGlobalEventStream.parseCliEvent(
            """{"id":"evt_connected","type":"server.connected","data":{}}""",
            fallbackDirectory = "/Users/me/project",
        )!!
        assertEquals("/Users/me/project", event.directory)
        assertEquals("server.connected", event.type)
        assertEquals("evt_connected", event.recordId)
        assertTrue(event.properties.entrySet().isEmpty())
    }

    @Test
    fun parseCliEventDropsDirectoryLessEventsWithoutFallback() {
        assertNull(
            OpenCodeGlobalEventStream.parseCliEvent(
                """{"id":"evt_connected","type":"server.connected","data":{}}""",
                fallbackDirectory = null,
            ),
        )
    }

    @Test
    fun parseCliEventMapsExecutionStartedToBusyStatus() {
        val event = OpenCodeGlobalEventStream.parseCliEvent(
            """{"id":"evt_exec","type":"session.execution.started","data":{"sessionID":"ses_1"}}""",
            fallbackDirectory = "/tmp/project",
        )!!
        assertEquals("session.status", event.type)
        assertEquals("ses_1", event.properties.get("sessionID").asString)
        assertEquals("busy", event.properties.getAsJsonObject("status").get("type").asString)
    }

    @Test
    fun parseCliEventMapsExecutionSucceededToIdleStatus() {
        val event = OpenCodeGlobalEventStream.parseCliEvent(
            """{"id":"evt_done","type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}""",
            fallbackDirectory = "/tmp/project",
        )!!
        assertEquals("session.status", event.type)
        assertEquals("idle", event.properties.getAsJsonObject("status").get("type").asString)
    }

    @Test
    fun parseCliEventMatchesCapturedPermissionFixtures() {
        val asked = OpenCodeGlobalEventStream.parseCliEvent(
            javaClass.getResource("/de/moritzf/opencodewebpanel/server/wire/v2_cli/event-permission-asked.json")!!.readText(),
            fallbackDirectory = "/fallback",
        )!!
        assertEquals("permission.asked", asked.type)
        assertEquals("/Users/moritz/Desktop/git/intellij-opencode-web-ui", asked.directory)
        assertEquals("per_0ab82a936001Ab0k4zIgsRFD7a", asked.properties.get("id").asString)
        val replied = OpenCodeGlobalEventStream.parseCliEvent(
            javaClass.getResource("/de/moritzf/opencodewebpanel/server/wire/v2_cli/event-permission-replied.json")!!.readText(),
            fallbackDirectory = "/fallback",
        )!!
        assertEquals("permission.replied", replied.type)
        assertEquals("once", replied.properties.get("reply").asString)
        assertEquals("per_0ab82a936001Ab0k4zIgsRFD7a", replied.properties.get("requestID").asString)
    }

    @Test
    fun parseGlobalEventRejectsMalformedEvents() {
        assertNull(OpenCodeGlobalEventStream.parseGlobalEvent("not json"))
        assertNull(OpenCodeGlobalEventStream.parseGlobalEvent("[]"))
        assertNull(OpenCodeGlobalEventStream.parseGlobalEvent("""{"payload": {"type": "session.idle"}}"""))
        assertNull(OpenCodeGlobalEventStream.parseGlobalEvent("""{"directory": " ", "payload": {"type": "session.idle"}}"""))
        assertNull(OpenCodeGlobalEventStream.parseGlobalEvent("""{"directory": "/tmp"}"""))
        assertNull(OpenCodeGlobalEventStream.parseGlobalEvent("""{"directory": "/tmp", "payload": {"properties": {}}}"""))
    }

    @Test
    fun streamPublishesConnectedAndEventsAndReconnects() {
        val connections = AtomicInteger()
        val authHeaders = ConcurrentLinkedQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/global/event") { exchange ->
            connections.incrementAndGet()
            authHeaders.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write(
                    (
                        "data: {\"directory\":\"/tmp/project\",\"payload\":{\"id\":\"evt_1\",\"type\":\"session.idle\"," +
                            "\"properties\":{\"sessionID\":\"ses_1\"}}}\n\n" +
                            ": keep-alive\n\n" +
                            "data: {\"directory\":\"/tmp/project\",\"payload\":{\"id\":\"evt_2\",\"type\":\"permission.asked\"," +
                            "\"properties\":{\"id\":\"per_1\"}}}\n\n"
                        ).toByteArray(StandardCharsets.UTF_8),
                )
                body.flush()
            }
        }
        server.start()

        val connectedLatch = CountDownLatch(2)
        val connectedBackendIds = ConcurrentLinkedQueue<String>()
        val events = ConcurrentLinkedQueue<OpenCodeGlobalEvent>()
        val eventsLatch = CountDownLatch(2)
        val listener = object : OpenCodeGlobalEventListener {
            override fun connected(backendId: String) {
                connectedBackendIds.add(backendId)
                connectedLatch.countDown()
            }

            override fun eventReceived(event: OpenCodeGlobalEvent) {
                events.add(event)
                eventsLatch.countDown()
            }
        }
        val stream = OpenCodeGlobalEventStream(listener = { listener }, reconnectDelayMillis = 50L)
        try {
            stream.start("http://127.0.0.1:${server.address.port}", "Basic dGVzdA==")

            assertTrue("events not received", eventsLatch.await(10, TimeUnit.SECONDS))
            // The server closes the response after two events; the stream must reconnect.
            assertTrue("stream did not reconnect", connectedLatch.await(10, TimeUnit.SECONDS))

            val received = events.toList()
            assertEquals("session.idle", received[0].type)
            assertEquals("/tmp/project", received[0].directory)
            assertEquals("evt_1", received[0].recordId)
            assertEquals(OpenCodeServerBackend.NATIVE_ID, received[0].backendId)
            assertEquals("ses_1", received[0].properties.get("sessionID").asString)
            assertEquals("permission.asked", received[1].type)
            assertEquals("per_1", received[1].properties.get("id").asString)
            assertEquals(OpenCodeServerBackend.NATIVE_ID, received[1].backendId)
            assertTrue(connectedBackendIds.all { it == OpenCodeServerBackend.NATIVE_ID })
            assertEquals("Basic dGVzdA==", authHeaders.peek())
            assertTrue(connections.get() >= 2)
        } finally {
            stream.stop()
            server.stop(0)
        }
    }

    @Test
    fun cliStreamPublishesConnectedAndMappedEvents() {
        val connections = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/event") { exchange ->
            connections.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write(
                    (
                        "data: {\"id\":\"evt_c\",\"type\":\"server.connected\",\"data\":{}}\n\n" +
                            ": heartbeat\n\n" +
                            "data: {\"id\":\"evt_s\",\"type\":\"session.execution.started\",\"data\":{\"sessionID\":\"ses_1\"}}\n\n"
                        ).toByteArray(StandardCharsets.UTF_8),
                )
                body.flush()
            }
        }
        server.start()
        val events = ConcurrentLinkedQueue<OpenCodeGlobalEvent>()
        val eventsLatch = CountDownLatch(2)
        val connectedLatch = CountDownLatch(1)
        val listener = object : OpenCodeGlobalEventListener {
            override fun connected(backendId: String) {
                connectedLatch.countDown()
            }

            override fun eventReceived(event: OpenCodeGlobalEvent) {
                events.add(event)
                eventsLatch.countDown()
            }
        }
        val stream = OpenCodeGlobalEventStream(listener = { listener }, reconnectDelayMillis = 50L)
        try {
            stream.start(
                "http://127.0.0.1:${server.address.port}",
                "Basic dGVzdA==",
                wireProtocol = OpenCodeWireProtocol.V2_CLI,
                fallbackDirectory = "/tmp/project",
            )
            assertTrue("cli events not received", eventsLatch.await(10, TimeUnit.SECONDS))
            assertTrue("cli stream did not connect", connectedLatch.await(10, TimeUnit.SECONDS))
            val received = events.toList()
            assertEquals("server.connected", received[0].type)
            assertEquals("/tmp/project", received[0].directory)
            assertEquals("session.status", received[1].type)
            assertEquals("busy", received[1].properties.getAsJsonObject("status").get("type").asString)
            assertTrue(connections.get() >= 1)
        } finally {
            stream.stop()
            server.stop(0)
        }
    }

    @Test
    fun streamReleasesOversizedSseBlockAndReconnects() {
        val connections = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/global/event") { exchange ->
            val connectionNumber = connections.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                if (connectionNumber == 1) {
                    // No blank line: keep appending so the block exceeds the cap and forces reconnect.
                    body.write("data: ".toByteArray(StandardCharsets.UTF_8))
                    val chunk = "x".repeat(64 * 1024).toByteArray(StandardCharsets.UTF_8)
                    repeat((OpenCodeGlobalEventStream.MAX_SSE_BLOCK_CHARS / chunk.size) + 2) {
                        body.write(chunk)
                    }
                    body.flush()
                } else {
                    body.write(
                        (
                            "data: {\"directory\":\"/tmp/project\",\"payload\":{\"id\":\"evt_ok\",\"type\":\"session.idle\"," +
                                "\"properties\":{\"sessionID\":\"ses_ok\"}}}\n\n"
                            ).toByteArray(StandardCharsets.UTF_8),
                    )
                    body.flush()
                }
            }
        }
        server.start()

        val eventsLatch = CountDownLatch(1)
        val stream = OpenCodeGlobalEventStream(
            listener = {
                object : OpenCodeGlobalEventListener {
                    override fun eventReceived(event: OpenCodeGlobalEvent) {
                        if (event.type == "session.idle") eventsLatch.countDown()
                    }
                }
            },
            reconnectDelayMillis = 50L,
        )
        try {
            stream.start("http://127.0.0.1:${server.address.port}", "Basic dGVzdA==")
            assertTrue("did not recover after oversized block", eventsLatch.await(10, TimeUnit.SECONDS))
            assertTrue(connections.get() >= 2)
        } finally {
            stream.stop()
            server.stop(0)
        }
    }

    @Test
    fun streamRejectsWrongContentTypeBeforePublishingConnected() {
        val connections = AtomicInteger()
        val connected = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/global/event") { exchange ->
            connections.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()
        val stream = OpenCodeGlobalEventStream(
            listener = {
                object : OpenCodeGlobalEventListener {
                    override fun connected(backendId: String) { connected.incrementAndGet() }
                    override fun eventReceived(event: OpenCodeGlobalEvent) = Unit
                }
            },
            reconnectDelayMillis = 20,
            readTimeoutMillis = 100,
        )
        try {
            stream.start("http://127.0.0.1:${server.address.port}", "Basic test")
            val deadline = System.currentTimeMillis() + 3_000
            while (connections.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(connections.get() >= 2)
            assertEquals(0, connected.get())
        } finally {
            stream.stop()
            server.stop(0)
        }
    }

    @Test
    fun stalledStreamReconnectsAfterReadTimeout() {
        val connections = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/global/event") { exchange ->
            connections.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.flush()
                Thread.sleep(400)
            }
        }
        server.start()
        val stream = OpenCodeGlobalEventStream(
            reconnectDelayMillis = 20,
            readTimeoutMillis = 100,
        )
        try {
            stream.start("http://127.0.0.1:${server.address.port}", "Basic test")
            val deadline = System.currentTimeMillis() + 3_000
            while (connections.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("stalled stream did not reconnect", connections.get() >= 2)
        } finally {
            stream.stop()
            server.stop(0)
        }
    }

    @Test
    fun stopDoesNotBlockWhileALiveReadIsInProgress() {
        val connected = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/global/event") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.flush()
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        server.start()
        val stream = OpenCodeGlobalEventStream(
            listener = {
                object : OpenCodeGlobalEventListener {
                    override fun connected(backendId: String) {
                        connected.countDown()
                    }

                    override fun eventReceived(event: OpenCodeGlobalEvent) = Unit
                }
            },
            reconnectDelayMillis = 50L,
            readTimeoutMillis = 45_000,
        )
        try {
            stream.start("http://127.0.0.1:${server.address.port}", "Basic dGVzdA==")
            assertTrue("stream did not connect", connected.await(5, TimeUnit.SECONDS))
            val startedAtNanos = System.nanoTime()
            stream.stop()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
            assertTrue("stop() blocked for ${elapsedMillis}ms", elapsedMillis < 1_000)
        } finally {
            stream.stop()
            server.stop(0)
        }
    }

    @Test
    fun stopPreventsFurtherReconnects() {
        val connections = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/global/event") { exchange ->
            connections.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.flush() }
        }
        server.start()

        val stream = OpenCodeGlobalEventStream(
            listener = {
                object : OpenCodeGlobalEventListener {
                    override fun eventReceived(event: OpenCodeGlobalEvent) = Unit
                }
            },
            reconnectDelayMillis = 20L,
        )
        try {
            stream.start("http://127.0.0.1:${server.address.port}", "Basic dGVzdA==")
            waitUntil { connections.get() >= 1 }
            stream.stop()
            val connectionsAtStop = connections.get()
            Thread.sleep(200)
            // One extra connect can slip through when stop() lands mid-reconnect; the loop
            // must terminate afterwards instead of reconnecting on every delay tick.
            assertTrue(connections.get() <= connectionsAtStop + 1)
        } finally {
            stream.stop()
            server.stop(0)
        }
    }

    private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            assertTrue("condition not met in time", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }
}
