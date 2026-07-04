package de.moritzf.opencodewebpanel.features

import org.cef.misc.EventFlags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class OpenCodeFileDropHandlerTest {
    @Test
    fun isPasteShortcutAcceptsCommandOrControlV() {
        assertTrue(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_V, EventFlags.EVENTFLAG_COMMAND_DOWN))
        assertTrue(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_V, EventFlags.EVENTFLAG_CONTROL_DOWN))
        assertTrue(OpenCodeFileDropHandler.isPasteShortcut(0, EventFlags.EVENTFLAG_COMMAND_DOWN, 'v', 'v'))
    }

    @Test
    fun isPasteShortcutRejectsNonPasteKeys() {
        assertFalse(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_C, EventFlags.EVENTFLAG_COMMAND_DOWN))
        assertFalse(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_V, EventFlags.EVENTFLAG_ALT_DOWN))
        assertFalse(OpenCodeFileDropHandler.isPasteShortcut(KeyEvent.VK_V, EventFlags.EVENTFLAG_NONE))
        assertFalse(
            OpenCodeFileDropHandler.isPasteShortcut(
                KeyEvent.VK_V,
                EventFlags.EVENTFLAG_COMMAND_DOWN or EventFlags.EVENTFLAG_CONTROL_DOWN,
            ),
        )
    }

    @Test
    fun encodeImageToPngProducesDecodablePngBytes() {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 4, 3)
        graphics.dispose()

        val bytes = OpenCodeFileDropHandler.encodeImageToPng(image)

        assertNotNull(bytes)
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertArrayEquals(pngSignature, bytes!!.copyOfRange(0, pngSignature.size))
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
    }

    @Test
    fun shouldUseDroppedImageFlavorWhenDropHasNoFiles() {
        assertTrue(OpenCodeFileDropHandler.shouldUseDroppedImageFlavor(emptyList(), null))
    }

    @Test
    fun shouldUseDroppedImageFlavorForNonFileDropWithoutProjectReference() {
        val directory = Files.createTempDirectory("opencode-drop-test")
        try {
            assertTrue(OpenCodeFileDropHandler.shouldUseDroppedImageFlavor(listOf(directory.toFile()), null))
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun shouldNotUseDroppedImageFlavorWhenDropHasProjectFileReference() {
        val projectRoot = Files.createTempDirectory("opencode-project")
        try {
            val file = projectRoot.resolve("src/App.kt")
            Files.createDirectories(file.parent)
            Files.writeString(file, "fun main() {}")

            assertFalse(OpenCodeFileDropHandler.shouldUseDroppedImageFlavor(listOf(file.toFile()), projectRoot.toString()))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun shouldNotUseDroppedImageFlavorWhenDropHasAttachableFile() {
        val file = Files.createTempFile("opencode-image", ".png")
        try {
            Files.write(file, byteArrayOf(1, 2, 3))

            assertFalse(OpenCodeFileDropHandler.shouldUseDroppedImageFlavor(listOf(file.toFile()), null))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
