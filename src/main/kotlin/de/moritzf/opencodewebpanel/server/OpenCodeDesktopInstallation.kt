package de.moritzf.opencodewebpanel.server

import com.intellij.util.text.VersionComparatorUtil
import java.io.File

/** Desktop ships the ordinary server CLI alongside its GUI. Never launch the GUI as a server. */
internal object OpenCodeDesktopInstallation {
    private const val APP_ID = "ai.opencode.desktop"
    private val STAGED_VERSION = Regex("""(\d+\.\d+\.\d+)(?:-(x64|arm64))?""")
    private val MAC_LAUNCHER =
        Regex("""(?:^|[/\\])OpenCode\.app[/\\]Contents[/\\]MacOS[/\\]""", RegexOption.IGNORE_CASE)

    fun executableName(osName: String): String =
        if (osName.startsWith("Windows", ignoreCase = true)) "opencode-cli.exe" else "opencode-cli"

    fun executableDirectories(
        environment: Map<String, String>,
        osName: String,
        osArch: String,
    ): List<String> {
        fun env(key: String) =
            environment.entries
                .firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)

        val home = env("HOME") ?: env("USERPROFILE")
        val bundles: List<File>
        val appData: File?
        when {
            osName.startsWith("Windows", ignoreCase = true) -> {
                val local = env("LOCALAPPDATA") ?: home?.resolve("AppData/Local")
                appData = env("APPDATA") ?: home?.resolve("AppData/Roaming")
                bundles =
                    listOfNotNull(
                            local?.resolve("Programs"),
                            env("ProgramW6432"),
                            env("ProgramFiles"),
                            env("ProgramFiles(x86)"),
                        )
                        .flatMap { root -> listOf(root.resolve(APP_ID), root.resolve("OpenCode")) }
                        .map { it.resolve("resources") }
            }
            osName.startsWith("Mac", ignoreCase = true) -> {
                appData = home?.resolve("Library/Application Support")
                bundles =
                    listOfNotNull(home?.resolve("Applications"), File("/Applications")).flatMap {
                        val contents = it.resolve("OpenCode.app/Contents")
                        listOf(contents.resolve("Resources"), contents.resolve("MacOS"))
                    }
            }
            osName.startsWith("Linux", ignoreCase = true) -> {
                appData = env("XDG_CONFIG_HOME") ?: home?.resolve(".config")
                bundles = listOf(File("/opt/OpenCode/resources"))
            }
            else -> return emptyList()
        }
        // The installed bundle wins over caches left by an older Desktop installation. Staged
        // copies also cover custom install locations and Linux AppImages after their first run.
        val staged =
            appData?.resolve("$APP_ID/cli")?.let { stagedDirectories(it, osArch) }.orEmpty()
        return (bundles + staged).map { it.path }.distinct()
    }

    private fun stagedDirectories(root: File, osArch: String): List<File> {
        val arch =
            when (osArch.lowercase()) {
                "aarch64",
                "arm64" -> "arm64"
                "amd64",
                "x86_64",
                "x64" -> "x64"
                else -> return emptyList()
            }
        return root
            .listFiles()
            .orEmpty()
            .filter {
                val version = STAGED_VERSION.matchEntire(it.name)
                it.isDirectory &&
                    version != null &&
                    version.groupValues[2].let { suffix -> suffix.isEmpty() || suffix == arch }
            }
            .sortedWith { left, right ->
                VersionComparatorUtil.compare(
                        right.name.substringBefore('-'),
                        left.name.substringBefore('-'),
                    )
                    .takeIf { it != 0 } ?: right.name.compareTo(left.name)
            }
    }

    fun isLauncher(file: File): Boolean {
        val resolved = runCatching { file.canonicalFile }.getOrDefault(file)
        if (!resolved.isFile) return false
        if (
            resolved.name.equals("opencode-cli", true) ||
                resolved.name.equals("opencode-cli.exe", true)
        )
            return false
        if (MAC_LAUNCHER.containsMatchIn(resolved.path)) return true
        val cliName =
            when {
                resolved.name.equals("opencode.exe", true) -> "opencode-cli.exe"
                resolved.name == "ai.opencode" || resolved.name == APP_ID -> "opencode-cli"
                else -> return false
            }
        val resources = resolved.parentFile?.resolve("resources") ?: return false
        // A missing/quarantined sidecar must not turn the Electron GUI into a runnable CLI.
        // Match its app archive, not just a directory named OpenCode (also used by CLI installs).
        return resources.resolve(cliName).isFile || resources.resolve("app.asar").isFile
    }

    /** Supports a custom .app path and launchers/symlinks pointing into a Desktop install. */
    fun bundledExecutables(file: File, osName: String): List<File> {
        val resolved = runCatching { file.canonicalFile }.getOrDefault(file)
        val name = executableName(osName)
        if (resolved.name.endsWith(".app", ignoreCase = true)) {
            return listOf(
                resolved.resolve("Contents/Resources/$name"),
                resolved.resolve("Contents/MacOS/$name"),
            )
        }
        if (!isLauncher(resolved)) return emptyList()
        val parent = resolved.parentFile ?: return emptyList()
        return if (MAC_LAUNCHER.containsMatchIn(resolved.path)) {
            listOfNotNull(parent.parentFile?.resolve("Resources/$name"), parent.resolve(name))
        } else {
            listOf(parent.resolve("resources/$name"))
        }
    }
}
