package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import java.io.File
import java.io.IOException
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
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): String {
        return (currentPath.split(File.pathSeparator) +
                (additionalPaths
                    ?: (commonExecutablePaths(environment) +
                        OpenCodeDesktopInstallation.executableDirectories(
                            environment,
                            osName,
                            osArch,
                        ))))
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
            OpenCodeDesktopInstallation.bundledExecutables(commandFile, osName)
                .firstOrNull { it.isRunnableCommand() }
                ?.let {
                    return it.absolutePath
                }
            if (OpenCodeDesktopInstallation.isLauncher(commandFile)) return null
            return commandFile.takeIf { it.isRunnableCommand() }?.absolutePath
        }

        val directories = path.split(pathSeparator).filter { it.isNotBlank() }
        val candidates =
            directories.asSequence().flatMap { directory ->
                candidateExecutableNames(command, osName).asSequence().map { File(directory, it) }
            }
        // Complete the standalone CLI search before considering any Desktop alias, even when a
        // Desktop directory appears first on PATH.
        candidates
            .firstOrNull { it.isRunnableCommand() && !OpenCodeDesktopInstallation.isLauncher(it) }
            ?.let {
                return it.absolutePath
            }
        if (!command.equals(OpenCodeSettingsState.DEFAULT_EXECUTABLE, ignoreCase = true)) {
            return null
        }
        return (directories.asSequence().map {
                File(it, OpenCodeDesktopInstallation.executableName(osName))
            } +
                candidates.flatMap {
                    OpenCodeDesktopInstallation.bundledExecutables(it, osName).asSequence()
                })
            .firstOrNull { it.isRunnableCommand() }
            ?.absolutePath
    }

    fun resolveExecutableForLaunch(
        executable: String = OpenCodeSettingsState.DEFAULT_EXECUTABLE,
        path: String = resolvePath(),
    ): String {
        detectExecutablePath(executable, path)?.let {
            return it
        }
        val command = executable.ifBlank { OpenCodeSettingsState.DEFAULT_EXECUTABLE }
        val candidates =
            if (command.contains('/') || command.contains('\\')) listOf(File(command))
            else
                path
                    .split(File.pathSeparator)
                    .filter { it.isNotBlank() }
                    .flatMap { directory ->
                        candidateExecutableNames(command, System.getProperty("os.name").orEmpty())
                            .map { File(directory, it) }
                    }
        if (candidates.any { OpenCodeDesktopInstallation.isLauncher(it) }) {
            throw IOException(
                "The Desktop launcher cannot serve OpenCode. Choose its bundled opencode-cli executable.",
            )
        }
        return command
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
            return (listOf(executable) +
                    windowsExtensions.filterNot { lower.endsWith(it) }.map { executable + it })
                .distinct()
        }
        return (windowsExtensions.filterNot { lower.endsWith(it) }.map { executable + it } +
                executable)
            .distinct()
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
