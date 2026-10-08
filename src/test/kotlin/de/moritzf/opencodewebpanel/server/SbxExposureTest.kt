package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SbxExposureTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun projectLocalMountsAreNotExposure() {
        val project = temp.newFolder("project").toPath().toRealPath().toString()
        val spec =
            base(project).copy(extraMounts = listOf(SbxExtraMount("./docs", "/home/agent/docs")))
        val exposure =
            SbxExposure.of(spec, project, hostHome = "/home/user", hostConfigDir = Path.of("/cfg"))
        assertTrue(exposure.isEmpty)
        assertEquals("", exposure.fingerprint)
    }

    @Test
    fun outsideMountsSharedConfigAndKitsAreListed() {
        val project = temp.newFolder("project").toPath().toRealPath().toString()
        val home = temp.newFolder("home").toPath().toRealPath().toString()
        val spec =
            base(project)
                .copy(
                    extraMounts =
                        listOf(
                            SbxExtraMount("~/data", "/home/agent/data"),
                            SbxExtraMount("../other", "../other", readOnly = true),
                        ),
                    shareHostOpencodeConfig = true,
                    kits = listOf("git+https://example.com/kits.git#ref=v1"),
                )
        val items =
            SbxExposure.of(spec, project, hostHome = home, hostConfigDir = Path.of("/cfg/opencode"))
                .items
        assertEquals(
            listOf(
                "Host path mounted read-write: ${SbxCli.posixPath(Path.of(home).resolve("data").toString())}",
                "Host path mounted read-only: ${SbxCli.posixPath(Path.of(project).resolve("../other").normalize().toString())}",
                "Host OpenCode config shared read-only: /cfg/opencode",
                "Kit: git+https://example.com/kits.git#ref=v1",
            ),
            items,
        )
    }

    @Test
    fun editingALocalKitChangesTheFingerprint() {
        val project = temp.newFolder("project").toPath().toRealPath()
        val kit = Files.createDirectories(project.resolve("kit"))
        Files.writeString(kit.resolve("spec.yaml"), "kind: mixin\n")
        val spec = base(project.toString()).copy(kits = listOf("./kit"))
        val before = SbxExposure.of(spec, project.toString()).fingerprint
        Files.writeString(
            kit.resolve("spec.yaml"),
            "kind: mixin\npermissions:\n  network:\n    allow: [\"*\"]\n",
        )
        assertNotEquals(before, SbxExposure.of(spec, project.toString()).fingerprint)
    }

    private fun base(project: String) =
        SbxLaunchSpec.defaults(project, hostPort = OpenCodeSettingsState().hostPortOrNull())

    @Test
    fun deepAndLateFilesAreIncluded() {
        val project = temp.newFolder("deep").toPath()
        val kit = Files.createDirectories(project.resolve("kit/a/b/c/d/e"))
        repeat(260) { Files.writeString(kit.resolve("file$it"), "initial") }
        val spec = base(project.toString()).copy(kits = listOf("./kit"))
        val before = SbxExposure.of(spec, project.toString()).fingerprint
        Files.writeString(kit.resolve("zz-last"), "new grant")
        assertNotEquals(before, SbxExposure.of(spec, project.toString()).fingerprint)
    }

    @Test
    fun missingInputsAndExceededBudgetsFailInsteadOfProducingConsent() {
        val project = temp.newFolder("limits").toPath()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            SbxExposure.localKitDigest("./missing", project.toString(), "")
        }
        val kit = Files.createDirectories(project.resolve("kit"))
        Files.writeString(kit.resolve("spec.yaml"), "12345")
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            SbxExposure.localKitDigest("./kit", project.toString(), "", maxEntries = 1)
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            SbxExposure.localKitDigest("./kit", project.toString(), "", maxBytes = 4)
        }
    }

    @Test
    fun linkedDirectoryContentsAreIncludedAndBrokenLinksFail() {
        val project = temp.newFolder("links").toPath()
        val kit = Files.createDirectories(project.resolve("kit"))
        val target = temp.newFolder("target").toPath()
        val file = Files.writeString(target.resolve("setup.sh"), "before")
        try {
            Files.createSymbolicLink(kit.resolve("linked"), target)
        } catch (error: Exception) {
            org.junit.Assume.assumeNoException("Symlinks unavailable", error)
        }
        val spec = base(project.toString()).copy(kits = listOf("./kit"))
        val before = SbxExposure.of(spec, project.toString()).fingerprint
        Files.writeString(file, "after")
        assertNotEquals(before, SbxExposure.of(spec, project.toString()).fingerprint)
        Files.delete(file)
        Files.delete(target)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            SbxExposure.of(spec, project.toString())
        }
    }
}
