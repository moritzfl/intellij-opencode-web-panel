package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.Comparator

internal class OpenCodeServerLogBuffer(
    private val logDir: Path = defaultLogDir(),
    private val maxLogFiles: Int = 20,
    private val maxLogAge: Duration = Duration.ofDays(7),
    private val maxLogFileBytes: Long = DEFAULT_MAX_LOG_FILE_BYTES,
    private val enabled: () -> Boolean = { OpenCodeSettingsState.getInstance().enableServerLogs },
) {
    private val fileLock = Any()
    private var currentLogFile: Path? = null
    private var currentLogFileBytes = 0L

    fun startNewFile(startedAt: Instant = Instant.now()): Path? {
        return synchronized(fileLock) {
            if (!enabled()) {
                currentLogFile = null
                return@synchronized null
            }
            pruneOldLogs()
            setCurrentFile(
                createLogFile(
                    "========== OpenCode server start/restart via OpenCode Web Panel at $startedAt ==========" +
                        System.lineSeparator(),
                ),
            )
            currentLogFile
        }
    }

    fun currentOrLatestFile(): Path? {
        synchronized(fileLock) {
            currentLogFile?.takeIf { Files.isRegularFile(it) }?.let { return it }
        }
        if (!Files.isDirectory(logDir)) return null
        return try {
            Files.list(logDir).use { entries ->
                entries
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(LOG_FILE_EXTENSION) }
                    .max(Comparator.comparingLong(::lastModifiedMillis))
                    .orElse(null)
            }
        } catch (_: IOException) {
            null
        }
    }

    fun append(line: String) {
        if (!enabled()) return
        appendToCurrentFile(line)
    }

    /**
     * Best-effort retention: delete logs older than [maxLogAge], then keep only
     * the [maxLogFiles] newest files. Failures never block server startup.
     */
    fun pruneOldLogs() {
        if (!Files.isDirectory(logDir)) return
        try {
            Files.list(logDir).use { entries ->
                val files = entries
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(LOG_FILE_EXTENSION) }
                    .toList()
                    .toMutableList()
                val cutoffMillis = System.currentTimeMillis() - maxLogAge.toMillis()
                files.removeIf { deleteIfOlderThan(it, cutoffMillis) }
                if (files.size > maxLogFiles) {
                    files.sortWith(Comparator.comparingLong(::lastModifiedMillis))
                    val excess = files.size - maxLogFiles
                    for (index in 0 until excess) {
                        deleteQuietly(files[index])
                    }
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun createLogFile(initialContent: String = ""): Path? {
        return try {
            Files.createDirectories(logDir)
            // Millisecond timestamps can collide when a size rotation happens right after the
            // start header; a numeric suffix keeps the name unique in that case.
            val baseName = "opencode-server-${Instant.now().toEpochMilli()}"
            for (attempt in 0..MAX_NAME_COLLISION_RETRIES) {
                val name = if (attempt == 0) baseName else "$baseName-$attempt"
                val file = logDir.resolve(name + LOG_FILE_EXTENSION)
                try {
                    Files.writeString(
                        file,
                        initialContent,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                    return file
                } catch (_: FileAlreadyExistsException) {
                    // try the next suffix
                }
            }
            thisLogger().warn("Failed to create OpenCode server log file: too many name collisions")
            null
        } catch (exception: IOException) {
            thisLogger().warn("Failed to create OpenCode server log file: ${exception.message}")
            null
        }
    }

    private fun appendToCurrentFile(line: String) {
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + System.lineSeparator().length
        val file = synchronized(fileLock) {
            var target = currentLogFile ?: setCurrentFile(createLogFile())
            // A single long-running server process would otherwise grow one file without
            // bound; age/count retention only ever sees whole files.
            if (target != null && currentLogFileBytes + lineBytes > maxLogFileBytes) {
                val rotationHeader = "========== log continues (size limit reached) ==========" + System.lineSeparator()
                // On a failed rotation (e.g. same-millisecond name collision) keep the old
                // file rather than dropping output; the next append retries.
                createLogFile(rotationHeader)?.let { target = setCurrentFile(it) }
            }
            currentLogFileBytes += lineBytes
            target
        } ?: return
        writeLine(file, line)
    }

    /** Must be called under [fileLock]; tracks the byte budget of the active file. */
    private fun setCurrentFile(file: Path?): Path? {
        currentLogFile = file
        currentLogFileBytes = file?.let { runCatching { Files.size(it) }.getOrDefault(0L) } ?: 0L
        return file
    }

    private fun writeLine(file: Path, line: String) {
        try {
            Files.writeString(
                file,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (exception: IOException) {
            thisLogger().warn("Failed to write OpenCode server log file: ${exception.message}")
        }
    }

    private fun deleteIfOlderThan(path: Path, cutoffMillis: Long): Boolean {
        if (lastModifiedMillis(path) < cutoffMillis) {
            deleteQuietly(path)
            return true
        }
        return false
    }

    private fun lastModifiedMillis(path: Path): Long {
        return try {
            Files.getLastModifiedTime(path).toMillis()
        } catch (_: IOException) {
            Long.MAX_VALUE
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val LOG_FILE_EXTENSION = ".log"
        private const val TAIL_MAX_BYTES = 64 * 1024
        private const val DEFAULT_MAX_LOG_FILE_BYTES = 50L * 1024 * 1024
        private const val MAX_NAME_COLLISION_RETRIES = 9

        private fun defaultLogDir(): Path {
            return Path.of(PathManager.getLogPath(), "opencode-web-panel", "server")
        }

        /**
         * Best-effort read of the last [maxLines] lines of [file], limited to the trailing
         * [TAIL_MAX_BYTES] so huge logs never stall the caller. Returns an empty list on any error.
         */
        fun tailLines(file: Path?, maxLines: Int = 30): List<String> {
            if (file == null || !Files.isRegularFile(file)) return emptyList()
            return try {
                val size = Files.size(file)
                val text = if (size <= TAIL_MAX_BYTES) {
                    Files.readString(file, StandardCharsets.UTF_8)
                } else {
                    Files.newByteChannel(file).use { channel ->
                        channel.position(size - TAIL_MAX_BYTES)
                        val buffer = java.nio.ByteBuffer.allocate(TAIL_MAX_BYTES)
                        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                            // keep reading until the tail window is full or EOF
                        }
                        buffer.flip()
                        String(buffer.array(), 0, buffer.limit(), StandardCharsets.UTF_8)
                    }
                }
                text.lines().filter { it.isNotBlank() }.takeLast(maxLines)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
