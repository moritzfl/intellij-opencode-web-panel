package de.moritzf.opencodewebpanel.server

import java.io.File
import java.nio.file.Files

/**
 * Host CLI launch: command line, PATH lookup, and the serve [ProcessBuilder].
 * Call sites still go through [OpenCodeServerProtocol].
 */
internal object OpenCodeProcessLaunch {
    private const val HOST = "127.0.0.1"

    fun buildOpenCodeCommand(
        port: String = OpenCodeServerProtocol.DYNAMIC_PORT,
        executable: String = OpenCodeServerProtocol.DEFAULT_EXECUTABLE,
    ): List<String> {
        return listOf(
            executable.ifBlank { OpenCodeServerProtocol.DEFAULT_EXECUTABLE },
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
        executable: String = OpenCodeServerProtocol.DEFAULT_EXECUTABLE,
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

    fun resolveExecutableForLaunch(
        executable: String = OpenCodeServerProtocol.DEFAULT_EXECUTABLE,
        path: String = resolvePath(),
    ): String {
        return detectExecutablePath(executable, path) ?: executable.ifBlank { OpenCodeServerProtocol.DEFAULT_EXECUTABLE }
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
}
