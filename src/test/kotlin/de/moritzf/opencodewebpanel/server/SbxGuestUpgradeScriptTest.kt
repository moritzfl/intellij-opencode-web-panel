package de.moritzf.opencodewebpanel.server

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs the real 2.x guest upgrade script under `sh` with a fake `opencode`. The fake mimics
 * OpenCode's `upgrade --method curl`: it skips when its own version is the latest, otherwise
 * installs the latest into ~/.opencode/bin. The same cases were run against Docker's 2.3.0 kit.
 */
class SbxGuestUpgradeScriptTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var home: Path
    private lateinit var kit: Path
    private lateinit var template: Path
    private lateinit var log: Path

    @Before
    fun setUp() {
        assumeTrue("POSIX sh", File.separatorChar == '/')
        home = temp.newFolder("home").toPath()
        kit = temp.newFolder("kit").toPath()
        log = temp.root.toPath().resolve("calls.log")
        template = temp.root.toPath().resolve("opencode.template")
        Files.writeString(
            template,
            $$"""
            #!/bin/sh
            echo "@VERSION@ $*" >> "$FAKE_LOG"
            case "$1" in
              --version) echo "opencode v@VERSION@" ;;
              upgrade)
                if [ "@VERSION@" = "$FAKE_LATEST" ]; then
                  echo "OpenCode upgrade skipped: @VERSION@ is already installed"
                  exit 0
                fi
                # Like the real installer: replace the binary with mv, never rewrite it in place.
                mkdir -p "$HOME/.opencode/bin"
                # Split so installing this fake does not replace its own placeholder pattern.
                sed "s/@VERSION""@/$FAKE_LATEST/g" "$FAKE_TEMPLATE" > "$HOME/.opencode/bin/next"
                chmod 755 "$HOME/.opencode/bin/next"
                mv "$HOME/.opencode/bin/next" "$HOME/.opencode/bin/opencode"
                ;;
            esac
            """
                .trimIndent() + "\n",
        )
        install(kit.resolve("opencode"), "2.3.0")
    }

    @Test
    fun kitBinaryInstallsTheReleaseAndLinksIt() {
        val run = upgrade(latest = "2.3.1")

        assertEquals(run.output, 0, run.exit)
        assertEquals("opencode v2.3.1", run.output.lines().last { it.isNotBlank() })
        assertEquals(installed(), Files.readSymbolicLink(link()))
        assertTrue(Files.readString(log).contains("2.3.0 upgrade --method curl --print-logs"))
    }

    @Test
    fun linkedCopyUpgradesInPlace() {
        upgrade(latest = "2.3.1")

        val run = upgrade(latest = "2.3.2")

        assertEquals(run.output, 0, run.exit)
        assertEquals("opencode v2.3.2", run.output.lines().last { it.isNotBlank() })
        assertTrue(
            "the linked copy ran the upgrade",
            Files.readString(log).contains("2.3.1 upgrade"),
        )
    }

    @Test
    fun staleCopyNeverReplacesACurrentKit() {
        install(installed(), "2.2.7")

        val run = upgrade(latest = "2.3.0")

        assertEquals(run.output, 0, run.exit)
        assertFalse(Files.exists(link(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("opencode v2.3.0", run.output.lines().last { it.isNotBlank() })
    }

    @Test
    fun failsWhenTheLinkIsNotFirstOnPath() {
        val run = upgrade(latest = "2.3.1", linkFirst = false)

        assertEquals(1, run.exit)
        assertTrue(run.output, run.output.contains("is not first on the sandbox PATH"))
    }

    private data class Run(val exit: Int, val output: String)

    private fun upgrade(latest: String, linkFirst: Boolean = true): Run {
        val command = SbxCli.buildExecUpgradeCommand("sbx", "ide-ocwp-abc", SbxOpenCodeVersion.V2)
        val script = decodedGuestScript(command)!!
        val links = home.resolve(".local/bin").toString()
        val path =
            listOf(kit.toString(), "/usr/bin", "/bin").let {
                if (linkFirst) listOf(links) + it else it + links
            }
        val process =
            ProcessBuilder("sh", "-c", script)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["HOME"] = home.toString()
                    environment()["PATH"] = path.joinToString(":")
                    environment()["FAKE_LATEST"] = latest
                    environment()["FAKE_TEMPLATE"] = template.toString()
                    environment()["FAKE_LOG"] = log.toString()
                }
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return Run(process.exitValue(), output)
    }

    private fun install(target: Path, version: String) {
        Files.createDirectories(target.parent)
        Files.writeString(target, Files.readString(template).replace("@VERSION@", version))
        target.toFile().setExecutable(true)
    }

    private fun installed(): Path = home.resolve(".opencode/bin/opencode")

    private fun link(): Path = home.resolve(".local/bin/opencode")
}
