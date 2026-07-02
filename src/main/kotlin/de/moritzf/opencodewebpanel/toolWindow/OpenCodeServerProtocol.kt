package de.moritzf.opencodewebpanel.toolWindow

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

    fun buildSystemNotificationBridgeScript(enabled: Boolean, notificationCallback: String?): String? {
        return OpenCodeBrowserSnippets.buildSystemNotificationBridgeScript(enabled, notificationCallback)
    }

    fun buildAgentStatusBridgeScript(projectBasePath: String?, enabled: Boolean, statusCallback: String?): String? {
        return OpenCodeBrowserSnippets.buildAgentStatusBridgeScript(projectBasePath, enabled, statusCallback)
    }

    fun buildProjectSwitchPromptSuppressionScript(enabled: Boolean): String? {
        return OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled)
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

    fun parseSystemNotificationPayload(payload: String?): SystemNotificationPayload? {
        val parts = payload?.split('\n', limit = 8) ?: return null
        if (parts.size < 5) return null
        val id = decodeUrlParameter(parts[0])?.trim().orEmpty()
        val directory = decodeUrlParameter(parts[1])?.trim().orEmpty()
        val route = decodeUrlParameter(parts[2])?.trim().orEmpty()
        val title = decodeUrlParameter(parts[3])?.trim().orEmpty()
        val body = decodeUrlParameter(parts[4])?.trim().orEmpty()
        val kind = parts.getOrNull(5)?.let(::decodeUrlParameter)?.trim().orEmpty()
        val sessionID = parts.getOrNull(6)?.let(::decodeUrlParameter)?.trim().orEmpty()
        val requestID = parts.getOrNull(7)?.let(::decodeUrlParameter)?.trim().orEmpty()
        if (id.isBlank() || directory.isBlank() || !route.startsWith('/') || title.isBlank()) return null
        return SystemNotificationPayload(
            id = id,
            directory = directory,
            route = route,
            title = title,
            body = body,
            kind = kind,
            sessionID = sessionID,
            requestID = requestID,
        )
    }

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
        allow: Boolean,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
    ): Boolean = replyToPermission(
        serverUrl, basicAuthHeader, directory, sessionID, permissionID,
        if (allow) PermissionResponse.ONCE else PermissionResponse.REJECT,
        connectTimeoutMillis, readTimeoutMillis,
    )

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
    private fun isOpenCodeRecordId(value: String): Boolean {
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
}
