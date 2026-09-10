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

class SbxLauncherTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun requireUnixBash() {
        assumeTrue("Unix launcher requires /bin/bash", Files.isExecutable(Path.of("/bin/bash")))
    }

    @Test
    fun adjacentProjectSpecPreservesKitScalarsAndFileMountsFromUnrelatedCwd() {
        val project = directory("project with spaces")
        val caller = directory("unrelated caller")
        val home = directory("home")
        val fileMount = Files.writeString(home.resolve("mounted \"file\" #1.txt"), "fixture")
        val directoryMount = Files.createDirectory(project.resolve("mounted directory"))
        Files.createDirectory(project.resolve("kit #1"))
        Files.writeString(
            project.resolve("opencode-sbx.yaml"),
            """
            canonicalDirectory: './' # cloned project, not the caller
            name: stale-name-from-another-clone
            memory: "8g" # ignored "quotes and # hashes"
            cpus: '4'
            hostPort: 49123 # fixed publish
            kits:
              - git+https://github.com/team/kits.git#ref=v1&dir=network # unquoted ref
              - "git+https://github.com/team/kits.git#ref=v2&dir=network" # quoted ref
              - './kit #1' # single-quoted hash
              - "./kit \"quoted\" #2" # escaped quotes do not end the scalar
              - './kit ''quoted'' #3' # doubled single quotes
              - "./kit\\path" # escaped backslash
              - ./team's-kit # an apostrophe in a plain scalar is not a quote
            extraMounts:
              - host: "~/mounted \"file\" #1.txt" # file, not directory
                sandbox: '/home/agent/file #1'
              - host: ./mounted directory # relative mount
                sandbox: /home/agent/directory
              - host: ./missing-file
                sandbox: /home/agent/missing
            shareHostOpencodeConfig: false
            """.trimIndent(),
        )

        val actual = runLauncher(launcher(project), caller)

        assertEquals(
            listOf(
                "create", "-q", "--name", SbxCli.sandboxName(project.toString()),
                "--memory", "8g", "--cpus", "4", "--publish", "127.0.0.1:49123:4096/tcp4",
                "--kit", "git+https://github.com/team/kits.git#ref=v1&dir=network",
                "--kit", "git+https://github.com/team/kits.git#ref=v2&dir=network",
                "--kit", "./kit #1",
                "--kit", "./kit \"quoted\" #2",
                "--kit", "./kit 'quoted' #3",
                "--kit", "./kit\\path",
                "--kit", "./team's-kit",
                "opencode", project.toString(),
            ) + protectCreateArgs(project, listOf("./kit #1", "./kit \"quoted\" #2", "./kit 'quoted' #3", "./kit\\path", "./team's-kit")) + persistCreateArgs(project) + listOf(
                fileMount.toString(), directoryMount.toString(),
            ),
            actual,
        )
        assertProvisioningCwd(project)
    }

    @Test
    fun explicitRelativeDirectoryResolvesWorkspaceAgainstSpecParentAndCanonicalizesSymlinks() {
        val project = directory("real workspace")
        val specDirectory = directory("spec directory")
        val caller = directory("unrelated caller")
        Files.createSymbolicLink(temp.root.toPath().resolve("workspace link"), project)
        Files.writeString(
            specDirectory.resolve("opencode-sbx.yaml"),
            "canonicalDirectory: '../workspace link/.'\nname: stale-clone-name\n",
        )
        val scriptDirectory = directory("script directory")
        Files.writeString(scriptDirectory.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")

        val actual = runLauncher(launcher(scriptDirectory), caller, "../spec directory")

        assertEquals(createArgs(project), actual)
        assertProvisioningCwd(project)
    }

    @Test
    fun legacyAbsoluteSpecKeepsNameWhileCanonicalizingWorkspace() {
        val project = directory("real workspace")
        val specDirectory = directory("legacy project")
        val caller = directory("unrelated caller")
        val link = Files.createSymbolicLink(temp.root.toPath().resolve("workspace link"), project)
        Files.writeString(
            specDirectory.resolve("opencode-web-panel.sbx.yaml"),
            "canonicalDirectory: '$link/.'\nname: legacy-sandbox-name\n",
        )

        val actual = runLauncher(launcher(specDirectory), caller)

        assertEquals(createArgs(project, "legacy-sandbox-name"), actual)
        assertProvisioningCwd(project)
    }

    @Test
    fun machineLauncherWithoutAdjacentSpecUsesCallerProject() {
        val project = directory("caller project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nname: stale-name\n")

        val actual = runLauncher(launcher(directory("machine bin")), project)

        assertEquals(createArgs(project), actual)
        assertProvisioningCwd(project)
    }

    @Test
    fun machineCopyResolvesRelativeWorkspaceAgainstMachineSpecParent() {
        val project = directory("real workspace")
        val caller = directory("unrelated caller")
        val config = Files.createDirectories(temp.root.toPath().resolve("config/sbx"))
        Files.writeString(
            config.resolve("${SbxCli.sandboxName(caller.toString())}.yaml"),
            "canonicalDirectory: '../../real workspace'\nname: stale-name\n",
        )

        val actual = runLauncher(launcher(directory("machine bin")), caller)

        assertEquals(createArgs(project), actual)
        assertProvisioningCwd(project)
    }

    @Test
    fun protectSandboxFilesOverlaysControlDirectoryNotFiles() {
        val project = directory("project")
        val spec = SbxLaunchSpec.projectSpecPath(project.toString())
        Files.createDirectories(spec.parent)
        Files.writeString(spec, "canonicalDirectory: ./\n")
        val args = runLauncher(launcher(project), project)
        assertEquals(createArgs(project), args)
        assertTrue(args.contains(SbxCli.posixPath(spec.parent.toString()) + ":ro"))
        assertFalse(args.any { it.contains("opencode-sbx.yaml") })
        assertFalse(args.any { it.endsWith("opencode-sbx.sh:ro") })
    }

    @Test
    fun launcherInsideControlDirUsesProjectParent() {
        val project = directory("project")
        val control = Files.createDirectories(project.resolve(SbxCli.PROJECT_CONTROL_DIR))
        Files.writeString(control.resolve(SbxLaunchSpec.PROJECT_SPEC_NAME), "canonicalDirectory: ./\n")
        val args = runLauncher(launcher(control), project)
        assertEquals(createArgs(project), args)
        assertProvisioningCwd(project)
    }

    @Test
    fun protectSandboxFilesFalseSkipsControlFileOverlays() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nprotectSandboxFiles: false\n")
        val args = runLauncher(launcher(project), project)
        assertEquals(
            listOf(
                "create", "-q", "--name", SbxCli.sandboxName(project.toString()),
                "--memory", "4g", "--cpus", "2", "--publish", "4096/tcp4",
                "opencode", project.toString(),
            ) + persistCreateArgs(project),
            args,
        )
        assertFalse(args.any { it.endsWith(":ro") })
    }

    @Test
    fun sharingMountsHostConfigAsIs() {
        val project = directory("project")
        val shared = Files.createDirectories(temp.root.toPath().resolve("home/.config/opencode")).toRealPath()
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nshareHostOpencodeConfig: true\n")
        val script = launcher(project)
        assertEquals(createArgs(project) + "$shared:ro", runLauncher(script, project))
    }

    @Test
    fun existingSharedSandboxUsesGlobalConfigAndAuthEnvironment() {
        val project = directory("project")
        val home = Files.createDirectories(temp.root.toPath().resolve("home")).toRealPath()
        Files.createDirectories(home.resolve(".config/opencode"))
        val auth = home.resolve(".local/share/opencode/auth.json")
        Files.createDirectories(auth.parent)
        Files.writeString(auth, """{"fixture":{"type":"api","key":"fixture-only"}}""")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nshareHostOpencodeConfig: true\n")
        val args = runLauncher(launcher(project), project, serve = true)
        assertEquals(
            listOf("exec", "-e", "OPENCODE_SERVER_PASSWORD", "-e", "XDG_CONFIG_HOME", "-e", "OPENCODE_AUTH_CONTENT",
                "-w", project.toString(), SbxCli.sandboxName(project.toString()),
                "opencode", "serve", "--hostname", "0.0.0.0", "--port", "4096", "--print-logs"),
            args,
        )
        assertEquals(home.resolve(".config").toString(), Files.readString(temp.root.toPath().resolve("log/config-home")))
        assertEquals(Files.readString(auth), Files.readString(temp.root.toPath().resolve("log/auth-content")))
    }

    @Test
    fun sharingDisabledDoesNotForwardConfigOrAuthEnvironment() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nshareHostOpencodeConfig: false\n")
        val args = runLauncher(launcher(project), project, serve = true)
        assertFalse(args.contains("XDG_CONFIG_HOME"))
        assertFalse(args.contains("OPENCODE_AUTH_CONTENT"))
        assertFalse(args.contains("XDG_DATA_HOME"))
    }

    @Test
    fun unixLauncherIsValidBashAndDocumentsCliAndWeb() {
        val script = launcher(directory("machine bin"))
        val syntax = ProcessBuilder("/bin/bash", "-n", script.toString()).start()
        assertTrue(syntax.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, syntax.exitValue())
        val text = Files.readString(script)
        assertTrue(text.contains("./opencode-sbx/opencode-sbx.sh --cli"))
        assertTrue(text.contains("./opencode-sbx/opencode-sbx.sh [--web]"))
        assertTrue(text.contains("./opencode-sbx/opencode-sbx.sh --acp"))
        assertTrue(text.contains("--oc-args"))
        assertFalse(text.contains("Generated OPENCODE_SERVER_PASSWORD"))
        assertFalse(text.contains("openssl rand"))
        assertFalse(text.contains(" \"\$SBX\" run"))
        assertFalse(text.contains("run -d"))
    }

    @Test
    fun helpListsCliAndWebAndDoesNotInvokeSbx() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        assertEquals(emptyList<String>(), runLauncher(launcher(project), project, "--help", expectedExit = 2))
        val output = launcherOutput()
        assertTrue(output, output.contains("--cli"))
        assertTrue(output, output.contains("--web"))
        assertTrue(output, output.contains("--acp"))
        assertTrue(output, output.contains("--oc-args"))
        assertTrue(output, output.contains("sbx exec"))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/calls")))
    }

    @Test
    fun cliAndWebTogetherPrintsUsage() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        runLauncher(launcher(project), project, "--cli", "--web", expectedExit = 2)
        assertTrue(launcherOutput().contains("Usage:"))
    }

    @Test
    fun acpAndWebTogetherPrintsUsage() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        runLauncher(launcher(project), project, "--acp", "--web", expectedExit = 2)
        assertTrue(launcherOutput().contains("Usage:"))
    }

    @Test
    fun acpAndCliTogetherPrintsUsage() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        runLauncher(launcher(project), project, "--acp", "--cli", expectedExit = 2)
        assertTrue(launcherOutput().contains("Usage:"))
    }

    @Test
    fun cliCreateUsesSameSandboxAsWeb() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val script = launcher(project)
        assertEquals(createArgs(project), runLauncher(script, project, "--cli"))
        assertProvisioningCwd(project)
    }

    @Test
    fun existingSandboxCliStartsTuiNotServe() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val args = runLauncher(launcher(project), project, "--cli", serve = true)
        assertEquals(
            listOf(
                "exec", "-i",
                "-w", project.toString(), SbxCli.sandboxName(project.toString()),
                "opencode",
            ),
            args,
        )
        assertFalse(args.contains("serve"))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/health-checked")))
    }

    @Test
    fun existingSandboxAcpStartsStdioWithoutTty() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val args = runLauncher(launcher(project), project, "--acp", serve = true)
        assertEquals(
            listOf(
                "exec", "-i",
                "-w", project.toString(), SbxCli.sandboxName(project.toString()),
                "opencode", "acp",
            ),
            args,
        )
        assertFalse(args.contains("-t"))
        assertFalse(args.contains("serve"))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/health-checked")))
    }

    @Test
    fun acpDoesNotGenerateOrForwardServerPassword() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val args = runLauncher(launcher(project), project, "--acp", serve = true, serverPassword = null)
        assertFalse(args.contains("OPENCODE_SERVER_PASSWORD"))
        assertFalse(launcherOutput().contains("Generated OPENCODE_SERVER_PASSWORD"))
    }

    @Test
    fun existingSandboxWebFlagStartsServe() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val args = runLauncher(launcher(project), project, "--web", serve = true)
        assertEquals(
            listOf(
                "exec", "-e", "OPENCODE_SERVER_PASSWORD",
                "-w", project.toString(), SbxCli.sandboxName(project.toString()),
                "opencode", "serve", "--hostname", "0.0.0.0", "--port", "4096", "--print-logs",
            ),
            args,
        )
        assertTrue(Files.exists(temp.root.toPath().resolve("log/health-checked")))
    }

    @Test
    fun webDoesNotGeneratePasswordWhenUnset() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val args = runLauncher(launcher(project), project, "--web", serve = true, serverPassword = null)
        assertFalse(args.contains("OPENCODE_SERVER_PASSWORD"))
        assertFalse(launcherOutput().contains("Generated OPENCODE_SERVER_PASSWORD"))
        assertTrue(Files.exists(temp.root.toPath().resolve("log/health-checked")))
    }

    @Test
    fun extraArgsAfterDashDashGoToCli() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val cli = runLauncher(launcher(project), project, "--cli", "--oc-args", "--continue", serve = true)
        assertEquals("--continue", cli.last())
        assertFalse(cli.contains("serve"))
        assertFalse(cli.contains("OPENCODE_SERVER_PASSWORD"))
    }

    @Test
    fun extraArgsAfterDashDashGoToAcp() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val acp = runLauncher(launcher(project), project, "--acp", "--oc-args", "--print-logs", serve = true)
        assertEquals(listOf("opencode", "acp", "--print-logs"), acp.takeLast(3))
        assertFalse(acp.contains("-t"))
        assertFalse(acp.contains("OPENCODE_SERVER_PASSWORD"))
    }

    @Test
    fun extraArgsAfterDashDashGoToWebServe() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val web = runLauncher(launcher(project), project, "--web", "--oc-args", "--print-logs", serve = true)
        assertEquals(
            listOf("serve", "--hostname", "0.0.0.0", "--port", "4096", "--print-logs", "--print-logs"),
            web.takeLast(7),
        )
    }

    @Test
    fun sharingConfigForwardsEnvironmentToCli() {
        val project = directory("project")
        val home = Files.createDirectories(temp.root.toPath().resolve("home")).toRealPath()
        Files.createDirectories(home.resolve(".config/opencode"))
        val auth = home.resolve(".local/share/opencode/auth.json")
        Files.createDirectories(auth.parent)
        Files.writeString(auth, """{"fixture":{"type":"api","key":"fixture-only"}}""")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nshareHostOpencodeConfig: true\n")
        val args = runLauncher(launcher(project), project, "--cli", serve = true)
        assertEquals(
            listOf(
                "exec", "-i", "-e", "XDG_CONFIG_HOME", "-e", "OPENCODE_AUTH_CONTENT",
                "-w", project.toString(), SbxCli.sandboxName(project.toString()),
                "opencode",
            ),
            args,
        )
        assertEquals(home.resolve(".config").toString(), Files.readString(temp.root.toPath().resolve("log/config-home")))
        assertEquals(Files.readString(auth), Files.readString(temp.root.toPath().resolve("log/auth-content")))
    }

    @Test
    fun rmRemovesSandboxWithoutStartingOpencode() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val args = runLauncher(launcher(project), project, "--rm", expectedExit = 0, resultCommand = "rm", owned = true)
        assertEquals(listOf("rm", "--force", SbxCli.sandboxName(project.toString())), args)
        assertTrue(launcherOutput().contains("Removed sandbox"))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/exec.args")))
    }

    @Test
    fun rmDoesNotDeleteWhenNameAndWorkspaceAreInDifferentEntries() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val name = SbxCli.sandboxName(project.toString())
        val lsJson = """{"sandboxes":[{"name":"$name","workspaces":["/other/project"]},{"name":"other-box","workspaces":["$project"]}]}"""
        runLauncher(launcher(project), project, "--rm", expectedExit = 1, lsJson = lsJson)
        assertFalse(Files.exists(temp.root.toPath().resolve("log/rm.args")))
        assertTrue(launcherOutput().contains("no owned sandbox"))
    }

    @Test
    fun recreateDoesNotRemoveUnownedNameMatch() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val name = SbxCli.sandboxName(project.toString())
        val lsJson = """{"sandboxes":[{"name":"$name","workspaces":["/other/project"]}]}"""
        runLauncher(launcher(project), project, "--recreate", serve = true, expectedExit = 37, lsJson = lsJson)
        assertFalse(Files.exists(temp.root.toPath().resolve("log/rm.args")))
    }

    @Test
    fun persistReplaceRequiresThisSandboxWorkspace() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val name = SbxCli.sandboxName(project.toString())
        val persist = persistCreateArgs(project, name).single()
        val lsJson = """{"sandboxes":[{"name":"$name","workspaces":["$project"]},{"name":"other-box","workspaces":["$persist"]}]}"""
        val args = runLauncher(launcher(project), project, serve = true, lsJson = lsJson)
        assertFalse(args.contains("opencode-link"))
        assertTrue(args.contains("serve"))
    }

    @Test
    fun windowsDriveCanonicalDirectoryIsNotPrefixedWithSpecBase() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: C:/definitely-missing-ocwp-test\n")
        runLauncher(launcher(project), project, expectedExit = 1)
        val output = launcherOutput()
        assertFalse(output.contains("${project}/C:"))
        assertTrue(output.contains("C:/definitely-missing-ocwp-test") || output.contains("No such file"))
    }

    @Test
    fun relativeExtraMountIsCanonicalizedBeforeLink() {
        val project = directory("project")
        val docs = Files.createDirectory(project.resolve("docs"))
        Files.writeString(
            project.resolve("opencode-sbx.yaml"),
            """
            canonicalDirectory: ./
            extraMounts:
              - host: ./docs
                sandbox: /home/agent/docs
            """.trimIndent() + "\n",
        )
        val args = runLauncher(launcher(project), project, createExit = 0, resultCommand = "exec")
        assertTrue(args.contains("opencode-link"))
        assertTrue(args.contains(docs.toString()))
        assertFalse(args.contains("./docs"))
    }

    @Test
    fun flowStyleKitsArePassedToCreate() {
        val project = directory("project")
        Files.createDirectory(project.resolve("network-kit"))
        Files.writeString(
            project.resolve("opencode-sbx.yaml"),
            "canonicalDirectory: ./\nkits: [./network-kit, git+https://example.com/kits.git#ref=v1, './quoted kit']\n",
        )
        val args = runLauncher(launcher(project), project)
        // Items after a comma must not keep their leading space, and quoted flow items
        // must reach --kit exactly as the Kotlin parser reads them.
        assertTrue(args.contains("--kit"))
        assertTrue(args.contains("./network-kit"))
        assertFalse(args.contains(" ./"))
        assertTrue(args.contains("git+https://example.com/kits.git#ref=v1"))
        assertTrue(args.contains("./quoted kit"))
    }

    @Test
    fun initAcpWritesSetupToStderrNotStdout() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        runLauncher(launcher(project), project, "--init", "--acp", serve = true, mergeErrorStream = false)
        assertEquals("", Files.readString(temp.root.resolve("launcher-output.txt").toPath()).trim())
        assertTrue(Files.readString(temp.root.resolve("launcher-error.txt").toPath()).contains("spec already exists"))
    }

    @Test
    fun initWritesPortableSpecInCallerDirectoryForMachineLauncher() {
        val project = directory("new project")
        val scriptDirectory = directory("machine bin")

        val actual = runLauncher(launcher(scriptDirectory), project, "--init")

        assertEquals(createArgs(project), actual)
        assertProvisioningCwd(project)
        assertEquals(
            """
            schemaVersion: 1
            canonicalDirectory: ./
            name: ${SbxCli.sandboxName(project.toString())}
            memory: 4g
            cpus: "2"
            kits: []
            extraMounts: []
            shareHostOpencodeConfig: false
            enableIntellijMcp: true
            protectSandboxFiles: true
            persistSandboxSessions: true
            """.trimIndent() + "\n",
            Files.readString(SbxLaunchSpec.projectSpecPath(project.toString())),
        )
        assertFalse(Files.exists(scriptDirectory.resolve("opencode-sbx.yaml")))
        assertFalse(Files.exists(scriptDirectory.resolve(SbxCli.PROJECT_CONTROL_DIR).resolve("opencode-sbx.yaml")))
    }

    @Test
    fun initDoesNotOverwriteExistingSpec() {
        val project = directory("existing spec project")
        val path = SbxLaunchSpec.projectSpecPath(project.toString())
        Files.createDirectories(path.parent)
        Files.writeString(path, "canonicalDirectory: ./\nmemory: 8g\n")
        runLauncher(launcher(directory("init bin")), project, "--init")
        assertTrue(Files.readString(path).contains("memory: 8g"))
        assertFalse(Files.readString(path).contains("schemaVersion"))
    }

    private fun directory(name: String): Path = temp.newFolder(name).toPath().toRealPath()

    private fun launcher(directory: Path): Path {
        val script = checkNotNull(javaClass.getResourceAsStream("opencode-sbx.sh"))
            .bufferedReader().use { it.readText() }
        // Integration probes deliberately stop at create, before serve and its health poller.
        assertTrue("Windows Git Bash identity must match SbxCli.sandboxIdentityPath", script.contains("identity_path"))
        assertTrue("Mount paths must be argv, not interpolated shell", script.contains("opencode-link"))
        assertTrue("Persist relink must require an attached workspace", script.contains("workspace_has"))
        assertFalse("Provisioning must not start the detached agent TUI", script.contains("run -d"))
        assertFalse("TUI must use sbx exec, not sbx run", script.contains(" \"\$SBX\" run"))
        return Files.writeString(directory.resolve("opencode-sbx.sh"), script)
    }

    private fun launcherOutput(): String = Files.readString(temp.root.resolve("launcher-output.txt").toPath())

    private fun createArgs(project: Path, name: String = SbxCli.sandboxName(project.toString())) = listOf(
        "create", "-q", "--name", name, "--memory", "4g", "--cpus", "2",
        "--publish", "4096/tcp4", "opencode", project.toString(),
    ) + protectCreateArgs(project) + persistCreateArgs(project, name)

    private fun protectCreateArgs(project: Path, kits: List<String> = emptyList()): List<String> {
        return SbxCli.extraMountCreateArgs(
            SbxCli.sandboxProtectMounts(project.toString(), kits),
            project.toString(),
        )
    }

    private fun persistCreateArgs(project: Path, name: String = SbxCli.sandboxName(project.toString())): List<String> {
        val home = Files.createDirectories(temp.root.toPath().resolve("home")).toRealPath()
        val dir = Files.createDirectories(home.resolve(".local/share/opencode-web-panel/sbx").resolve(name))
        return listOf(dir.toRealPath().toString())
    }

    private fun assertProvisioningCwd(project: Path) {
        for (command in listOf("daemon", "ls", "create")) {
            assertEquals(project.toString(), Files.readString(temp.root.toPath().resolve("log/$command.cwd")))
        }
    }

    private fun runLauncher(
        script: Path,
        cwd: Path,
        vararg args: String,
        serve: Boolean = false,
        expectedExit: Int = 37,
        resultCommand: String? = null,
        owned: Boolean = false,
        serverPassword: String? = "launcher-test-only",
        lsJson: String? = null,
        mergeErrorStream: Boolean = true,
        createExit: Int = 37,
    ): List<String> {
        val bin = directory("fake bin")
        val log = directory("log")
        val fakeSbx = Files.writeString(
            bin.resolve("sbx"),
            """
            #!/bin/bash
            set -euo pipefail
            printf '%s\n' "${'$'}1" >> "${'$'}OCWP_TEST_LOG/calls"
            case "${'$'}1" in
              daemon|ls|create|exec|rm)
                printf '%s' "${'$'}(pwd -P)" > "${'$'}OCWP_TEST_LOG/${'$'}1.cwd"
                printf '%s\0' "${'$'}@" > "${'$'}OCWP_TEST_LOG/${'$'}1.args"
                ;;
              ports) ;;
              *) exit 97 ;;
            esac
            case "${'$'}1" in
              daemon) exit 0 ;;
              ls)
                if [[ -n "${'$'}OCWP_TEST_LS" ]]; then
                  printf '%s\n' "${'$'}OCWP_TEST_LS"
                elif [[ "${'$'}OCWP_TEST_SERVE" == true ]]; then
                  printf '{"sandboxes":[{"name":"%s"}]}\n' "${'$'}OCWP_TEST_NAME"
                else
                  printf '[]\n'
                fi ;;
              create) exit "${'$'}{OCWP_TEST_CREATE_EXIT:-37}" ;;
              rm) exit 0 ;;
              exec)
                printf '%s' "${'$'}{XDG_CONFIG_HOME:-}" > "${'$'}OCWP_TEST_LOG/config-home"
                printf '%s' "${'$'}{OPENCODE_AUTH_CONTENT:-}" > "${'$'}OCWP_TEST_LOG/auth-content"
                has_serve=0
                for a in "${'$'}@"; do
                  [[ "${'$'}a" == serve ]] && has_serve=1
                done
                if [[ "${'$'}has_serve" -eq 1 ]]; then
                  for ((i=0; i<500; i++)); do
                    [[ -e "${'$'}OCWP_TEST_LOG/health-checked" ]] && break
                    sleep 0.01
                  done
                fi
                exit 37 ;;
              ports) printf '[{"host_port":49123}]\n' ;;
            esac
            """.trimIndent() + "\n",
        )
        assertTrue(fakeSbx.toFile().setExecutable(true, true))
        // The health poller must never contact a real server in these launcher tests.
        assertTrue(Files.writeString(bin.resolve("curl"), "#!/bin/sh\n: > \"\$OCWP_TEST_LOG/health-checked\"\nexit 0\n").toFile().setExecutable(true, true))
        val output = temp.root.resolve("launcher-output.txt")
        val error = temp.root.resolve("launcher-error.txt")
        val process = ProcessBuilder(listOf("/bin/bash", script.toString()) + args)
            .directory(cwd.toFile())
            .redirectErrorStream(mergeErrorStream)
            .redirectOutput(output)
            .apply {
                if (!mergeErrorStream) redirectError(error)
                // Never inherit credentials, BASH_ENV, sbx overrides, or the user's executable PATH.
                environment().clear()
                environment().putAll(
                    mapOf(
                        "PATH" to "$bin:/usr/bin:/bin",
                        "HOME" to Files.createDirectories(temp.root.toPath().resolve("home")).toRealPath().toString(),
                        "OCWP_CONFIG_DIR" to temp.root.toPath().resolve("config").toString(),
                        "OCWP_SBX" to fakeSbx.toString(),
                        "OCWP_TEST_LOG" to log.toString(),
                        "OCWP_TEST_SERVE" to serve.toString(),
                        "OCWP_TEST_NAME" to SbxCli.sandboxName(script.parent.toString()),
                        "OCWP_TEST_CREATE_EXIT" to createExit.toString(),
                        "OCWP_TEST_LS" to (lsJson ?: if (owned) {
                            """{"sandboxes":[{"name":"${SbxCli.sandboxName(cwd.toString())}","workspaces":["$cwd"]}]}"""
                        } else {
                            ""
                        }),
                    ),
                )
                if (serverPassword != null) environment()["OPENCODE_SERVER_PASSWORD"] = serverPassword
            }.start()
        try {
            assertTrue("Launcher timed out", process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(Files.readString(output.toPath()) + if (error.exists()) Files.readString(error.toPath()) else "", expectedExit, process.exitValue())
        } finally {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        if (expectedExit != 37 && resultCommand == null) return emptyList()
        if (!serve && expectedExit == 37 && createExit != 0) {
            assertEquals(listOf("daemon", "ls", "create"), Files.readAllLines(log.resolve("calls")))
        }
        val command = resultCommand ?: if (serve) "exec" else "create"
        return Files.readString(log.resolve("$command.args")).removeSuffix("\u0000").split('\u0000')
    }
}
