package de.moritzf.opencodewebpanel.server

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCodeDesktopInstallationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun executable(path: String): File =
        temp.root.resolve(path).apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
        }

    private fun directories(
        os: String,
        arch: String = "aarch64",
        environment: Map<String, String> = mapOf("HOME" to temp.root.path),
    ) = OpenCodeDesktopInstallation.executableDirectories(environment, os, arch)

    private fun detect(directories: List<String>, os: String, command: String = "opencode") =
        OpenCodeServerProtocol.detectExecutablePath(
            executable = command,
            path =
                directories
                    .filter { File(it).toPath().startsWith(temp.root.toPath()) }
                    .joinToString(File.pathSeparator),
            osName = os,
        )

    @Test
    fun desktopOnlyInstallationsResolveOnAllThreePlatforms() {
        val mac = executable("Applications/OpenCode.app/Contents/Resources/opencode-cli")
        assertEquals(mac.path, detect(directories("Mac OS X"), "Mac OS X"))

        val windows =
            executable("AppData/Local/Programs/ai.opencode.desktop/resources/opencode-cli.exe")
        val windowsEnv = mapOf("UserProfile" to temp.root.path)
        assertEquals(
            windows.path,
            detect(directories("Windows 11", "amd64", windowsEnv), "Windows 11"),
        )

        val linux = executable(".config/ai.opencode.desktop/cli/2.3.0-arm64/opencode-cli")
        val linuxDirectories = directories("Linux")
        assertTrue(linuxDirectories.contains("/opt/OpenCode/resources"))
        assertEquals(linux.path, detect(linuxDirectories, "Linux"))
    }

    @Test
    fun windowsUsesRelocatedEnvironmentDirectoriesAndSystemInstalls() {
        val environment =
            mapOf(
                "localappdata" to temp.root.resolve("Local Data").path,
                "APPDATA" to temp.root.resolve("Roaming Data").path,
                "ProgramFiles" to temp.root.resolve("Program Files").path,
            )
        val system = executable("Program Files/OpenCode/resources/opencode-cli.exe")
        assertEquals(
            system.path,
            detect(directories("Windows 11", "amd64", environment), "Windows 11"),
        )
        val local = executable("Local Data/Programs/ai.opencode.desktop/resources/opencode-cli.exe")
        assertEquals(
            local.path,
            detect(directories("Windows 11", "amd64", environment), "Windows 11"),
        )
        local.delete()
        system.delete()
        val cached = executable("Roaming Data/ai.opencode.desktop/cli/2.3.0-x64/opencode-cli.exe")
        assertEquals(
            cached.path,
            detect(directories("Windows 11", "amd64", environment), "Windows 11"),
        )
    }

    @Test
    fun standaloneCliWinsEvenWhenDesktopIsEarlierOnPath() {
        for (os in listOf("Windows 11", "Mac OS X", "Linux")) {
            val desktop =
                executable("$os/desktop/${OpenCodeDesktopInstallation.executableName(os)}")
            val cli =
                executable(
                    "$os/cli/" + if (os.startsWith("Windows")) "opencode.cmd" else "opencode",
                )
            val paths = listOf(desktop.parent, cli.parent)
            assertEquals(cli.path, detect(paths, os))
            cli.delete()
            assertEquals(desktop.path, detect(paths, os))
            assertNull(detect(paths, os, "sbx"))
            assertNull(detect(paths, os, "my-opencode"))
        }
    }

    @Test
    fun cachedVersionsAreNumericNewestFirstAndArchitectureSpecific() {
        val environment = mapOf("XDG_CONFIG_HOME" to temp.root.resolve("xdg config").path)
        val root = "xdg config/ai.opencode.desktop/cli"
        executable("$root/2.9.0-arm64/opencode-cli")
        val newest = executable("$root/2.10.0-arm64/opencode-cli")
        executable("$root/99.0.0-x64/opencode-cli")
        executable("$root/local/opencode-cli")
        executable("$root/2.11.0-arm64/nested/opencode-cli")
        executable("$root/2.12.0-arm64/opencode-cli.tmp")
        assertEquals(newest.path, detect(directories("Linux", environment = environment), "Linux"))
        assertFalse(
            directories("Linux", environment = environment).any { it.endsWith("99.0.0-x64") },
        )
        newest.delete()
        val legacy = executable("$root/2.10.1/opencode-cli")
        assertEquals(legacy.path, detect(directories("Linux", environment = environment), "Linux"))
    }

    @Test
    fun macStagedCopiesAndLegacyBundleCliAreSupported() {
        val cached =
            executable(
                "Library/Application Support/ai.opencode.desktop/cli/2.3.0-arm64/opencode-cli",
            )
        assertEquals(cached.path, detect(directories("Mac OS X"), "Mac OS X"))
        val gui = executable("Applications/OpenCode.app/Contents/MacOS/OpenCode")
        val bundled = executable("Applications/OpenCode.app/Contents/MacOS/opencode-cli")
        assertEquals(bundled.path, detect(directories("Mac OS X"), "Mac OS X"))
        val probe =
            OpenCodeExecutableProbes.openCode(bundled.path) { command, _, _, _ ->
                assertEquals(listOf(bundled.path, "--version"), command)
                SbxCommandResult(0, "2.3.0", "2.3.0")
            }
        assertEquals("2.3.0", probe.version)
        assertFalse(probe.desktopApp)
        assertEquals(bundled.canonicalPath, detect(emptyList(), "Mac OS X", gui.path))
    }

    @Test
    fun customMacAppResolvesItsBundledCliWithoutOpeningTheGui() {
        val bundled = executable("Custom Location/OpenCode.app/Contents/Resources/opencode-cli")
        val app = bundled.parentFile.parentFile.parentFile
        assertEquals(bundled.canonicalPath, detect(emptyList(), "Mac OS X", app.path))
        assertNull(detect(emptyList(), "Mac OS X", temp.root.resolve("missing.app").path))
        val custom = executable("custom-opencode")
        assertEquals(custom.path, detect(listOf(bundled.parent), "Mac OS X", custom.path))
    }

    @Test
    fun guiOnPathNeverBeatsStandaloneCliOrRunsAsTheServer() {
        val gui = executable("OpenCode.app/Contents/MacOS/opencode")
        val bundled = executable("OpenCode.app/Contents/Resources/opencode-cli")
        val cli = executable("bin/opencode")
        assertEquals(cli.path, detect(listOf(gui.parent, cli.parent), "Mac OS X"))
        assertEquals(bundled.canonicalPath, detect(listOf(gui.parent), "Mac OS X"))
        bundled.delete()
        assertNull(detect(listOf(gui.parent), "Mac OS X"))
        assertThrows(IOException::class.java) {
            OpenCodeServerProtocol.resolveExecutableForLaunch(path = gui.parent)
        }
        assertThrows(IOException::class.java) {
            OpenCodeServerProtocol.resolveExecutableForLaunch(gui.path, path = "")
        }

        val windowsGui = executable("Windows/OpenCode.exe")
        val windowsCli = executable("Windows/resources/opencode-cli.exe")
        assertEquals(windowsCli.canonicalPath, detect(emptyList(), "Windows 11", windowsGui.path))
        val linuxCli = executable("Linux/resources/opencode-cli")
        for (name in listOf("ai.opencode", "ai.opencode.desktop")) {
            val linuxGui = executable("Linux/$name")
            assertEquals(linuxCli.canonicalPath, detect(emptyList(), "Linux", linuxGui.path))
        }
    }

    @Test
    fun linuxInstalledLauncherSymlinkResolvesToBundledCli() {
        val gui = executable("opt/OpenCode/ai.opencode")
        val cli = executable("opt/OpenCode/resources/opencode-cli")
        val link = temp.root.resolve("usr/bin/ai.opencode")
        link.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(link.toPath(), gui.toPath())

        assertEquals(cli.canonicalPath, detect(emptyList(), "Linux", link.path))
        assertEquals(
            cli.canonicalPath,
            OpenCodeServerProtocol.resolveExecutableForLaunch(link.path, path = ""),
        )
    }

    @Test
    fun desktopArchiveBlocksGuiProbesAndLaunchesWithoutABundledCli() {
        for ((os, name) in
            listOf(
                "Windows 11" to "OpenCode.exe",
                "Linux" to "ai.opencode",
                "Linux" to "ai.opencode.desktop",
            )) {
            val gui = executable("$os/Custom App/$name")
            gui.parentFile.resolve("resources/app.asar").apply {
                parentFile.mkdirs()
                writeText("")
            }

            assertNull(detect(emptyList(), os, gui.path))
            val probe =
                OpenCodeExecutableProbes.openCode(gui.path) { _, _, _, _ ->
                    error("A Desktop GUI without its CLI must never be probed")
                }
            assertTrue(probe.desktopApp)
            assertEquals(gui.path, probe.path)
            assertThrows(IOException::class.java) {
                OpenCodeServerProtocol.resolveExecutableForLaunch(gui.path, path = "")
            }
        }
    }

    @Test
    fun windowsGuiWithoutItsSidecarDoesNotHideStandaloneOrCachedCli() {
        val gui = executable("Programs/ai.opencode.desktop/opencode.exe")
        gui.parentFile.resolve("resources/app.asar").apply {
            parentFile.mkdirs()
            writeText("")
        }
        val standalone = executable("bin/opencode.exe")
        val cached = executable("cache/2.3.0-x64/opencode-cli.exe")
        val paths = listOf(gui.parent, standalone.parent, cached.parent)

        assertEquals(standalone.path, detect(paths, "Windows 11"))
        assertTrue(standalone.delete())
        assertEquals(cached.path, detect(paths, "Windows 11"))
        assertTrue(cached.delete())
        assertNull(detect(paths, "Windows 11"))
        assertThrows(IOException::class.java) {
            OpenCodeServerProtocol.resolveExecutableForLaunch(
                path = paths.joinToString(File.pathSeparator),
            )
        }
    }

    @Test
    fun standaloneWindowsCliInAnOpenCodeDirectoryStillProbesAndLaunches() {
        val cli = executable("Programs/OpenCode/opencode.exe")
        cli.parentFile.resolve("resources/other.asar").apply {
            parentFile.mkdirs()
            writeText("")
        }

        assertEquals(cli.path, detect(listOf(cli.parent), "Windows 11"))
        assertEquals(
            cli.path,
            OpenCodeServerProtocol.resolveExecutableForLaunch(cli.path, path = ""),
        )
        val probe =
            OpenCodeExecutableProbes.openCode(cli.path) { command, _, _, _ ->
                assertEquals(listOf(cli.path, "--version"), command)
                SbxCommandResult(0, "opencode v2.3.0")
            }
        assertEquals("2.3.0", probe.version)
        assertFalse(probe.desktopApp)
    }

    @Test
    fun missingDesktopInstallAndOtherAppsDoNotBecomeDesktopLaunchers() {
        val missing = temp.root.resolve("OpenCode.app/Contents/MacOS")
        assertEquals(
            "opencode",
            OpenCodeServerProtocol.resolveExecutableForLaunch(path = missing.path),
        )
        val sbx = executable("Other.app/Contents/MacOS/sbx")
        assertEquals(sbx.path, detect(listOf(sbx.parent), "Mac OS X", "sbx"))
    }

    @Test
    fun resolvePathIncludesDesktopLocationsAfterStandaloneLocations() {
        val path =
            OpenCodeProcessLaunch.resolvePath(
                    currentPath = "/first",
                    environment = mapOf("HOME" to temp.root.path),
                    osName = "Mac OS X",
                    osArch = "aarch64",
                )
                .split(File.pathSeparator)
        assertEquals("/first", path.first())
        assertTrue(
            path.indexOf(temp.root.resolve(".opencode/bin").path) <
                path.indexOf(
                    temp.root.resolve("Applications/OpenCode.app/Contents/Resources").path,
                ),
        )
        assertEquals(
            "/only",
            OpenCodeServerProtocol.resolvePath("/only", additionalPaths = emptyList()),
        )
    }
}
