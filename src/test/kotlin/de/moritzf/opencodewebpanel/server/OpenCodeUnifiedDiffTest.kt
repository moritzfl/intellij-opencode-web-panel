package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeUnifiedDiffTest {

    @Test
    fun reconstructsModifiedHunkAndSkipsHeaderNoise() {
        val patch = """
            diff --git a/foo.txt b/foo.txt
            index 1111111..2222222 100644
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,3 @@
             line1
            -old
            +new
             line3
        """.trimIndent()
        val sides = OpenCodeUnifiedDiff.sides(patch)!!
        assertEquals("line1\nold\nline3", sides.before)
        assertEquals("line1\nnew\nline3", sides.after)
    }

    @Test
    fun addedFileHasEmptyBefore() {
        val patch = """
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +alpha
            +beta
        """.trimIndent()
        val sides = OpenCodeUnifiedDiff.sides(patch)!!
        assertEquals("", sides.before)
        assertEquals("alpha\nbeta", sides.after)
    }

    @Test
    fun deletedFileHasEmptyAfter() {
        val patch = """
            @@ -1,2 +0,0 @@
            -alpha
            -beta
        """.trimIndent()
        val sides = OpenCodeUnifiedDiff.sides(patch)!!
        assertEquals("alpha\nbeta", sides.before)
        assertEquals("", sides.after)
    }

    @Test
    fun concatenatesMultipleHunks() {
        val patch = """
            @@ -1,2 +1,2 @@
             a
            -b
            +B
            @@ -10,2 +10,2 @@
             y
            -z
            +Z
        """.trimIndent()
        val sides = OpenCodeUnifiedDiff.sides(patch)!!
        assertEquals("a\nb\ny\nz", sides.before)
        assertEquals("a\nB\ny\nZ", sides.after)
    }

    @Test
    fun ignoresNoNewlineMarker() {
        val patch = "@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file"
        val sides = OpenCodeUnifiedDiff.sides(patch)!!
        assertEquals("a", sides.before)
        assertEquals("b", sides.after)
    }

    @Test
    fun treatsEmptyContextLineAsBlankOnBothSides() {
        val patch = "@@ -1,3 +1,3 @@\n a\n\n-b\n+c"
        val sides = OpenCodeUnifiedDiff.sides(patch)!!
        assertEquals("a\n\nb", sides.before)
        assertEquals("a\n\nc", sides.after)
    }

    @Test
    fun returnsNullForNullEmptyBlankOrHeaderOnly() {
        assertNull(OpenCodeUnifiedDiff.sides(null))
        assertNull(OpenCodeUnifiedDiff.sides(""))
        assertNull(OpenCodeUnifiedDiff.sides("    "))
        assertNull(
            OpenCodeUnifiedDiff.sides(
                """
                diff --git a/x b/x
                index 123..456 100644
                --- a/x
                +++ b/x
                """.trimIndent(),
            ),
        )
    }
}
