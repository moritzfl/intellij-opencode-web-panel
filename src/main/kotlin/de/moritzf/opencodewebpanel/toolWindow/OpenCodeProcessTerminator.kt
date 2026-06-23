package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import java.util.concurrent.TimeUnit

internal class OpenCodeProcessTerminator(
    private val stopTimeoutSeconds: Long = 5L,
) {
    fun destroy(process: Process?) {
        if (process?.isAlive != true) return

        val descendants = descendantHandles(process)
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

    private fun descendantHandles(process: Process): List<ProcessHandle> {
        return runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
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
