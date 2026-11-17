package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException

/**
 * Host CLI launch: command line, PATH lookup, and the serve [ProcessBuilder]. Call sites still go
 * through [OpenCodeServerProtocol].
 */
internal object OpenCodeProcessLaunch {
    private const val HOST = "127.0.0.1"

    fun buildOpenCodeCommand(
        port: String = OpenCodeSettingsState.DYNAMIC_PORT,
        executable: String = OpenCodeSettingsState.DEFAULT_EXECUTABLE,
    ): List<String> {
        return listOf(
            executable.ifBlank { OpenCodeSettingsState.DEFAULT_EXECUTABLE },
            "serve",
            "--hostname",
            HOST,
            "--port",
            port,
            "--print-logs",
        )
    }

    fun createProcessBuilder(
        projectBasePath: String?,
        password: String,
        port: String,
        executable: String,
        path: String,
        command: List<String>,
        httpProxy: IdeHttpProxy?,
        stripInheritedProxy: Boolean,
    ): ProcessBuilder {
        val processBuilder = ProcessBuilder().command(command).redirectErrorStream(true)

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

    fun resolvePath(
        currentPath: String = System.getenv("PATH").orEmpty(),
        additionalPaths: List<String>? = null,
        environment: Map<String, String> = System.getenv(),
    ): String {
        return (currentPath.split(File.pathSeparator) +
                (additionalPaths ?: commonExecutablePaths(environment)))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(File.pathSeparator)
    }

    fun detectExecutablePath(
        executable: String = OpenCodeSettingsState.DEFAULT_EXECUTABLE,
        path: String = resolvePath(),
        pathSeparator: String = File.pathSeparator,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): String? {
        val command = executable.trim().takeIf { it.isNotBlank() } ?: return null
        val commandFile = File(command)
        if (commandFile.isAbsolute || command.contains('/') || command.contains('\\')) {
            return commandFile.takeIf { it.isRunnableCommand() }?.absolutePath
        }

        return path
            .split(pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { directory ->
                candidateExecutableNames(command, osName).asSequence().map { File(directory, it) }
            }
            .firstOrNull { it.isRunnableCommand() }
            ?.absolutePath
    }

    fun resolveExecutableForLaunch(
        executable: String = OpenCodeSettingsState.DEFAULT_EXECUTABLE,
        path: String = resolvePath(),
    ): String {
        return detectExecutablePath(executable, path)
            ?: executable.ifBlank { OpenCodeSettingsState.DEFAULT_EXECUTABLE }
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
        ) + desktopAppExecutableDirectories(environment)
    }

    /**
     * The OpenCode desktop app ships the same server as `opencode-cli(.exe)` instead of putting an
     * `opencode` command on PATH, so a machine that only installed the app fails the bare-name
     * lookup. The Windows installer keeps a copy next to the app resources and unpacks the
     * versioned build under `%APPDATA%\ai.opencode.desktop\cli\<version>-<arch>`; the version
     * folders are listed newest first rather than hardcoded so an in-place update keeps working.
     */
    private fun desktopAppExecutableDirectories(environment: Map<String, String>): List<String> {
        val appData = environmentValue(environment, "APPDATA")
        val localAppData = environmentValue(environment, "LOCALAPPDATA")
        val home = environmentValue(environment, "HOME")
        val roots =
            listOfNotNull(
                localAppData?.windowsChild("Programs\\ai.opencode.desktop\\resources"),
                appData?.windowsChild("ai.opencode.desktop\\cli"),
                home?.unixChild("Applications/OpenCode.app/Contents/Resources"),
                "/Applications/OpenCode.app/Contents/Resources",
            )
        return roots.flatMap { root -> listOf(root) + versionedSubdirectories(root) }
    }

    private fun versionedSubdirectories(root: String): List<String> {
        val directory = File(root)
        if (!directory.isDirectory) return emptyList()
        return directory
            .listFiles { file -> file.isDirectory }
            ?.sortedWith(
                compareByDescending<File> { it.lastModified() }.thenByDescending { it.name },
            )
            ?.map { it.absolutePath }
            .orEmpty()
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
        val candidates =
            if (!osName.startsWith("Windows", ignoreCase = true)) {
                (listOf(executable) +
                        windowsExtensions.filterNot { lower.endsWith(it) }.map { executable + it })
                    .distinct()
            } else {
                (windowsExtensions.filterNot { lower.endsWith(it) }.map { executable + it } +
                        executable)
                    .distinct()
            }
        return (candidates + desktopAppCliAlias(executable, osName)).distinct()
    }

    /**
     * The desktop app's bundled server is named `opencode-cli`, not `opencode`, so the bare-name
     * lookup has to know the alias to find it in [desktopAppExecutableDirectories].
     */
    private fun desktopAppCliAlias(executable: String, osName: String): List<String> {
        val name = executable.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name != OpenCodeSettingsState.DEFAULT_EXECUTABLE) return emptyList()
        return if (osName.startsWith("Windows", ignoreCase = true)) {
            listOf("opencode-cli.exe")
        } else {
            listOf("opencode-cli")
        }
    }

    /** A typed path can be no path at all (quotes or `*` on Windows, NUL): not runnable. */
    private fun File.isRunnableCommand(): Boolean {
        val path =
            try {
                toPath()
            } catch (_: InvalidPathException) {
                return false
            }
        return Files.isRegularFile(path) &&
            (Files.isExecutable(path) || hasWindowsCommandExtension(name))
    }

    private fun hasWindowsCommandExtension(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".cmd") ||
            lower.endsWith(".exe") ||
            lower.endsWith(".bat") ||
            lower.endsWith(".ps1")
    }
}
