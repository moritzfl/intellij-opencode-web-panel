package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Native Wayland clipboard source, adapted from @nvandamme's PR #16. Never reads arbitrary MIME
 * data as text.
 */
internal object OpenCodeWaylandClipboard {
    sealed interface Result {
        data class Text(val value: String) : Result

        data object Unavailable : Result

        data object TooLarge : Result
    }

    fun isWaylandSession(
        isLinux: Boolean = SystemInfo.isLinux,
        display: String? = System.getenv("WAYLAND_DISPLAY"),
    ): Boolean = isLinux && !display.isNullOrBlank()

    fun read(): Result = read { command, limit -> run(command, limit) }

    internal fun read(run: (List<String>, Int) -> Result): Result {
        val offered =
            run(listOf("wl-paste", "--list-types"), 16_384) as? Result.Text
                ?: return Result.Unavailable
        val types =
            offered.value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        // File/image copies often advertise incidental text too. Keep those on the existing
        // AWT attachment path, rather than turning a filename/caption into a chat message.
        if (
            types.any {
                it.startsWith("image/", ignoreCase = true) ||
                    it.lowercase() in
                        setOf(
                            "text/uri-list",
                            "x-special/gnome-copied-files",
                            "application/x-qt-image",
                        )
            }
        )
            return Result.Unavailable
        val type =
            types.firstOrNull { UTF8_PLAIN.matches(it) }
                ?: types.firstOrNull { it.equals("text/plain", ignoreCase = true) }
                ?: types.firstOrNull { it == "UTF8_STRING" }
                ?: return Result.Unavailable
        // wl-clipboard matches full MIME names exactly, including charset parameters.
        return run(
            listOf("wl-paste", "--no-newline", "--type", type),
            OpenCodeClipboardText.MAX_CHARS,
        )
    }

    /** Drain stdout while the process runs. Discard stderr, never log clipboard contents. */
    internal fun run(command: List<String>, maxChars: Int, timeoutMillis: Long = 2_000): Result {
        val process =
            try {
                ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            } catch (_: IOException) {
                return Result.Unavailable
            }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val output =
            AppExecutorUtil.getAppExecutorService().submit<Result> {
                process.inputStream.reader(Charsets.UTF_8).use { reader ->
                    val text = StringBuilder()
                    val chunk = CharArray(8_192)
                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) break
                        if (text.length + count > maxChars) return@submit Result.TooLarge
                        text.append(chunk, 0, count)
                    }
                    Result.Text(text.toString())
                }
            }
        try {
            process.outputStream.close()
            val result = output.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (result === Result.TooLarge) return result
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            return if (process.waitFor(remaining, TimeUnit.NANOSECONDS) && process.exitValue() == 0)
                result
            else Result.Unavailable
        } catch (_: TimeoutException) {
            return Result.Unavailable
        } catch (_: ExecutionException) {
            return Result.Unavailable
        } catch (_: IOException) {
            return Result.Unavailable
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.Unavailable
        } finally {
            // wl-paste can fork cat; stop both so a timed-out owner cannot retain the pipe.
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            if (process.isAlive) process.destroyForcibly()
            output.cancel(true)
        }
    }

    private val UTF8_PLAIN =
        Regex("""text/plain\s*;\s*charset\s*=\s*"?utf-8"?""", RegexOption.IGNORE_CASE)
}
