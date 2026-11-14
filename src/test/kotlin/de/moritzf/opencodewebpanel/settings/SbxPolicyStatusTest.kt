package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.server.SbxCommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SbxPolicyStatusTest {
    private fun ok(stdout: String) = SbxCommandResult(0, stdout, stdout)

    @Test
    fun onlyAReadableRuleListDecidesPresentOrAbsent() {
        assertEquals(
            SbxPolicyStatus.PRESENT,
            sbxPolicyCheckOf(true, ok("""{"rules":[{"scope":"global"}]}""")).status,
        )
        assertEquals(
            SbxPolicyStatus.ABSENT,
            sbxPolicyCheckOf(true, ok("""{"rules":[{"scope":"sandbox"}]}""")).status,
        )
        assertEquals(SbxPolicyStatus.ABSENT, sbxPolicyCheckOf(true, ok("""{"rules":[]}""")).status)
        for (unreadable in listOf("", "Update available: sbx 0.60\n{\"rules\":[]}", "{}")) {
            assertEquals(
                unreadable,
                SbxPolicyCheck(SbxPolicyStatus.UNKNOWN, "unexpected answer from sbx"),
                sbxPolicyCheckOf(true, ok(unreadable)),
            )
        }
    }

    @Test
    fun failuresStayUnknownWithAReason() {
        assertEquals(
            SbxPolicyCheck(SbxPolicyStatus.UNKNOWN, "daemon is not running"),
            sbxPolicyCheckOf(true, SbxCommandResult(1, "\n daemon is not running\nmore", "")),
        )
        assertEquals(
            SbxPolicyCheck(SbxPolicyStatus.UNKNOWN, "sbx exited with 2"),
            sbxPolicyCheckOf(true, SbxCommandResult(2, "", "")),
        )
        assertEquals(SbxPolicyStatus.UNKNOWN, sbxPolicyCheckOf(false, null).status)
        assertEquals(SbxPolicyStatus.UNKNOWN, sbxPolicyCheckOf(true, null).status)
    }
}
