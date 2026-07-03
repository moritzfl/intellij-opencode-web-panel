package de.moritzf.opencodewebpanel.toolWindow

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.ln
import org.jetbrains.annotations.TestOnly

internal object OpenCodeServerProtocol {
    private const val HOST = "127.0.0.1"
    const val DYNAMIC_PORT = "0"
    const val CHECK_INTERVAL_SECONDS = 30L
    const val HEALTH_CHECK_CONFIRMATION_ATTEMPTS = 2
    const val HEALTH_CHECK_CONFIRMATION_DELAY_MILLIS = 3_000L
    const val HEALTH_CHECK_CONFIRMATION_TIMEOUT_MILLIS = 5_000
    private const val SUSPEND_DETECTION_SLACK_MILLIS = 60_000L
    private const val START_FAILURE_BACKOFF_BASE_MILLIS = 5_000L
    private const val START_FAILURE_BACKOFF_MAX_MILLIS = 60_000L
    const val HEALTH_PATH = "/api/health"
    const val GLOBAL_HEALTH_PATH = "/global/health"
    const val BASIC_AUTH_USERNAME = "opencode"
    const val DEFAULT_EXECUTABLE = "opencode"
    const val OPEN_FILE_LINK_SCHEME = "opencode-web-panel"
    const val OPEN_FILE_LINK_HOST = "open-file"
    const val OPEN_CODE_DEFAULT_SERVER_URL_STORAGE_KEY = "opencode.settings.dat:defaultServerUrl"
    const val OPEN_CODE_THEME_ID_STORAGE_KEY = "opencode-theme-id"
    const val OPEN_CODE_COLOR_SCHEME_STORAGE_KEY = "opencode-color-scheme"
    const val NOTIFICATION_GROUP_ID = "OpenCode Web Panel"
    const val RECENT_SESSION_WINDOW_MILLIS = 5 * 60 * 1000L

    private val secureRandom = SecureRandom()
    fun buildServerRootUrl(serverUrl: String): String {
        return serverUrl.trimEnd('/')
    }

    fun buildProjectUrl(serverUrl: String, projectBasePath: String? = null): String {
        val root = serverUrl.trimEnd('/')
        if (projectBasePath.isNullOrBlank()) return root
        return "$root/${encodeDirectory(projectBasePath)}/session"
    }

    fun buildOpenProjectScript(
        projectBasePath: String?,
        serverUrl: String? = null,
        openMostRecentConversation: Boolean = false,
    ): String? {
        if (projectBasePath.isNullOrBlank()) return null
        return OpenCodeBrowserSnippets.buildOpenProjectScript(
            projectBasePath = projectBasePath,
            projectPath = "/${encodeDirectory(projectBasePath)}/session",
            expectedOrigin = serverUrl?.let(::buildOrigin),
            openMostRecentConversation = openMostRecentConversation,
        )
    }

    @TestOnly
    fun buildFileLinkHandlerScript(projectBasePath: String?): String? {
        return buildFileLinkHandlerScript(projectBasePath, enabled = true)
    }

    fun buildRestoreOpenCodeLocalStorageScript(snapshot: String?): String? {
        return OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(snapshot)
    }

    fun buildSyncOpenCodeLocalStorageScript(openStorageCallback: String?): String? {
        return OpenCodeBrowserSnippets.buildSyncOpenCodeLocalStorageScript(openStorageCallback)
    }

    fun buildCodeNavigationScript(enabled: Boolean, openCodeCallback: String?): String? {
        return OpenCodeBrowserSnippets.buildCodeNavigationScript(enabled, openCodeCallback)
    }

    fun buildProjectSwitchPromptSuppressionScript(enabled: Boolean): String? {
        return OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled)
    }

    fun buildCursorMirrorScript(enabled: Boolean, cursorCallback: String?): String? {
        return OpenCodeBrowserSnippets.buildCursorMirrorScript(enabled, cursorCallback)
    }

    /**
     * Maps a CSS cursor computed value to the closest AWT predefined cursor type. Custom
     * `url(...)` cursors resolve through their keyword fallback; CSS values without an AWT
     * counterpart (help, copy, zoom-in, ...) fall back to the default arrow.
     */
    fun awtCursorTypeForCss(cssCursor: String?): Int {
        val keyword = cssCursor?.split(',')
            ?.map { it.trim().lowercase() }
            ?.lastOrNull { it.isNotBlank() && !it.startsWith("url(") }
            ?: return java.awt.Cursor.DEFAULT_CURSOR
        return when (keyword) {
            "pointer" -> java.awt.Cursor.HAND_CURSOR
            "text", "vertical-text" -> java.awt.Cursor.TEXT_CURSOR
            "wait", "progress" -> java.awt.Cursor.WAIT_CURSOR
            "crosshair", "cell" -> java.awt.Cursor.CROSSHAIR_CURSOR
            "move", "grab", "grabbing", "all-scroll" -> java.awt.Cursor.MOVE_CURSOR
            "n-resize" -> java.awt.Cursor.N_RESIZE_CURSOR
            "s-resize", "ns-resize", "row-resize" -> java.awt.Cursor.S_RESIZE_CURSOR
            "e-resize" -> java.awt.Cursor.E_RESIZE_CURSOR
            "w-resize", "ew-resize", "col-resize" -> java.awt.Cursor.W_RESIZE_CURSOR
            "ne-resize", "nesw-resize" -> java.awt.Cursor.NE_RESIZE_CURSOR
            "nw-resize", "nwse-resize" -> java.awt.Cursor.NW_RESIZE_CURSOR
            "se-resize" -> java.awt.Cursor.SE_RESIZE_CURSOR
            "sw-resize" -> java.awt.Cursor.SW_RESIZE_CURSOR
            else -> java.awt.Cursor.DEFAULT_CURSOR
        }
    }

    fun buildCompactLayoutScript(enabled: Boolean): String? {
        return OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled)
    }

    fun buildIdeThemeSyncScript(enabled: Boolean, dark: Boolean): String? {
        return OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled, dark)
    }

    @TestOnly
    fun buildDispatchDroppedFilesScript(files: List<DroppedFilePayload>): String? {
        return OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(files)
    }

    @TestOnly
    fun buildDispatchDroppedFilesScript(files: List<DroppedFilePayload>, enabled: Boolean): String? {
        return OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(files, enabled)
    }

    @TestOnly
    fun buildDispatchDroppedFilesScript(files: List<DroppedFilePayload>, textPlain: String?, enabled: Boolean): String? {
        return OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(files, textPlain, enabled)
    }

    fun buildDispatchDroppedFilesScript(files: List<DroppedFilePayload>, textPlain: List<String>, enabled: Boolean): String? {
        return OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(files, textPlain, enabled)
    }

    fun buildFilePasteSuppressionScript(enabled: Boolean): String? {
        return OpenCodeBrowserSnippets.buildFilePasteSuppressionScript(enabled)
    }

    fun localFileDropText(file: File, projectBasePath: String?): String? {
        val projectRoot = projectBasePath?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: return null
        val filePath = file.toPath().toAbsolutePath().normalize()
        if (!filePath.startsWith(projectRoot) || filePath == projectRoot) return null
        if (!Files.isRegularFile(filePath)) return null
        val relativePath = projectRoot.relativize(filePath).joinToString("/") { it.toString() }
        if (relativePath.isBlank() || relativePath.startsWith("..")) return null
        return "file:$relativePath"
    }

    @TestOnly
    fun buildFileLinkHandlerScript(projectBasePath: String?, enabled: Boolean): String? {
        return OpenCodeBrowserSnippets.buildFileLinkHandlerScript(projectBasePath, enabled)
    }

    fun buildFileLinkHandlerScript(projectBasePath: String?, enabled: Boolean, openFileCallback: String?): String? {
        return OpenCodeBrowserSnippets.buildFileLinkHandlerScript(projectBasePath, enabled, openFileCallback)
    }

    fun buildExternalLinkHandlerScript(enabled: Boolean, openExternalCallback: String?): String? {
        return OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(enabled, openExternalCallback)
    }

    fun externalHttpUrl(href: String?, serverUrl: String): String? {
        val text = href?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val uri = URI(text)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            if (shouldSendBasicAuthHeader(serverUrl, text)) return null
            uri.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun isOpenFileLinkRequest(requestUrl: String?): Boolean {
        if (requestUrl == null) return false
        return try {
            val uri = URI(requestUrl)
            uri.scheme == OPEN_FILE_LINK_SCHEME && uri.host == OPEN_FILE_LINK_HOST
        } catch (_: Exception) {
            false
        }
    }

    fun openFileLinkHref(requestUrl: String?): String? {
        if (!isOpenFileLinkRequest(requestUrl)) return null
        return openFileLinkQueryParameter(requestUrl, "href")
    }

    fun openFileLinkBase(requestUrl: String?): String? {
        if (!isOpenFileLinkRequest(requestUrl)) return null
        return openFileLinkQueryParameter(requestUrl, "base")
    }

    fun parseOpenFileLinkPayload(payload: String?): OpenFileLinkPayload? {
        val text = payload?.takeIf { it.isNotBlank() } ?: return null
        val parts = text.split('\n', limit = 2)
        val href = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return OpenFileLinkPayload(href, parts.getOrNull(1)?.takeIf { it.isNotBlank() })
    }

    private fun openFileLinkQueryParameter(requestUrl: String?, name: String): String? {
        val url = requestUrl ?: return null
        return URI(url).rawQuery
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
    }

    private fun URI.rawPathWithQuery(): String {
        // Trailing slashes are insignificant in the path but meaningful in a query value,
        // so normalize the path before appending the query.
        val path = (rawPath?.takeIf { it.isNotBlank() } ?: "/").trimEnd('/').ifBlank { "/" }
        val query = rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return path + query
    }

    private fun normalizedRoute(route: String?): String? {
        val text = route?.trim()?.takeIf { it.startsWith('/') } ?: return null
        return runCatching { URI(text).rawPathWithQuery() }.getOrNull()
    }

    fun routeDirectoryFromUrl(frameUrl: String?): String? {
        if (frameUrl.isNullOrBlank()) return null
        return try {
            val encodedDirectory = URI(frameUrl).path
                ?.trimStart('/')
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
                ?: return null
            decodeDirectory(encodedDirectory)
        } catch (_: Exception) {
            null
        }
    }

    fun isOpenCodeRouteAlreadyOpen(serverUrl: String?, currentUrl: String?, route: String?): Boolean {
        if (!isOpenCodeServerPage(serverUrl, currentUrl)) return false
        val targetRoute = normalizedRoute(route) ?: return false
        val currentRoute = normalizedRoute(runCatching { URI(currentUrl).rawPathWithQuery() }.getOrNull()) ?: return false
        return currentRoute == targetRoute
    }

    /**
     * Session routes of OpenCode's new layout (`/server/<key>/session/...`, `/new-session`) do not
     * encode the project directory, so a directory match cannot be verified for them. Callers use
     * this to treat such routes as valid destinations instead of waiting for a timeout.
     */
    fun isDirectorylessSessionRouteUrl(frameUrl: String?): Boolean {
        if (frameUrl.isNullOrBlank()) return false
        val path = runCatching { URI(frameUrl).path }.getOrNull() ?: return false
        return Regex("^/server/[^/]+/session(?:/|$)").containsMatchIn(path) ||
            path == "/new-session" ||
            path.startsWith("/new-session/")
    }

    fun resolveFileLink(href: String?, projectBasePath: String?): FileLinkTarget? {
        return resolveFileLink(href, projectBasePath, routeBasePath = null)
    }

    fun resolveFileLink(href: String?, projectBasePath: String?, routeBasePath: String?): FileLinkTarget? {
        val basePaths = listOfNotNull(routeBasePath?.takeIf { it.isNotBlank() }, projectBasePath?.takeIf { it.isNotBlank() })
            .distinct()
        if (href.isNullOrBlank() || basePaths.isEmpty()) return null
        if (isOpenCodeSessionRouteHref(href)) return null
        val parsed = parseFileLink(href, basePaths) ?: return null
        val path = candidateFileLinkPaths(parsed, basePaths)
            .firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
            ?: return null
        return FileLinkTarget(path, parsed.line?.coerceAtLeast(0), parsed.column?.coerceAtLeast(0))
    }

    fun isOpenCodeSessionRouteHref(href: String?): Boolean {
        val text = href?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val routePath = when {
            text.startsWith('/') -> text
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> {
                runCatching { URI(text).path }.getOrNull() ?: return false
            }
            else -> return false
        }
        val match = Regex("""^/([^/]+)/session(?:/|$)""")
            .find(routePath.substringBefore('?').substringBefore('#'))
            ?: return false
        val directory = decodeDirectory(match.groupValues[1]) ?: return false
        return looksLikeAbsoluteFilesystemPath(directory)
    }

    fun isSameFilesystemPath(first: String?, second: String?): Boolean {
        val left = normalizeFilesystemPathForComparison(first) ?: return false
        val right = normalizeFilesystemPathForComparison(second) ?: return false
        return left == right
    }

    private fun normalizeFilesystemPathForComparison(path: String?): String? {
        val text = path?.trim()?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        return if (Regex("^[A-Za-z]:/").containsMatchIn(text)) {
            text.replaceFirstChar { it.lowercase() }
        } else {
            text
        }
    }

    fun parseCodeReference(ref: String): ParsedCodeReference? {
        val text = ref.trim().ifBlank { return null }
        val lineMatch = Regex("^(.+):(\\d+)$").find(text)
        val (pathPart, line) = if (lineMatch != null) {
            lineMatch.groupValues[1] to (lineMatch.groupValues[2].toIntOrNull()?.minus(1))
        } else {
            text to null
        }
        val hasPath = pathPart.contains('/') || pathPart.contains('\\')
        val qualifiedName = if (!hasPath && Regex("^(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)+[A-Z][a-zA-Z0-9_]*$").matches(pathPart)) {
            pathPart
        } else {
            null
        }
        val fileName = (qualifiedName?.substringAfterLast('.')
            ?: pathPart.substringAfterLast('/').substringAfterLast('\\'))
            .ifBlank { return null }
        val extension = if (qualifiedName == null) fileName.substringAfterLast('.', "").ifBlank { null } else null
        return ParsedCodeReference(
            path = pathPart,
            qualifiedName = qualifiedName,
            fileName = fileName,
            extension = extension,
            line = line,
            hasPath = hasPath,
        )
    }

    data class ParsedCodeReference(
        val path: String,
        val qualifiedName: String?,
        val fileName: String,
        val extension: String?,
        val line: Int?,
        val hasPath: Boolean,
    )

    data class SystemNotificationPayload(
        val id: String,
        val directory: String,
        val route: String,
        val title: String,
        val body: String,
        val kind: String = "",
        val sessionID: String = "",
        val requestID: String = "",
    )

    /**
     * Keys under which a shown notification can be auto-dismissed. Permission and question
     * notifications are dismissed when their request is answered (a `request` scope key);
     * plain session notifications (response ready, session error) when the user views the
     * notified session (`session` scope). Deliberately not both for permissions: merely
     * viewing the session must not remove a still-unanswered request.
     */
    fun notificationDismissKeys(payload: SystemNotificationPayload): List<String> {
        return when (payload.kind) {
            "permission", "question" -> listOfNotNull(
                payload.requestID.takeIf(::isOpenCodeRecordId)?.let { "request:$it" },
            )
            else -> listOfNotNull(
                payload.sessionID.takeIf(::isOpenCodeRecordId)?.let { "session:$it" },
            )
        }
    }

    fun isPermissionNotification(payload: SystemNotificationPayload): Boolean {
        return payload.kind == "permission" &&
            isOpenCodeRecordId(payload.sessionID) &&
            isOpenCodeRecordId(payload.requestID)
    }

    /**
     * Answers a pending permission request via the long-standing endpoint the OpenCode web app
     * itself uses: `POST /session/{sessionID}/permissions/{permissionID}?directory=...` with
     * `{"response":"once"|"always"|"reject"}`. Returns true when the server accepted the reply.
     */
    enum class PermissionResponse(val jsonValue: String) {
        ONCE("once"), ALWAYS("always"), REJECT("reject")
    }

    fun replyToPermission(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        permissionID: String,
        response: PermissionResponse,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
    ): Boolean {
        if (!isOpenCodeRecordId(sessionID) || !isOpenCodeRecordId(permissionID)) return false
        return try {
            val url = buildServerRootUrl(serverUrl) +
                "/session/$sessionID/permissions/$permissionID" +
                "?directory=" + java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", basicAuthHeader)
                connection.setRequestProperty("Content-Type", "application/json")
                val body = "{\"response\":\"${response.jsonValue}\"}"
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** OpenCode record IDs (`ses_...`, `per_...`) are URL-safe by construction; reject anything else. */
    fun isOpenCodeRecordId(value: String): Boolean {
        return value.isNotBlank() && Regex("^[A-Za-z0-9_-]+$").matches(value)
    }

    private fun looksLikeAbsoluteFilesystemPath(value: String): Boolean {
        return value.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)
    }

    private fun parseFileLink(href: String, basePaths: List<String>): ParsedFileLink? {
        val withoutFragment = href.substringBefore('#')
        val fragment = href.substringAfter('#', missingDelimiterValue = "")
        val pathPart = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        val decodedPath = decodeFileLinkPath(pathPart).ifBlank { return null }
        val fragmentLineColumn = parseLineColumn(fragment)
        val queryLine = parseQueryLine(query)
        val trailingLineColumn = if (fragmentLineColumn == null && queryLine == null) {
            parseTrailingLineColumn(decodedPath, basePaths)
        } else {
            null
        }
        val pathText = trailingLineColumn?.first ?: decodedPath
        return runCatching {
            ParsedFileLink(
                Path.of(pathText).normalize(),
                fragmentLineColumn?.first ?: queryLine ?: trailingLineColumn?.second,
                fragmentLineColumn?.second ?: trailingLineColumn?.third,
                fallbackToProjectFileName = pathPart.startsWith("sandbox:", ignoreCase = true),
            )
        }.getOrNull()
    }

    private fun candidateFileLinkPaths(parsed: ParsedFileLink, basePaths: List<String>): List<Path> {
        val paths = if (parsed.path.isAbsolute) {
            listOf(parsed.path)
        } else {
            basePaths.map { Path.of(it).resolve(parsed.path).normalize() }
        }
        val fallbacks = if (parsed.fallbackToProjectFileName) {
            parsed.path.fileName?.let { fileName -> basePaths.map { Path.of(it).resolve(fileName).normalize() } }.orEmpty()
        } else {
            emptyList()
        }
        return (paths + fallbacks)
            .distinct()
    }

    private fun decodeFileLinkPath(value: String): String {
        if (value.startsWith("sandbox:", ignoreCase = true)) return value.substringAfter(':')
        if (!value.startsWith("file:", ignoreCase = true)) return URI(null, null, value, null).path ?: value
        val uri = runCatching { URI(value) }.getOrNull() ?: return value.removePrefix("file:")
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        val decoded = when {
            host.isNotEmpty() && !host.equals("localhost", ignoreCase = true) -> "$host/${path.trimStart('/')}"
            else -> path
        }
        return decoded.replace(Regex("^/([A-Za-z]:/)"), "$1")
    }

    private fun parseLineColumn(fragment: String): Pair<Int?, Int?>? {
        val match = Regex("^L?(\\d+)(?::(\\d+))?(?:-.+)?$").find(fragment) ?: return null
        return Pair(match.groupValues[1].toIntOrNull()?.minus(1), match.groupValues.getOrNull(2)?.toIntOrNull()?.minus(1))
    }

    private fun parseQueryLine(query: String): Int? {
        if (query.isBlank()) return null
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == "start" }
            ?.substringAfter('=', "")
            ?.toIntOrNull()
            ?.minus(1)
    }

    private fun parseTrailingLineColumn(path: String, basePaths: List<String>): Triple<String, Int?, Int?>? {
        val match = Regex("^(.+?):(\\d+)(?::(\\d+))?$").find(path) ?: return null
        val candidate = match.groupValues[1]
        val exists = if (Path.of(candidate).isAbsolute) {
            Files.exists(Path.of(candidate))
        } else {
            basePaths.any { Files.exists(Path.of(it).resolve(candidate).normalize()) }
        }
        if (!exists) return null
        return Triple(candidate, match.groupValues[2].toIntOrNull()?.minus(1), match.groupValues.getOrNull(3)?.toIntOrNull()?.minus(1))
    }

    data class FileLinkTarget(val path: Path, val line: Int?, val column: Int?)

    data class OpenFileLinkPayload(val href: String, val basePath: String?)

    data class DroppedFilePayload(
        val name: String,
        val mime: String,
        val lastModified: Long,
        val base64: String,
    )

    private data class ParsedFileLink(
        val path: Path,
        val line: Int?,
        val column: Int?,
        val fallbackToProjectFileName: Boolean,
    )

    fun isOpenCodeServerPage(serverUrl: String?, frameUrl: String?): Boolean {
        return shouldSendBasicAuthHeader(serverUrl, frameUrl)
    }

    fun buildAuthToken(password: String): String {
        val credentials = "$BASIC_AUTH_USERNAME:$password"
        return Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
    }

    fun buildBasicAuthHeader(password: String): String {
        return "Basic ${buildAuthToken(password)}"
    }

    fun shouldSendBasicAuthHeader(serverUrl: String?, requestUrl: String?): Boolean {
        if (serverUrl == null || requestUrl == null) return false
        return try {
            val server = URI(buildServerRootUrl(serverUrl))
            val request = URI(requestUrl)
            server.scheme.equals(request.scheme, ignoreCase = true) &&
                server.host.equals(request.host, ignoreCase = true) &&
                effectivePort(server) == effectivePort(request)
        } catch (_: Exception) {
            false
        }
    }

    fun shouldHandleBasicAuthChallenge(serverUrl: String?, isProxy: Boolean, host: String?, port: Int): Boolean {
        if (isProxy || serverUrl == null || host == null) return false

        val uri = URI(buildProjectUrl(serverUrl))
        val expectedPort = if (uri.port >= 0) uri.port else defaultPort(uri.scheme)
        return host.equals(uri.host, ignoreCase = true) && port == expectedPort
    }

    fun parseServerUrl(line: String): String? {
        val match = Regex("opencode server listening on (https?://\\S+)", RegexOption.IGNORE_CASE).find(line)
        return match?.groupValues?.get(1)?.trimEnd('/')
    }

    fun buildOpenCodeCommand(port: String = DYNAMIC_PORT, executable: String = DEFAULT_EXECUTABLE): List<String> {
        return listOf(executable.ifBlank { DEFAULT_EXECUTABLE }, "serve", "--hostname", HOST, "--port", port, "--print-logs")
    }

    fun createProcessBuilder(
        projectBasePath: String?,
        password: String,
        port: String = DYNAMIC_PORT,
        executable: String = DEFAULT_EXECUTABLE,
        path: String = resolvePath(),
        command: List<String> = buildOpenCodeCommand(port, resolveExecutableForLaunch(executable, path)),
    ): ProcessBuilder {
        val processBuilder = ProcessBuilder()
            .command(command)
            .redirectErrorStream(true)

        if (projectBasePath != null) {
            processBuilder.directory(File(projectBasePath))
        }

        processBuilder.environment()["PATH"] = path
        processBuilder.environment()["OPENCODE_SERVER_PASSWORD"] = password
        return processBuilder
    }

    fun generateServerPassword(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun checkServerResponding(
        serverUrl: String,
        basicAuthHeader: String? = null,
        connectTimeoutMillis: Int = 2000,
        readTimeoutMillis: Int = 2000,
    ): Boolean {
        return try {
            val connection = URI(buildHealthUrl(serverUrl)).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "GET"
                if (!basicAuthHeader.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", basicAuthHeader)
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                Regex("\"healthy\"\\s*:\\s*true").containsMatchIn(body)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun shouldRestartServer(serverUrl: String?, serverResponding: Boolean): Boolean {
        return serverUrl != null && !serverResponding
    }

    /**
     * Reads the OpenCode version from `/global/health` (`{"healthy":true,"version":"..."}`).
     * Returns null when the endpoint is unavailable; the version is informational only.
     */
    fun fetchServerVersion(
        serverUrl: String,
        basicAuthHeader: String?,
        connectTimeoutMillis: Int = 2000,
        readTimeoutMillis: Int = 2000,
    ): String? {
        return try {
            val connection = URI(buildServerRootUrl(serverUrl) + GLOBAL_HEALTH_PATH).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "GET"
                if (!basicAuthHeader.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", basicAuthHeader)
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Heuristic: does the server log tail look like a failed attempt to bind the port?
     * `ServeError` is the generic listen-failure marker of current OpenCode releases; the other
     * patterns cover classic bind errors. Callers should only act on this in fixed-port mode,
     * where switching to automatic port selection is a safe suggestion.
     */
    fun logIndicatesPortConflict(logLines: List<String>): Boolean {
        val pattern = Regex("EADDRINUSE|address already in use|is port .{0,16} in use|ServeError", RegexOption.IGNORE_CASE)
        return logLines.any { pattern.containsMatchIn(it) }
    }

    fun shouldDelayServerStart(nextStartAllowedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return nextStartAllowedAtMillis > nowMillis
    }

    fun startFailureBackoffMillis(consecutiveFailures: Int): Long {
        var delay = START_FAILURE_BACKOFF_BASE_MILLIS
        repeat((consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(20)) {
            delay = (delay * 2).coerceAtMost(START_FAILURE_BACKOFF_MAX_MILLIS)
        }
        return delay
    }

    fun resolvePath(
        currentPath: String = System.getenv("PATH").orEmpty(),
        additionalPaths: List<String>? = null,
        environment: Map<String, String> = System.getenv(),
    ): String {
        return (currentPath.split(File.pathSeparator) + (additionalPaths ?: commonExecutablePaths(environment)))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(File.pathSeparator)
    }

    fun detectExecutablePath(
        executable: String = DEFAULT_EXECUTABLE,
        path: String = resolvePath(),
        pathSeparator: String = File.pathSeparator,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): String? {
        val command = executable.trim().takeIf { it.isNotBlank() } ?: return null
        val commandFile = File(command)
        if (commandFile.isAbsolute || command.contains('/') || command.contains('\\')) {
            return commandFile.takeIf { it.isRunnableCommand() }?.absolutePath
        }

        return path.split(pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { directory -> candidateExecutableNames(command, osName).asSequence().map { File(directory, it) } }
            .firstOrNull { it.isRunnableCommand() }
            ?.absolutePath
    }

    fun resolveExecutableForLaunch(executable: String = DEFAULT_EXECUTABLE, path: String = resolvePath()): String {
        return detectExecutablePath(executable, path) ?: executable.ifBlank { DEFAULT_EXECUTABLE }
    }

    fun toCefZoomLevel(percent: Int): Double {
        val scale = percent.coerceAtLeast(1) / 100.0
        return ln(scale) / ln(1.2)
    }

    private fun commonExecutablePaths(environment: Map<String, String>): List<String> {
        val home = environmentValue(environment, "HOME")
        val appData = environmentValue(environment, "APPDATA")
        val localAppData = environmentValue(environment, "LOCALAPPDATA")
        val userProfile = environmentValue(environment, "USERPROFILE")
        val programData = environmentValue(environment, "PROGRAMDATA") ?: "C:\\ProgramData"
        val nvmHome = environmentValue(environment, "NVM_HOME")
        return listOfNotNull(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
            "/usr/sbin",
            "/sbin",
            home?.unixChild(".opencode/bin"),
            home?.unixChild(".local/bin"),
            home?.unixChild(".npm-global/bin"),
            home?.unixChild(".bun/bin"),
            home?.unixChild(".cargo/bin"),
            "C:\\Program Files\\nodejs",
            "C:\\Program Files (x86)\\nodejs",
            appData?.windowsChild("npm"),
            userProfile?.windowsChild("AppData\\Roaming\\npm"),
            localAppData?.windowsChild("pnpm"),
            localAppData?.windowsChild("Microsoft\\WindowsApps"),
            localAppData?.windowsChild("Programs\\opencode"),
            localAppData?.windowsChild("Volta\\bin"),
            userProfile?.windowsChild(".bun\\bin"),
            userProfile?.windowsChild("scoop\\shims"),
            nvmHome,
            programData.windowsChild("chocolatey\\bin"),
        )
    }

    private fun environmentValue(environment: Map<String, String>, key: String): String? {
        return environment.entries
            .firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.windowsChild(child: String): String = trimEnd('\\', '/') + "\\" + child

    private fun String.unixChild(child: String): String = trimEnd('/') + "/" + child

    private fun candidateExecutableNames(executable: String, osName: String): List<String> {
        val lower = executable.lowercase()
        val windowsExtensions = listOf(".cmd", ".exe", ".bat", ".ps1")
        if (!osName.startsWith("Windows", ignoreCase = true)) {
            return (listOf(executable) + windowsExtensions.filterNot { lower.endsWith(it) }.map { executable + it }).distinct()
        }
        return (windowsExtensions.filterNot { lower.endsWith(it) }.map { executable + it } + executable).distinct()
    }

    private fun File.isRunnableCommand(): Boolean {
        return Files.isRegularFile(toPath()) && (Files.isExecutable(toPath()) || hasWindowsCommandExtension(name))
    }

    private fun hasWindowsCommandExtension(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".cmd") || lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".ps1")
    }

    fun encodeDirectory(directory: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(directory.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeDirectory(directory: String): String? {
        val padding = "=".repeat((4 - directory.length % 4) % 4)
        return runCatching {
            String(Base64.getUrlDecoder().decode(directory + padding), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun decodeUrlParameter(value: String): String? {
        return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun buildHealthUrl(serverUrl: String): String {
        return buildServerRootUrl(serverUrl) + HEALTH_PATH
    }

    private fun buildOrigin(serverUrl: String): String {
        val uri = URI(buildServerRootUrl(serverUrl))
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }

    private fun defaultPort(scheme: String?): Int {
        return when (scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    private fun effectivePort(uri: URI): Int {
        return if (uri.port >= 0) uri.port else defaultPort(uri.scheme)
    }

    // ─── Interrupted-session recovery ───────────────────────────────────────────

    /**
     * Returns the wall-clock gap between two periodic-check runs when it is too large to be
     * scheduler jitter — i.e. the machine was suspended (sleep, hibernate) in between — or
     * null otherwise. On Apple Silicon the JVM's monotonic clock advances during sleep, so
     * the overdue tick fires right on wake and the gap approximates the sleep duration; on
     * platforms where it pauses, the tick fires up to one interval after wake instead.
     */
    fun detectSuspendGapMillis(previousRunMillis: Long, nowMillis: Long, intervalMillis: Long): Long? {
        if (previousRunMillis <= 0L) return null
        val gap = nowMillis - previousRunMillis
        return gap.takeIf { it > intervalMillis + SUSPEND_DETECTION_SLACK_MILLIS }
    }

    /**
     * Detects an assistant turn that a machine suspend severed: the turn started before the
     * machine went to sleep ([createdBeforeMillis]) and settled with an error only after it
     * resumed ([completedAfterMillis]) — the provider connection cannot survive the gap, and
     * nobody was at the machine to stop the turn in between. The error payload cannot serve
     * as the discriminator because a user stop settles with the same
     * `{"type":"unknown",...}` shape (see [isInterruptedLastMessage]); the timestamps can.
     */
    fun isSuspendSeveredLastMessage(messageJson: String, createdBeforeMillis: Long, completedAfterMillis: Long): Boolean {
        val message = parseJsonObject(messageJson) ?: return false
        if (message.stringMember("type") != "assistant") return false
        if (message.get("error")?.isJsonNull != false) return false
        val time = message.objectMember("time") ?: return false
        val created = time.longMember("created") ?: return false
        val completed = time.longMember("completed") ?: return false
        return created <= createdBeforeMillis && completed >= completedAfterMillis
    }

    /**
     * An assistant turn that started before [createdBeforeMillis] and has not settled yet
     * (no `time.completed`). After a resume from suspend such a turn is either hung on a dead
     * provider connection (and will settle with an error once the server notices) or genuinely
     * survived the sleep and is still streaming; callers poll until it settles either way.
     */
    fun isUnsettledTurnFromBefore(messageJson: String, createdBeforeMillis: Long): Boolean {
        val message = parseJsonObject(messageJson) ?: return false
        if (message.stringMember("type") != "assistant") return false
        val time = message.objectMember("time") ?: return false
        val created = time.longMember("created") ?: return false
        return created <= createdBeforeMillis && !time.has("completed")
    }

    // ─── Session lookup for notifications ───────────────────────────────────────

    data class SessionInfo(val title: String, val parentID: String?)

    /**
     * Fetches one session (`GET /session/{sessionID}?directory=...`), used for notification
     * titles and to skip notifications for child sessions. Handles both the bare session
     * object and the `{"data": {...}}` envelope. Returns null on any error.
     */
    fun fetchSessionInfo(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
    ): SessionInfo? {
        if (!isOpenCodeRecordId(sessionID)) return null
        val url = buildServerRootUrl(serverUrl) + "/session/" + sessionID + "?directory=" +
            java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val body = httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return null
        return parseSessionInfo(body)
    }

    fun parseSessionInfo(json: String): SessionInfo? {
        val root = parseJsonObject(json) ?: return null
        val session = root.objectMember("data") ?: root
        return SessionInfo(
            title = session.stringMember("title").orEmpty(),
            parentID = session.stringMember("parentID")?.takeIf { it.isNotBlank() },
        )
    }

    /** Builds the SPA route for a project directory, or one of its sessions if [sessionID] is set. */
    fun buildSessionRoute(directory: String, sessionID: String?): String {
        val root = "/" + encodeDirectory(directory)
        if (sessionID.isNullOrBlank()) return root
        return root + "/session/" + java.net.URLEncoder.encode(sessionID, StandardCharsets.UTF_8)
    }

    /**
     * Extracts the session ID from an OpenCode route URL (`.../session/<id>`), or null when
     * the URL shows no session. Both the classic and the new `/server/<key>/session/<id>`
     * layout put the ID after a `/session/` path segment.
     */
    fun sessionIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // rawPath keeps percent-encoding, so an encoded slash cannot split the segment early.
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        val encoded = Regex("/session/([^/?#]+)").find(path)?.groupValues?.get(1) ?: return null
        return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8) }.getOrNull()
            ?.takeIf { isOpenCodeRecordId(it) }
    }

    /** The last path segment of a project directory, for human-readable notification texts. */
    fun projectDisplayName(directory: String): String {
        return directory.trimEnd('/', '\\')
            .split('/', '\\')
            .lastOrNull { it.isNotBlank() }
            ?: directory
    }

    // ─── Agent-status seeding ───────────────────────────────────────────────────

    const val PERMISSION_LIST_PATH = "/permission"
    const val QUESTION_LIST_PATH = "/question"

    /**
     * Fetches the current session statuses for a project directory
     * (`GET /session/status?directory=...`, shape `{"ses_...": {"type": "busy"|...}, ...}`)
     * and returns the IDs of sessions that are busy or retrying. Returns null on any error
     * so callers can tell "no busy sessions" from "seed unavailable".
     */
    fun fetchBusySessionIds(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
    ): Set<String>? {
        val url = buildServerRootUrl(serverUrl) + "/session/status?directory=" +
            java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val body = httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return null
        return parseBusySessionIds(body)
    }

    fun parseBusySessionIds(json: String): Set<String> {
        val statuses = parseJsonObject(json) ?: return emptySet()
        return statuses.entrySet().mapNotNullTo(mutableSetOf()) { (sessionID, status) ->
            val type = status?.takeIf { it.isJsonObject }?.asJsonObject?.stringMember("type")
            sessionID.takeIf { type == "busy" || type == "retry" }
        }
    }

    /**
     * Fetches the pending permission or question requests for a project directory
     * (`GET /permission?directory=...` or `GET /question?directory=...`, a JSON array of
     * request objects) and returns their IDs. Returns null on any error.
     */
    fun fetchPendingRequestIds(
        serverUrl: String,
        basicAuthHeader: String,
        listPath: String,
        directory: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
    ): List<String>? {
        val url = buildServerRootUrl(serverUrl) + listPath + "?directory=" +
            java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val body = httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return null
        return parsePendingRequestIds(body)
    }

    fun parsePendingRequestIds(json: String): List<String> {
        val requests = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return requests.mapNotNull { request ->
            request.takeIf { it.isJsonObject }?.asJsonObject
                ?.stringMember("id")
                ?.takeIf { it.isNotBlank() }
        }
    }

    data class SessionSummary(val id: String, val updatedMillis: Long)

    /**
     * Fetches recent sessions for a project directory from the v2 API
     * (`GET /api/session?directory=...&order=desc&limit=N`). Returns session IDs with their
     * `time.updated` timestamp, filtered to those updated within [maxAgeMillis] of [nowMillis].
     */
    fun fetchRecentSessions(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        maxAgeMillis: Long = RECENT_SESSION_WINDOW_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 20,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
    ): List<SessionSummary> {
        val url = buildServerRootUrl(serverUrl) +
            "/api/session?order=desc&limit=$limit&directory=" +
            java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val body = httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return emptyList()
        return parseSessionList(body, maxAgeMillis, nowMillis)
    }

    @TestOnly
    fun parseSessionList(json: String, maxAgeMillis: Long, nowMillis: Long): List<SessionSummary> {
        // Response shape (verified against opencode 1.17.13): {"data":[SessionV2Info...],"cursor":{...}}
        // with each session carrying id ("ses_...") and time.{created,updated} epoch millis.
        val data = parseJsonObject(json)?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        val results = mutableListOf<SessionSummary>()
        for (element in data) {
            val session = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val id = session.stringMember("id")?.takeIf { it.startsWith("ses_") } ?: continue
            val updated = session.objectMember("time")
                ?.longMember("updated")
                ?: continue
            if (nowMillis - updated <= maxAgeMillis) {
                results.add(SessionSummary(id, updated))
            }
        }
        return results.distinctBy { it.id }
    }

    /**
     * Fetches the last projected message for a session from the v2 API
     * (`GET /api/session/{sessionID}/message?order=desc&limit=1`). Returns the raw JSON
     * object string for [isInterruptedLastMessage] to inspect, or null on any error.
     */
    fun fetchLastMessageJson(
        serverUrl: String,
        basicAuthHeader: String,
        sessionID: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
    ): String? {
        if (!isOpenCodeRecordId(sessionID)) return null
        val url = buildServerRootUrl(serverUrl) +
            "/api/session/$sessionID/message?order=desc&limit=1"
        val body = httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return null
        return extractFirstDataObject(body)
    }

    /** Extracts the first JSON object of the `data` array from a `{"data":[{...}]}` response. */
    @TestOnly
    fun extractFirstDataObject(body: String): String? {
        val data = parseJsonObject(body)?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return null
        return data.firstOrNull { it.isJsonObject }?.toString()
    }

    /**
     * Inspects the last projected session message for signs that the agent turn was
     * interrupted by a crash or kill (not by a user-initiated stop). Verified against a
     * live opencode 1.17.13 server:
     * - A hard kill mid-turn never persists the partial assistant reply, so after a crash
     *   the last message is the unanswered `user` prompt.
     * - An assistant message missing `time.completed`, or with a tool in `pending`/`running`
     *   state, is an in-flight projection that only an unclean shutdown leaves behind.
     * - A user-initiated stop settles the message: it sets both `time.completed` and the
     *   top-level `error` field, so it is intentionally not treated as a crash here.
     */
    fun isInterruptedLastMessage(messageJson: String): Boolean {
        val message = parseJsonObject(messageJson) ?: return false
        // A user prompt with no assistant reply after it: the turn died before any part of
        // the reply was persisted. Other non-assistant types (compaction, model-switched,
        // system, ...) do not imply an unanswered prompt.
        if (message.stringMember("type") == "user") return true
        if (message.stringMember("type") != "assistant") return false
        // Top-level error → the turn ended (user stop or provider failure), not a crash.
        if (message.get("error")?.isJsonNull == false) return false
        // time.completed missing → turn never finished (process died mid-turn).
        val time = message.objectMember("time")
        if (time != null && !time.has("completed")) return true
        // Any tool part with pending/running state → unsettled work.
        val content = message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        return content.any { part ->
            val partObject = part.takeIf { it.isJsonObject }?.asJsonObject
            partObject?.stringMember("type") == "tool" &&
                partObject.objectMember("state")
                    ?.stringMember("status") in listOf("pending", "running")
        }
    }

    private fun parseJsonObject(text: String): JsonObject? {
        if (text.isBlank()) return null
        return runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
    }

    /**
     * Sends a continuation prompt to a session via the v2 API
     * (`POST /api/session/{sessionID}/prompt` with `{"prompt":{"text":"Continue"},"resume":true}`).
     * Returns true when the server accepted the prompt.
     */
    fun sendContinuePrompt(
        serverUrl: String,
        basicAuthHeader: String,
        sessionID: String,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
    ): Boolean {
        if (!isOpenCodeRecordId(sessionID)) return false
        return try {
            val url = buildServerRootUrl(serverUrl) + "/api/session/$sessionID/prompt"
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", basicAuthHeader)
                connection.setRequestProperty("Content-Type", "application/json")
                val body = """{"prompt":{"text":"Continue"},"resume":true}"""
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun httpGet(
        url: String,
        basicAuthHeader: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): String? {
        return try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", basicAuthHeader)
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
