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
    fun matchesSuffixPathsUsingPlatformCaseSemantics() {
        assertTrue(OpenCodeDiffNavigation.matchesFile("packages/app/src/Main.kt", "src/Main.kt", caseSensitive = true))
        assertFalse(OpenCodeDiffNavigation.matchesFile("packages/Src/Main.kt", "src/main.kt", caseSensitive = true))
        assertTrue(OpenCodeDiffNavigation.matchesFile("packages/Src/Main.kt", "src/main.kt", caseSensitive = false))
        assertFalse(OpenCodeDiffNavigation.matchesFile("src/a/Main.kt", "src/b/Main.kt", caseSensitive = true))
        assertFalse(OpenCodeDiffNavigation.matchesFile("src/Other.kt", "Main.kt", caseSensitive = true))
    }

    @Test
    fun selectDiffsDoesNotFallBackToWholeTurnOnMismatch() {
        val diffs = listOf(diff("src/a/Main.kt"), diff("src/b/Main.kt"))
        assertEquals(listOf(diffs[0]), OpenCodeDiffNavigation.selectDiffs(diffs, "src/a/Main.kt", caseSensitive = true))
        assertEquals(emptyList<OpenCodeServerProtocol.SnapshotFileDiff>(), OpenCodeDiffNavigation.selectDiffs(diffs, "src/c/Main.kt", caseSensitive = true))
        assertEquals(diffs, OpenCodeDiffNavigation.selectDiffs(diffs, null))
    }

    @Test
    fun exactMatchWinsAndAmbiguousSuffixIsRejected() {
        val exact = diff("src/Main.kt")
        val nested = diff("packages/app/src/Main.kt")
        assertEquals(
            listOf(exact),
            OpenCodeDiffNavigation.selectDiffs(listOf(exact, nested), "src/Main.kt", caseSensitive = true),
        )
        assertEquals(
            emptyList<OpenCodeServerProtocol.SnapshotFileDiff>(),
            OpenCodeDiffNavigation.selectDiffs(
                listOf(diff("src/a/Main.kt"), diff("src/b/Main.kt")),
                "Main.kt",
                caseSensitive = true,
            ),
        )
        assertEquals(
            emptyList<OpenCodeServerProtocol.SnapshotFileDiff>(),
            OpenCodeDiffNavigation.selectDiffs(
                listOf(diff("Foo.kt"), diff("foo.kt")),
                "FOO.kt",
                caseSensitive = true,
            ),
        )
    }

    @Test
    fun resolvePartDiffsKeepsSingleFileAndFiltersMultiFile() {
        val a = diff("src/A.kt")
        val b = diff("src/B.kt")
        assertEquals(listOf(a), OpenCodeDiffNavigation.resolvePartDiffs(listOf(a), "src/other.kt", caseSensitive = true))
        assertEquals(listOf(a, b), OpenCodeDiffNavigation.resolvePartDiffs(listOf(a, b), null, caseSensitive = true))
        assertEquals(listOf(b), OpenCodeDiffNavigation.resolvePartDiffs(listOf(a, b), "src/B.kt", caseSensitive = true))
        assertEquals(
            emptyList<OpenCodeServerProtocol.SnapshotFileDiff>(),
            OpenCodeDiffNavigation.resolvePartDiffs(listOf(a, b), "src/C.kt", caseSensitive = true),
        )
    }
}
