package de.moritzf.opencodewebpanel.features

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCodeWaylandClipboardTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun requiresLinuxAndWaylandDisplay() {
        assertTrue(OpenCodeWaylandClipboard.isWaylandSession(true, "wayland-0"))
        // Windows can inherit WAYLAND_DISPLAY from a Unix-like environment. "Not macOS"
        // is insufficient: neither Windows nor macOS may activate the Linux workaround.
        assertFalse(
            "Non-Linux with inherited Wayland display",
            OpenCodeWaylandClipboard.isWaylandSession(false, "wayland-0"),
        )
        for (display in listOf(null, "", " ")) assertFalse(
            OpenCodeWaylandClipboard.isWaylandSession(true, display)
        )
    }

    @Test
    fun requestsExactAdvertisedPlainTextTypeAndPreservesWhitespace() {
        val commands = mutableListOf<List<String>>()
        val result = OpenCodeWaylandClipboard.read { command, limit ->
            commands += command
            if (command.last() == "--list-types") {
                OpenCodeWaylandClipboard.Result.Text(
                    "text/html\ntext/plain\ntext/plain;charset=utf-8\n"
                )
            } else {
                assertEquals(OpenCodeClipboardText.MAX_CHARS, limit)
                OpenCodeWaylandClipboard.Result.Text(" \t\n")
            }
        }
        assertEquals(OpenCodeWaylandClipboard.Result.Text(" \t\n"), result)
        assertEquals(
            listOf("wl-paste", "--no-newline", "--type", "text/plain;charset=utf-8"),
            commands.last(),
        )
    }

    @Test
    fun supportsBarePlainTextAndExactCharsetSpelling() {
        for (type in
            listOf(
                "text/plain",
                "text/plain;charset=UTF-8",
                "text/plain; charset=\"utf-8\"",
                "UTF8_STRING",
            )) {
            val result = OpenCodeWaylandClipboard.read { command, _ ->
                if (command.last() == "--list-types") OpenCodeWaylandClipboard.Result.Text(type)
                else {
                    assertEquals(type, command.last())
                    OpenCodeWaylandClipboard.Result.Text("Über\r\nsecond line")
                }
            }
            assertEquals(OpenCodeWaylandClipboard.Result.Text("Über\r\nsecond line"), result)
        }
    }

    @Test
    fun neverRequestsImageHtmlOrFileContentsAsText() {
        for (types in
            listOf(
                "image/png",
                "text/html",
                "image/png\ntext/plain",
                "text/uri-list\ntext/plain",
                "application/x-qt-image",
            )) {
            var calls = 0
            assertEquals(
                OpenCodeWaylandClipboard.Result.Unavailable,
                OpenCodeWaylandClipboard.read { _, _ ->
                    calls++
                    OpenCodeWaylandClipboard.Result.Text(types)
                },
            )
            assertEquals(1, calls)
        }
    }

    @Test
    fun unavailableInventoryDoesNotReadClipboard() {
        var calls = 0
        assertEquals(
            OpenCodeWaylandClipboard.Result.Unavailable,
            OpenCodeWaylandClipboard.read { _, _ ->
                calls++
                OpenCodeWaylandClipboard.Result.Unavailable
            },
        )
        assertEquals(1, calls)
    }

    @Test
    fun drainsLargeOutputAndDiscardsStderr() {
        assertEquals(
            OpenCodeWaylandClipboard.Result.Text("x".repeat(1024 * 1024)),
            OpenCodeWaylandClipboard.run(probe("output"), OpenCodeClipboardText.MAX_CHARS, 10_000),
        )
    }

    @Test
    fun processOutputPreservesUtf8AndWhitespaceWithoutAddingANewline() {
        assertEquals(
            OpenCodeWaylandClipboard.Result.Text(" \t\nÜber 🦊\r\n"),
            OpenCodeWaylandClipboard.run(probe("unicode"), 100, 10_000),
        )
    }

    @Test
    fun rejectsOversizedAndFailedReadsWithoutReturningPartialText() {
        assertEquals(
            OpenCodeWaylandClipboard.Result.TooLarge,
            OpenCodeWaylandClipboard.run(probe("output"), 100, 10_000),
        )
        assertEquals(
            OpenCodeWaylandClipboard.Result.Unavailable,
            OpenCodeWaylandClipboard.run(probe("failure"), 100, 10_000),
        )
        assertEquals(
            OpenCodeWaylandClipboard.Result.Unavailable,
            OpenCodeWaylandClipboard.run(listOf(temp.root.resolve("missing-wl-paste").path), 100),
        )
    }

    @Test
    fun stalledClipboardOwnerIsBoundedAndDestroyed() {
        val pidFile = temp.root.toPath().resolve("pid")
        val started = System.nanoTime()
        assertEquals(
            OpenCodeWaylandClipboard.Result.Unavailable,
            OpenCodeWaylandClipboard.run(probe("sleep") + pidFile.toString(), 100, 4_000),
        )
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 8_000)
        val pid = Files.readString(pidFile).toLong()
        val process = ProcessHandle.of(pid).orElse(null)
        process?.onExit()?.get(3, TimeUnit.SECONDS)
        assertFalse(process?.isAlive == true)
    }

    private fun probe(mode: String): List<String> {
        val source = temp.root.toPath().resolve("ClipboardProbe.java")
        Files.writeString(
            source,
            """
            import java.nio.file.*;
            class ClipboardProbe {
                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "output":
                            System.err.print("e".repeat(1024 * 1024));
                            System.out.print("x".repeat(1024 * 1024));
                            break;
                        case "failure":
                            System.out.print("partial"); System.exit(1); break;
                        case "unicode":
                            System.out.write(" \t\nÜber 🦊\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            break;
                        case "sleep":
                            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                            Thread.sleep(30000); break;
                    }
                }
            }
            """
                .trimIndent(),
        )
        val java =
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
        return listOf(java.toString(), source.toString(), mode)
    }
}
