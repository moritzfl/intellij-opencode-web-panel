package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SbxGuestVersionTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var bin: Path
    private lateinit var guestBinary: Path

    @Before
    fun setup() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")))
        bin = Files.createDirectories(temp.root.toPath().resolve("bin"))
        // Production Linux guests have GNU timeout; this shim executes the test binaries on macOS too.
        executable(bin.resolve("timeout"), "shift 3; exec \"\$@\"")
        guestBinary = Files.createDirectories(temp.root.toPath().resolve(".opencode/bin")).resolve("opencode")
    }

    @Test
    fun missingBinaryIsDistinctFromInvalidInstalledBinary() {
        assertEquals(SbxCli.GUEST_V2_MISSING_EXIT_CODE, probe().exitCode)
        Files.writeString(guestBinary, "not executable")
        val invalid = probe()
        assertEquals(45, invalid.exitCode)
        assertTrue(invalid.output.contains("not executable"))
    }

    @Test
    fun acceptsOnlyRunnableTwoDotVersions() {
        for (version in listOf("2.0.11", "opencode v2.0.11", "v2.1.0-beta.1")) {
            executable(guestBinary, "printf '%s\\n' '$version'")
            assertEquals(version, 0, probe().exitCode)
        }
        for (version in listOf("1.18.23", "opencode v3.0.0", "unknown", "log says version=2.0.11")) {
            executable(guestBinary, "printf '%s\\n' '$version'")
            val invalid = probe()
            assertEquals(version, 45, invalid.exitCode)
            assertTrue(invalid.output.contains("Expected OpenCode 2.x"))
        }
    }

    @Test
    fun executableThatFailsVersionCheckIsRejected() {
        executable(guestBinary, "echo 'opencode v2.0.11'; exit 126")
        val invalid = probe()
        assertEquals(45, invalid.exitCode)
        assertTrue(invalid.output.contains("exit 126"))
    }

    @Test
    fun timeoutFailureIsReported() {
        executable(guestBinary, "echo 'opencode v2.0.11'")
        executable(bin.resolve("timeout"), "exit 124")
        val invalid = probe()
        assertEquals(45, invalid.exitCode)
        assertTrue(invalid.output.contains("exit 124"))
    }

    @Test
    fun v2DispatchNeverFallsBackToKitBinary() {
        val marker = temp.root.toPath().resolve("kit-started")
        executable(bin.resolve("opencode"), "touch '$marker'")
        assertTrue(runScript(SbxCli.GUEST_OPENCODE_DISPATCH).exitCode != 0)
        assertFalse(Files.exists(marker))
    }

    private fun probe(): SbxCommandResult = runScript(SbxCli.GUEST_V2_VERSION_SCRIPT)

    private fun runScript(script: String): SbxCommandResult {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).apply {
            environment().clear()
            environment()["HOME"] = temp.root.toString()
            environment()["PATH"] = "$bin:/usr/bin:/bin"
        }.start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        return SbxCommandResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
    }

    private fun executable(path: Path, script: String) {
        Files.writeString(path, "#!/bin/sh\n$script\n")
        assertTrue(path.toFile().setExecutable(true))
    }
}
