package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.moritzf.opencodewebpanel.server.OpenCodeHttpTransport.httpGet
import de.moritzf.opencodewebpanel.server.OpenCodeHttpTransport.httpGetResult
import de.moritzf.opencodewebpanel.server.OpenCodeHttpTransport.httpGetResultAndHeader
import de.moritzf.opencodewebpanel.server.OpenCodeHttpTransport.httpPostResult
import de.moritzf.opencodewebpanel.server.OpenCodeRecoveryClassifier.normalizeLastMessageForClassification
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.CLI_PERMISSION_LIST_PATH
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.PERMISSION_LIST_PATH
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.PendingRequestSummary
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.QUESTION_LIST_PATH
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.RECENT_SESSION_WINDOW_MILLIS
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.SessionInfo
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.SessionSummary
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.SnapshotFileDiff
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.ToolPartChange
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.buildServerRootUrl
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.isMessageId
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.isOpenCodeRecordId
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.isPartId
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.isSessionId
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol.usesCliHttpApi
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets

/** Dual-version session REST operations and their loose JSON envelopes. */
internal object OpenCodeSessionApi {
    private const val MESSAGE_PAGE_MAX = 40

    private const val MESSAGE_PAGE_LIMIT = 50

    private data class SessionPage(val sessions: List<SessionSummary>, val nextCursor: String?)

    private data class ParsedPendingRequests(
        val requests: List<PendingRequestSummary>,
        val malformedEntry: Boolean,
    )

    fun fetchSessionInfo(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): SessionInfo? {
        if (wireProtocol == OpenCodeWireProtocol.UNKNOWN || !isSessionId(sessionID)) return null
        val encodedDirectory = java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val url =
            if (usesCliHttpApi(wireProtocol)) {
                buildServerRootUrl(serverUrl) +
                    "/api/session/" +
                    sessionID +
                    "?directory=" +
                    encodedDirectory
            } else {
                buildServerRootUrl(serverUrl) +
                    "/session/" +
                    sessionID +
                    "?directory=" +
                    encodedDirectory
            }
        val body =
            httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return null
        return parseSessionInfo(body)?.takeIf { it.id == sessionID }
    }

    fun fetchSessionChildren(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): List<SessionInfo> {
        if (wireProtocol == OpenCodeWireProtocol.UNKNOWN || !isSessionId(sessionID))
            return emptyList()
        val encodedDirectory = java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val url =
            if (usesCliHttpApi(wireProtocol)) {
                buildServerRootUrl(serverUrl) +
                    "/api/session?directory=" +
                    encodedDirectory +
                    "&parentID=" +
                    java.net.URLEncoder.encode(sessionID, StandardCharsets.UTF_8)
            } else {
                buildServerRootUrl(serverUrl) +
                    "/session/" +
                    sessionID +
                    "/children?directory=" +
                    encodedDirectory
            }
        val body =
            httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
                ?: return emptyList()
        return parseSessionChildren(body)
    }

    fun parseSessionInfo(json: String): SessionInfo? {
        val root = parseJsonObject(json) ?: return null
        val session = root.objectMember("data") ?: root
        return parseSessionInfoObject(session)
    }

    fun parseSessionChildren(json: String): List<SessionInfo> {
        val parsed = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        val array =
            when {
                parsed.isJsonArray -> parsed.asJsonArray
                parsed.isJsonObject ->
                    parsed.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return emptyList()
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseSessionInfoObject)
        }
    }

    private fun parseSessionInfoObject(session: JsonObject): SessionInfo? {
        val id = session.stringMember("id")?.takeIf(::isSessionId) ?: return null
        return SessionInfo(
            title = session.stringMember("title").orEmpty(),
            parentID = session.stringMember("parentID")?.takeIf { it.isNotBlank() },
            id = id,
            directory = sessionDirectory(session),
        )
    }

    fun fetchSessionDiffResult(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        messageID: String? = null,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): OpenCodeProtocolResult<List<SnapshotFileDiff>> {
        if (wireProtocol == OpenCodeWireProtocol.UNKNOWN) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        }
        if (!isSessionId(sessionID)) {
            return OpenCodeProtocolResult.Failure(
                OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER
            )
        }
        val normalizedMessageID = messageID?.takeIf { it.isNotBlank() }
        if (normalizedMessageID != null && !isMessageId(normalizedMessageID)) {
            return OpenCodeProtocolResult.Failure(
                OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER
            )
        }
        val encodedDirectory = java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val url =
            if (usesCliHttpApi(wireProtocol)) {
                val fromParam =
                    normalizedMessageID
                        ?.let { "&from=" + java.net.URLEncoder.encode(it, StandardCharsets.UTF_8) }
                        .orEmpty()
                buildServerRootUrl(serverUrl) +
                    "/api/session/" +
                    sessionID +
                    "/diff?directory=" +
                    encodedDirectory +
                    fromParam
            } else {
                val messageParam =
                    normalizedMessageID
                        ?.let {
                            "&messageID=" + java.net.URLEncoder.encode(it, StandardCharsets.UTF_8)
                        }
                        .orEmpty()
                buildServerRootUrl(serverUrl) +
                    "/session/" +
                    sessionID +
                    "/diff?directory=" +
                    encodedDirectory +
                    messageParam
            }
        return when (
            val response =
                httpGetResult(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
        ) {
            is OpenCodeProtocolResult.Failure -> response
            is OpenCodeProtocolResult.Success -> {
                val array =
                    sessionDiffArray(response.value)
                        ?: return OpenCodeProtocolResult.Failure(
                            OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                        )
                OpenCodeProtocolResult.Success(parseSessionDiffArray(array))
            }
        }
    }

    fun fetchVcsDiffResult(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        mode: String,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): OpenCodeProtocolResult<List<SnapshotFileDiff>> {
        if (!usesCliHttpApi(wireProtocol)) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        }
        val normalizedMode =
            when (mode) {
                "working",
                "branch" -> mode
                else ->
                    return OpenCodeProtocolResult.Failure(
                        OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER
                    )
            }
        if (directory.isBlank()) {
            return OpenCodeProtocolResult.Failure(
                OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER
            )
        }
        val encodedDirectory = java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val url =
            buildServerRootUrl(serverUrl) +
                "/api/vcs/diff?mode=" +
                normalizedMode +
                "&directory=" +
                encodedDirectory
        return when (
            val response =
                httpGetResult(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
        ) {
            is OpenCodeProtocolResult.Failure -> response
            is OpenCodeProtocolResult.Success -> {
                val array =
                    sessionDiffArray(response.value)
                        ?: return OpenCodeProtocolResult.Failure(
                            OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                        )
                OpenCodeProtocolResult.Success(parseSessionDiffArray(array))
            }
        }
    }

    fun parseSessionDiff(json: String): List<SnapshotFileDiff> {
        val array = sessionDiffArray(json) ?: return emptyList()
        return parseSessionDiffArray(array)
    }

    private fun sessionDiffArray(json: String): JsonArray? {
        val parsed = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        return when {
            parsed.isJsonArray -> parsed.asJsonArray
            parsed.isJsonObject ->
                parsed.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        }
    }

    private fun parseSessionDiffArray(array: JsonArray): List<SnapshotFileDiff> {
        val results = mutableListOf<SnapshotFileDiff>()
        for (element in array) {
            val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            results.add(
                SnapshotFileDiff(
                    file = entry.stringMember("file")?.takeIf { it.isNotBlank() },
                    patch = entry.stringMember("patch"),
                    additions = entry.longMember("additions") ?: 0L,
                    deletions = entry.longMember("deletions") ?: 0L,
                    status = entry.stringMember("status"),
                )
            )
        }
        return results
    }

    fun fetchToolPartChange(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        partID: String,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): OpenCodeProtocolResult<ToolPartChange> {
        if (wireProtocol == OpenCodeWireProtocol.UNKNOWN) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        }
        val validPart = if (usesCliHttpApi(wireProtocol)) partID.isNotBlank() else isPartId(partID)
        if (!isSessionId(sessionID) || !validPart || directory.isBlank()) {
            return OpenCodeProtocolResult.Failure(
                OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER
            )
        }
        val root = buildServerRootUrl(serverUrl)
        val directoryParam =
            "directory=" + java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        if (usesCliHttpApi(wireProtocol)) {
            var cursor: String? = null
            repeat(MESSAGE_PAGE_MAX) {
                val cursorParam =
                    cursor
                        ?.let {
                            "&cursor=" + java.net.URLEncoder.encode(it, StandardCharsets.UTF_8)
                        }
                        .orEmpty()
                val url =
                    "$root/api/session/$sessionID/message?$directoryParam&limit=$MESSAGE_PAGE_LIMIT$cursorParam"
                when (
                    val page =
                        httpGetResult(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
                ) {
                    is OpenCodeProtocolResult.Failure -> return page
                    is OpenCodeProtocolResult.Success -> {
                        val parsed =
                            parseJsonObject(page.value)
                                ?: return OpenCodeProtocolResult.Failure(
                                    OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                                )
                        val array =
                            parsed.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                                ?: return OpenCodeProtocolResult.Failure(
                                    OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                                )
                        val part = toolPartFromMessages(array, partID)
                        if (part != null)
                            return OpenCodeProtocolResult.Success(parseToolPartChange(part))
                        cursor =
                            parsed.objectMember("cursor")?.stringMember("next")?.takeIf {
                                it.isNotBlank()
                            }
                        if (cursor == null) {
                            return OpenCodeProtocolResult.Success(ToolPartChange(emptyList()))
                        }
                    }
                }
            }
            return OpenCodeProtocolResult.Success(ToolPartChange(emptyList()))
        }
        var before: String? = null
        repeat(MESSAGE_PAGE_MAX) {
            val beforeParam =
                before
                    ?.let { "&before=" + java.net.URLEncoder.encode(it, StandardCharsets.UTF_8) }
                    .orEmpty()
            val url =
                "$root/session/$sessionID/message?$directoryParam&limit=$MESSAGE_PAGE_LIMIT$beforeParam"
            val page =
                httpGetResultAndHeader(
                    url,
                    basicAuthHeader,
                    connectTimeoutMillis,
                    readTimeoutMillis,
                    headerName = "X-Next-Cursor",
                )
            when (page) {
                is OpenCodeProtocolResult.Failure -> return page
                is OpenCodeProtocolResult.Success -> {
                    val array =
                        parseJsonArray(page.value.first)
                            ?: return OpenCodeProtocolResult.Failure(
                                OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                            )
                    val part = toolPartFromMessages(array, partID)
                    if (part != null)
                        return OpenCodeProtocolResult.Success(parseToolPartChange(part))
                    before = page.value.second?.takeIf { it.isNotBlank() }
                    if (before == null) {
                        return OpenCodeProtocolResult.Success(ToolPartChange(emptyList()))
                    }
                }
            }
        }
        return OpenCodeProtocolResult.Success(ToolPartChange(emptyList()))
    }

    fun parseToolPartChange(json: String): ToolPartChange {
        val part = parseJsonObject(json) ?: return ToolPartChange(emptyList())
        return parseToolPartChange(part)
    }

    private fun parseToolPartChange(part: JsonObject): ToolPartChange {
        if (part.stringMember("type") != "tool") return ToolPartChange(emptyList())
        val state = part.objectMember("state")
        val metadata = state?.objectMember("metadata")
        val input = state?.objectMember("input")
        val files = metadata?.get("files")?.takeIf { it.isJsonArray }?.asJsonArray
        if (files != null && files.size() > 0) {
            val diffs = files.mapNotNull { element ->
                val file =
                    element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val filePath =
                    file.stringMember("relativePath")?.takeIf { it.isNotBlank() }
                        ?: file.stringMember("file")?.takeIf { it.isNotBlank() }
                        ?: file.stringMember("filePath")?.takeIf { it.isNotBlank() }
                        ?: input?.stringMember("path")?.takeIf { it.isNotBlank() }
                        ?: input?.stringMember("filePath")?.takeIf { it.isNotBlank() }
                SnapshotFileDiff(
                    file = filePath,
                    patch = file.stringMember("patch") ?: file.stringMember("diff"),
                    additions = file.longMember("additions") ?: 0L,
                    deletions = file.longMember("deletions") ?: 0L,
                    status =
                        when (file.stringMember("type") ?: file.stringMember("status")) {
                            "add",
                            "added" -> "added"
                            "delete",
                            "deleted" -> "deleted"
                            else -> file.stringMember("status") ?: "modified"
                        },
                )
            }
            if (diffs.isNotEmpty()) return ToolPartChange(diffs)
        }
        val filediff = metadata?.objectMember("filediff")
        if (filediff != null) {
            val path =
                filediff.stringMember("file")?.takeIf { it.isNotBlank() }
                    ?: input?.stringMember("filePath")?.takeIf { it.isNotBlank() }
            return ToolPartChange(
                listOf(
                    SnapshotFileDiff(
                        file = path,
                        patch = filediff.stringMember("patch"),
                        additions = filediff.longMember("additions") ?: 0L,
                        deletions = filediff.longMember("deletions") ?: 0L,
                        status = filediff.stringMember("status") ?: "modified",
                    )
                )
            )
        }
        val writePath =
            input?.stringMember("filePath")?.takeIf { it.isNotBlank() }
                ?: input?.stringMember("path")?.takeIf { it.isNotBlank() }
                ?: metadata?.stringMember("filepath")?.takeIf { it.isNotBlank() }
        return ToolPartChange(emptyList(), fileHint = writePath)
    }

    fun findToolPartInMessages(json: String, partID: String): String? {
        val array = parseJsonArray(json) ?: return null
        return toolPartFromMessages(array, partID)?.toString()
    }

    private fun toolPartFromMessages(array: JsonArray, partID: String): JsonObject? {
        for (element in array) {
            val message = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val parts =
                message.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: continue
            for (partElement in parts) {
                val part = partElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                if (part.stringMember("id") == partID) return part
            }
        }
        return null
    }

    fun fetchBusySessionIds(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): Set<String>? {
        if (wireProtocol == OpenCodeWireProtocol.UNKNOWN) return null
        val url =
            if (usesCliHttpApi(wireProtocol)) {
                buildServerRootUrl(serverUrl) + "/api/session/active"
            } else {
                buildServerRootUrl(serverUrl) +
                    "/session/status?directory=" +
                    java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
            }
        val body =
            httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return null
        if (parseJsonObject(body) == null) return null
        return parseBusySessionIds(body)
    }

    fun parseBusySessionIds(json: String): Set<String> {
        val root = parseJsonObject(json) ?: return emptySet()
        val statuses = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
        return statuses.entrySet().mapNotNullTo(mutableSetOf()) { (sessionID, status) ->
            if (!sessionID.startsWith("ses_")) return@mapNotNullTo null
            val type = status?.takeIf { it.isJsonObject }?.asJsonObject?.stringMember("type")
            sessionID.takeIf { type == "busy" || type == "retry" || type == "running" }
        }
    }

    fun fetchPendingRequestIds(
        serverUrl: String,
        basicAuthHeader: String,
        listPath: String,
        directory: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): List<String>? {
        return when (
            val result =
                fetchPendingRequestsResult(
                    serverUrl,
                    basicAuthHeader,
                    listPath,
                    directory,
                    connectTimeoutMillis,
                    readTimeoutMillis,
                    wireProtocol,
                )
        ) {
            is OpenCodeProtocolResult.Success -> result.value.map { it.id }
            is OpenCodeProtocolResult.Failure -> null
        }
    }

    fun parsePendingRequestIds(json: String): List<String> {
        val parsed = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        val requests =
            when {
                parsed.isJsonArray -> parsed.asJsonArray
                parsed.isJsonObject ->
                    parsed.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return emptyList()
        return requests.mapNotNull { request ->
            request
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.stringMember("id")
                ?.takeIf { it.isNotBlank() }
        }
    }

    fun fetchPendingRequestsResult(
        serverUrl: String,
        basicAuthHeader: String,
        listPath: String,
        directory: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): OpenCodeProtocolResult<List<PendingRequestSummary>> {
        if (wireProtocol == OpenCodeWireProtocol.UNKNOWN) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        }
        if (usesCliHttpApi(wireProtocol) && listPath == QUESTION_LIST_PATH) {
            return OpenCodeProtocolResult.Success(emptyList())
        }
        val path =
            if (usesCliHttpApi(wireProtocol) && listPath == PERMISSION_LIST_PATH) {
                CLI_PERMISSION_LIST_PATH
            } else {
                listPath
            }
        val url =
            buildServerRootUrl(serverUrl) +
                path +
                "?directory=" +
                java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        return when (
            val response =
                httpGetResult(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
        ) {
            is OpenCodeProtocolResult.Failure -> response
            is OpenCodeProtocolResult.Success -> {
                parsePendingRequestsResult(response.value)
            }
        }
    }

    fun parsePendingRequests(json: String): List<PendingRequestSummary> =
        parsePendingRequestsBody(json)?.requests.orEmpty()

    fun parsePendingRequestsResult(
        json: String
    ): OpenCodeProtocolResult<List<PendingRequestSummary>> {
        val parsed =
            parsePendingRequestsBody(json)
                ?: return OpenCodeProtocolResult.Failure(
                    OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                )
        if (parsed.malformedEntry) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        }
        return OpenCodeProtocolResult.Success(parsed.requests)
    }

    private fun parsePendingRequestsBody(json: String): ParsedPendingRequests? {
        val parsed = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        val requests =
            when {
                parsed.isJsonArray -> parsed.asJsonArray
                parsed.isJsonObject ->
                    parsed.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return null
        var malformedEntry = false
        val summaries =
            requests
                .mapNotNull { request ->
                    val value = request.takeIf { it.isJsonObject }?.asJsonObject
                    val id = value?.stringMember("id")?.takeIf(::isOpenCodeRecordId)
                    val sessionID = value?.stringMember("sessionID")?.takeIf(::isSessionId)
                    if (id == null || sessionID == null) {
                        malformedEntry = true
                        null
                    } else {
                        PendingRequestSummary(id, sessionID)
                    }
                }
                .distinctBy { it.id }
        return ParsedPendingRequests(summaries, malformedEntry)
    }

    fun fetchRecentSessionsResult(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        maxAgeMillis: Long = RECENT_SESSION_WINDOW_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 20,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
        maxPages: Int = 10,
    ): OpenCodeProtocolResult<List<SessionSummary>> {
        val rootUrl = buildServerRootUrl(serverUrl)
        var url =
            rootUrl +
                "/api/session?order=desc&limit=$limit&directory=" +
                java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val sessions = linkedMapOf<String, SessionSummary>()
        val seenCursors = mutableSetOf<String>()
        repeat(maxPages.coerceAtLeast(1)) {
            val response =
                httpGetResult(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
            if (response is OpenCodeProtocolResult.Failure) return response
            val body = (response as OpenCodeProtocolResult.Success).value
            val page =
                parseSessionPage(body, maxAgeMillis, nowMillis)
                    ?: return OpenCodeProtocolResult.Failure(
                        OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                    )
            page.sessions.forEach { session -> sessions.putIfAbsent(session.id, session) }
            val cursor =
                page.nextCursor?.takeIf { it.isNotBlank() }
                    ?: return OpenCodeProtocolResult.Success(sessions.values.toList())
            if (!seenCursors.add(cursor)) {
                return OpenCodeProtocolResult.Failure(
                    OpenCodeProtocolResult.Failure.Kind.INVALID_BODY
                )
            }
            // Cursor pages still need directory (+ order). OpenCode scopes lists per project;
            // dropping directory lets page 2+ mix in other workspaces.
            url =
                rootUrl +
                    "/api/session?order=desc&limit=$limit&cursor=" +
                    java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8) +
                    "&directory=" +
                    java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        }
        return OpenCodeProtocolResult.Success(sessions.values.toList())
    }

    fun parseSessionList(json: String, maxAgeMillis: Long, nowMillis: Long): List<SessionSummary> {
        return parseSessionPage(json, maxAgeMillis, nowMillis)?.sessions.orEmpty()
    }

    fun parseSessionDirectory(json: String): String? {
        val root = parseJsonObject(json) ?: return null
        val session = root.objectMember("data") ?: root
        return sessionDirectory(session)
    }

    private fun sessionDirectory(session: JsonObject): String? {
        return session.objectMember("location")?.stringMember("directory")?.takeIf {
            it.isNotBlank()
        } ?: session.stringMember("directory")?.takeIf { it.isNotBlank() }
    }

    private fun parseSessionPage(json: String, maxAgeMillis: Long, nowMillis: Long): SessionPage? {
        // Response shape (verified against opencode 1.17.13):
        // {"data":[SessionV2Info...],"cursor":{...}}
        // with each session carrying id ("ses_...") and time.{created,updated} epoch millis.
        val root = parseJsonObject(json) ?: return null
        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val results = mutableListOf<SessionSummary>()
        for (element in data) {
            val session = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val id = session.stringMember("id")?.takeIf { it.startsWith("ses_") } ?: continue
            val updated = session.objectMember("time")?.longMember("updated") ?: continue
            if (nowMillis - updated <= maxAgeMillis) {
                results.add(
                    SessionSummary(
                        id,
                        updated,
                        session.stringMember("parentID")?.takeIf { it.isNotBlank() },
                        sessionDirectory(session),
                    )
                )
            }
        }
        val cursor = root.objectMember("cursor")?.stringMember("next")?.takeIf { it.isNotBlank() }
        return SessionPage(results.distinctBy { it.id }, cursor)
    }

    fun fetchLastMessageJsonResult(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): OpenCodeProtocolResult<String?> {
        if (wireProtocol == OpenCodeWireProtocol.UNKNOWN) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        }
        if (!isSessionId(sessionID) || directory.isBlank()) {
            return OpenCodeProtocolResult.Failure(
                OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER
            )
        }
        val root = buildServerRootUrl(serverUrl)
        if (!usesCliHttpApi(wireProtocol)) {
            val v1Url =
                "$root/session/$sessionID/message" +
                    "?directory=" +
                    java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8) +
                    "&limit=1"
            when (
                val v1 =
                    httpGetResult(v1Url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
            ) {
                is OpenCodeProtocolResult.Success -> {
                    val message = parseLastMessageResponse(v1.value)
                    if (message !is OpenCodeProtocolResult.Success || message.value != null)
                        return message
                    // Empty v1 list → try v2 (session may have been created through the v2 API).
                }
                is OpenCodeProtocolResult.Failure -> {
                    // A missing v1 route falls through to v2; transport failures must surface so
                    // recovery can retry rather than pretend "no message".
                    if (v1.statusCode != HttpURLConnection.HTTP_NOT_FOUND) return v1
                }
            }
        }
        val v2Url =
            "$root/api/session/$sessionID/message?order=desc&limit=1" +
                "&directory=" +
                java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        return when (
            val v2 = httpGetResult(v2Url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
        ) {
            is OpenCodeProtocolResult.Failure -> v2
            is OpenCodeProtocolResult.Success -> parseLastMessageResponse(v2.value)
        }
    }

    fun extractLastMessageRaw(body: String): String? =
        messageArray(body)?.firstOrNull { it.isJsonObject }?.toString()

    private fun parseLastMessageResponse(body: String): OpenCodeProtocolResult<String?> {
        val invalid =
            OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        val messages = messageArray(body) ?: return invalid
        if (messages.isEmpty) return OpenCodeProtocolResult.Success(null)
        val first = messages.first().takeIf { it.isJsonObject } ?: return invalid
        val normalized = normalizeLastMessageForClassification(first.toString()) ?: return invalid
        return OpenCodeProtocolResult.Success(normalized)
    }

    private fun messageArray(body: String): JsonArray? {
        val parsed = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
        return when {
            parsed.isJsonArray -> parsed.asJsonArray
            parsed.isJsonObject ->
                parsed.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        }
    }

    fun sendContinuePromptResult(
        serverUrl: String,
        basicAuthHeader: String,
        sessionID: String,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.V1_18,
    ): OpenCodeProtocolResult<Unit> {
        if (wireProtocol == OpenCodeWireProtocol.UNKNOWN) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        }
        if (!isSessionId(sessionID)) {
            return OpenCodeProtocolResult.Failure(
                OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER
            )
        }
        val url = buildServerRootUrl(serverUrl) + "/api/session/$sessionID/prompt"
        val body =
            if (usesCliHttpApi(wireProtocol)) {
                """{"text":"Continue","resume":true}"""
            } else {
                """{"prompt":{"text":"Continue"},"resume":true}"""
            }
        return httpPostResult(
            url,
            basicAuthHeader,
            body,
            connectTimeoutMillis,
            readTimeoutMillis,
        )
    }
}
