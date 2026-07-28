package de.moritzf.opencodewebpanel.features

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeDiffNavigationTest {

    private fun diff(file: String) = OpenCodeServerProtocol.SnapshotFileDiff(
        file = file,
        patch = "--- a/$file\n+++ b/$file\n@@ -1 +1 @@\n-old\n+new\n",
        additions = 1,
        deletions = 1,
        status = "modified",
    )

    @Test
    fun matchesExactAndSuffixPathsIgnoringSeparatorsAndCase() {
        assertTrue(OpenCodeDiffNavigation.matchesFile("src/Main.kt", "src/Main.kt"))
        assertTrue(OpenCodeDiffNavigation.matchesFile("src\\Main.kt", "src/Main.kt"))
        assertTrue(OpenCodeDiffNavigation.matchesFile("/src/Main.kt", "src/Main.kt"))
        assertTrue(OpenCodeDiffNavigation.matchesFile("packages/app/src/Main.kt", "src/Main.kt"))
        assertTrue(OpenCodeDiffNavigation.matchesFile("Src/Main.kt", "src/main.kt"))
        assertFalse(OpenCodeDiffNavigation.matchesFile("src/a/Main.kt", "src/b/Main.kt"))
        assertFalse(OpenCodeDiffNavigation.matchesFile("src/Other.kt", "Main.kt"))
    }

    @Test
    fun selectDiffsDoesNotFallBackToWholeTurnOnMismatch() {
        val diffs = listOf(diff("src/a/Main.kt"), diff("src/b/Main.kt"))
        assertEquals(listOf(diffs[0]), OpenCodeDiffNavigation.selectDiffs(diffs, "src/a/Main.kt"))
        assertEquals(emptyList<OpenCodeServerProtocol.SnapshotFileDiff>(), OpenCodeDiffNavigation.selectDiffs(diffs, "src/c/Main.kt"))
        assertEquals(diffs, OpenCodeDiffNavigation.selectDiffs(diffs, null))
    }
}
