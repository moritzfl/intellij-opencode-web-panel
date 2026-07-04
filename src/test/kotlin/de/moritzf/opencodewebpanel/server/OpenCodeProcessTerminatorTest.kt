package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenCodeProcessTerminatorTest {

    /**
     * Simulates the Windows launcher pattern on POSIX: the launched process spawns a
     * long-lived child and exits. destroy() must fall back to the descendant handles
     * captured while the launcher was alive, otherwise the child is orphaned.
     */
    @Test
    fun destroyKillsCapturedDescendantsAfterLauncherExits() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val terminator = OpenCodeProcessTerminator(stopTimeoutSeconds = 5)
        val launcher = ProcessBuilder("/bin/sh", "-c", "sleep 30 & sleep 1").start()
        try {
            var descendants: List<ProcessHandle> = emptyList()
            val deadline = System.currentTimeMillis() + 5_000
            while (descendants.isEmpty() && System.currentTimeMillis() < deadline) {
                descendants = terminator.descendantHandles(launcher)
                if (descendants.isEmpty()) Thread.sleep(50)
            }
            assertTrue("expected to capture descendants while the launcher is alive", descendants.isNotEmpty())

            assertTrue("launcher should exit on its own", launcher.waitFor(10, TimeUnit.SECONDS))
            val orphans = descendants.filter { it.isAlive }
            assertTrue("the spawned child should outlive the launcher", orphans.isNotEmpty())

            terminator.destroy(launcher, descendants)

            for (orphan in orphans) {
                runCatching { orphan.onExit().get(5, TimeUnit.SECONDS) }
                assertFalse("captured descendant should be terminated", orphan.isAlive)
            }
        } finally {
            launcher.toHandle().descendants().forEach { it.destroyForcibly() }
            launcher.destroyForcibly()
        }
    }

    @Test
    fun destroyWithoutCapturedDescendantsIsANoOpForDeadProcess() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val terminator = OpenCodeProcessTerminator(stopTimeoutSeconds = 1)
        val process = ProcessBuilder("/bin/sh", "-c", "exit 0").start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        terminator.destroy(process)
        terminator.destroy(process, emptyList())
    }
}
