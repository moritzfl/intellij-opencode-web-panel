package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SbxCliTest {

    @Test
    fun mountPathsResolveAgainstProjectRatherThanIdeWorkingDirectory() {
        val root = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val home = root.resolve("user")
        val workspace = root.resolve("project")
        assertEquals(
            listOf(
                SbxExtraMount(SbxCli.posixPath(home.resolve("docs #1").toString()), "/home/agent/docs"),
                SbxExtraMount(SbxCli.posixPath(workspace.resolve("local.txt").toString()), "/home/agent/file"),
                SbxExtraMount(SbxCli.posixPath(workspace.resolve("data").toString()), SbxCli.posixPath(workspace.resolve("data").toString())),
            ),
            SbxCli.resolveExtraMounts(
                listOf(SbxExtraMount("~/docs #1", "~/docs"), SbxExtraMount("./local.txt", "/home/agent/file"), SbxExtraMount("./data", "./data")),
                workspace.toString(), home.toString(),
            ),
        )
    }

    @Test
    fun sandboxNameIsStablePrefixPlusSha256Prefix() {
        val name = SbxCli.sandboxName("/tmp/project")
        assertTrue(name.startsWith("ide-ocwp-"))
        assertEquals(SbxCli.sandboxName("/tmp/project"), name)
        assertTrue(SbxCli.isValidSandboxName(name))
        assertFalse(SbxCli.isValidSandboxName("default"))
        assertFalse(SbxCli.isValidSandboxName("x"))
    }

    @Test
    fun sanitizeMemoryAndCpusFallBackToDefaults() {
        assertEquals("4g", SbxCli.sanitizeMemory(null))
        assertEquals("4g", SbxCli.sanitizeMemory("nope"))
        assertNull(SbxCli.parseMemory("8GB"))
        assertNull(SbxCli.parseCpus("0"))
        assertEquals("8g", SbxCli.sanitizeMemory("8G"))
        assertEquals("512m", SbxCli.sanitizeMemory("512m"))
        assertEquals("2", SbxCli.sanitizeCpus(null))
        assertEquals("2", SbxCli.sanitizeCpus("0"))
        assertEquals("4", SbxCli.sanitizeCpus("4"))
    }

    @Test
    fun windowsSandboxIdentityUnifiesMsysAndDriveSpellings() {
        assertEquals("C:/Users/me/project", SbxCli.sandboxIdentityPath("C:\\Users\\me\\project", "Windows 11"))
        assertEquals("C:/Users/me/project", SbxCli.sandboxIdentityPath("/c/Users/me/project", "Windows 11"))
        assertEquals(
            SbxCli.sandboxName("C:/Users/me/project", "Windows 11"),
            SbxCli.sandboxName("/c/Users/me/project", "Windows 11"),
        )
        assertEquals("/tmp/project", SbxCli.sandboxIdentityPath("/tmp/project", "Mac OS X"))
    }

    @Test
    fun diagnoseReportsUnsupportedFromJson() {
        assertTrue(SbxCli.diagnoseReportsUnsupported("""{"supported":false}"""))
        assertTrue(SbxCli.diagnoseReportsUnsupported("""{"virtualization":{"available":false}}"""))
        assertTrue(SbxCli.diagnoseReportsUnsupported("""{"errors":["Apple silicon only"]}"""))
        assertFalse(SbxCli.diagnoseReportsUnsupported("""{"supported":true}"""))
        assertTrue(SbxCli.looksUnsupportedHost("Mac OS X", "x86_64"))
        assertFalse(SbxCli.looksUnsupportedHost("Mac OS X", "aarch64"))
    }

    @Test
    fun parseExtraMountRowsPairsHostAndSandboxPaths() {
        assertEquals(
            listOf("/work/C#Proj" to "/work/C#Proj"),
            SbxCli.parseExtraMountRows("/work/C#Proj # trailing comment\n# whole-line comment"),
        )
        assertEquals(
            listOf("~/docs" to "/home/agent/docs", "/no/such/dir" to "/home/agent/missing"),
            SbxCli.parseExtraMountRows(" ~/docs | /home/agent/docs \n\n /no/such/dir | /home/agent/missing \n ~/docs | /home/agent/docs "),
        )
        assertEquals(
            "~/docs | /home/agent/docs\n/no/such/dir | /home/agent/missing",
            SbxCli.normalizeExtraMountText(" ~/docs | /home/agent/docs \n\n /no/such/dir | /home/agent/missing \n ~/docs | /home/agent/docs "),
        )
        val command = SbxCli.buildLinkExtraMountCommand(
            name = "ide-ocwp-abc",
            mount = SbxExtraMount("/Users/me/docs", "/home/agent/docs"),
        )
        assertEquals(listOf("sbx", "exec", "-w", "/", "ide-ocwp-abc", "sh", "-c"), command.take(7))
        assertEquals("set -- '/Users/me/docs' '/home/agent/docs'\n${SbxCli.extraMountLinkScript(false)}", decodedGuestScript(command))
        assertTrue(SbxCli.needsSandboxLink(SbxExtraMount("/Users/me/docs", "/home/agent/docs")))
        assertFalse(SbxCli.needsSandboxLink(SbxExtraMount("/tmp/docs", "/tmp/docs")))
        assertEquals("C:/Users/me/docs", SbxCli.posixPath("C:\\Users\\me\\docs"))
        assertEquals("./kit.yaml", SbxCli.parseLineList(".\\kit.yaml").single())
        assertEquals(
            listOf("C:/Users/me/docs" to "/home/agent/docs"),
            SbxCli.parseExtraMountRows("C:\\Users\\me\\docs | /home/agent/docs"),
        )
        assertEquals(
            "C:/Users/me/kit.yaml",
            SbxCli.parseKitRefs("C:\\Users\\me\\kit.yaml").single(),
        )
        assertEquals(
            "https://github.com/org/kit.git",
            SbxCli.parseKitRefs("https://github.com/org/kit.git").single(),
        )
    }

    @Test
    fun createCommandAddsKitsBeforeAgent() {
        assertEquals(
            listOf(
                "sbx", "create", "-q", "--name", "ide-ocwp-abc", "--memory", "4g", "--cpus", "2",
                "--publish", "4096/tcp4", "--kit", "./my-kit", "--kit", "docker.io/sbx/playwright-kit:latest",
                "opencode", "/tmp/project",
            ),
            SbxCli.buildCreateCommand(
                name = "ide-ocwp-abc",
                workspace = "/tmp/project",
                kits = listOf("./my-kit", "docker.io/sbx/playwright-kit:latest"),
            ),
        )
        assertEquals(
            listOf("./my-kit", "/Users/me/kits/lint"),
            SbxCli.parseKitRefs("./my-kit\n~/kits/lint\n# comment", home = "/Users/me"),
        )
    }

    @Test
    fun extraMountCreateArgsAppendReadOnlySuffix() {
        val mounts = listOf(
            SbxExtraMount("/Users/me/docs", "/home/agent/docs"),
            SbxExtraMount("/Users/me/.config/opencode", "/Users/me/.config/opencode", readOnly = true),
            SbxExtraMount("/tmp/project", "/tmp/project", readOnly = true),
        )
        assertEquals(
            listOf("/Users/me/docs", "/Users/me/.config/opencode"),
            SbxCli.extraMountHostPaths(mounts, "/tmp/project"),
        )
        assertEquals(
            listOf("/Users/me/docs", "/Users/me/.config/opencode:ro"),
            SbxCli.extraMountCreateArgs(mounts, "/tmp/project"),
        )
        assertEquals("/Users/me/.config/opencode:ro", SbxCli.readOnlyWorkspaceArg("/Users/me/.config/opencode"))
        assertEquals("/Users/me/.config/opencode:ro", SbxCli.readOnlyWorkspaceArg("/Users/me/.config/opencode:ro"))
        assertEquals("C:/Users/me/docs:ro", SbxCli.readOnlyWorkspaceArg("C:/Users/me/docs"))
        assertEquals("C:/Users/me/docs", SbxCli.workspaceHostPath("C:/Users/me/docs:ro"))
        assertEquals("/c/Users/me/docs", SbxCli.guestBindPath("C:/Users/me/docs"))
        assertEquals("/c/Users/me/docs", SbxCli.guestBindPath("C:\\Users\\me\\docs"))
        assertEquals("/Users/me/docs", SbxCli.guestBindPath("/Users/me/docs"))
    }

    @Test
    fun guestToHostPathMappingsCoverExtraMountsPersistAndWindowsBinds() {
        val mappings = SbxCli.guestToHostPathMappings(
            "C:/Users/me/project",
            listOf(SbxExtraMount("/Users/me/docs", "/home/agent/docs")),
            persistHostPath = "/Users/me/.local/share/opencode-web-panel/sbx/ide-ocwp-x",
        )
        assertEquals("/Users/me/docs/guide.md", OpenCodeServerProtocol.applyGuestToHostPrefixes("/home/agent/docs/guide.md", mappings))
        assertEquals(
            "/Users/me/.local/share/opencode-web-panel/sbx/ide-ocwp-x/opencode.db",
            OpenCodeServerProtocol.applyGuestToHostPrefixes(
                "${SbxCli.persistSandboxGuestPath()}/opencode.db",
                mappings,
            ),
        )
        assertEquals("C:/Users/me/project/src/Main.kt", OpenCodeServerProtocol.applyGuestToHostPrefixes("/c/Users/me/project/src/Main.kt", mappings))
        assertTrue(mappings.zipWithNext().all { it.first.first.length >= it.second.first.length })
    }

    @Test
    fun sandboxProtectMountsCoverLocalKitDirectoriesNotFiles() {
        val root = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).resolve("ocwp-protect").toAbsolutePath().normalize()
        val control = root.resolve(SbxCli.PROJECT_CONTROL_DIR)
        val nestedKit = control.resolve(SbxCli.NETWORK_KIT_DIR)
        val kit = root.resolve("extra-kit")
        val directories = setOf(control.toString(), nestedKit.toString(), kit.toString())
        val mounts = SbxCli.sandboxProtectMounts(
            root.toString(),
            listOf("./${SbxCli.PROJECT_CONTROL_DIR}/${SbxCli.NETWORK_KIT_DIR}", "./extra-kit", "./opencode-sbx.yaml", "git+https://github.com/team/kits.git#ref=v1", "docker.io/sbx/playwright-kit:latest"),
            isDirectory = { directories.contains(it.toString()) },
        )
        assertEquals(
            listOf(
                SbxExtraMount(SbxCli.posixPath(control.toString()), SbxCli.posixPath(control.toString()), readOnly = true),
                SbxExtraMount(SbxCli.posixPath(kit.toString()), SbxCli.posixPath(kit.toString()), readOnly = true),
            ),
            mounts,
        )
        assertTrue(SbxCli.isLocalKitRef("./opencode-network-kit"))
        assertTrue(SbxCli.isLocalKitRef("~/kits/net"))
        assertFalse(SbxCli.isLocalKitRef("git+https://github.com/team/kits.git#ref=v1"))
        assertFalse(SbxCli.isLocalKitRef("docker.io/sbx/playwright-kit:latest"))
        assertFalse(SbxCli.isLocalKitRef("organization/kit"))
        assertEquals(
            listOf(
                SbxCli.posixPath(control.toString()) + ":ro",
                SbxCli.posixPath(kit.toString()) + ":ro",
            ),
            SbxCli.extraMountCreateArgs(mounts, root.toString()),
        )
    }

    @Test
    fun staleCreateReasonsWarnWhenExistingVmLacksNewOverlays() {
        val record = SbxSandboxRecord("id", "ide-ocwp-x", "opencode", "/tmp/p", shareHostConfig = true)
        assertEquals(
            listOf("read-only sandbox files", "read-only host OpenCode config"),
            SbxCli.staleCreateReasons(
                record, "4g", "2", protectSandboxFiles = true,
                extraCreateArgs = listOf("/tmp/p/opencode-sbx.yaml:ro", "/tmp/cfg:ro"),
                shareHostConfig = true,
            ),
        )
        val current = record.copy(
            createSnapshot = SbxCli.createSnapshot("4g", "2", true, listOf("/tmp/p/opencode-sbx.yaml:ro")),
        )
        assertTrue(SbxCli.staleCreateReasons(current, "4g", "2", true, listOf("/tmp/p/opencode-sbx.yaml:ro")).isEmpty())
        assertEquals(
            listOf("memory", "CPU count"),
            SbxCli.staleCreateReasons(current, "8g", "4", true, listOf("/tmp/p/opencode-sbx.yaml:ro")),
        )
        assertTrue(
            SbxCli.staleCreateReasons(
                record, "4g", "2", protectSandboxFiles = false, extraCreateArgs = emptyList(),
                shareHostConfig = false, persistSandboxSessions = true,
            ).contains("persisted sandbox sessions"),
        )
    }

    @Test
    fun persistDataHomeIsPluginLocalNotHostOpencodeDb() {
        val root = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).resolve("ocwp-data").toAbsolutePath().normalize()
        val home = SbxCli.sandboxPersistDataHome("ide-ocwp-abc", SbxCli.persistDataDir(userHome = root.toString(), xdgDataHome = null, osName = "Linux", override = null))
        assertTrue(home.contains("opencode-web-panel/sbx/ide-ocwp-abc"))
        assertFalse(home.endsWith("/opencode"))
        assertFalse(home.contains(".local/share/opencode/"))
        val win = SbxCli.persistDataDir(
            userHome = "C:\\Users\\me", xdgDataHome = null, osName = "Windows 11", override = null,
            localAppData = "C:\\Users\\me\\AppData\\Local",
        )
        assertEquals("C:/Users/me/AppData/Local/opencode-web-panel", SbxCli.posixPath(win.toString()))
        val mount = SbxCli.persistSandboxMount("ide-ocwp-abc") { }
        assertEquals("/home/agent/.local/share/opencode", mount.sandboxPath)
        assertTrue(SbxCli.needsSandboxLink(mount))
        val replace = SbxCli.buildLinkExtraMountCommand(name = "ide-ocwp-abc", mount = mount, replaceExistingDirectory = true)
        val replaceScript = decodedGuestScript(replace)!!
        assertTrue(replaceScript.contains("cp -a"))
        assertTrue(replaceScript.contains("rm -rf"))
        assertTrue(replaceScript.startsWith("set -- '${SbxCli.guestBindPath(mount.hostPath)}' '${mount.sandboxPath}'\n"))
        val windowsPersist = SbxExtraMount("C:/Users/me/AppData/Local/opencode-web-panel/sbx/ide-ocwp-abc", SbxCli.persistSandboxGuestPath())
        assertEquals(
            "set -- '/c/Users/me/AppData/Local/opencode-web-panel/sbx/ide-ocwp-abc' '/home/agent/.local/share/opencode'\n${SbxCli.extraMountLinkScript(false)}",
            decodedGuestScript(SbxCli.buildLinkExtraMountCommand(name = "ide-ocwp-abc", mount = windowsPersist)),
        )
        assertTrue(
            decodedGuestScript(SbxCli.buildLinkExtraMountCommand(
                name = "ide-ocwp-abc", mount = SbxExtraMount("C:/Users/O'Brien/docs #1", "/home/agent/O'Brien/docs"),
            ))!!.startsWith("set -- '/c/Users/O'\\''Brien/docs #1' '/home/agent/O'\\''Brien/docs'\n"),
        )
        assertFalse(SbxCli.persistMountIsAttached(emptyList(), mount))
        assertTrue(SbxCli.persistMountIsAttached(listOf(mount.hostPath), mount))
        val v2 = SbxCli.guestOpenCodeMount("ide-ocwp-abc") { }
        assertEquals("/home/agent/.opencode", v2.sandboxPath)
        assertTrue(v2.hostPath.contains("opencode-web-panel/sbx-opencode/ide-ocwp-abc"))
        assertFalse(v2.hostPath.contains("/.opencode/"))
        assertTrue(SbxCli.needsSandboxLink(v2))
        val data = SbxCli.persistDataDir(userHome = root.toString(), xdgDataHome = null, osName = "Linux", override = null)
        val cached = java.nio.file.Path.of(SbxCli.guestOpenCodeDataHome("ide-ocwp-abc", data))
        java.nio.file.Files.createDirectories(cached.resolve("bin"))
        java.nio.file.Files.writeString(cached.resolve("bin/opencode"), "x")
        SbxCli.deleteGuestOpenCode("ide-ocwp-abc", data)
        assertFalse(java.nio.file.Files.exists(cached))
    }

    @Test
    fun createCommandAppendsExtraWorkspaces() {
        assertEquals(
            listOf(
                "sbx", "create", "-q", "--name", "ide-ocwp-abc", "--memory", "4g", "--cpus", "2",
                "--publish", "4096/tcp4", "opencode", "/tmp/project", "/Users/me/.local/share/opencode",
            ),
            SbxCli.buildCreateCommand(
                name = "ide-ocwp-abc",
                workspace = "/tmp/project",
                extraWorkspaces = listOf("/Users/me/.local/share/opencode", "/tmp/project"),
            ),
        )
    }

    @Test
    fun createCommandPublishesTcp4AndPinsMemoryCpus() {
        assertEquals(
            listOf(
                "sbx",
                "create",
                "-q",
                "--name",
                "ide-ocwp-abc",
                "--memory",
                "4g",
                "--cpus",
                "2",
                "--publish",
                "4096/tcp4",
                "opencode",
                "/tmp/project",
            ),
            SbxCli.buildCreateCommand(name = "ide-ocwp-abc", workspace = "/tmp/project"),
        )
        assertEquals("4096/tcp4", SbxCli.publishSpec())
        assertEquals("127.0.0.1:8080:4096/tcp4", SbxCli.publishSpec(8080))
        assertEquals(
            "127.0.0.1:49161:4096/tcp4",
            SbxCli.unpublishSpec(SbxPortMapping("127.0.0.1", 49161, 4096, "tcp4")),
        )
        assertEquals(
            listOf("sbx", "ports", "ide-ocwp-abc", "--publish", "127.0.0.1:4096:4096/tcp4"),
            SbxCli.buildPortsPublishCommand(name = "ide-ocwp-abc", publish = SbxCli.publishSpec(4096)),
        )
        assertEquals(
            listOf("sbx", "ports", "ide-ocwp-abc", "--unpublish", "127.0.0.1:49161:4096/tcp4"),
            SbxCli.buildPortsUnpublishCommand(name = "ide-ocwp-abc", publish = "127.0.0.1:49161:4096/tcp4"),
        )
        assertEquals(
            listOf(
                "sbx", "create", "-q", "--name", "ide-ocwp-abc", "--memory", "4g", "--cpus", "2",
                "--publish", "127.0.0.1:4096:4096/tcp4", "opencode", "/tmp/project",
            ),
            SbxCli.buildCreateCommand(name = "ide-ocwp-abc", workspace = "/tmp/project", hostPort = 4096),
        )
    }

    @Test
    fun ideaMcpOverlayUsesHostDockerInternal() {
        assertEquals(
            """{"mcp":{"idea":{"type":"remote","url":"http://host.docker.internal:64342/sse","enabled":true}}}""",
            SbxCli.ideaMcpConfigContent(64342),
        )
        assertEquals(64342, SbxCli.ideMcpLoopbackPort("IntelliJ MCP server is running at http://127.0.0.1:64342/sse"))
        assertNull(SbxCli.ideMcpLoopbackPort("IntelliJ MCP server is running"))
    }

    @Test
    fun policyAllowIsSandboxScoped() {
        assertEquals(
            listOf("sbx", "policy", "allow", "network", "--sandbox", "ide-ocwp-abc", "localhost:64342"),
            SbxCli.buildPolicyAllowCommand(name = "ide-ocwp-abc", target = "localhost:64342"),
        )
        assertEquals(
            listOf("sbx", "policy", "allow", "network", "--sandbox", "ide-ocwp-abc", "models.opencode.ai:443"),
            SbxCli.buildPolicyAllowCommand(name = "ide-ocwp-abc", target = "models.opencode.ai:443"),
        )
    }

    @Test
    fun policyInitNeverRunsWithoutExplicitArgv() {
        assertEquals(
            listOf("sbx", "policy", "init", "balanced"),
            SbxCli.buildPolicyInitCommand(),
        )
    }

    @Test
    fun execServeUsesBareEnvKeysAndInVmBind() {
        val command = SbxCli.buildExecServeCommand(
            name = "ide-ocwp-abc",
            workspace = "/tmp/project",
            extraEnvKeys = listOf("OPENCODE_CONFIG_CONTENT", "OPENCODE_AUTH_CONTENT"),
            preferGuestV2 = true,
        )
        assertEquals(
            listOf("sbx", "exec", "-e", "OPENCODE_SERVER_PASSWORD", "-e", "OPENCODE_CONFIG_CONTENT",
                "-e", "OPENCODE_AUTH_CONTENT", "-w", "/tmp/project", "ide-ocwp-abc", "sh", "-c"),
            command.take(13),
        )
        assertEquals(
            "set -- 'serve' '--hostname' '0.0.0.0' '--port' '4096' '--print-logs'\n${SbxCli.GUEST_OPENCODE_DISPATCH}",
            decodedGuestScript(command),
        )
        assertFalse(SbxCli.commandContainsBoundEnvAssignment(command))
        assertEquals(
            listOf(
                "sbx",
                "exec",
                "-e",
                "OPENCODE_SERVER_PASSWORD",
                "-w",
                "/tmp/project",
                "ide-ocwp-abc",
                "opencode",
                "serve",
                "--hostname",
                "0.0.0.0",
                "--port",
                "4096",
                "--print-logs",
            ),
            SbxCli.buildExecServeCommand(name = "ide-ocwp-abc", workspace = "/tmp/project"),
        )
        val windows = SbxCli.buildExecServeCommand(name = "ide-ocwp-abc", workspace = "C:\\Source\\WorkspacePosy")
        assertEquals("/c/Source/WorkspacePosy", windows[windows.indexOf("-w") + 1])
        assertFalse(windows.contains("C:/Source/WorkspacePosy"))
    }

    @Test
    fun networkKitTemplateCommentsCommonHosts() {
        val yaml = SbxCli.networkKitTemplateYaml()
        assertTrue(yaml.contains("kind: mixin"))
        assertTrue(yaml.contains("name: opencode-network"))
        assertTrue(yaml.contains("uncomment ALL THREE lines"))
        assertTrue(yaml.contains("# - \"*\""))
        assertTrue(yaml.contains("CIDRs alone do not allow hostname requests"))
        assertTrue(yaml.contains("# - 0.0.0.0/0"))
        assertTrue(yaml.contains("NOT a local-only restriction"))
        assertTrue(yaml.contains("# - localhost"))
        assertTrue(yaml.contains("# - api.openai.com  # OpenAI"))
        assertTrue(yaml.contains("# - registry.npmjs.org  # npm registry"))
        assertFalse(yaml.contains("\n      - api.openai.com"))
        assertFalse(yaml.contains("\n      - \"*\""))
        assertEquals("./opencode-sbx/opencode-network-kit", SbxCli.networkKitRef())
    }

    @Test
    fun execUpgradePrefersGuestOpenCodeBin() {
        val v2 = SbxCli.buildExecUpgradeCommand(name = "ide-ocwp-abc", preferGuestV2 = true)
        assertEquals(listOf("sbx", "exec", "-w", "/", "ide-ocwp-abc", "sh", "-c"), v2.take(7))
        assertEquals("set -- 'upgrade' '--print-logs' '--method' 'curl'\n${SbxCli.GUEST_OPENCODE_DISPATCH}", decodedGuestScript(v2))
        assertEquals(
            listOf("sbx", "exec", "-w", "/", "ide-ocwp-abc", "opencode", "upgrade", "--print-logs"),
            SbxCli.buildExecUpgradeCommand(name = "ide-ocwp-abc"),
        )
    }

    @Test
    fun execInstallV2DownloadsOfficialInstaller() {
        val command = SbxCli.buildExecInstallV2Command(name = "ide-ocwp-abc")
        assertEquals(listOf("sbx", "exec", "-w", "/", "ide-ocwp-abc", "sh", "-c"), command.take(7))
        assertEquals(SbxCli.V2_INSTALL_SCRIPT, decodedGuestScript(command))
        assertTrue(SbxCli.V2_INSTALL_SCRIPT.contains(SbxCli.V2_INSTALL_URL))
        assertTrue(SbxCli.V2_INSTALL_SCRIPT.contains("--no-modify-path"))
        assertTrue(SbxCli.V2_INSTALL_SCRIPT.contains("--version"))
        assertTrue(SbxCli.V2_INSTALL_SCRIPT.contains("registry.npmjs.org"))
        assertTrue(SbxCli.GUEST_OPENCODE_DISPATCH.contains("\$HOME/.opencode/bin/opencode"))
        assertFalse(SbxCli.commandContainsBoundEnvAssignment(command))
        assertEquals(SbxCli.GUEST_V2_VERSION_SCRIPT, decodedGuestScript(SbxCli.buildExecGuestV2VersionCommand(name = "ide-ocwp-abc")))
    }

    @Test
    fun failedPinnedInstallCannotReportSuccessFromAnOldBinary() {
        org.junit.Assume.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")))
        val root = java.nio.file.Files.createTempDirectory("ocwp-install-failure")
        try {
            val bin = java.nio.file.Files.createDirectories(root.resolve("bin"))
            fun executable(path: java.nio.file.Path, script: String) {
                java.nio.file.Files.writeString(path, "#!/bin/sh\n$script\n")
                assertTrue(path.toFile().setExecutable(true))
            }
            executable(bin.resolve("bash"), "exit 23")
            executable(bin.resolve("sleep"), "exit 0")
            executable(bin.resolve("curl"), """case "$*" in *registry.npmjs.org*) printf '%s' '{"version":"2.0.11"}' ;; esac""")
            val installed = java.nio.file.Files.createDirectories(root.resolve(".opencode/bin")).resolve("opencode")
            executable(installed, "exit 0")
            val process = ProcessBuilder("/bin/sh", "-c", SbxCli.V2_INSTALL_SCRIPT).apply {
                environment().clear()
                environment()["HOME"] = root.toString()
                environment()["PATH"] = "$bin:/usr/bin:/bin"
            }.start()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("Failed installer must not be masked by an existing executable", 23, process.exitValue())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun remotePkillEscalatesToKillAndMatchesWrappedArgv() {
        val command = SbxCli.buildRemotePkillCommand(name = "ide-ocwp-abc")
        assertEquals("sbx", command[0])
        assertEquals("exec", command[1])
        assertEquals("-w", command[2])
        assertEquals("/", command[3])
        assertEquals("ide-ocwp-abc", command[4])
        assertEquals("sh", command[5])
        assertEquals("-c", command[6])
        val script = decodedGuestScript(command)!!
        assertTrue(script.contains("pkill -TERM -f '[o]pencode serve --hostname 0.0.0.0 --port 4096 --print-logs'"))
        assertTrue(script.contains("pkill -KILL -f '[o]pencode serve --hostname 0.0.0.0 --port 4096 --print-logs'"))
        assertTrue(script.contains("pkill -0 -f"))
        assertFalse(script.contains("^opencode serve"))
        assertFalse(SbxCli.commandContainsBoundEnvAssignment(command))
    }

    @Test
    fun parseTemplateLsKeepsOfficialOpencodeRefs() {
        val images = SbxCli.parseTemplateLsJson(resource("sbx-template-ls.json"))
        assertEquals(2, images.size)
        assertEquals(
            listOf("docker.io/docker/sandbox-templates:opencode-docker"),
            SbxCli.officialOpencodeTemplateRefs(images),
        )
        assertEquals(
            listOf("sbx", "template", "ls", "--json"),
            SbxCli.buildTemplateLsCommand(),
        )
        assertEquals(
            listOf("sbx", "template", "rm", SbxCli.DEFAULT_OPENCODE_TEMPLATE),
            SbxCli.buildTemplateRmCommand(ref = SbxCli.DEFAULT_OPENCODE_TEMPLATE),
        )
    }

    @Test
    fun parseLsJsonFixtureFromSpike() {
        val entries = SbxCli.parseLsJson(resource("sbx-ls-running.json"))
        assertEquals(1, entries.size)
        val sandbox = entries.single()
        assertEquals("ide-ocwp-spike", sandbox.name)
        assertEquals("7ab26e93-c28f-4310-a567-0c059b22a4a3", sandbox.id)
        assertEquals("opencode", sandbox.agent)
        assertEquals("running", sandbox.status)
        assertEquals(
            listOf("/var/folders/9z/vghfln3n0s5c12b80dfmwmww0000gn/T/opencode/sbx-ocwp-spike-project"),
            sandbox.workspaces,
        )
        assertEquals(49161, SbxCli.publishedHostPort(sandbox.ports))
    }

    @Test
    fun malformedSandboxInventoryIsNotAnEmptyInventory() {
        assertNull(SbxCli.parseLsJsonOrNull("ERROR: daemon unavailable"))
        assertNull(SbxCli.parseLsJsonOrNull("{}"))
        assertEquals(
            "Entries without an id are skipped, not a reason to distrust the whole inventory",
            emptyList<SbxSandboxListEntry>(),
            SbxCli.parseLsJsonOrNull("""{"sandboxes":[{"name":"missing-id"}]}"""),
        )
        assertEquals(emptyList<SbxSandboxListEntry>(), SbxCli.parseLsJsonOrNull("""{"sandboxes":[]}"""))
    }

    @Test
    fun parsePortsJsonFixtureFromSpike() {
        val ports = SbxCli.parsePortsJson(resource("sbx-ports-running.json"))
        assertEquals(1, ports.size)
        assertEquals("127.0.0.1", ports.single().hostIp)
        assertEquals(49159, ports.single().hostPort)
        assertEquals(4096, ports.single().sandboxPort)
        assertEquals("tcp4", ports.single().protocol)
        assertEquals(49159, SbxCli.publishedHostPort(ports))
        assertEquals(listOf(49159), SbxCli.publishedHostPorts(ports))
        assertTrue(SbxCli.parsePortsJson(resource("sbx-ports-stopped.json")).isEmpty())
    }

    @Test
    fun publishedHostPortsTriesEveryLoopbackMapping() {
        val ports = listOf(
            SbxPortMapping("127.0.0.1", 49154, 4096, "tcp4"),
            SbxPortMapping("0.0.0.0", 49155, 4096, "tcp4"),
            SbxPortMapping("127.0.0.1", 49156, 4096, "tcp4"),
            SbxPortMapping("127.0.0.1", 8080, 8080, "tcp4"),
        )
        assertEquals(listOf(49154, 49156), SbxCli.publishedHostPorts(ports))
        assertEquals(listOf(49156), SbxCli.publishedHostPorts(ports, desiredHostPort = 49156))
        assertEquals(emptyList<Int>(), SbxCli.publishedHostPorts(ports, desiredHostPort = 4096))
    }

    @Test
    fun policyInitializationRequiresAGlobalRule() {
        assertFalse(SbxCli.policyIsInitialized("""{"rules":[]}"""))
        assertFalse(SbxCli.policyIsInitialized("""{"rules":[{"scope":"sandbox","applies_to":"sandbox:x"}]}"""))
        assertFalse(SbxCli.policyIsInitialized("not json"))
        assertTrue(SbxCli.policyIsInitialized("""{"rules":[{"id":"default-ai-services","scope":"global","applies_to":"all"}]}"""))
    }

    @Test
    fun conflictingSandboxMatchesNameOrWorkspace() {
        val entries = SbxCli.parseLsJson(resource("sbx-ls-running.json"))
        val workspace = entries.single().workspaces.single()
        assertEquals(entries.single(), SbxCli.conflictingSandbox(entries, "ide-ocwp-spike", "/tmp/other"))
        assertEquals(entries.single(), SbxCli.conflictingSandbox(entries, "other-name", workspace))
        assertNull(SbxCli.conflictingSandbox(entries, "other-name", "/tmp/other"))
        assertNull(
            "A VM recorded for another project may mount this directory as an extra workspace",
            SbxCli.conflictingSandbox(entries, "other-name", workspace) { true },
        )
    }

    @Test
    fun recreateReasonsCoverReadOnlyChangesButKeepLegacySharedConfigBinds() {
        val record = SbxSandboxRecord("id", "ide-ocwp-x", "opencode", "/p", shareHostConfig = true)
        val config = SbxExtraMount("/home/u/.config/opencode", "/home/u/.config/opencode", readOnly = true)
        val data = SbxExtraMount("/data", "/data", readOnly = true)
        fun reasons(listed: List<String>) = SbxCli.recreateReasons(
            record, listed, "/p", "", true, listOf(config, data), emptyList(), config.hostPath,
        )
        assertEquals(emptyList<String>(), reasons(listOf("/p", "/home/u/.config/opencode", "/data:ro")))
        assertEquals(listOf("mount /data should be read-only"), reasons(listOf("/p", "/home/u/.config/opencode:ro", "/data")))
        assertEquals(emptyList<String>(), SbxCli.recreateReasons(
            record.copy(adopted = true), listOf("/p"), "/p", "./x", false, listOf(data), emptyList(), null,
        ))
    }

    @Test
    fun ownedSandboxMatchesByIdEvenWhenProjectIsNotFirstWorkspace() {
        val project = "/tmp/project"
        val persist = "/tmp/persist"
        val protect = "/tmp/project/opencode-sbx"
        val entry = SbxSandboxListEntry(
            name = "ide-ocwp-spike",
            id = "7ab26e93-c28f-4310-a567-0c059b22a4a3",
            agent = "opencode",
            status = "running",
            ports = emptyList(),
            workspaces = listOf(protect, persist, project),
        )
        val record = SbxSandboxRecord(
            sandboxId = entry.id,
            name = "stale-name",
            agent = "claude",
            workspace = project,
        )
        assertEquals(entry, SbxCli.findOwnedSandbox(listOf(entry), record))
        assertNull(
            SbxCli.findOwnedSandbox(
                listOf(entry),
                record.copy(sandboxId = "00000000-0000-0000-0000-000000000000"),
            ),
        )
        val fixture = SbxCli.parseLsJson(resource("sbx-ls-running.json"))
        assertEquals(
            fixture.single(),
            SbxCli.findOwnedSandbox(
                fixture,
                SbxSandboxRecord(fixture.single().id, "other", "opencode", "/tmp/other"),
            ),
        )
    }

    private fun resource(name: String): String {
        return javaClass.getResource("/de/moritzf/opencodewebpanel/server/$name")!!.readText()
    }
}

internal fun decodedGuestScript(command: List<String>): String? {
    val index = command.indexOf("-c")
    val wrapper = command.getOrNull(index + 1) ?: return null
    val prefix = "printf %s "
    val suffix = " | base64 -d | sh"
    if (!wrapper.startsWith(prefix) || !wrapper.endsWith(suffix)) return null
    val payload = wrapper.substring(prefix.length, wrapper.length - suffix.length)
    return String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
}
