package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SbxLaunchSpecTest {

    @Test
    fun yamlRoundTripPreservesMountsKitsAndFlags() {
        val spec = SbxLaunchSpec(
            canonicalDirectory = "/tmp/project",
            name = SbxCli.sandboxName("/tmp/project"),
            memory = "8g",
            cpus = "4",
            kits = listOf("./my-kit", "docker.io/sbx/playwright-kit:latest"),
            extraMounts = listOf(SbxExtraMount("~/docs", "/home/agent/docs")),
            shareHostOpencodeConfig = true,
            openCodeVersion = SbxOpenCodeVersion.V2,
            enableIntellijMcp = false,
            useSandbox = true,
            hostPort = 4096,
            workingDirectory = "./app",
        )
        val parsed = SbxLaunchSpec.parseYaml(spec.toYaml())
        assertEquals(spec, parsed)
        assertEquals(java.nio.file.Path.of("/tmp/project").resolve("app").toString(), parsed!!.hostWorkingDirectory())
        val ignoredLegacy = SbxLaunchSpec.parseYaml(
            """
            schemaVersion: 1
            canonicalDirectory: /tmp/project
            name: ide-ocwp-test
            setupCommands:
              - apt-get install -y jq
            networkAllows:
              - api.example.com:443
            """.trimIndent(),
        )
        assertNotNull(ignoredLegacy)
        assertEquals("/tmp/project", ignoredLegacy!!.canonicalDirectory)
        assertTrue(ignoredLegacy.kits.isEmpty())
    }

    @Test
    fun fromSettingsUsesSandboxNameAndIgnoresHiddenLegacyAppDefaults() {
        val settings = OpenCodeSettingsState().apply {
            sbxMemory = "8g"
            sbxCpus = "4"
            sbxExtraKits = "./kit\n# skip"
            sbxExtraWorkspaces = "~/docs | /home/agent/docs"
            sbxShareHostOpencodeConfig = true
            runtimeMode = de.moritzf.opencodewebpanel.settings.OpenCodeRuntimeMode.DOCKER_SANDBOX.name
        }
        val spec = SbxLaunchSpec.fromSettings(settings, "/tmp/project")
        assertEquals(SbxCli.sandboxName("/tmp/project"), spec.name)
        assertEquals(SbxCli.DEFAULT_MEMORY, spec.memory)
        assertEquals(emptyList<String>(), spec.kits)
        assertEquals(emptyList<SbxExtraMount>(), spec.extraMounts)
        assertTrue(spec.enableIntellijMcp)
        assertFalse(spec.shareHostOpencodeConfig)
        assertFalse("A project without a spec runs the Host CLI", spec.useSandbox)
        assertEquals(SbxOpenCodeVersion.V1, spec.openCodeVersion)
        assertEquals(
            SbxOpenCodeVersion.V1,
            SbxLaunchSpec.parseYaml("schemaVersion: 1\ncanonicalDirectory: /tmp/p\n")!!.openCodeVersion,
        )
        assertEquals(
            SbxOpenCodeVersion.V2,
            SbxLaunchSpec.parseYaml(
                "schemaVersion: 1\ncanonicalDirectory: /tmp/p\ninstallOpenCodeV2: true\n",
            )!!.openCodeVersion,
        )
        assertEquals(
            SbxOpenCodeVersion.V2,
            SbxLaunchSpec.parseYaml(
                "schemaVersion: 1\ncanonicalDirectory: /tmp/p\nopenCodeVersion: 2.x\n",
            )!!.openCodeVersion,
        )
        assertEquals(
            SbxOpenCodeVersion.V1,
            SbxLaunchSpec.parseYaml(
                "schemaVersion: 1\ncanonicalDirectory: /tmp/p\nopenCodeVersion: 1.x\ninstallOpenCodeV2: true\n",
            )!!.openCodeVersion,
        )
        assertTrue(spec.protectSandboxFiles)
        assertTrue(spec.persistSandboxSessions)
        assertTrue(SbxLaunchSpec.parseYaml("schemaVersion: 1\ncanonicalDirectory: /tmp/p\n")!!.protectSandboxFiles)
        assertFalse(
            SbxLaunchSpec.parseYaml(
                "schemaVersion: 1\ncanonicalDirectory: /tmp/p\nprotectSandboxFiles: false\n",
            )!!.protectSandboxFiles,
        )
    }

    @Test
    fun persistWritesProjectSpecAndSingleLauncherAndRemovesOldHelpers() {
        val root = Files.createTempDirectory("opencode-sbx-spec")
        val control = Files.createDirectories(SbxLaunchSpec.projectControlDir(root.toString()))
        val obsolete = listOf(
            "opencode-sbx-install-v2.sh", "opencode-sbx-version-v2.sh", "opencode-sbx-config-v2.json",
            SbxLaunchSpec.PROJECT_LAUNCHER_WINDOWS,
        )
        obsolete.forEach { Files.writeString(control.resolve(it), "old generated helper") }
        val settings = OpenCodeSettingsState().apply { sbxMemory = "8g" }
        val path = SbxLaunchSpec.persist(settings, root.toString())
        assertNotNull(path)
        assertTrue(Files.isSameFile(SbxLaunchSpec.projectSpecPath(root.toString()), path!!))
        val text = Files.readString(path)
        assertTrue("Hidden legacy app defaults no longer seed specs", text.contains("memory: 4g"))
        assertEquals("*.sh text eol=lf\n", Files.readString(control.resolve(".gitattributes")))
        assertTrue(text.contains("canonicalDirectory: ./\n"))
        assertTrue(Files.isRegularFile(control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX)))
        Files.list(control).use { files ->
            assertEquals(listOf(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX), files.map { it.fileName.toString() }.filter { it.endsWith(".sh") }.toList())
        }
        obsolete.forEach { assertFalse(Files.exists(control.resolve(it))) }
        val launcher = Files.readString(control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX))
        assertTrue(launcher.contains(SbxCli.V2_INSTALL_SCRIPT))
        assertTrue(launcher.contains(SbxCli.GUEST_V2_VERSION_SCRIPT))
        assertTrue(launcher.contains("--cli"))
        assertTrue(launcher.contains("--web"))
        assertTrue(launcher.contains("--acp"))
        val parsed = SbxLaunchSpec.parseYaml(text)
        assertEquals("4g", parsed?.memory)
        assertEquals(SbxCli.sandboxName(root.toRealPath().toString()), parsed?.name)
    }

    @Test
    fun startupPreservesEveryProjectSetting() {
        val root = Files.createTempDirectory("opencode-project-settings").toRealPath()
        try {
            val settings = OpenCodeSettingsState()
            val expected = SbxLaunchSpec.fromSettings(settings, root.toString()).copy(
                useSandbox = true,
                memory = "8g",
                cpus = "4",
                kits = listOf("./opencode-network-kit", "docker.io/example/kit:v1"),
                extraMounts = listOf(SbxExtraMount("~/docs", "/home/agent/docs")),
                shareHostOpencodeConfig = true,
                enableIntellijMcp = false,
                hostPort = 49123,
            )
            assertNotNull(SbxLaunchSpec.persist(expected))
            assertEquals(expected, SbxLaunchSpec.load(root.toString()))
            repeat(2) {
                assertNotNull(SbxLaunchSpec.persist(settings, root.toString()))
                assertEquals(expected, SbxLaunchSpec.load(root.toString()))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun copiedProjectSpecResolvesAgainstNewCheckout() {
        val root = Files.createTempDirectory("opencode-clones")
        try {
            val first = Files.createDirectory(root.resolve("first")).toRealPath()
            val second = Files.createDirectory(root.resolve("second")).toRealPath()
            val settings = OpenCodeSettingsState()
            val expected = SbxLaunchSpec.fromSettings(settings, first.toString()).copy(useSandbox = false)
            val path = SbxLaunchSpec.persist(expected)!!
            Files.createDirectories(SbxLaunchSpec.projectControlDir(second.toString()))
            Files.copy(path, SbxLaunchSpec.projectSpecPath(second.toString()))
            val loaded = SbxLaunchSpec.load(second.toString())!!
            assertEquals(second.toString(), loaded.canonicalDirectory)
            assertEquals(SbxCli.sandboxName(second.toString()), loaded.name)
            assertNotNull(SbxLaunchSpec.persist(settings, second.toString()))
            assertEquals(loaded, SbxLaunchSpec.load(second.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun startupDoesNotOverwriteUnsupportedOrMalformedProjectSpec() {
        val root = Files.createTempDirectory("opencode-invalid-spec")
        try {
            for (yaml in listOf("not a spec", "schemaVersion: 999\ncanonicalDirectory: ./\n")) {
                val path = SbxLaunchSpec.projectSpecPath(root.toString())
                Files.createDirectories(path.parent)
                Files.writeString(path, yaml)
                assertNull(SbxLaunchSpec.persist(OpenCodeSettingsState(), root.toString()))
                assertEquals(yaml, Files.readString(path))
                val inspection = SbxLaunchSpec.inspect(root.toString())
                assertTrue(inspection is SbxLaunchSpecInspection.Invalid)
                assertTrue("Invalid spec must not fall back to Host CLI", SbxLaunchSpec.usesSandbox(root.toString()))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unsafeSandboxNamesMakeTheSpecInvalid() {
        for (name in listOf("../../../../x", "a/b", "default", ".hidden", "ünicode")) {
            val yaml = "schemaVersion: 1\ncanonicalDirectory: /tmp/project\nname: \"$name\"\n"
            assertNull(name, SbxLaunchSpec.parseYaml(yaml))
        }
        assertEquals("ide-ocwp-test", SbxLaunchSpec.parseYaml("canonicalDirectory: /tmp/p\nname: ide-ocwp-test\n")?.name)
        assertThrows(IllegalArgumentException::class.java) { SbxLaunchSpec.specPath("../x") }
        assertThrows(IllegalArgumentException::class.java) { SbxCli.sandboxPersistDataHome("../x") }
        assertThrows(IllegalArgumentException::class.java) { SbxCli.guestOpenCodeDataHome("../x") }
    }

    @Test
    fun strictParserRejectsInputOutsideTheWrittenSubset() {
        val base = "canonicalDirectory: ./\n"
        val invalid = listOf(
            "<<<<<<< HEAD\n$base",
            "$base\tmemory: 4g\n",
            "$base  memory: 4g\n",
            "${base}memory: 4gb\n",
            "${base}cpus: 64\n",
            "${base}protectSandboxFiles: yes\n",
            "${base}openCodeVersion: 3.x\n",
            "${base}hostPort: 70000\n",
            "${base}memory: 4g\nmemory: 8g\n",
            "${base}extraMounts: [{host: /x}]\n",
            "${base}extraMounts:\n  - sandbox: /home/agent/x\n",
            "${base}extraMounts:\n  - host: /x\n    mode: rw\n",
            "${base}kits: ./a\n",
            "${base}name: |\n  multi\n",
            "memory: 4g\n",
        )
        for (yaml in invalid) {
            val result = SbxLaunchSpec.parseYamlResult(yaml)
            assertNull(yaml, result.spec)
            assertNotNull(yaml, result.error)
        }
        val legacy = SbxLaunchSpec.parseYaml("${base}unknownFutureKey: 1\nsetupCommands:\n  - apt-get install -y jq\n")
        assertNotNull("Unknown scalar keys and legacy lists stay readable", legacy)
        assertEquals("8g", SbxLaunchSpec.parseYaml("${base}memory: 8G\nprotectSandboxFiles: False\n")!!.memory)
    }

    @Test
    fun readOnlyMountsRoundTripAndAcceptTheSbxSuffix() {
        val spec = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), "/tmp/project").copy(
            extraMounts = listOf(SbxExtraMount("/data", "/data", readOnly = true), SbxExtraMount("/rw", "/home/agent/rw")),
        )
        assertEquals(spec, SbxLaunchSpec.parseYaml(spec.toYaml()))
        val suffix = SbxLaunchSpec.parseYaml(
            "canonicalDirectory: ./\nextraMounts:\n  - sandbox: /home/agent/d\n    host: /data:ro\n",
        )
        assertEquals(listOf(SbxExtraMount("/data", "/home/agent/d", readOnly = true)), suffix!!.extraMounts)
    }

    @Test
    fun applyKeepsCommentsUnknownKeysAndUnchangedValues() {
        val root = Files.createTempDirectory("opencode-sbx-merge")
        try {
            val path = SbxLaunchSpec.projectSpecPath(root.toString())
            Files.createDirectories(path.parent)
            Files.writeString(
                path,
                """
                # Team sandbox. Ask #infra before adding mounts.
                schemaVersion: 1
                canonicalDirectory: ./
                memory: '8g' # builds need it
                cpus: "2"
                # Kits we rely on:
                kits:
                  - ./opencode-sbx/opencode-network-kit # network allowances
                futureOption: keep-me
                """.trimIndent() + "\n",
            )
            val loaded = SbxLaunchSpec.load(root.toString())!!
            assertNotNull(SbxLaunchSpec.persist(loaded.copy(cpus = "4", hostPort = 49200)))
            val text = Files.readString(path)
            assertTrue(text.startsWith("# Team sandbox. Ask #infra before adding mounts.\n"))
            assertTrue(text.contains("memory: '8g' # builds need it\n"))
            assertTrue(text, text.contains("cpus: 4\n"))
            assertTrue(text.contains("# Kits we rely on:\nkits:\n  - ./opencode-sbx/opencode-network-kit # network allowances\n"))
            assertTrue(text.contains("futureOption: keep-me\n"))
            assertTrue(text.contains("hostPort: 49200\n"))
            assertEquals(loaded.copy(cpus = "4", hostPort = 49200), SbxLaunchSpec.load(root.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unquoteHandlesSingleQuotedApostrophes() {
        assertEquals("./O'Brien", SbxLaunchSpec.unquote("'./O''Brien'"))
        assertEquals("./O''Brien", SbxLaunchSpec.unquote("\"./O''Brien\""))
    }

    @Test
    fun yamlPreservesHashesInKitRefsAndMountPaths() {
        val expected = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), "/tmp/project").copy(
            kits = listOf("git+https://github.com/team/kits.git#ref=v1&dir=network", "./kit #1"),
            extraMounts = listOf(SbxExtraMount("~/docs #1", "/home/agent/docs #1")),
        )
        assertEquals(expected, SbxLaunchSpec.parseYaml(expected.toYaml()))
    }

    @Test
    fun loadReadsLegacyProjectSpecName() {
        val root = Files.createTempDirectory("opencode-sbx-spec")
        val yaml = """
            schemaVersion: 1
            canonicalDirectory: ${root.toRealPath()}
            name: ide-ocwp-test
            memory: 4g
            cpus: "2"
            kits: []
            extraMounts: []
            shareHostOpencodeConfig: false
            enableIntellijMcp: true
            protectSandboxFiles: true
            persistSandboxSessions: true
            useSandbox: true
        """.trimIndent()
        Files.writeString(root.resolve(SbxLaunchSpec.LEGACY_PROJECT_SPEC_NAME), yaml)
        val loaded = SbxLaunchSpec.load(root.toString())
        assertNotNull(loaded)
        assertEquals("ide-ocwp-test", loaded!!.name)
        assertTrue(loaded.useSandbox)
    }

    @Test
    fun nestedSpecDotSlashResolvesToProjectNotControlDir() {
        val root = Files.createTempDirectory("opencode-sbx-nested").toRealPath()
        try {
            val path = SbxLaunchSpec.projectSpecPath(root.toString())
            Files.createDirectories(path.parent)
            Files.writeString(
                path,
                """
                schemaVersion: 1
                canonicalDirectory: ./
                name: stale-clone-name
                """.trimIndent() + "\n",
            )
            val loaded = SbxLaunchSpec.load(root.toString())!!
            assertEquals(root.toString(), loaded.canonicalDirectory)
            assertEquals(SbxCli.sandboxName(root.toString()), loaded.name)
            assertEquals(root, SbxLaunchSpec.workspaceBaseForSpec(path))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun nestedOpenCodeDirectoryLoadsWithinTheMountedWorkspace() {
        val root = Files.createTempDirectory("opencode-sbx-working-dir").toRealPath()
        try {
            val workdir = Files.createDirectory(root.resolve("app")).toRealPath()
            val spec = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), root.toString()).copy(
                useSandbox = true, workingDirectory = "./app",
            )
            assertNotNull(SbxLaunchSpec.persist(spec))
            val loaded = SbxLaunchSpec.load(root.toString())!!
            assertEquals(root.toString(), loaded.canonicalDirectory)
            assertEquals(workdir.toString(), loaded.hostWorkingDirectory())
            val projectSettings = de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState()
            assertEquals(workdir.toString(), projectSettings.effectiveOpenCodeDirectory(root.toString()))
            assertEquals(root.toString(), projectSettings.effectiveProjectDirectory(root.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun guestCodePathAndQualifiedClassResolveInsideHostIdeSubdirectory() {
        val root = Files.createTempDirectory("ocwp-sample-repo").toRealPath()
        try {
            val source = "package org.example;\nclass SampleWidget {\n    void render() {}\n}\n"
            val file = Files.writeString(
                Files.createDirectories(root.resolve("app/src/org/example")).resolve("SampleWidget.java"), source,
            ).toRealPath()
            val spec = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), root.toString()).copy(
                useSandbox = true, workingDirectory = "./app",
            )
            assertNotNull(SbxLaunchSpec.persist(spec))
            val workdir = root.resolve("app")
            val prefixes = OpenCodeHostPaths.guestToHostPrefixes("sbx:${spec.name}", workdir.toString(), root.toString())
            val guestPath = SbxCli.guestBindPath(file.toString())
            val fileRef = OpenCodeServerProtocol.parseCodeReference("$guestPath:3")!!
            assertEquals(guestPath, fileRef.path)
            assertEquals(2, fileRef.line)
            val target = OpenCodeServerProtocol.resolveFileLinkWithBases(
                "$guestPath:3", listOf(workdir.toString()), guestToHostPrefixes = prefixes,
            )
            assertEquals(file, target?.path)
            assertEquals(2, target?.line)

            val member = OpenCodeServerProtocol.parseCodeReference("org.example.SampleWidget.render()")!!
            assertEquals("SampleWidget", member.fileName)
            assertEquals("render", member.memberName)
            assertEquals(file.toString(), OpenCodeServerProtocol.pickDistinctPath(listOf(file.toString()), member.path))
            assertEquals(2, OpenCodeServerProtocol.findMemberLineIndex(source, member.memberName!!))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun workingDirectoryCannotEscapeTheMountedWorkspaceOrBeMissing() {
        val root = Files.createTempDirectory("opencode-sbx-working-dir-check").toRealPath()
        try {
            val path = SbxLaunchSpec.projectSpecPath(root.toString())
            Files.createDirectories(path.parent)
            for (value in listOf("", "../other", "/tmp", "C:/outside", "C:relative", "~/other")) {
                assertNull(value, SbxLaunchSpec.parseYaml("canonicalDirectory: ./\nworkingDirectory: '$value'\n"))
            }
            Files.writeString(path, "canonicalDirectory: ./\nworkingDirectory: ./missing\n")
            assertTrue(SbxLaunchSpec.inspect(root.toString()) is SbxLaunchSpecInspection.Invalid)
            val outside = Files.createTempDirectory("opencode-sbx-working-outside")
            try {
                if (runCatching { Files.createSymbolicLink(root.resolve("escape"), outside) }.isSuccess) {
                    Files.writeString(path, "canonicalDirectory: ./\nworkingDirectory: ./escape\n")
                    assertTrue(SbxLaunchSpec.inspect(root.toString()) is SbxLaunchSpecInspection.Invalid)
                }
            } finally {
                outside.toFile().deleteRecursively()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun persistRemovesLegacyRootSpecAndLaunchers() {
        val root = Files.createTempDirectory("opencode-sbx-migrate")
        Files.writeString(root.resolve(SbxLaunchSpec.PROJECT_SPEC_NAME), "canonicalDirectory: ./\n")
        Files.writeString(root.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX), "legacy")
        assertNotNull(SbxLaunchSpec.persist(OpenCodeSettingsState(), root.toString()))
        assertFalse(Files.exists(root.resolve(SbxLaunchSpec.PROJECT_SPEC_NAME)))
        assertFalse(Files.exists(root.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX)))
        assertTrue(Files.isRegularFile(SbxLaunchSpec.projectSpecPath(root.toString())))
    }

    @Test
    fun persistRemovesWindowsCmdLauncher() {
        val root = Files.createTempDirectory("opencode-sbx-cmd")
        try {
            val control = SbxLaunchSpec.projectControlDir(root.toString())
            Files.createDirectories(control)
            Files.writeString(control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_WINDOWS), "legacy cmd")
            assertNotNull(SbxLaunchSpec.persist(OpenCodeSettingsState(), root.toString()))
            assertFalse(Files.exists(control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_WINDOWS)))
            assertTrue(Files.isRegularFile(control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun portArgumentPrefersYamlHostPort() {
        val root = Files.createTempDirectory("opencode-sbx-port").toRealPath()
        try {
            val spec = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), root.toString()).copy(hostPort = 49123)
            assertNotNull(SbxLaunchSpec.persist(spec))
            assertEquals("49123", SbxLaunchSpec.portArgument(root.toString(), "0"))
            val auto = spec.copy(hostPort = null)
            assertNotNull(SbxLaunchSpec.persist(auto))
            assertEquals("0", SbxLaunchSpec.portArgument(root.toString(), "4096"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun parseYamlReadsFlowStyleKits() {
        val parsed = SbxLaunchSpec.parseYaml(
            """
            schemaVersion: 1
            canonicalDirectory: /tmp/project
            kits: [./network-kit, "git+https://example.com/kit.git#ref=v1"]
            """.trimIndent(),
        )
        assertEquals(listOf("./network-kit", "git+https://example.com/kit.git#ref=v1"), parsed!!.kits)
    }

    @Test
    fun parseYamlKeepsQuotedHashAfterDoubledSingleQuotes() {
        val parsed = SbxLaunchSpec.parseYaml(
            """
            schemaVersion: 1
            canonicalDirectory: /tmp/project
            kits:
              - './kit ''quoted'' #1'
            """.trimIndent(),
        )
        assertEquals(listOf("./kit 'quoted' #1"), parsed!!.kits)
    }

    @Test
    fun parseYamlRejectsUnclosedFlowList() {
        assertNull(
            SbxLaunchSpec.parseYaml(
                """
                schemaVersion: 1
                canonicalDirectory: /tmp/project
                kits: [./network-kit
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun portArgumentMissingYamlUsesFallback() {
        val root = Files.createTempDirectory("opencode-sbx-noport")
        try {
            assertEquals("8181", SbxLaunchSpec.portArgument(root.toString(), "8181"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
