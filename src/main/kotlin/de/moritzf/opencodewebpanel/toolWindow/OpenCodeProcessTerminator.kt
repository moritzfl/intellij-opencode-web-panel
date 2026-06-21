package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import java.util.concurrent.TimeUnit

internal class OpenCodeProcessTerminator(
    private val stopTimeoutSeconds: Long = 5L,
) {
    fun destroy(process: Process?) {
        if (process?.isAlive != true) return

        process.destroy()
        try {
            if (!process.waitFor(stopTimeoutSeconds, TimeUnit.SECONDS)) {
                thisLogger().warn("OpenCode server did not stop gracefully, killing it")
                process.destroyForcibly()
                if (!process.waitFor(stopTimeoutSeconds, TimeUnit.SECONDS)) {
                    thisLogger().warn("OpenCode server process is still alive after force kill")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
        thisLogger().info("OpenCode server stopped")
    }
}
