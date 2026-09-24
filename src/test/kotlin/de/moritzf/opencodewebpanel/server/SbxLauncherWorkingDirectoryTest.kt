package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Exercises the real launcher in Bash on Unix and Git Bash on Windows, without creating a VM. */
class SbxLauncherWorkingDirectoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun launchUsesTheSubdirectoryButKeepsTheExistingSandboxIdentity() {
        val fixture = fixture()
        Files.writeString(fixture.spec, "canonicalDirectory: ./\nworkingDirectory: ./app\n")

        val (exitCode, output) = runLauncher(fixture)

        assertEquals(output, 19, exitCode)
        val argv = output.lineSequence().filter { it.startsWith("<") }
            .map { it.removeSurrounding("<", ">") }.toList()
        assertEquals(
            listOf("exec", "-i", "-w", SbxCli.guestBindPath(fixture.app.toString()),
                SbxCli.sandboxName(fixture.project.toString()), "opencode"),
            argv,
        )
    }

    @Test
    fun missingOrEscapingWorkingDirectoryFailsBeforeSbxExec() {
        val fixture = fixture()
        for (workdir in listOf("../other", "./missing", "/tmp")) {
            Files.writeString(fixture.spec, "canonicalDirectory: ./\nworkingDirectory: '$workdir'\n")
            val (exitCode, output) = runLauncher(fixture)
            assertEquals(output, 1, exitCode)
            assertTrue(output, output.contains("workingDirectory"))
        }
    }

    private data class Fixture(val project: Path, val app: Path, val spec: Path, val launcher: Path, val sbx: Path)

    private fun fixture(): Fixture {
        val project = temp.newFolder("sample-repo").toPath().toRealPath()
        val app = Files.createDirectory(project.resolve("app"))
        val control = Files.createDirectory(project.resolve(SbxCli.PROJECT_CONTROL_DIR))
        val spec = control.resolve(SbxLaunchSpec.PROJECT_SPEC_NAME)
        val launcher = control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX)
        javaClass.getResourceAsStream(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX)!!.use { Files.copy(it, launcher) }
        Files.createDirectory(temp.root.toPath().resolve("home"))
        val sbx = Files.writeString(temp.root.toPath().resolve("fake-sbx"), """
            #!/usr/bin/env bash
            set -euo pipefail
            case "${'$'}1" in
              daemon) exit 0 ;;
              ls) printf '{"sandboxes":[{"name":"%s","workspaces":["%s"]}]}\n' "${'$'}OCWP_TEST_NAME" "${'$'}OCWP_TEST_WORKSPACE" ;;
              exec) printf '<%s>\n' "${'$'}@"; exit 19 ;;
              *) printf 'Unexpected sbx command: %s\n' "${'$'}1" >&2; exit 97 ;;
            esac
        """.trimIndent() + "\n")
        assertTrue("Fake sbx must be executable", sbx.toFile().setExecutable(true))
        return Fixture(project, app, spec, launcher, sbx)
    }

    private fun runLauncher(fixture: Fixture): Pair<Int, String> {
        val bash = if (System.getProperty("os.name").startsWith("Windows")) {
            Path.of(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git", "bin", "bash.exe")
        } else {
            Path.of("/bin/bash")
        }
        assertTrue("Bash is required to run the sandbox launcher: $bash", Files.isExecutable(bash))
        val process = ProcessBuilder(bash.toString(), SbxCli.guestBindPath(fixture.launcher.toString()),
            "--cli", SbxCli.guestBindPath(fixture.project.toString()))
            .directory(fixture.project.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["OCWP_SBX"] = SbxCli.guestBindPath(fixture.sbx.toString())
                environment()["OCWP_TEST_NAME"] = SbxCli.sandboxName(fixture.project.toString())
                environment()["OCWP_TEST_WORKSPACE"] = SbxCli.sandboxIdentityPath(fixture.project.toString())
                environment()["OCWP_CONFIG_DIR"] = SbxCli.guestBindPath(temp.root.toPath().resolve("config").toString())
                environment()["OCWP_DATA_DIR"] = SbxCli.guestBindPath(temp.root.toPath().resolve("data").toString())
                environment()["HOME"] = SbxCli.guestBindPath(temp.root.toPath().resolve("home").toString())
            }.start()
        try {
            assertTrue("Launcher timed out", process.waitFor(20, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            process.destroyForcibly()
        }
    }
}
