package de.moritzf.opencodewebpanel.toolWindow

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

internal object OpenCodeServerProtocol {
    const val HOST = "127.0.0.1"
    const val DYNAMIC_PORT = "0"
    const val CHECK_INTERVAL_SECONDS = 30L
    const val BASIC_AUTH_USERNAME = "opencode"
    const val DEFAULT_EXECUTABLE = "opencode"

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

    fun buildOpenProjectScript(projectBasePath: String?, serverUrl: String? = null): String? {
        if (projectBasePath.isNullOrBlank()) return null
        val directory = escapeJavaScript(projectBasePath)
        val path = escapeJavaScript("/${encodeDirectory(projectBasePath)}/session")
        val expectedOrigin = serverUrl?.let { escapeJavaScript(buildOrigin(it)) }
        val originGuard = expectedOrigin?.let { "if (window.location.origin !== '$it') return;" }.orEmpty()
        return """
            (() => {
              const directory = '$directory';
              const path = '$path';
              const storageKey = 'opencode.global.dat:server';
              const navigationKey = 'opencode.intellij.project.opened:' + path;
              const scope = 'local';
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
        command: List<String> = buildOpenCodeCommand(port, executable),
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

    private fun commonExecutablePaths(environment: Map<String, String>): List<String> {
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
