package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import java.util.concurrent.atomic.AtomicLong

/** Latest-wins guard for asynchronous startup session lookups. */
internal class OpenCodeLoadIntent {
    private val revision = AtomicLong()

    fun begin(): Long = revision.incrementAndGet()

    fun invalidate() {
        revision.incrementAndGet()
    }

    fun accepts(
        token: Long,
        initialServerGeneration: Long,
        currentServerGeneration: Long,
        initialServerUrl: String,
        currentServerUrl: String?,
        initialDirectory: String,
        currentDirectory: String?,
        stillEnabled: Boolean,
    ): Boolean {
        return token == revision.get() &&
            stillEnabled &&
            initialServerGeneration == currentServerGeneration &&
            initialServerUrl == currentServerUrl &&
            OpenCodeServerProtocol.isSameFilesystemPath(initialDirectory, currentDirectory)
    }
}
