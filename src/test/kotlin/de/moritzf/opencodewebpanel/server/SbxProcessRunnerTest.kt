package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SbxProcessRunnerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun timeoutBoundsAChildThatKeepsStdoutOpen() {
        val started = System.nanoTime()
        val result = SbxProcessRunner.run(probe("sleep"), emptyMap(), 1_000L)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(-1, result.exitCode)
        assertTrue(result.output.contains("timed out"))
        assertTrue("Timeout took ${elapsedMillis}ms", elapsedMillis < 6_000L)
        val pid = result.output.lineSequence().firstNotNullOfOrNull { it.toLongOrNull() }
        if (pid != null) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertFalse("Timed-out child must be destroyed", ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }
    }

    @Test
    fun drainsMoreThanAPipeBufferAndPreservesNonzeroExit() {
        val result = SbxProcessRunner.run(probe("output"), emptyMap(), 10_000L)
        assertEquals(7, result.exitCode)
        assertTrue(result.output.startsWith("x".repeat(100)))
        assertTrue(result.output.contains("stderr-marker"))
        assertTrue(result.output.length >= 256 * 1024)
    }

    @Test
    fun resolvesRelativePathsAgainstExplicitProjectCwd() {
        val project = temp.newFolder("project with spaces").toPath().toRealPath()
        Files.writeString(project.resolve("kit.txt"), "project-kit")
        val result = SbxProcessRunner.run(probe("cwd"), emptyMap(), 10_000L, project)
        assertEquals(0, result.exitCode)
        assertEquals(listOf(project.toString(), "project-kit"), result.output.trim().lines())
    }

    @Test
    fun streamsStdoutLinesBeforeTheProcessExits() {
        val first = CountDownLatch(1)
        val lines = CopyOnWriteArrayList<String>()
        val result = SbxProcessRunner.run(probe("stream"), emptyMap(), 10_000L, null) { line ->
            lines += line
            if (line == "first") first.countDown()
        }
        assertTrue("Live callback must see the first line before exit", first.await(5, TimeUnit.SECONDS))
        assertEquals(0, result.exitCode)
        assertTrue(lines.contains("first"))
        assertTrue(lines.contains("second"))
        assertTrue(lines.indexOf("first") < lines.indexOf("second"))
    }

    @Test
    fun splitProcessOutputTreatsCarriageReturnAsALine() {
        val lines = mutableListOf<String>()
        val pending = StringBuilder()
        splitProcessOutputLines(pending, "Downloading 10%\rDownloading 20%\nDone\n", lines::add)
        flushProcessOutputLines(pending, lines::add)
        assertEquals(listOf("Downloading 10%", "Downloading 20%", "Done"), lines)
    }

    @Test
    fun sanitizeCliOutputDropsAnsiProgressJunk() {
        assertEquals("", sanitizeCliOutputLine("\u001B[?25l |"))
        assertEquals("", sanitizeCliOutputLine("[?25l |"))
        assertEquals("", sanitizeCliOutputLine("\u001B[999D\u001B[J"))
        assertEquals("", sanitizeCliOutputLine("[999D [J"))
        assertEquals("Upgrading 40%", sanitizeCliOutputLine("\u001B[32mUpgrading 40%\u001B[0m"))
        assertEquals("Upgrading 40%", sanitizeCliOutputLine("\u001B[999D\u001B[JUpgrading 40%"))
        assertEquals("Upgrading 40%", sanitizeCliOutputLine("[999D [J Upgrading 40%"))
    }

    @Test
    fun splitProcessOutputTreatsProgressCsiAsALineBreak() {
        val lines = mutableListOf<String>()
        val pending = StringBuilder()
        splitProcessOutputLines(
            pending,
            "\u001B[?25l\u001B[999D\u001B[JUpgrading 10%\u001B[999D\u001B[JUpgrading 20%\n",
            lines::add,
        )
        flushProcessOutputLines(pending, lines::add)
        assertEquals(listOf("Upgrading 10%", "Upgrading 20%"), lines)
    }

    @Test
    fun missingExecutableReturnsAnActionableFailure() {
        val result = SbxProcessRunner.run(listOf(temp.root.resolve("missing-executable").path), emptyMap(), 1_000L)
        assertEquals(-1, result.exitCode)
        assertTrue(result.output.contains("missing-executable"))
    }

    private fun probe(mode: String): List<String> {
        val java = Path.of(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java")
        // Source-file launch keeps the child independent of IntelliJ/Kover's instrumented classloader.
        val source = temp.root.toPath().resolve("SbxRunnerProbe.java")
        Files.writeString(source, """
            import java.nio.file.*;
            class SbxRunnerProbe {
                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "sleep":
                            System.out.println(ProcessHandle.current().pid());
                            System.out.flush();
                            Thread.sleep(30000);
                            break;
                        case "output":
                            System.out.print("x".repeat(256 * 1024));
                            System.err.println("stderr-marker");
                            System.exit(7);
                            break;
                        case "stream":
                            System.out.println("first");
                            System.out.flush();
                            Thread.sleep(400);
                            System.out.println("second");
                            break;
                        case "cwd":
                            System.out.println(Path.of(".").toRealPath());
                            System.out.println(Files.readString(Path.of("kit.txt")));
                            break;
                    }
                }
            }
        """.trimIndent())
        return listOf(java.toString(), source.toString(), mode)
    }
}
