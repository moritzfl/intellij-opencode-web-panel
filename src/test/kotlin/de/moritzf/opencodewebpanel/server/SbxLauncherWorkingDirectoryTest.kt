package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun nativeProjectAndFlagsUseTheExistingVmWithoutChangingItsWorkspace() {
        val fixture = fixture()
        Files.writeString(fixture.spec, "canonicalDirectory: ./\nworkingDirectory: ./app\n")

        val (exitCode, output) = runLauncher(fixture, "./app", "-m", "fixture/model", "--continue")

        assertEquals(output, 19, exitCode)
        val argv = output.lineSequence().filter { it.startsWith("<") }
            .map { it.removeSurrounding("<", ">") }.toList()
        assertEquals(
            listOf("exec", "-i", "-w", SbxCli.guestBindPath(fixture.app.toString()),
                SbxCli.sandboxName(fixture.project.toString()), "opencode",
                SbxCli.guestBindPath(fixture.app.toString()), "-m", "fixture/model", "--continue"),
            argv,
        )
    }

    @Test
    fun nativeRunArgumentsKeepSpacesAndDoNotLaunchServe() {
        val fixture = fixture()
        Files.writeString(fixture.spec, "canonicalDirectory: ./\n")

        val (exitCode, output) = runLauncher(fixture, "run", "two words", "--model", "fixture/model")

        assertEquals(output, 19, exitCode)
        val argv = output.lineSequence().filter { it.startsWith("<") }
            .map { it.removeSurrounding("<", ">") }.toList()
        assertEquals(listOf("opencode", "run", "two words", "--model", "fixture/model"), argv.takeLast(5))
        assertTrue("No second serve process", "serve" !in argv)
    }

    @Test
    fun existingReadOnlySharedConfigIsRecognizedWithWindowsGuestHome() {
        val fixture = fixture()
        val shared = Files.createDirectories(temp.root.toPath().resolve("home/.config/opencode")).toRealPath()
        Files.writeString(fixture.spec, "canonicalDirectory: ./\nshareHostOpencodeConfig: true\n")

        val (exitCode, output) = runLauncher(fixture, sharedConfig = shared)

        assertEquals(output, 19, exitCode)
        assertFalse("An attached config mount must not prompt for recreation: $output", output.contains("created without the shared OpenCode config"))
        val argv = output.lineSequence().filter { it.startsWith("<") }
            .map { it.removeSurrounding("<", ">") }.toList()
        assertEquals(
            listOf("exec", "-i", "-e", "XDG_CONFIG_HOME", "-w", SbxCli.guestBindPath(fixture.project.toString()),
                SbxCli.sandboxName(fixture.project.toString()), "opencode"),
            argv,
        )
    }

    @Test
    fun nativeAcpKeepsTheImmediateInitializeRequestOutOfSandboxSetup() {
        val fixture = fixture()
        Files.writeString(fixture.spec, "canonicalDirectory: ./\n")
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}""" + "\n"

        val (exitCode, output) = runLauncher(fixture, "acp", acpInput = request, mergeErrorStream = false)

        assertEquals(output, 0, exitCode)
        assertEquals("", Files.readString(temp.root.toPath().resolve("setup-input")))
        assertEquals(request, Files.readString(temp.root.toPath().resolve("acp-input")))
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""" + "\n", output)
        val argv = Files.readString(temp.root.toPath().resolve("acp-args")).removeSuffix("\u0000").split('\u0000')
        assertEquals(
            listOf("exec", "-i", "-w", SbxCli.guestBindPath(fixture.project.toString()),
                SbxCli.sandboxName(fixture.project.toString()), "opencode", "acp"),
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

    @Test
    fun hostVariableMountsMatchPluginCreateArguments() {
        val fixture = fixture()
        val home = temp.root.toPath().resolve("home").toRealPath()
        Files.createDirectories(home.resolve(".gradle/config"))
        val custom = temp.newFolder("Gradle home ä").toPath().toRealPath()
        Files.createDirectory(custom.resolve("config"))
        val mount = SbxExtraMount("\${OCWP_TEST_GRADLE_HOME:-~/.gradle}/config", "/home/agent/.gradle-host", readOnly = true)
        Files.writeString(fixture.spec, """
            canonicalDirectory: ./
            protectSandboxFiles: false
            persistSandboxSessions: false
            extraMounts:
              - host: '${mount.hostPath}'
                sandbox: '${mount.sandboxPath}'
                readOnly: true
        """.trimIndent() + "\n")

        for (value in listOf(null, "", custom.toString())) {
            val environment = value?.let { mapOf("OCWP_TEST_GRADLE_HOME" to it) }.orEmpty()
            val (exitCode, output) = runLauncher(fixture, createSandbox = true, mountEnvironment = environment)
            assertEquals(output, 23, exitCode)
            val argv = output.lineSequence().filter { it.startsWith("<") }
                .map { it.removeSurrounding("<", ">") }.toList()
            val workspace = SbxCli.posixPath(fixture.project.toString())
            val resolved = SbxCli.resolveExtraMounts(listOf(mount), workspace, home.toString(), environment)
            assertEquals(
                SbxCli.buildCreateCommand(
                    name = SbxCli.sandboxName(workspace), workspace = workspace,
                    extraWorkspaces = SbxCli.extraMountCreateArgs(resolved, workspace),
                ).drop(1),
                argv,
            )
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
              daemon)
                [[ "${'$'}2" == start && "${'$'}3" == --detach ]] || exit 97
                if [[ "${'$'}{OCWP_TEST_ACP:-false}" == true ]]; then cat > "${'$'}OCWP_TEST_DIR/setup-input"; fi
                exit 0 ;;
              ls)
                if [[ "${'$'}{OCWP_TEST_CREATE:-false}" == true ]]; then
                  printf '{"sandboxes":[]}\n'
                elif [[ -n "${'$'}{OCWP_TEST_SHARED:-}" ]]; then
                  printf '{"sandboxes":[{"name":"%s","workspaces":["%s","%s:ro"]}]}\n' "${'$'}OCWP_TEST_NAME" "${'$'}OCWP_TEST_WORKSPACE" "${'$'}OCWP_TEST_SHARED"
                else
                  printf '{"sandboxes":[{"name":"%s","workspaces":["%s"]}]}\n' "${'$'}OCWP_TEST_NAME" "${'$'}OCWP_TEST_WORKSPACE"
                 fi ;;
              create) printf '<%s>\n' "${'$'}@"; exit 23 ;;
              exec)
                if [[ "${'$'}{OCWP_TEST_ACP:-false}" == true ]]; then
                  printf '%s\0' "${'$'}@" > "${'$'}OCWP_TEST_DIR/acp-args"
                  cat > "${'$'}OCWP_TEST_DIR/acp-input"
                  printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                  exit 0
                fi
                printf '<%s>\n' "${'$'}@"; exit 19 ;;
              *) printf 'Unexpected sbx command: %s\n' "${'$'}1" >&2; exit 97 ;;
            esac
        """.trimIndent() + "\n")
        assertTrue("Fake sbx must be executable", sbx.toFile().setExecutable(true))
        return Fixture(project, app, spec, launcher, sbx)
    }

    private fun runLauncher(
        fixture: Fixture,
        vararg args: String,
        sharedConfig: Path? = null,
        acpInput: String? = null,
        mergeErrorStream: Boolean = true,
        createSandbox: Boolean = false,
        mountEnvironment: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val bash = if (System.getProperty("os.name").startsWith("Windows")) {
            Path.of(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git", "bin", "bash.exe")
        } else {
            Path.of("/bin/bash")
        }
        assertTrue("Bash is required to run the sandbox launcher: $bash", Files.isExecutable(bash))
        val process = ProcessBuilder(listOf(bash.toString(), SbxCli.guestBindPath(fixture.launcher.toString())) + args.toList())
            .directory(fixture.project.toFile())
            .redirectErrorStream(mergeErrorStream)
            .apply {
                environment()["OCWP_SBX"] = SbxCli.guestBindPath(fixture.sbx.toString())
                environment()["OCWP_TEST_NAME"] = SbxCli.sandboxName(fixture.project.toString())
                environment()["OCWP_TEST_WORKSPACE"] = SbxCli.sandboxIdentityPath(fixture.project.toString())
                environment()["OCWP_CONFIG_DIR"] = SbxCli.guestBindPath(temp.root.toPath().resolve("config").toString())
                environment()["OCWP_DATA_DIR"] = SbxCli.guestBindPath(temp.root.toPath().resolve("data").toString())
                environment()["HOME"] = SbxCli.guestBindPath(temp.root.toPath().resolve("home").toString())
                if (sharedConfig != null) environment()["OCWP_TEST_SHARED"] = SbxCli.posixPath(sharedConfig.toString())
                environment()["OCWP_TEST_ACP"] = (acpInput != null).toString()
                environment()["OCWP_TEST_DIR"] = SbxCli.guestBindPath(temp.root.toPath().toString())
                environment()["OCWP_TEST_CREATE"] = createSandbox.toString()
                environment().remove("OCWP_TEST_GRADLE_HOME")
                environment().putAll(mountEnvironment)
            }.start()
        process.outputStream.use { input -> acpInput?.let { input.write(it.toByteArray()) } }
        try {
            assertTrue("Launcher timed out", process.waitFor(20, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            process.destroyForcibly()
        }
    }
}
