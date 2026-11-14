package de.moritzf.opencodewebpanel.ui

import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeStartupProgress
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCodeServerLogDialogTest {
    @get:Rule val temp = TemporaryFolder()

    private val startOutput = listOf("Creating the sandbox…")

    private fun backend(currentFile: Path?, newestFileOnDisk: Path?): OpenCodeServerBackend =
        Proxy.newProxyInstance(
            OpenCodeServerBackend::class.java.classLoader,
            arrayOf(OpenCodeServerBackend::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentServerLogFile" -> currentFile
                "getServerLogFile" -> currentFile ?: newestFileOnDisk
                "getStartupProgress" ->
                    OpenCodeStartupProgress("Starting", "", 0, 0, 0, 0, startOutput)
                else -> null
            }
        } as OpenCodeServerBackend

    @Test
    fun anOlderRunOrAnotherProjectsLogIsNeverShown() {
        val stale = temp.newFile("opencode-server-1.log").toPath()
        Files.writeString(stale, "another project's server\n")
        for (logsEnabled in listOf(true, false)) {
            val (file, lines) = OpenCodeServerLogDialog.linesOf(backend(null, stale), logsEnabled)
            assertNull(file)
            assertEquals(startOutput, lines)
        }
    }

    @Test
    fun thisServersFileCountsOnlyWhileLogsAreOn() {
        val own = temp.newFile("opencode-server-2.log").toPath()
        Files.writeString(own, "listening\n")
        assertEquals(
            own to listOf("listening"),
            OpenCodeServerLogDialog.linesOf(backend(own, own), logsEnabled = true),
        )
        assertEquals(
            null to startOutput,
            OpenCodeServerLogDialog.linesOf(backend(own, own), logsEnabled = false),
        )
        assertTrue(OpenCodeServerLogDialog.hasOutput(backend(own, own), logsEnabled = true))
    }

    @Test
    fun nothingToShowWithoutOutput() {
        val quiet =
            Proxy.newProxyInstance(
                OpenCodeServerBackend::class.java.classLoader,
                arrayOf(OpenCodeServerBackend::class.java),
            ) { _, _, _ ->
                null
            } as OpenCodeServerBackend
        assertFalse(OpenCodeServerLogDialog.hasOutput(quiet, logsEnabled = true))
    }
}
