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
                "--kit", "./kit/path",
                "--kit", "./team's-kit",
                "opencode", project.toString(),
            ) + protectCreateArgs(project, listOf("./kit #1", "./kit \"quoted\" #2", "./kit 'quoted' #3", "./kit/path", "./team's-kit")) + persistCreateArgs(project) + listOf(
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
    fun protectedKitPathsResolveAliasesWithoutMakingProjectReadOnly() {
        val project = directory("project")
        val control = Files.createDirectories(project.resolve(SbxCli.PROJECT_CONTROL_DIR))
        val kit = directory("external kit")
        Files.createSymbolicLink(project.resolve("kit alias"), kit)
        Files.writeString(
            control.resolve(SbxLaunchSpec.PROJECT_SPEC_NAME),
            "canonicalDirectory: ./\nkits: [., './kit alias', ./opencode-sbx/../opencode-sbx]\n",
        )
        val args = runLauncher(launcher(control), project)
        assertEquals(listOf("$control:ro", "$kit:ro"), args.filter { it.endsWith(":ro") })
        assertFalse(args.contains("$project:ro"))
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
    fun existingSharedSandboxUsesGlobalConfigWithoutHostCredentials() {
        val project = directory("project")
        val home = Files.createDirectories(temp.root.toPath().resolve("home")).toRealPath()
        Files.createDirectories(home.resolve(".config/opencode"))
        val auth = home.resolve(".local/share/opencode/auth.json")
        Files.createDirectories(auth.parent)
        Files.writeString(auth, """{"fixture":{"type":"api","key":"fixture-only"}}""")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nshareHostOpencodeConfig: true\n")
        val name = SbxCli.sandboxName(project.toString())
        val lsJson = """{"sandboxes":[{"name":"$name","workspaces":["$project","${home.resolve(".config/opencode")}:ro"]}]}"""
        val args = runLauncher(launcher(project), project, serve = true, lsJson = lsJson)
        assertEquals(
            listOf("exec", "-e", "OPENCODE_SERVER_PASSWORD", "-e", "XDG_CONFIG_HOME",
                "-w", project.toString(), SbxCli.sandboxName(project.toString()),
                "opencode",
                "serve", "--hostname", "0.0.0.0", "--port", "4096", "--print-logs"),
            args,
        )
        assertEquals(home.resolve(".config").toString(), Files.readString(temp.root.toPath().resolve("log/config-home")))
        assertEquals("", Files.readString(temp.root.toPath().resolve("log/auth-content")))
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
    fun launcherNeverForwardsHostApiKeysOrOAuthTokens() {
        val project = directory("project")
        val home = Files.createDirectories(temp.root.toPath().resolve("home")).toRealPath()
        Files.createDirectories(home.resolve(".config/opencode"))
        val auth = home.resolve(".local/share/opencode/auth.json")
        Files.createDirectories(auth.parent)
        Files.writeString(auth, """{"openai":{"type":"oauth","access":"host-access","refresh":"host-refresh"},"fixture":{"type":"api","key":"fixture-only"}}""")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nshareHostOpencodeConfig: true\nopenCodeVersion: 2.x\n")
        runLauncher(launcher(project), project, serve = true)
        val forwarded = Files.readString(temp.root.toPath().resolve("log/auth-content"))
        assertEquals("", forwarded)
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
    fun acpKeepsFirstRequestAndReservesStdoutDuringSetup() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nopenCodeVersion: 2.x\n")
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}""" + "\n"
        runLauncher(launcher(project), project, "--acp", serve = true, mergeErrorStream = false, acpInput = request, versionExit = 44)
        val log = temp.root.toPath().resolve("log")
        assertEquals("", Files.readString(log.resolve("setup-input")))
        assertEquals(request, Files.readString(log.resolve("acp-input")))
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""" + "\n", launcherOutput())
        assertTrue(Files.readString(temp.root.toPath().resolve("launcher-error.txt")).contains("Preparing sandbox"))
    }

    @Test
    fun openCodeVersionTwoRequiresGuestBin() {
        val project = directory("project")
        Files.writeString(
            project.resolve("opencode-sbx.yaml"),
            "canonicalDirectory: ./\nopenCodeVersion: 2.x\n",
        )
        val args = runLauncher(launcher(project), project, "--web", serve = true)
        assertEquals(
            listOf(
                "exec", "-e", "OPENCODE_SERVER_PASSWORD",
                "-w", project.toString(), SbxCli.sandboxName(project.toString()),
                "sh", "-c", SbxCli.GUEST_OPENCODE_DISPATCH, "opencode",
                "serve", "--hostname", "0.0.0.0", "--port", "4096", "--print-logs",
            ),
            args,
        )
        assertEquals("", Files.readString(temp.root.toPath().resolve("log/config-content")))
    }

    @Test
    fun v2AuthCommandDoesNotInjectProviderConfiguration() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nopenCodeVersion: 2.x\n")
        val args = runLauncher(launcher(project), project, "--cli", "--oc-args", "auth", "login", "openai", serve = true)
        assertFalse(args.contains("OPENCODE_CONFIG_CONTENT"))
        assertEquals(listOf("auth", "login", "openai"), args.takeLast(3))
        assertEquals("", Files.readString(temp.root.toPath().resolve("log/config-content")))
    }

    @Test
    fun invalidGuestBinaryStopsLauncherWithoutInstallingOrServing() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nopenCodeVersion: 2.x\n")
        runLauncher(launcher(project), project, serve = true, versionExit = 45, expectedExit = 1)
        assertTrue(launcherOutput().contains("validation failed"))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/installed")))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/health-checked")))
    }

    @Test
    fun missingGuestBinaryIsInstalledAndRecheckedBeforeServe() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nopenCodeVersion: 2.x\n")
        val args = runLauncher(launcher(project), project, serve = true, versionExit = 44)
        assertTrue(args.contains("serve"))
        assertEquals(listOf("probe", "install", "probe"), Files.readAllLines(temp.root.toPath().resolve("log/binary-checks")))
        assertEquals(SbxCli.V2_INSTALL_SCRIPT, Files.readString(temp.root.toPath().resolve("log/install-script")))
        assertEquals(SbxCli.GUEST_V2_VERSION_SCRIPT, Files.readString(temp.root.toPath().resolve("log/version-script")))
    }

    @Test
    fun invalidInstalledBinaryStopsLauncherBeforeServe() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nopenCodeVersion: 2.x\n")
        runLauncher(launcher(project), project, serve = true, versionExit = 44, installedVersionExit = 45, expectedExit = 1)
        assertTrue(launcherOutput().contains("installer did not produce a runnable OpenCode 2.x binary"))
        assertEquals(listOf("probe", "install", "probe"), Files.readAllLines(temp.root.toPath().resolve("log/binary-checks")))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/health-checked")))
    }

    @Test
    fun bundledV2ScriptsWorkWithExplicitProjectAndUnrelatedCwd() {
        val project = directory("project")
        val caller = directory("caller")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nopenCodeVersion: 2.x\n")
        val args = runLauncher(
            launcher(directory("machine bin")), caller, project.toString(), serve = true, versionExit = 44,
            lsJson = """{"sandboxes":[{"name":"${SbxCli.sandboxName(project.toString())}","workspaces":["$project"]}]}""",
        )
        assertTrue(args.contains("serve"))
        assertEquals(SbxCli.V2_INSTALL_SCRIPT, Files.readString(temp.root.toPath().resolve("log/install-script")))
        assertEquals(SbxCli.GUEST_V2_VERSION_SCRIPT, Files.readString(temp.root.toPath().resolve("log/version-script")))
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
                "opencode",
                "serve", "--hostname", "0.0.0.0", "--port", "4096", "--print-logs",
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
        val lsJson = """{"sandboxes":[{"name":"${SbxCli.sandboxName(project.toString())}","workspaces":["$project","${home.resolve(".config/opencode")}:ro"]}]}"""
        val args = runLauncher(launcher(project), project, "--cli", serve = true, lsJson = lsJson)
        assertEquals(
            listOf(
                "exec", "-i", "-e", "XDG_CONFIG_HOME",
                "-w", project.toString(), SbxCli.sandboxName(project.toString()),
                "opencode",
            ),
            args,
        )
        assertEquals(home.resolve(".config").toString(), Files.readString(temp.root.toPath().resolve("log/config-home")))
        assertEquals("", Files.readString(temp.root.toPath().resolve("log/auth-content")))
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
        val binary = guestBinaryFixture(project)
        runLauncher(launcher(project), project, "--recreate", serve = true, expectedExit = 1, lsJson = lsJson)
        assertFalse(Files.exists(temp.root.toPath().resolve("log/rm.args")))
        assertEquals("guest-binary", Files.readString(binary))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/exec.args")))
    }

    @Test
    fun failedRecreateKeepsPersistedGuestBinaryAndDoesNotLaunch() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\nopenCodeVersion: 2.x\n")
        val binary = guestBinaryFixture(project)
        runLauncher(launcher(project), project, "--recreate", owned = true, expectedExit = 1, removeExit = 9)
        assertEquals("guest-binary", Files.readString(binary))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/exec.args")))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/create.args")))
    }

    @Test
    fun recreateWithUnknownInventoryKeepsPersistedGuestBinary() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val binary = guestBinaryFixture(project)
        runLauncher(launcher(project), project, "--recreate", expectedExit = 1, lsJson = "daemon unavailable")
        assertEquals("guest-binary", Files.readString(binary))
        assertFalse(Files.exists(temp.root.toPath().resolve("log/create.args")))
    }

    @Test
    fun recreateVerifiedMissingVmDropsOldGuestBinaryBeforeCreate() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        val binary = guestBinaryFixture(project)
        runLauncher(launcher(project), project, "--recreate", expectedExit = 23, createExit = 23, lsJson = """{"sandboxes":[]}""")
        assertFalse(Files.exists(binary))
        assertTrue(Files.exists(temp.root.toPath().resolve("log/create.args")))
    }

    private fun guestBinaryFixture(project: Path): Path {
        val binary = temp.root.toPath().resolve("home/.local/share/opencode-web-panel/sbx-opencode")
            .resolve(SbxCli.sandboxName(project.toString())).resolve("bin/opencode")
        Files.createDirectories(binary.parent)
        return Files.writeString(binary, "guest-binary")
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
            openCodeVersion: 1.x
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
    fun webHealthSkipsSpaHtmlAndProbesCliIdentity() {
        val project = directory("project")
        Files.writeString(project.resolve("opencode-sbx.yaml"), "canonicalDirectory: ./\n")
        runLauncher(launcher(project), project, serve = true, cliHealth = true)
        assertEquals(
            listOf("http://127.0.0.1:49123/global/health", "http://127.0.0.1:49123/api/info"),
            Files.readAllLines(temp.root.toPath().resolve("log/health-paths")),
        )
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

    @Test
    fun invalidSpecFailsClosedLikeThePlugin() {
        for (yaml in listOf(
            "canonicalDirectory: ./\nprotectSandboxFiles: yes\n",
            "canonicalDirectory: ./\nmemory: 8GB\n",
            "canonicalDirectory: ./\n<<<<<<< HEAD\n",
            "memory: 4g\n",
        )) {
            val project = directory("project ${yaml.hashCode()}")
            Files.writeString(project.resolve("opencode-sbx.yaml"), yaml)
            runLauncher(launcher(project), project, expectedExit = 1)
            assertTrue(yaml, launcherOutput().contains("invalid spec"))
            assertTrue("Plugin must reject the same spec", SbxLaunchSpec.parseYaml(yaml) == null)
        }
    }

    @Test
    fun readOnlyMountIsPassedWithTheSbxSuffix() {
        val project = directory("project")
        val data = directory("data")
        Files.writeString(
            project.resolve("opencode-sbx.yaml"),
            "canonicalDirectory: ./\nmemory: 8G\nextraMounts:\n  - host: '$data'\n    readOnly: true\n",
        )
        val args = runLauncher(launcher(project), project)
        assertTrue(args.contains("$data:ro"))
        assertTrue(args.containsAll(listOf("--memory", "8g")))
    }

    @Test
    fun createMatchesThePluginForTheSameSpec() {
        val project = directory("project")
        val home = Files.createDirectories(temp.root.toPath().resolve("home")).toRealPath()
        Files.createDirectories(home.resolve("data"))
        Files.createDirectories(project.resolve("docs"))
        Files.createDirectories(project.resolve("kit"))
        val yaml = """
            canonicalDirectory: ./
            memory: 6G
            cpus: "3"
            hostPort: 49200
            kits:
              - ./kit
              - git+https://example.com/kits.git#ref=v1
            extraMounts:
              - host: ~/data
                sandbox: ~/data
              - host: ./docs
                sandbox: ./docs
              - host: ~/data
                sandbox: data-ro
                readOnly: true
              - host: ~/missing
                sandbox: /home/agent/missing
        """.trimIndent() + "\n"
        Files.writeString(project.resolve("opencode-sbx.yaml"), yaml)
        val spec = SbxLaunchSpec.parseYaml(yaml)!!
        val resolved = SbxCli.resolveExtraMounts(spec.extraMounts, project.toString(), home.toString())
            .filter { Files.exists(Path.of(it.hostPath)) }
        val expected = SbxCli.buildCreateCommand(
            executable = "sbx",
            name = SbxCli.sandboxName(project.toString()),
            workspace = project.toString(),
            memory = spec.memory,
            cpus = spec.cpus,
            hostPort = spec.hostPort,
            kits = SbxCli.parseKitRefs(spec.kits.joinToString("\n"), home.toString()),
            extraWorkspaces = SbxCli.extraMountCreateArgs(SbxCli.sandboxProtectMounts(project.toString(), spec.kits, home.toString()), project.toString()) +
                persistCreateArgs(project) +
                SbxCli.extraMountCreateArgs(resolved, project.toString()),
        ).drop(1)
        assertEquals(expected, runLauncher(launcher(project), project))
        assertEquals(
            listOf(home.resolve("data").toString(), project.resolve("docs").toString(), "/home/agent/data-ro"),
            resolved.map { it.sandboxPath },
        )
    }

    @Test
    fun specNameCannotEscapePluginDataDirectory() {
        val project = directory("project")
        val home = Files.createDirectories(temp.root.toPath().resolve("home")).toRealPath()
        Files.createDirectories(home.resolve(".local/share/opencode-web-panel/sbx-opencode"))
        val sentinel = Files.writeString(Files.createDirectories(home.resolve("sentinel")).resolve("keep"), "keep")
        Files.writeString(
            project.resolve("opencode-sbx.yaml"),
            "canonicalDirectory: '$project'\nname: ../../../../sentinel\nopenCodeVersion: 2.x\n",
        )
        runLauncher(
            launcher(project), project, "--recreate",
            expectedExit = 1, lsJson = """{"sandboxes":[]}""",
        )
        assertTrue("Launcher must not delete outside its data directory", Files.exists(sentinel))
        assertTrue(launcherOutput().contains("invalid sandbox name"))
    }

    private fun directory(name: String): Path = temp.newFolder(name).toPath().toRealPath()

    private fun launcher(directory: Path): Path {
        val script = checkNotNull(javaClass.getResourceAsStream("opencode-sbx.sh"))
            .bufferedReader().use { it.readText() }
        // Integration probes deliberately stop at create, before serve and its health poller.
        assertTrue("Windows Git Bash identity must match SbxCli.sandboxIdentityPath", script.contains("identity_path"))
        assertTrue("In-guest Windows binds are /c/..., not C:/...", script.contains("guest_bind_path"))
        assertTrue("Mount paths must be argv, not interpolated shell", script.contains("opencode-link"))
        assertTrue("Persist relink must require an attached workspace", script.contains("workspace_has"))
        assertTrue("2.x serve must prefer a user-installed binary", script.contains("guest_opencode_dispatch"))
        assertTrue("Guest 2.x lives at \$HOME/.opencode/bin", script.contains("\$HOME/.opencode/bin/opencode"))
        assertTrue("2.x binary persists on the host until Reset", script.contains("sbx-opencode"))
        assertTrue("2.x host copy maps to /home/agent/.opencode", script.contains("/home/agent/.opencode"))
        assertTrue("YAML openCodeVersion selects 1.x or 2.x", script.contains("openCodeVersion"))
        assertTrue("Legacy installOpenCodeV2 still maps to 2.x", script.contains("installOpenCodeV2"))
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
        removeExit: Int = 0,
        versionExit: Int = 0,
        installedVersionExit: Int = 0,
        acpInput: String? = null,
        cliHealth: Boolean = false,
    ): List<String> {
        val bin = Files.createDirectories(temp.root.toPath().resolve("fake bin")).toRealPath()
        val log = Files.createDirectories(temp.root.toPath().resolve("log")).toRealPath()
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
              daemon)
                if [[ "${'$'}OCWP_TEST_ACP" == true ]]; then
                  cat > "${'$'}OCWP_TEST_LOG/setup-input"
                fi
                exit 0 ;;
              ls)
                if [[ -n "${'$'}OCWP_TEST_LS" ]]; then
                  printf '%s\n' "${'$'}OCWP_TEST_LS"
                elif [[ "${'$'}OCWP_TEST_SERVE" == true ]]; then
                  printf '{"sandboxes":[{"name":"%s","workspaces":["%s"]}]}\n' "${'$'}OCWP_TEST_NAME" "${'$'}OCWP_TEST_WORKSPACE"
                else
                  printf '[]\n'
                fi ;;
              create) exit "${'$'}{OCWP_TEST_CREATE_EXIT:-37}" ;;
              rm) exit "${'$'}OCWP_TEST_REMOVE_EXIT" ;;
              exec)
                printf '%s' "${'$'}{XDG_CONFIG_HOME:-}" > "${'$'}OCWP_TEST_LOG/config-home"
                printf '%s' "${'$'}{OPENCODE_AUTH_CONTENT:-}" > "${'$'}OCWP_TEST_LOG/auth-content"
                printf '%s' "${'$'}{OPENCODE_CONFIG_CONTENT:-}" > "${'$'}OCWP_TEST_LOG/config-content"
                for a in "${'$'}@"; do
                  if [[ "${'$'}a" == *'version=$(timeout -k 2 10'* ]]; then
                    printf '%s' "${'$'}a" > "${'$'}OCWP_TEST_LOG/version-script"
                    echo probe >> "${'$'}OCWP_TEST_LOG/binary-checks"
                    if [[ -e "${'$'}OCWP_TEST_LOG/installed" ]]; then exit "${'$'}OCWP_TEST_INSTALLED_VERSION_EXIT"; fi
                    exit "${'$'}OCWP_TEST_VERSION_EXIT"
                  fi
                  if [[ "${'$'}a" == *'https://opencode.ai/v2/install'* ]]; then
                    if [[ "${'$'}OCWP_TEST_ACP" == true ]]; then echo "Preparing sandbox"; fi
                    printf '%s' "${'$'}a" > "${'$'}OCWP_TEST_LOG/install-script"
                    echo install >> "${'$'}OCWP_TEST_LOG/binary-checks"
                    touch "${'$'}OCWP_TEST_LOG/installed"
                    exit 0
                  fi
                done
                has_serve=0
                for a in "${'$'}@"; do
                  [[ "${'$'}a" == serve ]] && has_serve=1
                  if [[ "${'$'}a" == acp && "${'$'}OCWP_TEST_ACP" == true ]]; then
                    cat > "${'$'}OCWP_TEST_LOG/acp-input"
                    printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                  fi
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
        assertTrue(Files.writeString(bin.resolve("curl"), """
            #!/bin/bash
            url="${'$'}{!#}"
            echo "${'$'}url" >> "${'$'}OCWP_TEST_LOG/health-paths"
            if [[ "${'$'}OCWP_TEST_CLI_HEALTH" == true ]]; then
              if [[ "${'$'}url" == */api/info ]]; then
                echo '{"pid":123,"version":"2.0.12"}'
              else
                echo '<!doctype html><html><body>OpenCode</body></html>'
                exit 0
              fi
            else
              echo '{"healthy":true}'
            fi
            : > "${'$'}OCWP_TEST_LOG/health-checked"
        """.trimIndent() + "\n").toFile().setExecutable(true, true))
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
                        "OCWP_TEST_WORKSPACE" to script.parent.toString(),
                        "OCWP_TEST_CREATE_EXIT" to createExit.toString(),
                        "OCWP_TEST_REMOVE_EXIT" to removeExit.toString(),
                        "OCWP_TEST_VERSION_EXIT" to versionExit.toString(),
                        "OCWP_TEST_INSTALLED_VERSION_EXIT" to installedVersionExit.toString(),
                        "OCWP_TEST_ACP" to (acpInput != null).toString(),
                        "OCWP_TEST_CLI_HEALTH" to cliHealth.toString(),
                        "OCWP_TEST_LS" to (lsJson ?: if (owned) {
                            """{"sandboxes":[{"name":"${SbxCli.sandboxName(cwd.toString())}","workspaces":["$cwd"]}]}"""
                        } else {
                            ""
                        }),
                    ),
                )
                if (serverPassword != null) environment()["OPENCODE_SERVER_PASSWORD"] = serverPassword
            }.start()
        process.outputStream.use { input -> acpInput?.let { input.write(it.toByteArray()) } }
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
