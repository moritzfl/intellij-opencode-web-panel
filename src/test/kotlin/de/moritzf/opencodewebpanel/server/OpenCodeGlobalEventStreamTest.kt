package de.moritzf.opencodewebpanel.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun parseGlobalEventDefaultsMissingIdAndProperties() {
        val event = OpenCodeGlobalEventStream.parseGlobalEvent(
            """{"directory": "/tmp/project", "payload": {"type": "session.idle"}}""",
        )!!

        assertEquals("", event.recordId)
        assertEquals(0, event.properties.size())
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
                            "data: {\"directory\":\"/tmp/project\",\"payload\":{\"type\":\"permission.asked\"," +
                            "\"properties\":{\"id\":\"per_1\"}}}\n\n"
                        ).toByteArray(StandardCharsets.UTF_8),
                )
                body.flush()
            }
        }
        server.start()

        val connectedLatch = CountDownLatch(2)
        val events = ConcurrentLinkedQueue<OpenCodeGlobalEvent>()
        val eventsLatch = CountDownLatch(2)
        val listener = object : OpenCodeGlobalEventListener {
            override fun connected() {
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
            assertEquals("ses_1", received[0].properties.get("sessionID").asString)
            assertEquals("permission.asked", received[1].type)
            assertEquals("per_1", received[1].properties.get("id").asString)
            assertEquals("Basic dGVzdA==", authHeaders.peek())
            assertTrue(connections.get() >= 2)
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
