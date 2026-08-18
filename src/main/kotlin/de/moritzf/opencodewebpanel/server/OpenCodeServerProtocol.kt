package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.text.SemVer
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import org.jetbrains.annotations.TestOnly

internal enum class OpenCodeEmbeddedProtocol {
    V1,
    V2,
    UNKNOWN,
}

internal sealed interface OpenCodeProtocolResult<out T> {
    data class Success<T>(val value: T) : OpenCodeProtocolResult<T>
    data class Failure(
        val kind: Kind,
        val statusCode: Int? = null,
    ) : OpenCodeProtocolResult<Nothing> {
        enum class Kind { INVALID_IDENTIFIER, HTTP, TIMEOUT, IO, TOO_LARGE, INVALID_BODY }
    }
}

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
    const val DISPOSE_PATH = "/global/dispose"
    const val BASIC_AUTH_USERNAME = "opencode"
    const val DEFAULT_EXECUTABLE = "opencode"
    /** Bump this when the plugin requires a newer OpenCode release. */
    const val MINIMUM_SUPPORTED_OPENCODE_VERSION = "1.18.0"
    const val OPEN_FILE_LINK_SCHEME = "opencode-web-panel"
    const val OPEN_FILE_LINK_HOST = "open-file"
    const val OPEN_CODE_THEME_ID_STORAGE_KEY = "opencode-theme-id"
    const val OPEN_CODE_COLOR_SCHEME_STORAGE_KEY = "opencode-color-scheme"
    const val NOTIFICATION_GROUP_ID = "OpenCode Web Panel"
    const val RECENT_SESSION_WINDOW_MILLIS = 5 * 60 * 1000L
    /** Cap REST response bodies so a large diff/list cannot exhaust heap. */
    const val MAX_HTTP_RESPONSE_CHARS = 8 * 1024 * 1024

    private val secureRandom = SecureRandom()
    private val minimumSupportedOpenCodeVersion = requireNotNull(SemVer.parseFromText(MINIMUM_SUPPORTED_OPENCODE_VERSION))
    fun buildServerRootUrl(serverUrl: String): String {
        return serverUrl.trimEnd('/')
    }

    fun buildProjectUrl(serverUrl: String, projectBasePath: String? = null): String {
        val root = serverUrl.trimEnd('/')
        if (projectBasePath.isNullOrBlank()) return root
        return "$root/${encodeDirectory(projectBasePath)}/session"
    }

    /**
     * The opencode 1.18 web UI routes sessions under `/server/<serverKey>/session[/<id>]`, where
     * `serverKey` is the base64url (no padding) of the server origin — the SPA computes it exactly
     * like [encodeDirectory]. This is the route the plugin boots and navigates to; the older
     * `/<encodedDir>/session` project route is only kept as a redirect by the SPA and, without a
     * session id, crashes its error boundary, so the plugin no longer uses it.
     */
    fun buildServerSessionUrl(serverUrl: String, sessionId: String? = null): String {
        val base = "${buildServerRootUrl(serverUrl)}/server/${encodeDirectory(buildOrigin(serverUrl))}/session"
        return if (sessionId.isNullOrBlank()) base else "$base/$sessionId"
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

    // A null serverUrl treats every http(s) target as external: with no server to stay on,
    // the embedded panel has no legitimate http destination of its own.
    fun externalHttpUrl(href: String?, serverUrl: String?): String? {
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
            val path = URI(frameUrl).path?.takeIf { it.isNotBlank() } ?: return null
            // Directoryless 1.18 routes never encode a project directory.
            if (path == "/new-session" || path.startsWith("/new-session/") || path.startsWith("/server/")) {
                return null
            }
            val encodedDirectory = path.trimStart('/').substringBefore('/').takeIf { it.isNotBlank() } ?: return null
            val directory = decodeDirectory(encodedDirectory) ?: return null
            directory.takeIf(::looksLikeAbsoluteFilesystemPath)
        } catch (_: Exception) {
            null
        }
    }

    fun isOpenCodeRouteAlreadyOpen(serverUrl: String?, currentUrl: String?, route: String?): Boolean {
        if (!isOpenCodeServerPage(serverUrl, currentUrl)) return false
        val targetRoute = normalizedRoute(route)
            ?: normalizedRoute(runCatching { URI(route).rawPathWithQuery() }.getOrNull())
        val currentRoute = normalizedRoute(runCatching { URI(currentUrl).rawPathWithQuery() }.getOrNull())
        if (targetRoute != null && currentRoute != null && targetRoute == currentRoute) {
            return true
        }
        // Same session under different path shapes (legacy directory route vs /server/.../session)
        // still counts as already open — notification clicks must not force a reload. A target that
        // pins a query (e.g. ?tab=) still requires an exact route match above.
        if (targetRoute?.contains('?') == true) return false
        val targetSession = sessionIdFromUrl(route) ?: sessionIdFromUrl(absoluteRouteUrl(serverUrl, route))
        val currentSession = sessionIdFromUrl(currentUrl)
        return targetSession != null && currentSession != null && targetSession == currentSession
    }

    private fun absoluteRouteUrl(serverUrl: String?, route: String?): String? {
        val path = route?.trim()?.takeIf { it.startsWith('/') } ?: return null
        val root = serverUrl?.let(::buildServerRootUrl) ?: return null
        return root + path
    }

    fun resolveFileLink(href: String?, projectBasePath: String?): FileLinkTarget? {
        return resolveFileLink(href, projectBasePath, routeBasePath = null)
    }

    fun resolveFileLink(href: String?, projectBasePath: String?, routeBasePath: String?): FileLinkTarget? {
        val basePaths = listOfNotNull(routeBasePath?.takeIf { it.isNotBlank() }, projectBasePath?.takeIf { it.isNotBlank() })
            .distinct()
        return resolveFileLinkWithBases(href, basePaths)
    }

    internal fun resolveFileLinkWithBases(
        href: String?,
        basePaths: List<String>,
        caseSensitive: Boolean = SystemInfo.isFileSystemCaseSensitive,
    ): FileLinkTarget? {
        if (href.isNullOrBlank() || basePaths.isEmpty()) return null
        val cleanedHref = cleanFileLinkHref(href).ifBlank { return null }
        // Check the route exclusion on the cleaned spelling too: a decorated SPA route must not
        // slip past it and get preventDefault-ed as a file.
        if (isOpenCodeSessionRouteHref(href) || isOpenCodeSessionRouteHref(cleanedHref)) return null
        val parsed = parseFileLink(cleanedHref) ?: return null
        val hit = candidateFileLinkPaths(parsed, basePaths).firstOrNull { Files.isRegularFile(it.second) }
            ?: bestGuessFileLinkPath(parsed, basePaths, caseSensitive)
            ?: return null
        val (spelling, path) = hit
        return FileLinkTarget(path, spelling.line?.coerceAtLeast(0), spelling.column?.coerceAtLeast(0))
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
        val path = routePath.substringBefore('?').substringBefore('#')
        // Keep in sync with isOpenCodeAppRoute in OpenCodeBrowserSnippets (file-link exclusions).
        if (path == "/" || path.isEmpty()) return true
        if (path == "/new-session" || path.startsWith("/new-session/")) return true
        if (path == "/server" || path.startsWith("/server/")) return true
        // Legacy directory-encoded project root or session: first segment decodes to an abs path.
        val match = Regex("""^/([^/]+)(?:/session(?:/|$)|$)""").find(path) ?: return false
        val directory = decodeDirectory(match.groupValues[1]) ?: return false
        return looksLikeAbsoluteFilesystemPath(directory)
    }

    fun isSameFilesystemPath(first: String?, second: String?): Boolean {
        val left = filesystemPathKey(first) ?: return false
        val right = filesystemPathKey(second) ?: return false
        return left == right
    }

    /**
     * The directory string OpenCode persists on sessions (`FSUtil.resolve` / `realpath`).
     * The SPA's `session.get` uses exact `===` against this, so the panel must seed
     * `lastProject` / `lastProjectSession.directory` with this spelling — not IntelliJ's
     * raw `basePath` (`/var` vs `/private/var`, symlink aliases, Windows real casing).
     */
    fun canonicalOpenCodeDirectory(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Path.of(raw).toRealPath().toString() }
            .recoverCatching { Path.of(raw).toAbsolutePath().normalize().toString() }
            .getOrDefault(raw)
    }

    /**
     * Prefer the server's session-directory spelling when it is the same folder as [idePath].
     * Worktree/sandbox paths are left alone so `lastProject` stays the project root.
     */
    fun adoptOpenCodeDirectory(idePath: String?, serverPath: String?): String? {
        val canonical = canonicalOpenCodeDirectory(idePath) ?: idePath?.trim()?.takeIf { it.isNotBlank() }
        val server = serverPath?.trim()?.takeIf { it.isNotBlank() } ?: return canonical
        return if (isSameFilesystemPath(canonical ?: idePath, server)) server else canonical
    }

    private val canonicalPathCache = ConcurrentHashMap<String, String>()

    fun filesystemPathKey(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return canonicalPathCache.computeIfAbsent(raw) {
            val lexical = raw.replace('\\', '/').trimEnd('/')
            if (Regex("^[A-Za-z]:/").containsMatchIn(lexical) || lexical.startsWith("//")) {
                lexical.lowercase(Locale.ROOT)
            } else {
                runCatching { Path.of(raw).toRealPath().toString().replace('\\', '/').trimEnd('/') }
                    .getOrElse { lexical }
            }
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
     * Answers a pending permission request via the non-deprecated endpoint
     * `POST /permission/{requestID}/reply?directory=...` with `{"reply":"once"|"always"|"reject"}`.
     *
     * This replaces the deprecated `POST /session/{sessionID}/permissions/{permissionID}` +
     * `{"response":...}` form (op `permission.respond`, marked `deprecated` in the 1.17.x OpenAPI
     * spec) with its successor `permission.reply`. The `permissionID` is the `per_...` request id
     * carried by the `permission.asked`/`permission.replied` events. Returns true when the server
     * accepted the reply.
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
        if (!isSessionId(sessionID) || !isPermissionId(permissionID)) return false
        val url = buildServerRootUrl(serverUrl) +
            "/permission/$permissionID/reply" +
            "?directory=" + java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        return httpPostJson(url, basicAuthHeader, "{\"reply\":\"${response.jsonValue}\"}", connectTimeoutMillis, readTimeoutMillis)
    }

    /** OpenCode record IDs are URL-safe by construction; endpoint-specific helpers validate kind. */
    fun isOpenCodeRecordId(value: String): Boolean {
        return value.isNotBlank() && Regex("^[A-Za-z0-9_-]+$").matches(value)
    }

    fun isSessionId(value: String): Boolean = value.startsWith("ses_") && isOpenCodeRecordId(value)

    fun isMessageId(value: String): Boolean = value.startsWith("msg_") && isOpenCodeRecordId(value)

    fun isPermissionId(value: String): Boolean = value.startsWith("per_") && isOpenCodeRecordId(value)

    private fun looksLikeAbsoluteFilesystemPath(value: String): Boolean {
        return value.startsWith('/') || value.startsWith("\\\\") ||
            Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)
    }

    private fun parseFileLink(href: String): ParsedFileLink? {
        val withoutFragment = href.substringBefore('#')
        val fragment = href.substringAfter('#', missingDelimiterValue = "")
        val pathPart = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        val decodedPaths = decodeFileLinkPaths(pathPart).takeIf { it.isNotEmpty() } ?: return null
        val fragmentLineColumn = parseLineColumn(fragment)
        val queryLine = parseQueryLine(query)
        val explicitLine = fragmentLineColumn?.first ?: queryLine
        val explicitColumn = fragmentLineColumn?.second
        // Each spelling carries the position that applies when *it* is the one found on disk, so
        // a `src/Main.kt:42` reference can be probed both stripped (a line reference, by far the
        // common case) and whole (a file whose name really ends in `:42`) without the line number
        // leaking onto the wrong reading.
        val spellings = decodedPaths.flatMap { text ->
            val trailing = if (explicitLine == null) parseTrailingLineColumn(text) else null
            listOfNotNull(
                trailing?.let { spellingOf(it.first, it.second, it.third) },
                spellingOf(text, explicitLine, explicitColumn),
            )
        }.distinctBy { it.text }
        if (spellings.isEmpty()) return null
        return ParsedFileLink(
            spellings,
            fallbackToProjectFileName = pathPart.startsWith("sandbox:", ignoreCase = true),
        )
    }

    /** Parses one path text; returns null for text that is not a legal path on this platform. */
    private fun spellingOf(text: String, line: Int?, column: Int?): PathSpelling? {
        val path = runCatching { Path.of(text).normalize() }.getOrNull() ?: return null
        return PathSpelling(text, path, line, column)
    }

    /**
     * A root-relative link (`/src/Main.kt`) is absolute on Unix but on Windows `Path.isAbsolute`
     * is false for it — and `Path.of("C:/proj").resolve("/src/Main.kt")` keeps only the drive
     * root, silently dropping the project directory. Decide on the href text, which is the same
     * on every platform, and strip the separator before resolving under a base.
     */
    private fun isRootRelative(text: String): Boolean = text.startsWith('/') || text.startsWith('\\')

    private fun candidateFileLinkPaths(
        parsed: ParsedFileLink,
        basePaths: List<String>,
    ): List<Pair<PathSpelling, Path>> {
        val paths = parsed.paths.flatMap { spelling ->
            candidatesFor(spelling, basePaths).map { spelling to it }
        }
        val fallbacks = if (parsed.fallbackToProjectFileName) {
            parsed.paths.flatMap { spelling ->
                val fileName = spelling.path.fileName ?: return@flatMap emptyList()
                basePaths.mapNotNull { base ->
                    runCatching { Path.of(base).resolve(fileName).normalize() }.getOrNull()?.let { spelling to it }
                }
            }
        } else {
            emptyList()
        }
        return (paths + fallbacks).distinctBy { it.second }
    }

    private fun candidatesFor(spelling: PathSpelling, basePaths: List<String>): List<Path> {
        val underBases = { relative: String ->
            basePaths.mapNotNull { base ->
                runCatching { Path.of(base).resolve(relative).normalize() }.getOrNull()
            }
        }
        return when {
            // Real absolute path (Unix `/x` or Windows `C:\x`): the literal target wins, but a
            // project-relative reading is still tried so `/src/Main.kt` works in both worlds.
            spelling.path.isAbsolute -> listOf(spelling.path) + underBases(spelling.text.trimStart('/', '\\'))
            isRootRelative(spelling.text) -> underBases(spelling.text.trimStart('/', '\\'))
            else -> underBases(spelling.text)
        }
    }

    /** Strips surrounding whitespace a chat link can pick up before it reaches the resolver. */
    private fun cleanFileLinkHref(href: String): String = href.trim()

    /** Bidi marks survive percent-decoding, so they are stripped from the decoded spelling. */
    private fun stripBidiMarks(value: String): String =
        value.replace(Regex("[\\u202A-\\u202E\\u2066-\\u2069]"), "")

    /**
     * Turns one href into the path spellings worth probing on disk, most specific first.
     *
     * OpenCode renders markdown links through marked, which runs `encodeURI` on every href, so a
     * relative link to a path containing a space or any non-ASCII character arrives
     * percent-encoded (`docs/My%20File.md`). Plain ASCII paths are untouched, which is why only
     * some relative links used to fail. Both spellings are returned because a file name may also
     * legitimately contain a literal `%`.
     */
    private fun decodeFileLinkPaths(value: String): List<String> {
        if (value.startsWith("sandbox:", ignoreCase = true)) {
            return withPercentDecoded(value.substringAfter(':'))
        }
        if (!value.startsWith("file:", ignoreCase = true)) return withPercentDecoded(value)
        val uri = runCatching { URI(value) }.getOrNull() ?: return withPercentDecoded(value.removePrefix("file:"))
        val host = uri.host.orEmpty()
        // `file:src/Main.kt` (no authority) is an opaque URI: `path` is null and the payload sits
        // in the scheme-specific part. That is the form the panel itself writes for dropped files.
        // Take the *raw* form so the single decode below is not applied twice.
        val path = uri.rawPath ?: uri.rawSchemeSpecificPart.orEmpty()
        val decoded = when {
            host.isNotEmpty() && !host.equals("localhost", ignoreCase = true) -> "$host/${path.trimStart('/')}"
            else -> path
        }
        return withPercentDecoded(decoded.replace(Regex("^/([A-Za-z]:/)"), "$1"))
    }

    private fun withPercentDecoded(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return emptyList()
        if (!trimmed.contains('%')) return listOf(stripBidiMarks(trimmed)).filter { it.isNotBlank() }
        // `+` is a literal in a path segment, so decode percent escapes only. A malformed escape
        // (`%zz`, a trailing `%`) throws, leaving just the raw spelling.
        val decoded = runCatching {
            URLDecoder.decode(trimmed.replace("+", "%2B"), StandardCharsets.UTF_8)
        }.getOrNull()?.let(::stripBidiMarks)?.takeIf { it.isNotBlank() && it != trimmed }
        return listOfNotNull(decoded, trimmed)
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

    private fun parseTrailingLineColumn(path: String): Triple<String, Int?, Int?>? {
        val match = Regex("^(.+?):(\\d+)(?::(\\d+))?$").find(path) ?: return null
        return Triple(
            match.groupValues[1],
            match.groupValues[2].toIntOrNull()?.minus(1),
            match.groupValues.getOrNull(3)?.toIntOrNull()?.minus(1),
        )
    }

    /**
     * Last-resort resolution for a reference whose path is incomplete or anchored at a different
     * level than the panel's project directory — a model writing `src/Main.kt` for
     * `packages/app/src/Main.kt`, or `packages/app/src/Main.kt` while the panel is already inside
     * `packages/app`. Both readings are guesses, so this only runs after every exact candidate
     * missed, and it prefers the most specific match it can prove.
     *
     * Order: drop leading segments of the reference (cheap, no directory walk), then a bounded
     * search under each base for a file whose trailing segments match the reference.
     */
    private fun bestGuessFileLinkPath(
        parsed: ParsedFileLink,
        basePaths: List<String>,
        caseSensitive: Boolean,
    ): Pair<PathSpelling, Path>? {
        val references = parsed.paths.filter { it.path.nameCount > 0 }
        if (references.isEmpty()) return null
        // 1. The base may already sit inside the referenced path: try `a/b/c` → `b/c` → `c`.
        for (spelling in references) {
            val reference = spelling.path
            for (start in 1 until reference.nameCount) {
                val suffix = reference.subpath(start, reference.nameCount)
                val hit = basePaths.asSequence()
                    .mapNotNull { runCatching { Path.of(it).resolve(suffix).normalize() }.getOrNull() }
                    .firstOrNull { Files.isRegularFile(it) }
                if (hit != null) return spelling to hit
            }
        }
        // 2. The reference may be missing leading segments: search for a matching tail.
        return references.firstNotNullOfOrNull { spelling ->
            basePaths.firstNotNullOfOrNull { base ->
                searchBySuffix(base, spelling.path, caseSensitive)?.let { spelling to it }
            }
        }
    }

    /** Directories that never hold sources but dominate a naive walk. */
    private val SEARCH_PRUNED_DIRECTORIES = setOf(
        ".git", ".hg", ".svn", ".idea", ".gradle", ".venv", "venv", "node_modules",
        "build", "out", "dist", "target", "bin", "obj", ".next", ".cache", "__pycache__",
    )
    private const val SEARCH_MAX_VISITED_ENTRIES = 20_000
    private const val SEARCH_MAX_DEPTH = 12

    /**
     * Finds the file under [base] whose path ends with the segments of [reference]. Ranked by
     * matched segments (most specific first), then by depth and path so the pick is stable;
     * bounded in depth and visited files so a click can never hang on a huge tree.
     */
    private fun searchBySuffix(base: String, reference: Path, caseSensitive: Boolean): Path? {
        val root = runCatching { Path.of(base) }.getOrNull()?.takeIf { Files.isDirectory(it) } ?: return null
        val segments = (0 until reference.nameCount).map { reference.getName(it).toString() }
        val fileName = segments.lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        var visited = 0
        var best: Path? = null
        var bestScore = -1
        var bestDepth = Int.MAX_VALUE
        val stack = ArrayDeque(listOf(root to 0))
        while (stack.isNotEmpty()) {
            val (directory, depth) = stack.removeLast()
            if (depth > SEARCH_MAX_DEPTH) continue
            val entries = runCatching { Files.newDirectoryStream(directory) }.getOrNull() ?: continue
            entries.use { directoryEntries ->
                for (entry in directoryEntries) {
                    if (++visited > SEARCH_MAX_VISITED_ENTRIES) return best
                    val name = entry.fileName?.toString() ?: continue
                    if (Files.isDirectory(entry)) {
                        val prunedName = if (caseSensitive) name else name.lowercase(Locale.ROOT)
                        if (name.startsWith('.') || prunedName in SEARCH_PRUNED_DIRECTORIES) continue
                        stack.addLast(entry to depth + 1)
                        continue
                    }
                    if (!name.equals(fileName, ignoreCase = !caseSensitive)) continue
                    val score = matchedSuffixSegments(entry, segments, caseSensitive)
                    val entryDepth = entry.nameCount
                    val better = score > bestScore || (score == bestScore && entryDepth < bestDepth) ||
                        (score == bestScore && entryDepth == bestDepth && best != null && entry.toString() < best.toString())
                    if (better) {
                        best = entry
                        bestScore = score
                        bestDepth = entryDepth
                    }
                }
            }
        }
        return best
    }

    /** How many trailing segments of [candidate] match [segments]; at least 1 (the file name). */
    private fun matchedSuffixSegments(candidate: Path, segments: List<String>, caseSensitive: Boolean): Int {
        var matched = 0
        var candidateIndex = candidate.nameCount - 1
        var segmentIndex = segments.size - 1
        while (candidateIndex >= 0 && segmentIndex >= 0) {
            if (!candidate.getName(candidateIndex).toString()
                    .equals(segments[segmentIndex], ignoreCase = !caseSensitive)
            ) {
                break
            }
            matched++
            candidateIndex--
            segmentIndex--
        }
        return matched
    }

    data class FileLinkTarget(val path: Path, val line: Int?, val column: Int?)

    data class OpenFileLinkPayload(val href: String, val basePath: String?)

    data class DroppedFilePayload(
        val name: String,
        val mime: String,
        val lastModified: Long,
        val base64: String,
    )

    /**
     * One spelling of a linked path: the text, its parsed form, and the position that applies
     * when this spelling is the one found on disk.
     */
    private data class PathSpelling(val text: String, val path: Path, val line: Int?, val column: Int?)

    private data class ParsedFileLink(
        /** Path spellings to probe, most specific first (see [decodeFileLinkPaths]). */
        val paths: List<PathSpelling>,
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
        val candidate = match?.groupValues?.get(1)?.trimEnd('/') ?: return null
        return candidate.takeIf { isLoopbackServerUrl(it) }
    }

    /** Accept only loopback URLs so a misbehaving binary cannot redirect auth traffic. */
    fun isLoopbackServerUrl(serverUrl: String): Boolean {
        return try {
            val uri = URI(serverUrl)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "::1"
        } catch (_: Exception) {
            false
        }
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
        httpProxy: IdeHttpProxy? = null,
        stripInheritedProxy: Boolean = false,
    ): ProcessBuilder {
        val processBuilder = ProcessBuilder()
            .command(command)
            .redirectErrorStream(true)

        if (projectBasePath != null) {
            processBuilder.directory(File(projectBasePath))
        }

        processBuilder.environment()["PATH"] = path
        processBuilder.environment()["OPENCODE_SERVER_PASSWORD"] = password
        if (stripInheritedProxy) {
            OpenCodeProcessProxyEnvironment.strip(processBuilder.environment())
        } else {
            OpenCodeProcessProxyEnvironment.apply(processBuilder.environment(), httpProxy)
        }
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
        val body = httpGet(buildHealthUrl(serverUrl), basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return false
        return parseJsonObject(body)?.booleanMember("healthy") == true
    }

    /**
     * Reads the OpenCode version from `/global/health` (`{"healthy":true,"version":"..."}`).
     * Returns null when the endpoint is unavailable; an unavailable version never blocks startup.
     */
    fun fetchServerVersion(
        serverUrl: String,
        basicAuthHeader: String?,
        connectTimeoutMillis: Int = 2000,
        readTimeoutMillis: Int = 2000,
    ): String? {
        val body = httpGet(buildServerRootUrl(serverUrl) + GLOBAL_HEALTH_PATH, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
            ?: return null
        return parseJsonObject(body)?.stringMember("version")
    }

    /**
     * Mirrors the SPA's `detectServerProtocol`: `/global/health` with `{healthy:true}` is v1;
     * `/api/health` with a numeric `pid` is v2; `{healthy:true}` on `/api/health` is still v1;
     * otherwise a reachable server defaults to v2. Unreachable probes stay [UNKNOWN] so a
     * transport blip does not warn that permissions will vanish.
     */
    fun detectEmbeddedProtocol(
        serverUrl: String,
        basicAuthHeader: String?,
        connectTimeoutMillis: Int = 2000,
        readTimeoutMillis: Int = 2000,
    ): OpenCodeEmbeddedProtocol {
        val root = buildServerRootUrl(serverUrl)
        val global = httpGetResult(root + GLOBAL_HEALTH_PATH, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
        val api = if (isV1HealthResult(global)) {
            null
        } else {
            httpGetResult(root + HEALTH_PATH, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
        }
        return classifyEmbeddedProtocol(global, api)
    }

    @TestOnly
    fun classifyEmbeddedProtocolForTest(
        global: OpenCodeProtocolResult<String>?,
        api: OpenCodeProtocolResult<String>?,
    ): OpenCodeEmbeddedProtocol = classifyEmbeddedProtocol(global, api)

    private fun classifyEmbeddedProtocol(
        global: OpenCodeProtocolResult<String>?,
        api: OpenCodeProtocolResult<String>?,
    ): OpenCodeEmbeddedProtocol {
        if (isV1HealthResult(global)) return OpenCodeEmbeddedProtocol.V1
        if (isV2HealthResult(api)) return OpenCodeEmbeddedProtocol.V2
        if (isV1HealthResult(api)) return OpenCodeEmbeddedProtocol.V1
        if (isHttpAnswer(global) || isHttpAnswer(api)) return OpenCodeEmbeddedProtocol.V2
        return OpenCodeEmbeddedProtocol.UNKNOWN
    }

    private fun isV1HealthResult(result: OpenCodeProtocolResult<String>?): Boolean {
        val body = (result as? OpenCodeProtocolResult.Success)?.value ?: return false
        return parseJsonObject(body)?.booleanMember("healthy") == true
    }

    private fun isV2HealthResult(result: OpenCodeProtocolResult<String>?): Boolean {
        val body = (result as? OpenCodeProtocolResult.Success)?.value ?: return false
        val pid = parseJsonObject(body)?.get("pid") ?: return false
        return pid.isJsonPrimitive && pid.asJsonPrimitive.isNumber
    }

    private fun isHttpAnswer(result: OpenCodeProtocolResult<String>?): Boolean {
        return result is OpenCodeProtocolResult.Success ||
            (result is OpenCodeProtocolResult.Failure && result.kind == OpenCodeProtocolResult.Failure.Kind.HTTP)
    }

    /**
     * Unknown version formats are not treated as unsupported, avoiding a false update warning
     * when the health endpoint cannot report a normal semantic version.
     */
    fun isOpenCodeVersionUnsupported(version: String?): Boolean {
        val parsedVersion = version
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.takeIf { it.isNotEmpty() }
            ?.let { SemVer.parseFromText(it) }
            ?: return false
        return parsedVersion < minimumSupportedOpenCodeVersion
    }

    fun disposeServer(
        serverUrl: String,
        basicAuthHeader: String,
        connectTimeoutMillis: Int = 2_000,
        readTimeoutMillis: Int = 5_000,
    ): Boolean {
        return try {
            val connection = URI(buildServerRootUrl(serverUrl) + DISPOSE_PATH).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", basicAuthHeader)
                connection.setFixedLengthStreamingMode(0)
                connection.doOutput = true
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
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

    fun startFailureBackoffMillis(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 4)
        return (START_FAILURE_BACKOFF_BASE_MILLIS shl exponent).coerceAtMost(START_FAILURE_BACKOFF_MAX_MILLIS)
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
        // The JDK URL decoder accepts unpadded base64url input.
        return runCatching {
            String(Base64.getUrlDecoder().decode(directory), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun buildHealthUrl(serverUrl: String): String {
        return buildServerRootUrl(serverUrl) + HEALTH_PATH
    }

    internal fun buildOrigin(serverUrl: String): String {
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

    data class SessionInfo(val title: String, val parentID: String?, val id: String = "")

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
        if (!isSessionId(sessionID)) return null
        val url = buildServerRootUrl(serverUrl) + "/session/" + sessionID + "?directory=" +
            java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val body = httpGet(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis) ?: return null
        return parseSessionInfo(body)?.takeIf { it.id == sessionID }
    }

    fun parseSessionInfo(json: String): SessionInfo? {
        val root = parseJsonObject(json) ?: return null
        val session = root.objectMember("data") ?: root
        val id = session.stringMember("id")?.takeIf(::isSessionId) ?: return null
        return SessionInfo(
            title = session.stringMember("title").orEmpty(),
            parentID = session.stringMember("parentID")?.takeIf { it.isNotBlank() },
            id = id,
        )
    }

    // ─── Session diffs (for the "open diff in IDE" feature) ─────────────────────

    /** One file's diff as returned by `GET /session/{id}/diff`; `patch` is a unified diff string. */
    data class SnapshotFileDiff(
        val file: String?,
        val patch: String?,
        val additions: Long,
        val deletions: Long,
        val status: String?,
    )

    /**
     * Fetches the diff for a session (`GET /session/{sessionID}/diff?directory=...`). With a
     * [messageID] (`msg_...`) it returns that message's snapshot diff; without one it returns the
     * session's cumulative diff. Returns an empty list on any error.
     */
    fun fetchSessionDiff(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        messageID: String? = null,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
    ): List<SnapshotFileDiff> {
        return when (val result = fetchSessionDiffResult(
            serverUrl,
            basicAuthHeader,
            directory,
            sessionID,
            messageID,
            connectTimeoutMillis,
            readTimeoutMillis,
        )) {
            is OpenCodeProtocolResult.Success -> result.value
            is OpenCodeProtocolResult.Failure -> emptyList()
        }
    }

    fun fetchSessionDiffResult(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        messageID: String? = null,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
    ): OpenCodeProtocolResult<List<SnapshotFileDiff>> {
        if (!isSessionId(sessionID)) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER)
        }
        val normalizedMessageID = messageID?.takeIf { it.isNotBlank() }
        if (normalizedMessageID != null && !isMessageId(normalizedMessageID)) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER)
        }
        val messageParam = normalizedMessageID
            ?.let { "&messageID=" + java.net.URLEncoder.encode(it, StandardCharsets.UTF_8) }
            .orEmpty()
        val url = buildServerRootUrl(serverUrl) + "/session/" + sessionID + "/diff?directory=" +
            java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8) + messageParam
        return when (val response = httpGetResult(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)) {
            is OpenCodeProtocolResult.Failure -> response
            is OpenCodeProtocolResult.Success -> {
                val array = parseJsonArray(response.value)
                    ?: return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
                OpenCodeProtocolResult.Success(parseSessionDiffArray(array))
            }
        }
    }

    @TestOnly
    fun parseSessionDiff(json: String): List<SnapshotFileDiff> {
        val array = parseJsonArray(json) ?: return emptyList()
        return parseSessionDiffArray(array)
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
                ),
            )
        }
        return results
    }

    /**
     * Path-only SPA route for opening a session from a notification.
     * Prefer the 1.18 `/server/<key>/session/<id>` form when [serverUrl] is known; fall back to the
     * legacy directory-encoded route only when the origin cannot be derived.
     */
    fun buildSessionRoute(serverUrl: String?, directory: String, sessionID: String?): String {
        if (!serverUrl.isNullOrBlank()) {
            val path = runCatching { URI(buildServerSessionUrl(serverUrl, sessionID?.takeIf(::isSessionId))).rawPath }
                .getOrNull()
            if (!path.isNullOrBlank()) return path
        }
        val root = "/" + encodeDirectory(directory)
        if (sessionID.isNullOrBlank() || !isSessionId(sessionID)) return root
        return root + "/session/" + java.net.URLEncoder.encode(sessionID, StandardCharsets.UTF_8)
    }

    fun buildSessionRoute(directory: String, sessionID: String?): String {
        return buildSessionRoute(serverUrl = null, directory = directory, sessionID = sessionID)
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
            ?.takeIf { isSessionId(it) }
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
        if (parseJsonObject(body) == null) return null
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
        return when (val result = fetchPendingRequestsResult(
            serverUrl,
            basicAuthHeader,
            listPath,
            directory,
            connectTimeoutMillis,
            readTimeoutMillis,
        )) {
            is OpenCodeProtocolResult.Success -> result.value.map { it.id }
            is OpenCodeProtocolResult.Failure -> null
        }
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

    data class PendingRequestSummary(val id: String, val sessionID: String)
    private data class ParsedPendingRequests(
        val requests: List<PendingRequestSummary>,
        val malformedEntry: Boolean,
    )

    fun fetchPendingRequestsResult(
        serverUrl: String,
        basicAuthHeader: String,
        listPath: String,
        directory: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
    ): OpenCodeProtocolResult<List<PendingRequestSummary>> {
        val url = buildServerRootUrl(serverUrl) + listPath + "?directory=" +
            java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        return when (val response = httpGetResult(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)) {
            is OpenCodeProtocolResult.Failure -> response
            is OpenCodeProtocolResult.Success -> {
                parsePendingRequestsResult(response.value)
            }
        }
    }

    @TestOnly
    fun parsePendingRequests(json: String): List<PendingRequestSummary> = parsePendingRequestsBody(json)?.requests.orEmpty()

    fun parsePendingRequestsResult(json: String): OpenCodeProtocolResult<List<PendingRequestSummary>> {
        val parsed = parsePendingRequestsBody(json)
            ?: return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        if (parsed.malformedEntry) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
        }
        return OpenCodeProtocolResult.Success(parsed.requests)
    }

    private fun parsePendingRequestsBody(json: String): ParsedPendingRequests? {
        val requests = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        var malformedEntry = false
        val parsed = requests.mapNotNull { request ->
            val value = request.takeIf { it.isJsonObject }?.asJsonObject
            val id = value?.stringMember("id")?.takeIf(::isOpenCodeRecordId)
            val sessionID = value?.stringMember("sessionID")?.takeIf(::isSessionId)
            if (id == null || sessionID == null) {
                malformedEntry = true
                null
            } else {
                PendingRequestSummary(id, sessionID)
            }
        }.distinctBy { it.id }
        return ParsedPendingRequests(parsed, malformedEntry)
    }

    data class SessionSummary(
        val id: String,
        val updatedMillis: Long,
        val parentID: String? = null,
        val directory: String? = null,
    )
    private data class SessionPage(val sessions: List<SessionSummary>, val nextCursor: String?)

    /**
     * Fetches recent sessions for a project directory from the v2 API
     * (`GET /api/session?directory=...&order=desc&limit=N`). Returns session IDs with their
     * `time.updated` timestamp and parent ID, filtered to those updated within [maxAgeMillis]
     * of [nowMillis]. Note (verified against opencode 1.17.13): the listing is ordered by
     * creation, not by `time.updated`, and includes subagent child sessions — callers that
     * want "most recent activity" must select by [SessionSummary.updatedMillis] themselves.
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
        return when (val result = fetchRecentSessionsResult(
            serverUrl,
            basicAuthHeader,
            directory,
            maxAgeMillis,
            nowMillis,
            limit,
            connectTimeoutMillis,
            readTimeoutMillis,
        )) {
            is OpenCodeProtocolResult.Success -> result.value
            is OpenCodeProtocolResult.Failure -> emptyList()
        }
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
        var url = rootUrl + "/api/session?order=desc&limit=$limit&directory=" +
            java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val sessions = linkedMapOf<String, SessionSummary>()
        val seenCursors = mutableSetOf<String>()
        repeat(maxPages.coerceAtLeast(1)) {
            val response = httpGetResult(url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)
            if (response is OpenCodeProtocolResult.Failure) return response
            val body = (response as OpenCodeProtocolResult.Success).value
            val page = parseSessionPage(body, maxAgeMillis, nowMillis)
                ?: return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
            page.sessions.forEach { session -> sessions.putIfAbsent(session.id, session) }
            val cursor = page.nextCursor?.takeIf { it.isNotBlank() }
                ?: return OpenCodeProtocolResult.Success(sessions.values.toList())
            if (!seenCursors.add(cursor)) {
                return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
            }
            // Cursor pages still need directory (+ order). OpenCode scopes lists per project;
            // dropping directory lets page 2+ mix in other workspaces.
            url = rootUrl + "/api/session?order=desc&limit=$limit&cursor=" +
                java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8) +
                "&directory=" + java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
        }
        return OpenCodeProtocolResult.Success(sessions.values.toList())
    }

    @TestOnly
    fun parseSessionList(json: String, maxAgeMillis: Long, nowMillis: Long): List<SessionSummary> {
        return parseSessionPage(json, maxAgeMillis, nowMillis)?.sessions.orEmpty()
    }

    @TestOnly
    fun parseSessionDirectory(json: String): String? {
        val root = parseJsonObject(json) ?: return null
        val session = root.objectMember("data") ?: root
        return sessionDirectory(session)
    }

    private fun sessionDirectory(session: JsonObject): String? {
        return session.objectMember("location")?.stringMember("directory")?.takeIf { it.isNotBlank() }
            ?: session.stringMember("directory")?.takeIf { it.isNotBlank() }
    }

    private fun parseSessionPage(json: String, maxAgeMillis: Long, nowMillis: Long): SessionPage? {
        // Response shape (verified against opencode 1.17.13): {"data":[SessionV2Info...],"cursor":{...}}
        // with each session carrying id ("ses_...") and time.{created,updated} epoch millis.
        val root = parseJsonObject(json) ?: return null
        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val results = mutableListOf<SessionSummary>()
        for (element in data) {
            val session = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val id = session.stringMember("id")?.takeIf { it.startsWith("ses_") } ?: continue
            val updated = session.objectMember("time")
                ?.longMember("updated")
                ?: continue
            if (nowMillis - updated <= maxAgeMillis) {
                results.add(
                    SessionSummary(
                        id,
                        updated,
                        session.stringMember("parentID")?.takeIf { it.isNotBlank() },
                        sessionDirectory(session),
                    ),
                )
            }
        }
        val cursor = root.objectMember("cursor")?.stringMember("next")?.takeIf { it.isNotBlank() }
        return SessionPage(results.distinctBy { it.id }, cursor)
    }

    /**
     * Fetches the most recent message of a session and returns it in the classifier shape
     * used by [isInterruptedLastMessage] / [isSuspendSeveredLastMessage] /
     * [isUnsettledTurnFromBefore], or null when the session has no messages / on any error.
     *
     * The embedded web app writes sessions through the **v1** API
     * (`POST /session` + `/session/{id}/prompt_async`), so its turns live in
     * `GET /session/{id}/message?directory=…&limit=1` (bare array of `{info, parts}`). The v2
     * store at `GET /api/session/{id}/message` stays empty for those sessions. This helper
     * reads v1 first and falls back to v2 only when the v1 list is empty or missing, so both
     * SPA sessions and any remaining v2-native ones recover.
     */
    fun fetchLastMessageJson(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
    ): String? {
        return when (val result = fetchLastMessageJsonResult(
            serverUrl,
            basicAuthHeader,
            directory,
            sessionID,
            connectTimeoutMillis,
            readTimeoutMillis,
        )) {
            is OpenCodeProtocolResult.Success -> result.value
            is OpenCodeProtocolResult.Failure -> null
        }
    }

    fun fetchLastMessageJsonResult(
        serverUrl: String,
        basicAuthHeader: String,
        directory: String,
        sessionID: String,
        connectTimeoutMillis: Int = 3000,
        readTimeoutMillis: Int = 3000,
    ): OpenCodeProtocolResult<String?> {
        if (!isSessionId(sessionID) || directory.isBlank()) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER)
        }
        val root = buildServerRootUrl(serverUrl)
        val v1Url = "$root/session/$sessionID/message" +
            "?directory=" + java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8) +
            "&limit=1"
        when (val v1 = httpGetResult(v1Url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)) {
            is OpenCodeProtocolResult.Success -> {
                val raw = extractLastMessageRaw(v1.value)
                if (raw != null) {
                    val normalized = normalizeLastMessageForClassification(raw)
                        ?: return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
                    return OpenCodeProtocolResult.Success(normalized)
                }
                // Empty v1 list → try v2 (session may have been created through the v2 API).
            }
            is OpenCodeProtocolResult.Failure -> {
                // A missing v1 route falls through to v2; transport failures must surface so
                // recovery can retry rather than pretend "no message".
                if (v1.statusCode != HttpURLConnection.HTTP_NOT_FOUND) return v1
            }
        }
        val v2Url = "$root/api/session/$sessionID/message?order=desc&limit=1"
        return when (val v2 = httpGetResult(v2Url, basicAuthHeader, connectTimeoutMillis, readTimeoutMillis)) {
            is OpenCodeProtocolResult.Failure -> v2
            is OpenCodeProtocolResult.Success -> {
                val raw = extractLastMessageRaw(v2.value)
                    ?: return OpenCodeProtocolResult.Success(null)
                val normalized = normalizeLastMessageForClassification(raw)
                    ?: return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY)
                OpenCodeProtocolResult.Success(normalized)
            }
        }
    }

    /**
     * Pulls the most-recent message object out of either list shape the two message stores
     * return: v1 bare array (newest first with `limit=1`) or v2 `{"data":[…],"cursor":…}`.
     */
    @TestOnly
    fun extractLastMessageRaw(body: String): String? {
        val parsed = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
        val messages = when {
            parsed.isJsonArray -> parsed.asJsonArray
            parsed.isJsonObject -> parsed.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        } ?: return null
        return messages.firstOrNull { it.isJsonObject }?.toString()
    }

    /** @deprecated Prefer [extractLastMessageRaw]; kept for existing call sites. */
    @TestOnly
    fun extractFirstDataObject(body: String): String? = extractLastMessageRaw(body)

    /**
     * Maps a raw message from either store onto the flat classifier shape
     * `{type, time, error?, content[]}` that [isInterruptedLastMessage] and friends read.
     *
     * - v2 `SessionMessage` already has top-level `type` → returned unchanged.
     * - v1 `{info:{role,time,error?}, parts:[…]}` → `type` from `info.role`, `content` from
     *   `parts` (tool parts already carry `state.status` the same way).
     */
    @TestOnly
    fun normalizeLastMessageForClassification(messageJson: String): String? {
        val message = parseJsonObject(messageJson) ?: return null
        if (message.stringMember("type") != null) return messageJson
        val info = message.objectMember("info") ?: return null
        val role = info.stringMember("role") ?: return null
        val normalized = JsonObject()
        normalized.addProperty("type", role)
        info.objectMember("time")?.let { normalized.add("time", it) }
        info.get("error")?.takeUnless { it.isJsonNull }?.let { normalized.add("error", it) }
        message.get("parts")?.takeIf { it.isJsonArray }?.let { normalized.add("content", it) }
        return normalized.toString()
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
     *
     * [createdBeforeMillis] bounds the check to turns from before the current server
     * process was launched: a message created on the live server (a prompt the user just
     * sent, or its still-streaming reply) can look identical to an interrupted turn but
     * must never be "continued" — that would steer a spurious prompt into a running turn.
     */
    fun isInterruptedLastMessage(messageJson: String, createdBeforeMillis: Long = Long.MAX_VALUE): Boolean {
        val message = parseJsonObject(messageJson) ?: return false
        if (createdBeforeMillis != Long.MAX_VALUE) {
            // Without a creation timestamp the pre-restart origin cannot be proven; treat
            // the message as live rather than risk a false continuation.
            val created = message.objectMember("time")?.longMember("created")
            if (created == null || created >= createdBeforeMillis) return false
        }
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

    private fun parseJsonArray(text: String): JsonArray? {
        if (text.isBlank()) return null
        return runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
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
        return sendContinuePromptResult(
            serverUrl,
            basicAuthHeader,
            sessionID,
            connectTimeoutMillis,
            readTimeoutMillis,
        ) is OpenCodeProtocolResult.Success
    }

    fun sendContinuePromptResult(
        serverUrl: String,
        basicAuthHeader: String,
        sessionID: String,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 5000,
    ): OpenCodeProtocolResult<Unit> {
        if (!isSessionId(sessionID)) {
            return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER)
        }
        val url = buildServerRootUrl(serverUrl) + "/api/session/$sessionID/prompt"
        return httpPostJsonResult(
            url,
            basicAuthHeader,
            """{"prompt":{"text":"Continue"},"resume":true}""",
            connectTimeoutMillis,
            readTimeoutMillis,
        )
    }

    private fun httpGet(
        url: String,
        basicAuthHeader: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseChars: Int = MAX_HTTP_RESPONSE_CHARS,
    ): String? {
        return when (val result = httpGetResult(
            url,
            basicAuthHeader,
            connectTimeoutMillis,
            readTimeoutMillis,
            maxResponseChars,
        )) {
            is OpenCodeProtocolResult.Success -> result.value
            is OpenCodeProtocolResult.Failure -> null
        }
    }

    private fun httpGetResult(
        url: String,
        basicAuthHeader: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseChars: Int = MAX_HTTP_RESPONSE_CHARS,
    ): OpenCodeProtocolResult<String> {
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
                    return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, status)
                }
                val body = connection.inputStream.bufferedReader().use { reader -> readBounded(reader, maxResponseChars) }
                    ?: return OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.TOO_LARGE)
                OpenCodeProtocolResult.Success(body)
            } finally {
                connection.disconnect()
            }
        } catch (_: SocketTimeoutException) {
            OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.TIMEOUT)
        } catch (_: Exception) {
            OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.IO)
        }
    }

    @TestOnly
    fun readBoundedForTest(text: String, maxChars: Int): String? {
        return readBounded(text.reader().buffered(), maxChars)
    }

    private fun readBounded(reader: BufferedReader, maxChars: Int): String? {
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

    private fun httpPostJson(
        url: String,
        basicAuthHeader: String,
        body: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): Boolean {
        return httpPostJsonResult(url, basicAuthHeader, body, connectTimeoutMillis, readTimeoutMillis) is
            OpenCodeProtocolResult.Success
    }

    private fun httpPostJsonResult(
        url: String,
        basicAuthHeader: String,
        body: String,
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
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
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
