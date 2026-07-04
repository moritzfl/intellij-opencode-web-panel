package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.diagnostic.thisLogger
import java.util.concurrent.TimeUnit

internal class OpenCodeProcessTerminator(
    private val stopTimeoutSeconds: Long = 5L,
) {
    fun destroy(process: Process?, capturedDescendants: List<ProcessHandle> = emptyList()) {
        if (process == null) return
        if (!process.isAlive) {
            // The tracked launcher already exited (normal on Windows, where it spawns the
            // real server and quits). A dead process reports no descendants, so fall back to
            // the handles captured while it was alive to avoid orphaning the server.
            destroyDetachedDescendants(capturedDescendants)
            return
        }

        val descendants = (descendantHandles(process) + capturedDescendants).distinctBy { it.pid() }
        destroyGracefully(descendants)
        process.destroy()
        try {
            if (!process.waitFor(stopTimeoutSeconds, TimeUnit.SECONDS)) {
                thisLogger().warn("OpenCode server did not stop gracefully, killing it")
                process.destroyForcibly()
                if (!process.waitFor(stopTimeoutSeconds, TimeUnit.SECONDS)) {
                    thisLogger().warn("OpenCode server process is still alive after force kill")
                }
            }
            destroyRemainingDescendants(descendants)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (process.isAlive) {
                process.destroyForcibly()
            }
            destroyForcibly(descendants)
        }
        thisLogger().info("OpenCode server stopped")
    }

    fun descendantHandles(process: Process): List<ProcessHandle> {
        return runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
    }

    private fun destroyDetachedDescendants(capturedDescendants: List<ProcessHandle>) {
        val aliveHandles = capturedDescendants.filter { it.isAlive }
        if (aliveHandles.isEmpty()) return

        thisLogger().info("Stopping OpenCode server processes that outlived their launcher")
        destroyGracefully(aliveHandles)
        waitForExit(aliveHandles)
        destroyRemainingDescendants(aliveHandles)
        thisLogger().info("OpenCode server stopped")
    }

    private fun destroyGracefully(handles: List<ProcessHandle>) {
        handles.asReversed()
            .filter { it.isAlive }
            .forEach { it.destroy() }
    }

    private fun destroyRemainingDescendants(handles: List<ProcessHandle>) {
        val aliveHandles = handles.filter { it.isAlive }
        if (aliveHandles.isEmpty()) return

        thisLogger().warn("OpenCode child processes are still alive after graceful stop, killing them")
        destroyForcibly(aliveHandles)
        waitForExit(aliveHandles)
        if (aliveHandles.any { it.isAlive }) {
            thisLogger().warn("Some OpenCode child processes are still alive after force kill")
        }
    }

    private fun destroyForcibly(handles: List<ProcessHandle>) {
        handles.asReversed()
            .filter { it.isAlive }
            .forEach { it.destroyForcibly() }
    }

    private fun waitForExit(handles: List<ProcessHandle>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(stopTimeoutSeconds)
        for (handle in handles) {
            if (!handle.isAlive) continue
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return
            runCatching { handle.onExit().get(remainingNanos, TimeUnit.NANOSECONDS) }
        }
    }
}
