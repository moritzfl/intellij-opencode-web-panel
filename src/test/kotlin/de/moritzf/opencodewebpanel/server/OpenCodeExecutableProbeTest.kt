package de.moritzf.opencodewebpanel.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCodeExecutableProbeTest {
    @get:Rule val temp = TemporaryFolder()

    private fun executable(name: String): String =
        temp.newFile(name).apply { setExecutable(true) }.absolutePath

    private fun answering(stdout: String, exitCode: Int = 0) = SbxCommandRunner { _, _, _, _ ->
        SbxCommandResult(exitCode, "log line on stderr version=9.9.9\n$stdout", stdout)
    }

    @Test
    fun openCodeVersionComesFromStdoutOfEitherGeneration() {
        val path = executable("opencode")
        assertEquals(
            "2.1.2",
            OpenCodeExecutableProbes.openCode(path, answering("opencode v2.1.2\n")).version,
        )
        assertEquals("1.19.5", OpenCodeExecutableProbes.openCode(path, answering("1.19.5")).version)
        val failing = OpenCodeExecutableProbes.openCode(path, answering("", exitCode = 1))
        assertEquals(path, failing.path)
        assertNull(failing.version)
        assertTrue(failing.failed)
    }

    @Test
    fun missingOrUnparseablePathHasNoPathAndRunsNothing() {
        val mustNotRun = SbxCommandRunner { _, _, _, _ -> error("must not run") }
        for (path in listOf(File(temp.root, "missing").absolutePath, "/tmp/a\u0000b")) {
            assertNull(path, OpenCodeExecutableProbes.openCode(path, mustNotRun).path)
            assertNull(path, OpenCodeExecutableProbes.sbx(path, mustNotRun).path)
        }
    }

    @Test
    fun theDesktopAppIsNeverLaunched() {
        val macOs = File(temp.newFolder("OpenCode.app", "Contents", "MacOS"), "OpenCode")
        macOs.createNewFile()
        macOs.setExecutable(true)
        val probe =
            OpenCodeExecutableProbes.openCode(macOs.absolutePath) { _, _, _, _ ->
                error("must not run")
            }
        assertTrue(probe.desktopApp)
        assertEquals(macOs.absolutePath, probe.path)
    }

    @Test
    fun sbxBelowTheMinimumIsTooOld() {
        val path = executable("sbx")
        val old = OpenCodeExecutableProbes.sbx(path, answering("sbx version v0.48.0 abc"))
        assertEquals("0.48.0", old.version)
        assertTrue(old.tooOld)
        val current = OpenCodeExecutableProbes.sbx(path, answering("sbx version v0.53.0 abc"))
        assertFalse(current.tooOld)
    }
}
