package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Comparator
import java.util.UUID

internal class OpenCodeServerLogBuffer(
    private val maxLines: Int = 500,
    private val logDir: Path = defaultLogDir(),
    private val maxLogFiles: Int = 20,
    private val maxLogAge: Duration = Duration.ofDays(7),
) {
    private val lines = Collections.synchronizedList(mutableListOf<String>())
    private val fileLock = Any()
    private var currentLogFile: Path? = null

    fun startNewFile(): Path? {
        return synchronized(fileLock) {
            pruneOldLogs()
            currentLogFile = createLogFile()
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

    fun text(): String {
        return synchronized(lines) {
            lines.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
        }
    }

    fun append(line: String) {
        synchronized(lines) {
            if (lines.size >= maxLines) {
                lines.removeAt(0)
            }
            lines.add(line)
        }
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

    private fun createLogFile(): Path? {
        return try {
            Files.createDirectories(logDir)
            val file = logDir.resolve("opencode-server-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}$LOG_FILE_EXTENSION")
            Files.writeString(
                file,
                "",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            file
        } catch (exception: IOException) {
            thisLogger().warn("Failed to create OpenCode server log file: ${exception.message}")
            null
        }
    }

    private fun appendToCurrentFile(line: String) {
        val file = synchronized(fileLock) {
            currentLogFile ?: createLogFile()?.also { currentLogFile = it }
        } ?: return
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

        private fun defaultLogDir(): Path {
            return Path.of(PathManager.getLogPath(), "opencode-web-panel", "server")
        }
    }
}
