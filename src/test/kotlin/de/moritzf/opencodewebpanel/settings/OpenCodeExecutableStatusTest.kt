package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.server.OpenCodeExecutableProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeExecutableStatusTest {
    @Test
    fun openCodeStatusNamesTheExecutableOrWhatIsWrong() {
        assertEquals(
            ExecutableStatus("Uses /bin/opencode (OpenCode 2.1.2)", false),
            openCodeExecutableStatus(OpenCodeExecutableProbe("/bin/opencode", "2.1.2"), false, ""),
        )
        assertEquals(
            ExecutableStatus("Uses /bin/opencode", false),
            openCodeExecutableStatus(OpenCodeExecutableProbe("/bin/opencode", null), true, "x"),
        )
        assertEquals(
            ExecutableStatus("Not found or not executable: /nope", true),
            openCodeExecutableStatus(OpenCodeExecutableProbe(null, null), true, "/nope"),
        )
        assertTrue(openCodeExecutableStatus(OpenCodeExecutableProbe(null, null), false, "").problem)
        assertTrue(
            "An executable that does not answer --version is flagged",
            openCodeExecutableStatus(
                    OpenCodeExecutableProbe("/bin/opencode", null, failed = true),
                    true,
                    "/bin/opencode",
                )
                .problem,
        )
        assertEquals(
            ExecutableStatus(
                "/A/OpenCode.app/Contents/MacOS/OpenCode is the OpenCode desktop app. " +
                    "Choose the opencode command-line tool.",
                true,
            ),
            openCodeExecutableStatus(
                OpenCodeExecutableProbe(
                    "/A/OpenCode.app/Contents/MacOS/OpenCode",
                    null,
                    desktopApp = true,
                ),
                true,
                "x",
            ),
        )
    }

    @Test
    fun sandboxPrerequisitesNameWhatIsMissing() {
        assertEquals(
            ExecutableStatus("This computer: sbx 0.53.0 · network policy set up", false),
            sandboxPrerequisitesText(OpenCodeExecutableProbe("/bin/sbx", "0.53.0"), true),
        )
        assertEquals(
            ExecutableStatus("This computer: sbx not found · no network policy yet", true),
            sandboxPrerequisitesText(OpenCodeExecutableProbe(null, null), false),
        )
        assertTrue(
            sandboxPrerequisitesText(OpenCodeExecutableProbe("/bin/sbx", "0.48.0", true), true)
                .text
                .contains("sbx 0.48.0 is too old (needs 0.51.0)"),
        )
    }

    @Test
    fun missingSbxIsOnlyAProblemWhenAPathWasGiven() {
        assertFalse(sbxExecutableStatus(OpenCodeExecutableProbe(null, null), false, "").problem)
        assertTrue(sbxExecutableStatus(OpenCodeExecutableProbe(null, null), true, "/x").problem)
        val old =
            sbxExecutableStatus(OpenCodeExecutableProbe("/bin/sbx", "0.48.0", true), false, "")
        assertTrue(old.problem)
        assertTrue(old.text, old.text.contains("need sbx 0.51.0 or later"))
    }
}
