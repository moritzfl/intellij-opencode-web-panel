package de.moritzf.opencodewebpanel.server

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxCliTest {

    @Test
    fun mountPathsResolveAgainstProjectRatherThanIdeWorkingDirectory() {
        val root =
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val home = root.resolve("user")
        val workspace = root.resolve("project")
        assertEquals(
            listOf(
                SbxExtraMount(
                    SbxCli.posixPath(home.resolve("docs #1").toString()),
                    "/home/agent/docs",
                ),
                SbxExtraMount(
                    SbxCli.posixPath(workspace.resolve("local.txt").toString()),
                    "/home/agent/file",
                ),
                SbxExtraMount(
                    SbxCli.posixPath(workspace.resolve("data").toString()),
                    SbxCli.posixPath(workspace.resolve("data").toString()),
                ),
            ),
            SbxCli.resolveExtraMounts(
                listOf(
                    SbxExtraMount("~/docs #1", "~/docs"),
                    SbxExtraMount("./local.txt", "/home/agent/file"),
                    SbxExtraMount("./data", "./data"),
                ),
                workspace.toString(),
                home.toString(),
            ),
        )
    }

    @Test
    fun hostMountVariableUsesEachDevelopersEnvironmentOrHomeFallback() {
        val root =
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val home = root.resolve("user")
        val custom = root.resolve("Gradle home ä")
        val mount =
            SbxExtraMount(
                "\${GRADLE_USER_HOME:-~/.gradle}",
                "/home/agent/.gradle-host",
                readOnly = true,
            )
        for (environment in
            listOf(
                emptyMap(),
                mapOf("GRADLE_USER_HOME" to ""),
                mapOf("GRADLE_USER_HOME" to custom.toString()),
            )) {
            val expected =
                if (environment["GRADLE_USER_HOME"].isNullOrEmpty()) home.resolve(".gradle")
                else custom
            assertEquals(
                listOf(mount.copy(hostPath = SbxCli.posixPath(expected.toString()))),
                SbxCli.resolveExtraMounts(
                    listOf(mount),
                    root.toString(),
                    home.toString(),
                    environment,
                ),
            )
        }
    }

    @Test
    fun hostMountVariablesAreLiteralAndSupportSubdirectories() {
        assertEquals(
            "/data/Gradle home/config",
            SbxCli.expandHostMountPath(
                "\${TOOLS}/config",
                "/home/user",
                mapOf("TOOLS" to "/data/Gradle home"),
            ),
        )
        assertEquals(
            "/data/\$(touch untouched)/config",
            SbxCli.expandHostMountPath(
                "\${TOOLS}/config",
                "/home/user",
                mapOf("TOOLS" to "/data/\$(touch untouched)"),
            ),
        )
        assertEquals(
            "/data/\${OTHER}",
            SbxCli.expandHostMountPath(
                "\${TOOLS}",
                "/home/user",
                mapOf("TOOLS" to "/data/\${OTHER}", "OTHER" to "not-expanded"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SbxCli.expandHostMountPath("\${MISSING}", "/home/user", emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SbxCli.expandHostMountPath("\${TOOLS:=unsupported}", "/home/user", emptyMap())
        }
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
        assertEquals(
            "C:/Users/me/project",
            SbxCli.sandboxIdentityPath("C:\\Users\\me\\project", "Windows 11"),
        )
        assertEquals(
            "C:/Users/me/project",
            SbxCli.sandboxIdentityPath("/c/Users/me/project", "Windows 11"),
        )
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
        assertTrue(
            SbxCli.diagnoseReportsUnsupported(
                """{"checks":[{"name":"Virtualization","status":"fail","message":"not supported"}]}""",
            ),
        )
        assertFalse(
            SbxCli.diagnoseReportsUnsupported(
                """{"checks":[{"name":"Authentication","status":"fail"},{"name":"Virtualization","status":"pass"}]}""",
            ),
        )
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
            SbxCli.parseExtraMountRows(
                " ~/docs | /home/agent/docs \n\n /no/such/dir | /home/agent/missing \n ~/docs | /home/agent/docs ",
            ),
        )
        assertEquals(
            "~/docs | /home/agent/docs\n/no/such/dir | /home/agent/missing",
            SbxCli.normalizeExtraMountText(
                " ~/docs | /home/agent/docs \n\n /no/such/dir | /home/agent/missing \n ~/docs | /home/agent/docs ",
            ),
        )
        val command =
            SbxCli.buildLinkExtraMountCommand(
                name = "ide-ocwp-abc",
                mount = SbxExtraMount("/Users/me/docs", "/home/agent/docs"),
            )
        assertEquals(listOf("sbx", "exec", "-w", "/", "ide-ocwp-abc", "sh", "-c"), command.take(7))
        assertEquals(
            "set -- '/Users/me/docs' '/home/agent/docs'\n${SbxCli.extraMountLinkScript(false)}",
            decodedGuestScript(command),
        )
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
                "sbx",
                "create",
                "--name",
                "ide-ocwp-abc",
                "--memory",
                "4g",
                "--cpus",
                "2",
                "--publish",
                "4096/tcp4",
                "--kit",
                "./my-kit",
                "--kit",
                "docker.io/sbx/playwright-kit:latest",
                "opencode",
                "/tmp/project",
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
        val mounts =
            listOf(
                SbxExtraMount("/Users/me/docs", "/home/agent/docs"),
                SbxExtraMount(
                    "/Users/me/.config/opencode",
                    "/Users/me/.config/opencode",
                    readOnly = true,
                ),
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
        assertEquals(
            "/Users/me/.config/opencode:ro",
            SbxCli.readOnlyWorkspaceArg("/Users/me/.config/opencode"),
        )
        assertEquals(
            "/Users/me/.config/opencode:ro",
            SbxCli.readOnlyWorkspaceArg("/Users/me/.config/opencode:ro"),
        )
        assertEquals("C:/Users/me/docs:ro", SbxCli.readOnlyWorkspaceArg("C:/Users/me/docs"))
        assertEquals("C:/Users/me/docs", SbxCli.workspaceHostPath("C:/Users/me/docs:ro"))
        assertEquals("/c/Users/me/docs", SbxCli.guestBindPath("C:/Users/me/docs"))
        assertEquals("/c/Users/me/docs", SbxCli.guestBindPath("C:\\Users\\me\\docs"))
        assertEquals("/Users/me/docs", SbxCli.guestBindPath("/Users/me/docs"))
    }

    @Test
    fun guestToHostPathMappingsCoverExtraMountsPersistAndWindowsBinds() {
        val mappings =
            SbxCli.guestToHostPathMappings(
                "C:/Users/me/project",
                listOf(SbxExtraMount("/Users/me/docs", "/home/agent/docs")),
                persistHostPath = "/Users/me/.local/share/opencode-web-panel/sbx/ide-ocwp-x",
            )
        assertEquals(
            "/Users/me/docs/guide.md",
            OpenCodeServerProtocol.applyGuestToHostPrefixes("/home/agent/docs/guide.md", mappings),
        )
        assertEquals(
            "/Users/me/.local/share/opencode-web-panel/sbx/ide-ocwp-x/opencode.db",
            OpenCodeServerProtocol.applyGuestToHostPrefixes(
                "${SbxCli.persistSandboxGuestPath()}/opencode.db",
                mappings,
            ),
        )
        assertEquals(
            "C:/Users/me/project/src/Main.kt",
            OpenCodeServerProtocol.applyGuestToHostPrefixes(
                "/c/Users/me/project/src/Main.kt",
                mappings,
            ),
        )
        assertTrue(mappings.zipWithNext().all { it.first.first.length >= it.second.first.length })
    }

    @Test
    fun sandboxServerDirectoryUsesGuestSpellingButNativeKeepsHostSpelling() {
        val host = "C:/work/sample-repo/app"
        assertEquals(
            "/c/work/sample-repo/app",
            OpenCodeHostPaths.serverDirectory("sbx:example", host),
        )
        assertEquals(host, OpenCodeHostPaths.serverDirectory(OpenCodeServerBackend.NATIVE_ID, host))
    }

    @Test
    fun sandboxProtectMountsCoverLocalKitDirectoriesNotFiles() {
        val root =
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("ocwp-protect")
                .toAbsolutePath()
                .normalize()
        val control = root.resolve(SbxCli.PROJECT_CONTROL_DIR)
        val nestedKit = control.resolve(SbxCli.NETWORK_KIT_DIR)
        val kit = root.resolve("extra-kit")
        val directories = setOf(control.toString(), nestedKit.toString(), kit.toString())
        val mounts =
            SbxCli.sandboxProtectMounts(
                root.toString(),
                listOf(
                    "./${SbxCli.PROJECT_CONTROL_DIR}/${SbxCli.NETWORK_KIT_DIR}",
                    "./extra-kit",
                    "./opencode-sbx.yaml",
                    "git+https://github.com/team/kits.git#ref=v1",
                    "docker.io/sbx/playwright-kit:latest",
                ),
                isDirectory = { directories.contains(it.toString()) },
            )
        assertEquals(
            listOf(
                SbxExtraMount(
                    SbxCli.posixPath(control.toString()),
                    SbxCli.posixPath(control.toString()),
                    readOnly = true,
                ),
                SbxExtraMount(
                    SbxCli.posixPath(kit.toString()),
                    SbxCli.posixPath(kit.toString()),
                    readOnly = true,
                ),
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
        val record =
            SbxSandboxRecord("id", "ide-ocwp-x", "opencode", "/tmp/p", shareHostConfig = true)
        assertEquals(
            listOf("read-only sandbox files", "read-only host OpenCode config"),
            SbxCli.staleCreateReasons(
                record,
                "4g",
                "2",
                protectSandboxFiles = true,
                extraCreateArgs = listOf("/tmp/p/opencode-sbx.yaml:ro", "/tmp/cfg:ro"),
                shareHostConfig = true,
            ),
        )
        val current =
            record.copy(
                createSnapshot =
                    SbxCli.createSnapshot("4g", "2", true, listOf("/tmp/p/opencode-sbx.yaml:ro")),
            )
        assertTrue(
            SbxCli.staleCreateReasons(
                    current,
                    "4g",
                    "2",
                    true,
                    listOf("/tmp/p/opencode-sbx.yaml:ro"),
                )
                .isEmpty(),
        )
        assertEquals(
            listOf("memory", "CPU count"),
            SbxCli.staleCreateReasons(
                current,
                "8g",
                "4",
                true,
                listOf("/tmp/p/opencode-sbx.yaml:ro"),
            ),
        )
        assertTrue(
            SbxCli.staleCreateReasons(
                    record,
                    "4g",
                    "2",
                    protectSandboxFiles = false,
                    extraCreateArgs = emptyList(),
                    shareHostConfig = false,
                    persistSandboxSessions = true,
                )
                .contains("persisted sandbox sessions"),
        )
    }

    @Test
    fun persistDataHomeIsPluginLocalNotHostOpencodeDb() {
        val root =
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("ocwp-data")
                .toAbsolutePath()
                .normalize()
        val home =
            SbxCli.sandboxPersistDataHome(
                "ide-ocwp-abc",
                SbxCli.persistDataDir(
                    userHome = root.toString(),
                    xdgDataHome = null,
                    osName = "Linux",
                    override = null,
                ),
            )
        assertTrue(home.contains("opencode-web-panel/sbx/ide-ocwp-abc"))
        assertFalse(home.endsWith("/opencode"))
        assertFalse(home.contains(".local/share/opencode/"))
        val win =
            SbxCli.persistDataDir(
                userHome = "C:\\Users\\me",
                xdgDataHome = null,
                osName = "Windows 11",
                override = null,
                localAppData = "C:\\Users\\me\\AppData\\Local",
            )
        assertEquals(
            "C:/Users/me/AppData/Local/opencode-web-panel",
            SbxCli.posixPath(win.toString()),
        )
        val mount = SbxCli.persistSandboxMount("ide-ocwp-abc") {}
        assertEquals("/home/agent/.local/share/opencode", mount.sandboxPath)
        assertTrue(SbxCli.needsSandboxLink(mount))
        val replace =
            SbxCli.buildLinkExtraMountCommand(
                name = "ide-ocwp-abc",
                mount = mount,
                replaceExistingDirectory = true,
            )
        val replaceScript = decodedGuestScript(replace)!!
        assertTrue(replaceScript.contains("cp -a"))
        assertTrue(replaceScript.contains("rm -rf"))
        assertTrue(
            replaceScript.startsWith(
                "set -- '${SbxCli.guestBindPath(mount.hostPath)}' '${mount.sandboxPath}'\n",
            ),
        )
        val windowsPersist =
            SbxExtraMount(
                "C:/Users/me/AppData/Local/opencode-web-panel/sbx/ide-ocwp-abc",
                SbxCli.persistSandboxGuestPath(),
            )
        assertEquals(
            "set -- '/c/Users/me/AppData/Local/opencode-web-panel/sbx/ide-ocwp-abc' '/home/agent/.local/share/opencode'\n${SbxCli.extraMountLinkScript(false)}",
            decodedGuestScript(
                SbxCli.buildLinkExtraMountCommand(name = "ide-ocwp-abc", mount = windowsPersist),
            ),
        )
        assertTrue(
            decodedGuestScript(
                    SbxCli.buildLinkExtraMountCommand(
                        name = "ide-ocwp-abc",
                        mount =
                            SbxExtraMount("C:/Users/O'Brien/docs #1", "/home/agent/O'Brien/docs"),
                    ),
                )!!
                .startsWith(
                    "set -- '/c/Users/O'\\''Brien/docs #1' '/home/agent/O'\\''Brien/docs'\n",
                ),
        )
        assertFalse(SbxCli.persistMountIsAttached(emptyList(), mount))
        assertTrue(SbxCli.persistMountIsAttached(listOf(mount.hostPath), mount))
        val data =
            SbxCli.persistDataDir(
                userHome = root.toString(),
                xdgDataHome = null,
                osName = "Linux",
                override = null,
            )
        val legacy = java.nio.file.Path.of(SbxCli.legacyGuestOpenCodeDataHome("ide-ocwp-abc", data))
        assertTrue(
            SbxCli.posixPath(legacy.toString())
                .endsWith("opencode-web-panel/sbx-opencode/ide-ocwp-abc"),
        )
        java.nio.file.Files.createDirectories(legacy.resolve("bin"))
        java.nio.file.Files.writeString(legacy.resolve("bin/opencode"), "x")
        SbxCli.deleteLegacyGuestOpenCode("ide-ocwp-abc", data)
        assertFalse(java.nio.file.Files.exists(legacy))
    }

    @Test
    fun createCommandAppendsExtraWorkspaces() {
        assertEquals(
            listOf(
                "sbx",
                "create",
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
                "/Users/me/.local/share/opencode",
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
            SbxCli.buildPortsPublishCommand(
                name = "ide-ocwp-abc",
                publish = SbxCli.publishSpec(4096),
            ),
        )
        assertEquals(
            listOf("sbx", "ports", "ide-ocwp-abc", "--unpublish", "127.0.0.1:49161:4096/tcp4"),
            SbxCli.buildPortsUnpublishCommand(
                name = "ide-ocwp-abc",
                publish = "127.0.0.1:49161:4096/tcp4",
            ),
        )
        assertEquals(
            listOf(
                "sbx",
                "create",
                "--name",
                "ide-ocwp-abc",
                "--memory",
                "4g",
                "--cpus",
                "2",
                "--publish",
                "127.0.0.1:4096:4096/tcp4",
                "opencode",
                "/tmp/project",
            ),
            SbxCli.buildCreateCommand(
                name = "ide-ocwp-abc",
                workspace = "/tmp/project",
                hostPort = 4096,
            ),
        )
    }

    @Test
    fun ideaMcpOverlayUsesHostDockerInternal() {
        assertEquals(
            """{"mcp":{"idea":{"type":"remote","url":"http://host.docker.internal:64342/sse","enabled":true}}}""",
            SbxCli.ideaMcpConfigContent(64342),
        )
        assertEquals(
            64342,
            SbxCli.ideMcpLoopbackPort(
                "IntelliJ MCP server is running at http://127.0.0.1:64342/sse",
            ),
        )
        assertNull(SbxCli.ideMcpLoopbackPort("IntelliJ MCP server is running"))
    }

    @Test
    fun policyAllowIsSandboxScoped() {
        assertEquals(
            listOf(
                "sbx",
                "policy",
                "allow",
                "network",
                "--sandbox",
                "ide-ocwp-abc",
                "localhost:64342",
            ),
            SbxCli.buildPolicyAllowCommand(name = "ide-ocwp-abc", target = "localhost:64342"),
        )
        assertEquals(
            listOf(
                "sbx",
                "policy",
                "allow",
                "network",
                "--sandbox",
                "ide-ocwp-abc",
                "models.opencode.ai:443",
            ),
            SbxCli.buildPolicyAllowCommand(
                name = "ide-ocwp-abc",
                target = "models.opencode.ai:443",
            ),
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
    fun daemonStartsDetachedAndOnlyFallsBackForAnUnsupportedFlag() {
        assertEquals(listOf("sbx", "daemon", "start", "--detach"), SbxCli.buildDaemonStartCommand())
        assertEquals(listOf("sbx", "daemon", "start"), SbxCli.buildLegacyDaemonStartCommand())
        assertTrue(SbxCli.daemonDetachUnsupported("error: unknown flag: --detach"))
        assertTrue(SbxCli.daemonDetachUnsupported("flag provided but not defined: --detach"))
        assertFalse(SbxCli.daemonDetachUnsupported("daemon failed to start: container missing"))
        assertFalse(SbxCli.daemonDetachUnsupported("command timed out after 60000ms"))
    }

    @Test
    fun execServeUsesBareEnvKeysAndInVmBind() {
        val command =
            SbxCli.buildExecServeCommand(
                name = "ide-ocwp-abc",
                workspace = "/tmp/project",
                extraEnvKeys = listOf(SbxCli.CONFIG_OVERLAY_ENV, "XDG_CONFIG_HOME"),
            )
        assertEquals(
            listOf(
                "sbx",
                "exec",
                "-e",
                "OPENCODE_SERVER_PASSWORD",
                "-e",
                "OCWP_CONFIG_OVERLAY",
                "-e",
                "XDG_CONFIG_HOME",
                "-w",
                "/tmp/project",
                "ide-ocwp-abc",
                "sh",
                "-c",
            ),
            command.take(13),
        )
        val script = decodedGuestScript(command)!!
        assertTrue(
            script.startsWith(
                "set -- 'serve' '--hostname' '0.0.0.0' '--port' '4096' '--print-logs'\n",
            ),
        )
        assertTrue("Docker's kit binary on PATH", script.endsWith("exec opencode \"\$@\""))
        assertFalse(script.contains(".opencode/bin"))
        assertFalse(SbxCli.commandContainsBoundEnvAssignment(command))
        assertTrue(
            SbxCli.commandContainsBoundEnvAssignment(
                command + "${SbxCli.CONFIG_OVERLAY_ENV}={}",
            ),
        )
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
        val windows =
            SbxCli.buildExecServeCommand(
                name = "ide-ocwp-abc",
                workspace = "C:\\work\\sample-repo\\app",
            )
        assertEquals("/c/work/sample-repo/app", windows[windows.indexOf("-w") + 1])
        assertFalse(windows.contains("C:/work/sample-repo/app"))
    }

    @Test
    fun serveOverlayKeepsTheKitsInlineConfig() {
        org.junit.Assume.assumeTrue(
            java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")),
        )
        val root = java.nio.file.Files.createTempDirectory("ocwp-serve-overlay")
        try {
            val bin = java.nio.file.Files.createDirectories(root.resolve("bin"))
            val opencode = bin.resolve("opencode")
            java.nio.file.Files.writeString(
                opencode,
                "#!/bin/sh\nprintf '%s|%s|%s' \"\$OPENCODE_CONFIG_CONTENT\" \"\${OCWP_CONFIG_OVERLAY-unset}\" \"\$*\"\n",
            )
            assertTrue(opencode.toFile().setExecutable(true))
            val script =
                decodedGuestScript(
                    SbxCli.buildExecServeCommand(
                        name = "ide-ocwp-abc",
                        workspace = "/tmp/project",
                        extraEnvKeys = listOf(SbxCli.CONFIG_OVERLAY_ENV),
                    ),
                )!!
            fun serve(path: String, kitConfig: String): String {
                val process =
                    ProcessBuilder("/bin/sh", "-c", script)
                        .apply {
                            environment().clear()
                            environment()["PATH"] = path
                            environment()["OPENCODE_CONFIG_CONTENT"] = kitConfig
                            environment()[SbxCli.CONFIG_OVERLAY_ENV] =
                                """{"mcp":{"idea":{"type":"remote","enabled":true}}}"""
                        }
                        .redirectErrorStream(true)
                        .start()
                val output = process.inputStream.bufferedReader().readText()
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                return output
            }
            val args = "|unset|serve --hostname 0.0.0.0 --port 4096 --print-logs"
            assertEquals(
                "Without jq the overlay alone applies",
                """{"mcp":{"idea":{"type":"remote","enabled":true}}}$args""",
                serve(bin.toString(), """{"permission":{"*":"allow"}}"""),
            )
            val jq =
                System.getenv("PATH")
                    .orEmpty()
                    .split(java.io.File.pathSeparator)
                    .map { java.nio.file.Path.of(it, "jq") }
                    .firstOrNull { java.nio.file.Files.isExecutable(it) }
            org.junit.Assume.assumeNotNull(jq)
            val withJq = "$bin${java.io.File.pathSeparator}${jq!!.parent}"
            assertEquals(
                """{"permission":{"*":"allow"},"mcp":{"idea":{"type":"remote","enabled":true}}}$args""",
                serve(withJq, """{"permission":{"*":"allow"}}"""),
            )
            assertEquals(
                "Invalid kit JSON falls back to the overlay",
                """{"mcp":{"idea":{"type":"remote","enabled":true}}}$args""",
                serve(withJq, "not json"),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun networkKitTemplateIsAV3MixinWithCommonHostsCommented() {
        val yaml = SbxCli.networkKitTemplateYaml()
        assertTrue(yaml.startsWith("schemaVersion: \"3\"\nkind: mixin\n"))
        assertFalse("v3 descriptors have no top-level name", yaml.contains("\nname:"))
        assertTrue(yaml.contains("  - type: com.docker.sandbox/network-policy@1\n"))
        assertTrue(yaml.contains("\n      runtime:\n        allow:\n"))
        assertTrue(yaml.contains("Docker's OpenCode kits already allow"))
        assertTrue(yaml.contains("uncomment ALL THREE lines"))
        assertTrue(yaml.contains("          # - \"*\"\n"))
        assertTrue(yaml.contains("CIDRs alone do not allow hostname requests"))
        assertTrue(yaml.contains("          # - 0.0.0.0/0\n"))
        assertTrue(yaml.contains("NOT a local-only restriction"))
        assertTrue(yaml.contains("          # - localhost\n"))
        assertTrue(yaml.contains("          # - api.mistral.ai  # Mistral\n"))
        assertFalse("Covered by Docker's kit", yaml.contains("api.openai.com"))
        assertFalse(Regex("(?m)^\\s+- ").containsMatchIn(yaml.substringAfter("allow:\n")))
        assertEquals("./opencode-sbx/opencode-network-kit", SbxCli.networkKitRef())
    }

    @Test
    fun legacyKitFormatRejectionIsRecognized() {
        assertTrue(
            SbxCli.reportsLegacyKitFormat(
                "error: resolve kits: cannot mix v3 kits with v1/v2 kits in one sandbox; compositions are single-format",
            ),
        )
        assertFalse(SbxCli.reportsLegacyKitFormat("error: kit not found"))
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
        assertTrue(
            script.contains(
                "pkill -TERM -f '[o]pencode serve --hostname 0.0.0.0 --port 4096 --print-logs'",
            ),
        )
        assertTrue(
            script.contains(
                "pkill -KILL -f '[o]pencode serve --hostname 0.0.0.0 --port 4096 --print-logs'",
            ),
        )
        assertTrue(script.contains("pkill -0 -f"))
        assertFalse(script.contains("^opencode serve"))
        assertFalse(SbxCli.commandContainsBoundEnvAssignment(command))
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
            listOf(
                "/var/folders/9z/vghfln3n0s5c12b80dfmwmww0000gn/T/opencode/sbx-ocwp-spike-project",
            ),
            sandbox.workspaces,
        )
        assertEquals(49161, SbxCli.publishedHostPort(sandbox.ports))
    }

    @Test
    fun parsesCurrentLiveInventoryFixtures() {
        // Captured on macOS with sbx 0.49.1, 2026-10-20; names/host paths anonymized.
        val sandbox = SbxCli.parseLsJsonOrNull(resource("sbx-ls-0.49.1.json"))!!.single()
        assertEquals("dd1642cc-c281-4521-a030-f7cbe04641b0", sandbox.id)
        assertEquals("stopped", sandbox.status)
        assertEquals(
            listOf(
                "/workspace/project",
                "/workspace/project/opencode-sbx:ro",
                "/home/user/.config/opencode:ro",
            ),
            sandbox.workspaces,
        )
        assertTrue(sandbox.ports.isEmpty())
        // Captured on macOS with sbx 0.53.0 from Docker's OpenCode kit, 2026-11-13; anonymized.
        val kit = SbxCli.parseLsJsonOrNull(resource("sbx-ls-0.53.0.json"))!!.single()
        assertEquals(SbxCli.AGENT, kit.agent)
        assertEquals("running", kit.status)
        assertEquals(
            listOf(
                "/workspace/project",
                "/workspace/project/opencode-sbx:ro",
                "/home/user/.local/share/opencode-web-panel/sbx/ide-ocwp-0123456789ab",
            ),
            kit.workspaces,
        )
        assertEquals(listOf(49154), SbxCli.publishedHostPorts(kit.ports))
    }

    @Test
    fun sandboxListsAcceptBothEnvelopesAndIgnoreAdditionalFields() {
        val legacy = resource("sbx-ls-running.json")
        val array =
            com.google.gson.JsonParser.parseString(legacy).asJsonObject.getAsJsonArray("sandboxes")
        array[0].asJsonObject.addProperty("detached", false)
        array[0].asJsonObject.addProperty("created_at", "2026-10-20T08:00:00Z")
        assertEquals(SbxCli.parseLsJson(legacy), SbxCli.parseLsJsonOrNull(array.toString()))
        assertEquals(
            SbxCli.parseLsJson(legacy),
            SbxCli.parseLsJsonOrNull("""{"future":true,"sandboxes":$array}"""),
        )
        assertEquals(emptyList<SbxSandboxListEntry>(), SbxCli.parseLsJsonOrNull(" \n [] \n"))
    }

    @Test
    fun malformedSandboxInventoryIsNotAnEmptyInventory() {
        assertNull(SbxCli.parseLsJsonOrNull("ERROR: daemon unavailable"))
        assertNull(SbxCli.parseLsJsonOrNull("{}"))
        for (invalid in
            listOf(
                "null",
                "true",
                "42",
                "[",
                "[] trailing",
                "{\"sandboxes\":null}",
                "{\"sandboxes\":{}}",
            )) {
            assertNull(invalid, SbxCli.parseLsJsonOrNull(invalid))
        }
        assertEquals(
            "Entries without an id are skipped, not a reason to distrust the whole inventory",
            emptyList<SbxSandboxListEntry>(),
            SbxCli.parseLsJsonOrNull("""{"sandboxes":[{"name":"missing-id"}]}"""),
        )
        assertEquals(
            emptyList<SbxSandboxListEntry>(),
            SbxCli.parseLsJsonOrNull("""{"sandboxes":[]}"""),
        )
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
        val ports =
            listOf(
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
        assertFalse(
            SbxCli.policyIsInitialized(
                """{"rules":[{"scope":"sandbox","applies_to":"sandbox:x"}]}""",
            ),
        )
        assertFalse(SbxCli.policyIsInitialized("not json"))
        assertTrue(
            SbxCli.policyIsInitialized(
                """{"rules":[{"id":"default-ai-services","scope":"global","applies_to":"all"}]}""",
            ),
        )
    }

    @Test
    fun policyStateIsUnknownUnlessSbxAnsweredWithARuleList() {
        assertEquals(false, SbxCli.policyState("""{"rules":[]}"""))
        assertEquals(false, SbxCli.policyState("""{"rules":[{"scope":"sandbox"}]}"""))
        assertEquals(true, SbxCli.policyState("""{"rules":[{"scope":"global"}]}"""))
        for (unreadable in listOf("", "not json", "[]", "{}", """{"rules":null}""")) {
            assertNull(unreadable, SbxCli.policyState(unreadable))
        }
    }

    @Test
    fun conflictingSandboxMatchesNameOrWorkspace() {
        val entries = SbxCli.parseLsJson(resource("sbx-ls-running.json"))
        val workspace = entries.single().workspaces.single()
        assertEquals(
            entries.single(),
            SbxCli.conflictingSandbox(entries, "ide-ocwp-spike", "/tmp/other"),
        )
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
        val config =
            SbxExtraMount("/home/u/.config/opencode", "/home/u/.config/opencode", readOnly = true)
        val data = SbxExtraMount("/data", "/data", readOnly = true)
        fun reasons(listed: List<String>) =
            SbxCli.recreateReasons(
                record,
                listed,
                "/p",
                "",
                true,
                listOf(config, data),
                emptyList(),
                config.hostPath,
            )
        assertEquals(
            emptyList<String>(),
            reasons(listOf("/p", "/home/u/.config/opencode", "/data:ro")),
        )
        assertEquals(
            listOf("mount /data should be read-only"),
            reasons(listOf("/p", "/home/u/.config/opencode:ro", "/data")),
        )
        assertEquals(
            emptyList<String>(),
            SbxCli.recreateReasons(
                record.copy(adopted = true),
                listOf("/p"),
                "/p",
                "./x",
                false,
                listOf(data),
                emptyList(),
                null,
            ),
        )
    }

    @Test
    fun aVmWithThePluginInstalledOpenCodeMustMoveToDockersKit() {
        val record = SbxSandboxRecord("id", "ide-ocwp-x", "opencode", "/p")
        val legacy = "/data/opencode-web-panel/sbx-opencode/ide-ocwp-x"
        fun reasons(listed: List<String>) =
            SbxCli.recreateReasons(
                record,
                listed,
                "/p",
                "",
                false,
                emptyList(),
                emptyList(),
                null,
                legacy,
            )
        assertEquals(
            listOf(
                "OpenCode 2 now comes from Docker's sandbox kit; this VM still runs the plugin-installed copy",
            ),
            reasons(listOf("/p", legacy)),
        )
        assertEquals(emptyList<String>(), reasons(listOf("/p")))
        assertEquals(
            "1.x never launched the plugin-installed copy; the mount is still known",
            emptyList<String>(),
            SbxCli.recreateReasons(
                record,
                listOf("/p", legacy),
                "/p",
                "",
                false,
                emptyList(),
                emptyList(),
                null,
                legacy,
                SbxOpenCodeVersion.V1,
            ),
        )
    }

    @Test
    fun eachOpenCodeVersionUsesItsOwnDockerKit() {
        assertEquals("opencode", SbxCli.agentFor(SbxOpenCodeVersion.V2))
        assertEquals("docker.io/sbx/opencode:1.18.31", SbxCli.agentFor(SbxOpenCodeVersion.V1))
        val v1 =
            SbxCli.buildCreateCommand(
                name = "ide-ocwp-abc",
                workspace = "/tmp/project",
                kits = listOf("./kit"),
                agent = SbxCli.agentFor(SbxOpenCodeVersion.V1),
            )
        assertEquals(
            listOf("--kit", "./kit", SbxCli.V1_KIT, "/tmp/project"),
            v1.takeLast(4),
        )
        assertEquals(
            listOf("sbx", "exec", "-w", "/", "ide-ocwp-abc", "opencode", "upgrade", "--print-logs"),
            SbxCli.buildExecUpgradeCommand(name = "ide-ocwp-abc"),
        )
        // The 2.x kit's binary is no install method `opencode upgrade` detects; see
        // SbxGuestUpgradeScriptTest for the script's behavior.
        val v2 =
            SbxCli.buildExecUpgradeCommand(
                name = "ide-ocwp-abc",
                version = SbxOpenCodeVersion.V2,
            )
        assertEquals(listOf("sbx", "exec", "-w", "/", "ide-ocwp-abc", "sh", "-c"), v2.take(7))
        assertTrue(
            decodedGuestScript(v2)!!.contains(
                "opencode upgrade --method curl --print-logs </dev/null",
            ),
        )
    }

    @Test
    fun pinnedOpenCode2ImageIsATagOrDigestOfDockersKit() {
        val digest = "sha256:" + "0123456789abcdef".repeat(4)
        assertEquals("opencode", SbxCli.agentFor(SbxOpenCodeVersion.V2, null))
        assertEquals(
            "docker.io/sbx/opencode:2.2.7",
            SbxCli.agentFor(SbxOpenCodeVersion.V2, "2.2.7"),
        )
        assertEquals(
            "docker.io/sbx/opencode@$digest",
            SbxCli.agentFor(SbxOpenCodeVersion.V2, digest),
        )
        assertEquals(
            "A pin never applies to 1.x",
            SbxCli.V1_KIT,
            SbxCli.agentFor(SbxOpenCodeVersion.V1, "2.2.7"),
        )
        assertNull(SbxCli.normalizeOpenCodeImage(" latest "))
        assertNull(SbxCli.normalizeOpenCodeImage(""))
        assertEquals("Latest", SbxCli.normalizeOpenCodeImage("Latest"))
        assertNull(SbxCli.openCodeImageProblem("2.3.0-rc.1", SbxOpenCodeVersion.V2))
        assertNull(SbxCli.openCodeImageProblem(digest, SbxOpenCodeVersion.V2))
        assertNotNull(SbxCli.openCodeImageProblem("2.2.7", SbxOpenCodeVersion.V1))
        assertNotNull(SbxCli.openCodeImageProblem("1.18.31", SbxOpenCodeVersion.V2))
        assertNotNull(SbxCli.openCodeImageProblem("-flag", SbxOpenCodeVersion.V2))
        assertEquals("sha256:0123456789ab…", SbxCli.openCodeImageLabel(digest))
        assertEquals("latest", SbxCli.openCodeImageLabel(null))
        assertEquals(
            "Docker's OpenCode 2 kit 2.2.7",
            SbxCli.kitLabel(SbxOpenCodeVersion.V2, "2.2.7"),
        )
    }

    @Test
    fun changedImagePinIsAStaleCreateReasonForKnownRecords() {
        val record = SbxSandboxRecord("id", "ide-ocwp-abc", "opencode", "/tmp/p")
        assertNull(
            "Records of older releases are unknown",
            SbxCli.staleOpenCodeImageReason(record, SbxOpenCodeVersion.V2, "2.2.7"),
        )
        val latest = record.copy(openCodeImage = SbxCli.AGENT)
        assertNull(SbxCli.staleOpenCodeImageReason(latest, SbxOpenCodeVersion.V2, null))
        assertEquals(
            "Docker image 2.2.7",
            SbxCli.staleOpenCodeImageReason(latest, SbxOpenCodeVersion.V2, "2.2.7"),
        )
        val pinned = record.copy(openCodeImage = SbxCli.agentFor(SbxOpenCodeVersion.V2, "2.2.7"))
        assertEquals(
            "Docker image latest",
            SbxCli.staleOpenCodeImageReason(pinned, SbxOpenCodeVersion.V2, null),
        )
        assertNull(
            "A version switch is the mismatch check's job",
            SbxCli.staleOpenCodeImageReason(pinned, SbxOpenCodeVersion.V1, null),
        )
    }

    @Test
    fun runningVersionMustMatchTheSelectedKit() {
        assertNull(SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V1, "1.18.31"))
        assertNull(SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V1, "1.19.5"))
        assertNull(SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V2, "2.3.0"))
        assertNull(SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V2, "v2.3.0"))
        assertNull(
            "Unknown is not a mismatch",
            SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V2, null),
        )
        assertNull(SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V2, "unknown"))
        assertNull(
            "Only a version line counts",
            SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V1, """{"jsonrpc":"2.0"}"""),
        )
        assertNotNull(SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V1, "opencode v2.3.0"))
        assertEquals(
            "this sandbox runs OpenCode 1.18.31, but opencode-sbx.yaml selects 2.x (Docker's latest OpenCode 2 kit)",
            SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V2, "1.18.31"),
        )
        assertEquals(
            "this sandbox runs OpenCode 2.3.0, but opencode-sbx.yaml selects 1.x (Docker's OpenCode 1.x kit)",
            SbxCli.openCodeVersionMismatch(SbxOpenCodeVersion.V1, "v2.3.0"),
        )
    }

    @Test
    fun kitAddPermissionPromptIsRecognized() {
        assertTrue(
            SbxCli.kitAddNeedsTerminalApproval(
                "warning: this change requests permissions beyond what this sandbox was granted\n" +
                    "  + network.runtime.allow: api.mistral.ai\n" +
                    "error: stdin is not a terminal; run this command in a terminal to answer the prompt",
            ),
        )
        assertFalse(SbxCli.kitAddNeedsTerminalApproval("error: kit not found"))
    }

    @Test
    fun sbxVersionGateOnlyStopsAReportedOlderRelease() {
        assertEquals(listOf("sbx", "version"), SbxCli.buildVersionCommand())
        assertEquals(listOf(0, 53, 0), SbxCli.parseSbxVersion("sbx version v0.53.0 2c62838074"))
        assertNull(SbxCli.unsupportedSbxVersion("sbx version v0.53.0 2c62838074"))
        assertNull(SbxCli.unsupportedSbxVersion("sbx version v${SbxCli.MINIMUM_SBX_VERSION}"))
        assertNull(SbxCli.unsupportedSbxVersion("sbx version v1.0.0-rc.1"))
        assertEquals("0.49.1", SbxCli.unsupportedSbxVersion("sbx version v0.49.1 c5b19951af"))
        assertEquals("0.50.1", SbxCli.unsupportedSbxVersion("0.50.1"))
        assertNull("Unreadable output is not an old CLI", SbxCli.unsupportedSbxVersion(""))
        assertNull(SbxCli.unsupportedSbxVersion("error: daemon unavailable"))
    }

    @Test
    fun ownedSandboxMatchesByIdEvenWhenProjectIsNotFirstWorkspace() {
        val project = "/tmp/project"
        val persist = "/tmp/persist"
        val protect = "/tmp/project/opencode-sbx"
        val entry =
            SbxSandboxListEntry(
                name = "ide-ocwp-spike",
                id = "7ab26e93-c28f-4310-a567-0c059b22a4a3",
                agent = "opencode",
                status = "running",
                ports = emptyList(),
                workspaces = listOf(protect, persist, project),
            )
        val record =
            SbxSandboxRecord(
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
