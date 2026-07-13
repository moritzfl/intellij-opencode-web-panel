package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * The plugin's single reader of the OpenCode `/global/event` SSE stream, running on the JVM.
 *
 * Consuming the event stream here instead of from injected browser JavaScript keeps the
 * plugin out of Chromium's six-connections-per-host budget (each embedded SPA already holds
 * one `/global/event` stream of its own) and keeps event consumers working even while a
 * panel's page is loading or crashed.
 *
 * Owned by [SharedOpenCodeServerManager]: started when the server reaches RUNNING, stopped on
 * stop/restart, and forced to reconnect after a resume from system suspend (the blocked read
 * may sit on a dead socket). Parsed events fan out through
 * [OpenCodeGlobalEventListener.TOPIC] on the application message bus.
 */
internal class OpenCodeGlobalEventStream(
    private val listener: () -> OpenCodeGlobalEventListener = {
        ApplicationManager.getApplication().messageBus.syncPublisher(OpenCodeGlobalEventListener.TOPIC)
    },
    private val reconnectDelayMillis: Long = RECONNECT_DELAY_MILLIS,
) {
    companion object {
        const val EVENT_PATH = "/global/event"
        private const val RECONNECT_DELAY_MILLIS = 2_000L
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 10 * 60 * 1_000
        /** Cap one SSE event block so a stream without blank lines cannot grow without bound. */
        const val MAX_SSE_BLOCK_CHARS = 1 * 1024 * 1024

        /**
         * Extracts the payload of one SSE block: the `data:` lines with the field name
         * stripped, joined with newlines. Returns null for blocks without data (comments,
         * bare `event:`/`id:` fields).
         */
        fun sseBlockData(block: String): String? {
            val data = block.split('\n')
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trimStart() }
                .trim()
            return data.takeIf { it.isNotEmpty() }
        }

        /** Parses one event payload; returns null for malformed or directory-less events. */
        fun parseGlobalEvent(json: String): OpenCodeGlobalEvent? {
            val event = runCatching { JsonParser.parseString(json) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val directory = event.stringMember("directory")?.takeIf { it.isNotBlank() } ?: return null
            val payload = event.objectMember("payload") ?: return null
            val type = payload.stringMember("type")?.takeIf { it.isNotBlank() } ?: return null
            val recordId = payload.stringMember("id").orEmpty()
            val properties = payload.objectMember("properties") ?: JsonObject()
            return OpenCodeGlobalEvent(directory, type, recordId, properties)
        }
    }

    private val lock = Any()
    private var generation = 0L
    private var connection: HttpURLConnection? = null

    fun start(serverUrl: String, basicAuthHeader: String) {
        val myGeneration = synchronized(lock) {
            connection?.disconnect()
            connection = null
            ++generation
        }
        Thread({ runReadLoop(myGeneration, serverUrl, basicAuthHeader) }, "OpenCode-Event-Stream").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        synchronized(lock) {
            ++generation
            connection?.disconnect()
            connection = null
        }
    }

    /**
     * Drops the current connection so the read loop reconnects (and consumers re-seed)
     * immediately. Used after a resume from system suspend, when the blocked read may sit
     * on a connection the sleep severed without an error.
     */
    fun reconnectNow() {
        synchronized(lock) {
            connection?.disconnect()
            connection = null
        }
    }

    private fun isCurrent(myGeneration: Long): Boolean = synchronized(lock) { myGeneration == generation }

    private fun runReadLoop(myGeneration: Long, serverUrl: String, basicAuthHeader: String) {
        while (isCurrent(myGeneration)) {
            try {
                readStreamOnce(myGeneration, serverUrl, basicAuthHeader)
                if (isCurrent(myGeneration)) {
                    thisLogger().info("OpenCode event stream ended; reconnecting")
                }
            } catch (e: Exception) {
                if (isCurrent(myGeneration)) {
                    thisLogger().info("OpenCode event stream disconnected: ${e.message}")
                }
            }
            if (!isCurrent(myGeneration)) return
            try {
                Thread.sleep(reconnectDelayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun readStreamOnce(myGeneration: Long, serverUrl: String, basicAuthHeader: String) {
        val url = OpenCodeServerProtocol.buildServerRootUrl(serverUrl) + EVENT_PATH
        val newConnection = URI(url).toURL().openConnection() as HttpURLConnection
        newConnection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        // The server sends no keep-alives, so a silent stream is normal while no session is
        // active — but an unbounded read could also block forever on a socket that died
        // without an error. The bounded timeout turns that into a reconnect (and consumer
        // re-seed) after at most this long, at the cost of an occasional cheap local
        // reconnect on genuinely idle streams.
        newConnection.readTimeout = READ_TIMEOUT_MILLIS
        newConnection.requestMethod = "GET"
        newConnection.setRequestProperty("Accept", "text/event-stream")
        newConnection.setRequestProperty("Authorization", basicAuthHeader)
        val registered = synchronized(lock) {
            (myGeneration == generation).also { current ->
                if (current) connection = newConnection
            }
        }
        if (!registered) {
            newConnection.disconnect()
            return
        }
        try {
            if (newConnection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("OpenCode event stream returned HTTP ${newConnection.responseCode}")
            }
            dispatchConnected(myGeneration)
            newConnection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val lineReader = BoundedLineReader(reader, MAX_SSE_BLOCK_CHARS)
                val block = StringBuilder()
                while (isCurrent(myGeneration)) {
                    val line = lineReader.readLine() ?: break
                    if (line.isEmpty()) {
                        dispatchBlock(myGeneration, block.toString())
                        block.setLength(0)
                    } else {
                        if (block.isNotEmpty()) block.append('\n')
                        block.append(line)
                        if (block.length > MAX_SSE_BLOCK_CHARS) {
                            throw IOException(
                                "OpenCode event stream block exceeded $MAX_SSE_BLOCK_CHARS characters",
                            )
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (connection === newConnection) connection = null
            }
            newConnection.disconnect()
        }
    }

    private fun dispatchConnected(myGeneration: Long) {
        if (!isCurrent(myGeneration)) return
        try {
            listener().connected()
        } catch (e: Exception) {
            thisLogger().warn("OpenCode event listener failed on connect: ${e.message}")
        }
    }

    private fun dispatchBlock(myGeneration: Long, block: String) {
        val data = sseBlockData(block) ?: return
        val event = parseGlobalEvent(data) ?: return
        if (!isCurrent(myGeneration)) return
        try {
            listener().eventReceived(event)
        } catch (e: Exception) {
            thisLogger().warn("OpenCode event listener failed for ${event.type}: ${e.message}")
        }
    }
}

/**
 * Line reader with a hard per-line cap. [java.io.BufferedReader.readLine] materializes the
 * whole line before any caller-side size check can run, so a malformed server emitting one
 * enormous line without terminators could exhaust memory despite the SSE block cap; this
 * reader fails the connection as soon as one line exceeds [maxChars]. Recognizes `\n`,
 * `\r\n`, and `\r` line terminators like `readLine()` does.
 */
internal class BoundedLineReader(private val reader: Reader, private val maxChars: Int) {
    private var skipNextLineFeed = false

    /** Returns the next line without its terminator, or null at the end of the stream. */
    fun readLine(): String? {
        val line = StringBuilder()
        var sawAnyChar = false
        while (true) {
            val read = reader.read()
            if (read < 0) {
                return if (sawAnyChar) line.toString() else null
            }
            val char = read.toChar()
            if (skipNextLineFeed) {
                skipNextLineFeed = false
                if (char == '\n') continue
            }
            sawAnyChar = true
            when (char) {
                '\n' -> return line.toString()
                '\r' -> {
                    skipNextLineFeed = true
                    return line.toString()
                }
                else -> {
                    line.append(char)
                    if (line.length > maxChars) {
                        throw IOException("OpenCode event stream line exceeded $maxChars characters")
                    }
                }
            }
        }
    }
}
