package de.moritzf.opencodewebpanel.toolWindow

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.ln

internal object OpenCodeServerProtocol {
    const val HOST = "127.0.0.1"
    const val DYNAMIC_PORT = "0"
    const val CHECK_INTERVAL_SECONDS = 30L
    const val BASIC_AUTH_USERNAME = "opencode"
    const val DEFAULT_EXECUTABLE = "opencode"
    const val OPEN_FILE_LINK_SCHEME = "opencode-web-panel"
    const val OPEN_FILE_LINK_HOST = "open-file"

    private val secureRandom = SecureRandom()
    fun buildServerRootUrl(serverUrl: String): String {
        return serverUrl.trimEnd('/')
    }

    fun buildAuthenticatedServerRootUrl(serverUrl: String, password: String?): String {
        val root = buildServerRootUrl(serverUrl)
        if (password.isNullOrBlank()) return root
        return "$root?auth_token=${encodeUrlParameter(buildAuthToken(password))}"
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
        val directory = escapeJavaScript(projectBasePath)
        val projectPath = escapeJavaScript("/${encodeDirectory(projectBasePath)}/session")
        val expectedOrigin = serverUrl?.let { escapeJavaScript(buildOrigin(it)) }
        val originGuard = expectedOrigin?.let { "if (window.location.origin !== '$it') return;" }.orEmpty()
        val openMostRecentConversationLiteral = openMostRecentConversation.toString()
        return """
            (() => {
              const directory = '$directory';
              const projectPath = '$projectPath';
              let path = projectPath;
              const storageKey = 'opencode.global.dat:server';
              const layoutStorageKey = 'opencode.global.dat:layout.page';
              const scope = 'local';
              const openMostRecentConversation = $openMostRecentConversationLiteral;
              const routeDirectory = (value) => {
                const bytes = new TextEncoder().encode(value);
                let binary = '';
                bytes.forEach((byte) => binary += String.fromCharCode(byte));
                return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
              };
              if (openMostRecentConversation) {
                try {
                  const rawLayout = window.localStorage.getItem(layoutStorageKey);
                  const layout = rawLayout ? JSON.parse(rawLayout) : undefined;
                  const session = layout && layout.lastProjectSession && layout.lastProjectSession[directory];
                  if (session && typeof session.directory === 'string' && typeof session.id === 'string') {
                    path = '/' + routeDirectory(session.directory) + '/session/' + encodeURIComponent(session.id);
                  }
                } catch (_) {}
              }
              const navigationKey = 'opencode.intellij.project.opened:' + path;
              const getNavigationState = () => {
                try {
                  return window.sessionStorage.getItem(navigationKey);
                } catch (_) {
                  return undefined;
                }
              };
              const setNavigationState = (value) => {
                try {
                  window.sessionStorage.setItem(navigationKey, value);
                } catch (_) {}
              };
              $originGuard
              try {
                const raw = window.localStorage.getItem(storageKey);
                const state = raw ? JSON.parse(raw) : { list: [], projects: {}, lastProject: {} };
                state.list = Array.isArray(state.list) ? state.list : [];
                state.projects = state.projects && typeof state.projects === 'object' ? state.projects : {};
                state.lastProject = state.lastProject && typeof state.lastProject === 'object' ? state.lastProject : {};
                const projects = Array.isArray(state.projects[scope]) ? state.projects[scope] : [];
                state.projects[scope] = [
                  { worktree: directory, expanded: true },
                  ...projects.filter((project) => project && project.worktree !== directory),
                ];
                state.lastProject[scope] = directory;
                window.localStorage.setItem(storageKey, JSON.stringify(state));
              } catch (error) {
                if (window.console && window.console.warn) {
                  window.console.warn('Failed to seed OpenCode project state', error);
                }
              }
              if (window.location.pathname !== path) {
                setNavigationState('pending');
                window.location.assign(path);
                return;
              }
              if (getNavigationState() !== 'complete') {
                setNavigationState('complete');
                window.location.reload();
              }
            })();
        """.trimIndent()
    }

    fun buildFileLinkHandlerScript(projectBasePath: String?): String? {
        return buildFileLinkHandlerScript(projectBasePath, enabled = true)
    }

    fun buildFileLinkHandlerScript(projectBasePath: String?, enabled: Boolean): String? {
        return buildFileLinkHandlerScript(projectBasePath, enabled, openFileCallback = null)
    }

    fun buildFileLinkHandlerScript(projectBasePath: String?, enabled: Boolean, openFileCallback: String?): String? {
        if (projectBasePath.isNullOrBlank()) return null
        val directory = escapeJavaScript(projectBasePath)
        val enabledLiteral = enabled.toString()
        val openFileAction = openFileCallback ?: "window.location.assign('${OPEN_FILE_LINK_SCHEME}://${OPEN_FILE_LINK_HOST}?href=' + encodeURIComponent(rawHref) + '&base=' + encodeURIComponent(directory))"
        return """
            (() => {
              window.__opencodeIntellijFileLinksEnabled = $enabledLiteral;
              if (window.__opencodeIntellijFileLinksInstalled) return;
              window.__opencodeIntellijFileLinksInstalled = true;
              const directory = '$directory';
              const unsupportedProtocol = /^(https?|mailto|tel|data|blob|javascript):/i;
              const looksLikeFilePath = (value) => {
                if (!value) return false;
                const text = value.trim();
                return text.length > 0 && text.length < 512 && !/\s/.test(text) && /[./\\]/.test(text);
              };
              const inferredFileLink = (link) => {
                const row = link.closest ? link.closest('tr') : null;
                const cell = link.closest ? link.closest('td,th') : null;
                if (!row || !cell) return '';
                const cells = Array.from(row.children);
                const index = cells.indexOf(cell);
                if (index <= 0) return '';
                for (const candidate of cells.slice(0, index).reverse()) {
                  const text = (candidate.textContent || '').trim();
                  if (looksLikeFilePath(text)) return text;
                }
                return '';
              };
              const isLocalFileLink = (href) => {
                if (!href || href.startsWith('#')) return false;
                if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(href)) return !unsupportedProtocol.test(href);
                if (href.startsWith('/') || href.startsWith('./') || href.startsWith('../')) return true;
                if (/^[A-Za-z]:[\\/]/.test(href)) return true;
                return !href.startsWith('//') && !href.includes('://');
              };
              document.addEventListener('click', (event) => {
                if (!window.__opencodeIntellijFileLinksEnabled) return;
                const link = event.target && event.target.closest ? event.target.closest('a') : null;
                if (!link) return;
                const rawHref = link.getAttribute('href') || inferredFileLink(link);
                if (!isLocalFileLink(rawHref)) return;
                event.preventDefault();
                event.stopPropagation();
                $openFileAction;
              }, true);
            })();
        """.trimIndent()
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
        return URI(requestUrl).rawQuery
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == "href" }
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

    fun resolveFileLink(href: String?, projectBasePath: String?): FileLinkTarget? {
        return resolveFileLink(href, projectBasePath, routeBasePath = null)
    }

    fun resolveFileLink(href: String?, projectBasePath: String?, routeBasePath: String?): FileLinkTarget? {
        val basePaths = listOfNotNull(routeBasePath?.takeIf { it.isNotBlank() }, projectBasePath?.takeIf { it.isNotBlank() })
            .distinct()
        if (href.isNullOrBlank() || basePaths.isEmpty()) return null
        val parsed = parseFileLink(href, basePaths) ?: return null
        val path = candidateFileLinkPaths(parsed, basePaths)
            .firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
            ?: return null
        return FileLinkTarget(path, parsed.line?.coerceAtLeast(0), parsed.column?.coerceAtLeast(0))
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
        connectTimeoutMillis: Int = 2000,
        readTimeoutMillis: Int = 2000,
    ): Boolean {
        return try {
            val connection = URI(serverUrl).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.requestMethod = "GET"
                connection.responseCode in 200..499
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun shouldRestartServer(serverUrl: String?, serverResponding: Boolean): Boolean {
        return serverUrl == null || !serverResponding
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
    ): String? {
        val command = executable.trim().takeIf { it.isNotBlank() } ?: return null
        val commandFile = File(command)
        if (commandFile.isAbsolute || command.contains('/') || command.contains('\\')) {
            return commandFile.takeIf { it.isRunnableCommand() }?.absolutePath
        }

        return path.split(pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { directory -> candidateExecutableNames(command).asSequence().map { File(directory, it) } }
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

    private fun candidateExecutableNames(executable: String): List<String> {
        val lower = executable.lowercase()
        val windowsExtensions = listOf(".cmd", ".exe", ".bat", ".ps1")
        return (listOf(executable) + windowsExtensions.filterNot { lower.endsWith(it) }.map { executable + it }).distinct()
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

    private fun encodeUrlParameter(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun escapeJavaScript(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
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
