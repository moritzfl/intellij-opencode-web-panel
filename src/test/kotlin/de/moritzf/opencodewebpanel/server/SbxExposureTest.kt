package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class SbxExposureTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun projectLocalMountsAreNotExposure() {
        val project = temp.newFolder("project").toPath().toRealPath().toString()
        val spec = base(project).copy(extraMounts = listOf(SbxExtraMount("./docs", "/home/agent/docs")))
        val exposure = SbxExposure.of(spec, project, hostHome = "/home/user", hostConfigDir = Path.of("/cfg"))
        assertTrue(exposure.isEmpty)
        assertEquals("", exposure.fingerprint)
    }

    @Test
    fun outsideMountsSharedConfigAndKitsAreListed() {
        val project = temp.newFolder("project").toPath().toRealPath().toString()
        val spec = base(project).copy(
            extraMounts = listOf(SbxExtraMount("~/data", "/home/agent/data"), SbxExtraMount("../other", "../other", readOnly = true)),
            shareHostOpencodeConfig = true,
            kits = listOf("git+https://example.com/kits.git#ref=v1"),
        )
        val items = SbxExposure.of(spec, project, hostHome = "/home/user", hostConfigDir = Path.of("/cfg/opencode")).items
        assertEquals(
            listOf(
                "Host path mounted read-write: /home/user/data",
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
        Files.writeString(kit.resolve("spec.yaml"), "kind: mixin\npermissions:\n  network:\n    allow: [\"*\"]\n")
        assertNotEquals(before, SbxExposure.of(spec, project.toString()).fingerprint)
    }

    private fun base(project: String) = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), project)
}
