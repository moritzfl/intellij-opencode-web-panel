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
