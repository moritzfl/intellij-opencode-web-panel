package de.moritzf.opencodewebpanel.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.IOException
import java.io.StringReader

class OpenCodeClipboardTextTest {
    @Test
    fun richClipboardUsesPlainTextEvenWhenHtmlIsFirst() {
        val clipboard = contents(
            DataFlavor.selectionHtmlFlavor to { "<b>Über</b>" },
            DataFlavor.stringFlavor to { "Über\r\nsecond line" },
        )
        assertEquals("Über\r\nsecond line", OpenCodeClipboardText.read(clipboard))
    }

    @Test
    fun htmlOnlyDoesNotBecomeLiteralMarkupInChat() {
        val clipboard = contents(DataFlavor.selectionHtmlFlavor to { "<b>text</b>" })
        assertFalse(OpenCodeClipboardText.supports(clipboard))
        assertNull(OpenCodeClipboardText.read(clipboard))
    }

    @Test
    fun whitespaceIsAValidPaste() {
        assertEquals(" \t\n", OpenCodeClipboardText.read(StringSelection(" \t\n")))
    }

    @Test
    fun unreadableStringFallsBackToPlainTextReader() {
        val clipboard = contents(
            DataFlavor.stringFlavor to { throw IOException("clipboard owner disappeared") },
            DataFlavor("text/plain;class=java.io.Reader") to { StringReader("fallback") },
        )
        assertEquals("fallback", OpenCodeClipboardText.read(clipboard))
    }

    @Test
    fun oversizedTextIsRejectedAndReaderClosed() {
        var closed = false
        val reader = object : StringReader("x".repeat(OpenCodeClipboardText.MAX_CHARS + 1)) {
            override fun close() { closed = true; super.close() }
        }
        assertNull(OpenCodeClipboardText.read(contents(DataFlavor("text/plain;class=java.io.Reader") to { reader })))
        org.junit.Assert.assertTrue(closed)
    }

    private fun contents(vararg values: Pair<DataFlavor, () -> Any>) = object : Transferable {
        override fun getTransferDataFlavors() = values.map { it.first }.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = values.any { it.first == flavor }
        override fun getTransferData(flavor: DataFlavor): Any = values.first { it.first == flavor }.second()
    }
}
