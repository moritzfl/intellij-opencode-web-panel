package de.moritzf.opencodewebpanel.toolWindow

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeServerLogBufferTest {

    @Test
    fun appendsToCurrentLogFile() {
        val dir = Files.createTempDirectory("opencode-server-log-buffer")
        try {
            val buffer = OpenCodeServerLogBuffer(logDir = dir, maxLogFiles = 20, maxLogAge = Duration.ofDays(7))

            val file = buffer.startNewFile()
            buffer.append("first")
            buffer.append("second")

            assertEquals(file, buffer.currentOrLatestFile())
            assertEquals(listOf("first", "second"), Files.readAllLines(file))
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun prunesLogsOlderThanRetentionWindow() {
        val dir = Files.createTempDirectory("opencode-server-log-age")
        try {
            val stale = writeLog(dir, "stale", daysAgo = 30)
            val fresh = writeLog(dir, "fresh", daysAgo = 1)

            OpenCodeServerLogBuffer(logDir = dir, maxLogFiles = 20, maxLogAge = Duration.ofDays(7)).pruneOldLogs()

            assertFalse("stale log should be pruned", Files.exists(stale))
            assertTrue("fresh log should be retained", Files.exists(fresh))
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun trimsToNewestFilesWhenCountExceedsCap() {
        val dir = Files.createTempDirectory("opencode-server-log-count")
        try {
            val total = 7
            val files = (0 until total).map { index ->
                writeLog(dir, "entry-$index", daysAgo = 0, ageOffsetMillis = (total - index).toLong())
            }

            OpenCodeServerLogBuffer(logDir = dir, maxLogFiles = 4, maxLogAge = Duration.ofDays(7)).pruneOldLogs()

            val survivors = Files.list(dir).use { stream -> stream.count() }
            assertTrue("directory should be trimmed to the cap, was $survivors", survivors <= 4L)
            assertEquals(listOf(false, false, false), files.take(3).map { Files.exists(it) })
            assertTrue("newest log should be retained", Files.exists(files.last()))
        } finally {
            deleteRecursively(dir)
        }
    }

    private fun writeLog(dir: Path, name: String, daysAgo: Long, ageOffsetMillis: Long = 0): Path {
        val file = dir.resolve("$name.log")
        Files.writeString(file, "{}")
        val millis = System.currentTimeMillis() - daysAgo * 24L * 60 * 60 * 1000 - ageOffsetMillis
        Files.setLastModifiedTime(file, FileTime.fromMillis(millis))
        return file
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
