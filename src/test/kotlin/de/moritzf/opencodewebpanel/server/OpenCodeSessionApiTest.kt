package de.moritzf.opencodewebpanel.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeSessionApiTest {
    @Test
    fun malformedMessageResponsesAreFailuresInsteadOfEmptyHistory() {
        val body = AtomicReference("")
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val bytes = body.get().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            for (protocol in listOf(OpenCodeWireProtocol.V1_18, OpenCodeWireProtocol.V2_CLI)) {
                for (invalid in
                    listOf("<html>SPA</html>", "{}", "{\"data\":null}", "[null]", "[{}]")) {
                    body.set(invalid)
                    requests.set(0)
                    assertEquals(
                        "$protocol: $invalid",
                        OpenCodeProtocolResult.Failure(
                            OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                        ),
                        OpenCodeServerProtocol.fetchLastMessageJsonResult(
                            "http://127.0.0.1:${server.address.port}",
                            "Basic test",
                            "/workspace",
                            "ses_test",
                            wireProtocol = protocol,
                        ),
                    )
                    assertEquals(
                        "Malformed v1 must not fall through to the other store",
                        1,
                        requests.get(),
                    )
                }
            }
            for ((protocol, empty, count) in
                listOf(
                    Triple(OpenCodeWireProtocol.V1_18, "[]", 2),
                    Triple(OpenCodeWireProtocol.V2_CLI, "{\"data\":[]}", 1),
                )) {
                body.set(empty)
                requests.set(0)
                assertEquals(
                    OpenCodeProtocolResult.Success(null),
                    OpenCodeServerProtocol.fetchLastMessageJsonResult(
                        "http://127.0.0.1:${server.address.port}",
                        "Basic test",
                        "/workspace",
                        "ses_test",
                        wireProtocol = protocol,
                    ),
                )
                assertEquals(count, requests.get())
            }
        } finally {
            server.stop(0)
        }
    }
}
