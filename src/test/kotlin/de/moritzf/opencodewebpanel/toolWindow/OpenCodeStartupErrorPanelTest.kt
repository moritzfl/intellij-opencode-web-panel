package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OpenCodeStartupErrorPanelTest : BasePlatformTestCase() {
    private val detail =
        "OpenCode was started as \u201Copencode\u201D but exited with code 1 before reporting its " +
            "server address."
    private val attemptOutput =
        listOf("\u203A Starting OpenCode\u2026", "CloseBraceExpected at line 1, column 20")

    fun testShowsTheDetailAndThisAttemptsOutputWhenServerLogsAreOff() {
        useServerLogs(false)
        val panel = OpenCodeStartupErrorPanel(project) {}

        panel.showFailure(
            existingExecutable(),
            staleLogFile(),
            failureDetail = detail,
            recentOutput = attemptOutput,
        )

        assertEquals(attemptOutput.joinToString("\n"), awaitLog(panel))
        assertEquals(detail, message(panel))
    }

    fun testFallsBackToThisAttemptsOutputWhenTheLogFileIsMissing() {
        useServerLogs(true)
        val panel = OpenCodeStartupErrorPanel(project) {}

        panel.showFailure(existingExecutable(), null, recentOutput = attemptOutput)

        assertEquals(attemptOutput.joinToString("\n"), awaitLog(panel))
    }

    fun testMissingExecutableOutranksTheDetail() {
        useServerLogs(false)
        val panel = OpenCodeStartupErrorPanel(project) {}
        val missing = Files.createTempDirectory("ocwp-card").resolve("opencode").toString()

        panel.showFailure(missing, null, failureDetail = detail, recentOutput = attemptOutput)
        awaitLog(panel)

        assertEquals(
            "The OpenCode executable \u201C$missing\u201D was not found. " +
                "Configure its location in the settings or install OpenCode.",
            message(panel),
        )
    }

    private fun useServerLogs(enabled: Boolean) {
        ApplicationManager.getApplication()
            .replaceService(
                OpenCodeSettingsState::class.java,
                OpenCodeSettingsState().apply { enableServerLogs = enabled },
                testRootDisposable,
            )
    }

    private fun existingExecutable(): String =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                if (File.separatorChar == '\\') "java.exe" else "java",
            )
            .toString()

    private fun staleLogFile(): Path {
        val file = Files.createTempFile("opencode-server-old", ".log")
        Files.writeString(file, "output from an older run\n")
        return file
    }

    private fun awaitLog(panel: OpenCodeStartupErrorPanel): String {
        val area = UIUtil.findComponentsOfType(panel.component, JBTextArea::class.java).single()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (area.text.isEmpty() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        return area.text
    }

    private fun message(panel: OpenCodeStartupErrorPanel): String =
        UIUtil.findComponentsOfType(panel.component, JBLabel::class.java)[1].text
}
