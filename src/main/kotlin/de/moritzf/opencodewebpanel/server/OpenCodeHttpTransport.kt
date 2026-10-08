package de.moritzf.opencodewebpanel.server

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

/** Bounded HTTP transport. Protocol and endpoint decisions belong to callers. */
internal object OpenCodeHttpTransport {
    const val MAX_HTTP_RESPONSE_CHARS = 8 * 1024 * 1024

    fun httpGet(
        url: String,
        basicAuthHeader: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseChars: Int = MAX_HTTP_RESPONSE_CHARS,
    ): String? {
        return when (
            val result =
                httpGetResult(
                    url,
                    basicAuthHeader,
                    connectTimeoutMillis,
                    readTimeoutMillis,
                    maxResponseChars,
                )
        ) {
            is OpenCodeProtocolResult.Success -> result.value
            is OpenCodeProtocolResult.Failure -> null
        }
    }

    fun httpGetResult(
        url: String,
        basicAuthHeader: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseChars: Int = MAX_HTTP_RESPONSE_CHARS,
    ): OpenCodeProtocolResult<String> {
        return when (
            val result =
                httpGetResultAndHeader(
                    url,
                    basicAuthHeader,
                    connectTimeoutMillis,
                    readTimeoutMillis,
                    maxResponseChars,
                )
        ) {
            is OpenCodeProtocolResult.Failure -> result
            is OpenCodeProtocolResult.Success -> OpenCodeProtocolResult.Success(result.value.first)
        }
    }

    fun httpGetResultAndHeader(
        url: String,
        basicAuthHeader: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseChars: Int = MAX_HTTP_RESPONSE_CHARS,
        headerName: String? = null,
    ): OpenCodeProtocolResult<Pair<String, String?>> {
        return try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "GET"
                if (!basicAuthHeader.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", basicAuthHeader)
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    return OpenCodeProtocolResult.Failure(
                        OpenCodeProtocolResult.Failure.Kind.HTTP,
                        status,
                    )
                }
                val body =
                    connection.inputStream.bufferedReader().use { reader ->
                        readBounded(reader, maxResponseChars)
                    }
                        ?: return OpenCodeProtocolResult.Failure(
                            OpenCodeProtocolResult.Failure.Kind.TOO_LARGE
                        )
                OpenCodeProtocolResult.Success(
                    body to headerName?.let { connection.getHeaderField(it) }
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: SocketTimeoutException) {
            OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.TIMEOUT)
        } catch (_: Exception) {
            OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.IO)
        }
    }

    fun readBounded(reader: BufferedReader, maxChars: Int): String? {
        val buffer = StringBuilder()
        val chunk = CharArray(8_192)
        while (true) {
            val read = reader.read(chunk)
            if (read < 0) break
            if (buffer.length + read > maxChars) return null
            buffer.append(chunk, 0, read)
        }
        return buffer.toString()
    }

    fun httpPostJson(
        url: String,
        basicAuthHeader: String,
        body: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): Boolean {
        return httpPostResult(
            url,
            basicAuthHeader,
            body,
            connectTimeoutMillis,
            readTimeoutMillis,
        ) is
            OpenCodeProtocolResult.Success
    }

    /** A null body is an empty POST; other bodies are UTF-8 JSON. */
    fun httpPostResult(
        url: String,
        basicAuthHeader: String,
        body: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): OpenCodeProtocolResult<Unit> {
        return try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", basicAuthHeader)
                if (body == null) {
                    connection.setFixedLengthStreamingMode(0)
                } else {
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use {
                        it.write(body.toByteArray(StandardCharsets.UTF_8))
                    }
                }
                val status = connection.responseCode
                if (status in 200..299) {
                    OpenCodeProtocolResult.Success(Unit)
                } else {
                    OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, status)
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: SocketTimeoutException) {
            OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.TIMEOUT)
        } catch (_: Exception) {
            OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.IO)
        }
    }
}
