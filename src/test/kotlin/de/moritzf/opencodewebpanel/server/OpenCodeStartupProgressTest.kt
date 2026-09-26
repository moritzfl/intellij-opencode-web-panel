package de.moritzf.opencodewebpanel.server

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenCodeStartupProgressTest {
    @Test
    fun everyAttemptGetsItsOwnClockAndOutput() {
        var millis = 0L
        val progress = OpenCodeStartupProgressTracker { TimeUnit.MILLISECONDS.toNanos(millis) }
        progress.begin(1)
        progress.step(1, "Starting…", "First start")
        progress.output(1, "old process")
        millis = 914 * 60_000L
        progress.begin(2)
        progress.step(2, "Checking Docker Sandboxes…", "Checking service")
        progress.output(1, "late output from superseded process")
        millis += 1500
        val snapshot = progress.snapshot()!!
        assertEquals(1500L, snapshot.elapsedMillis)
        assertEquals(1500L, snapshot.stageElapsedMillis)
        assertFalse(snapshot.recentOutput.any { it.contains("process") })
    }

    @Test
    fun outputUpdatesActivityWithoutResettingTheStepOrAttemptClock() {
        var millis = 0L
        val progress = OpenCodeStartupProgressTracker { TimeUnit.MILLISECONDS.toNanos(millis) }
        progress.begin(1)
        progress.step(1, "Creating sandbox…", "Downloading images", 600_000)
        millis = 90_000
        progress.output(1, "Downloaded image layer")
        progress.step(1, "Creating sandbox…", "Downloading images", 600_000)
        millis = 95_000
        assertEquals(95_000L, progress.snapshot()!!.elapsedMillis)
        assertEquals(95_000L, progress.snapshot()!!.stageElapsedMillis)
        assertEquals(5_000L, progress.snapshot()!!.quietMillis)
        assertFalse(progress.snapshot()!!.takingLonger)
        progress.step(1, "Link extra mount…", "Preparing folders", 30_000)
        assertEquals(0L, progress.snapshot()!!.stageElapsedMillis)
        millis += 65_000
        assertTrue(progress.snapshot()!!.takingLonger)
        assertTrue(formatStartupActivity(progress.snapshot()!!).contains("Check the log"))
    }

    @Test
    fun finishedAttemptsStopCountingAndIgnoreFurtherOutput() {
        var millis = 0L
        val progress = OpenCodeStartupProgressTracker { TimeUnit.MILLISECONDS.toNanos(millis) }
        progress.begin(1)
        millis = 3000
        progress.finish(1)
        millis += 600_000
        progress.output(1, "steady state server log")
        assertEquals(3000L, progress.snapshot()!!.elapsedMillis)
        assertTrue(progress.snapshot()!!.recentOutput.isEmpty())
    }

    @Test
    fun activityTailIsBoundedAndSanitizedEvenWithoutDiskLogs() {
        val progress = OpenCodeStartupProgressTracker()
        progress.begin(1)
        repeat(100) { progress.output(1, "\u001B[32mline $it\u001B[0m") }
        val lines = progress.snapshot()!!.recentOutput
        assertEquals(40, lines.size)
        assertEquals("line 99", lines.last())
        assertFalse(lines.any { it.contains('\u001B') })
    }
}
