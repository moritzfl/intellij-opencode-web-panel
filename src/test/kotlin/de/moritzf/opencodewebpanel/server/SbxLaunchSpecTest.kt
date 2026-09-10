package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            enableIntellijMcp = false,
            useSandbox = true,
            hostPort = 4096,
        )
        val parsed = SbxLaunchSpec.parseYaml(spec.toYaml())
        assertEquals(spec, parsed)
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
    fun fromSettingsUsesSandboxNameAndDefaultsMcpOn() {
        val settings = OpenCodeSettingsState().apply {
            sbxMemory = "8g"
            sbxCpus = "4"
            sbxExtraKits = "./kit\n# skip"
            sbxExtraWorkspaces = "~/docs | /home/agent/docs"
            sbxShareHostOpencodeConfig = true
        }
        val spec = SbxLaunchSpec.fromSettings(settings, "/tmp/project")
        assertEquals(SbxCli.sandboxName("/tmp/project"), spec.name)
        assertEquals("8g", spec.memory)
        assertEquals(listOf("./kit"), spec.kits)
        assertEquals(listOf(SbxExtraMount("~/docs", "/home/agent/docs")), spec.extraMounts)
        assertTrue(spec.enableIntellijMcp)
        assertTrue(spec.shareHostOpencodeConfig)
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
    fun persistWritesProjectSpecAndLaunchers() {
        val root = Files.createTempDirectory("opencode-sbx-spec")
        val settings = OpenCodeSettingsState().apply { sbxMemory = "8g" }
        val path = SbxLaunchSpec.persist(settings, root.toString())
        assertNotNull(path)
        assertTrue(Files.isSameFile(SbxLaunchSpec.projectSpecPath(root.toString()), path!!))
        val text = Files.readString(path)
        assertTrue(text.contains("memory: 8g"))
        assertTrue(text.contains("canonicalDirectory: ./\n"))
        val control = SbxLaunchSpec.projectControlDir(root.toString())
        assertTrue(Files.isRegularFile(control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX)))
        assertFalse(Files.exists(control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_WINDOWS)))
        val launcher = Files.readString(control.resolve(SbxLaunchSpec.PROJECT_LAUNCHER_UNIX))
        assertTrue(launcher.contains("--cli"))
        assertTrue(launcher.contains("--web"))
        assertTrue(launcher.contains("--acp"))
        val parsed = SbxLaunchSpec.parseYaml(text)
        assertEquals("8g", parsed?.memory)
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
    fun unquoteHandlesSingleQuotedApostrophes() {
        assertEquals("./O'Brien", SbxLaunchSpec.unquote("'./O''Brien'"))
        assertEquals("./O''Brien", SbxLaunchSpec.unquote("\"./O''Brien\""))
    }

    @Test
    fun kitRemovalRequiresVmRecreationWhileAppendDoesNot() {
        val base = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), "/tmp/p").copy(kits = listOf("./a"))
        assertFalse(SbxLaunchSpec.requiresVmRecreation(base, base.copy(kits = listOf("./a", "./b"))))
        assertTrue(SbxLaunchSpec.requiresVmRecreation(base.copy(kits = listOf("./a", "./b")), base))
        assertTrue(SbxLaunchSpec.requiresVmRecreation(base, base.copy(shareHostOpencodeConfig = true)))
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
    fun portArgumentMissingYamlUsesFallback() {
        val root = Files.createTempDirectory("opencode-sbx-noport")
        try {
            assertEquals("8181", SbxLaunchSpec.portArgument(root.toString(), "8181"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
