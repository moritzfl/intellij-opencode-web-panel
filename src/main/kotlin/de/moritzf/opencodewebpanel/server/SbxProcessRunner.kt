package de.moritzf.opencodewebpanel.server

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Path

internal data class SbxCommandResult(
    val exitCode: Int,
    val output: String,
)

internal fun interface SbxCommandRunner {
    fun run(command: List<String>, env: Map<String, String>, timeoutMillis: Long, workingDirectory: Path?): SbxCommandResult

    fun run(command: List<String>, env: Map<String, String>, timeoutMillis: Long): SbxCommandResult =
        run(command, env, timeoutMillis, null)

    fun run(
        command: List<String>,
        env: Map<String, String>,
        timeoutMillis: Long,
        workingDirectory: Path?,
        onOutputLine: (String) -> Unit,
    ): SbxCommandResult {
        val result = run(command, env, timeoutMillis, workingDirectory)
        emitCapturedOutputLines(result.output, onOutputLine)
        return result
    }
}

internal object SbxProcessRunner : SbxCommandRunner {
    override fun run(
        command: List<String>,
        env: Map<String, String>,
        timeoutMillis: Long,
        workingDirectory: Path?,
    ): SbxCommandResult = run(command, env, timeoutMillis, workingDirectory, onOutputLine = {})

    override fun run(
        command: List<String>,
        env: Map<String, String>,
        timeoutMillis: Long,
        workingDirectory: Path?,
        onOutputLine: (String) -> Unit,
    ): SbxCommandResult {
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        workingDirectory?.let { processBuilder.directory(it.toFile()) }
        processBuilder.environment()["PATH"] = OpenCodeServerProtocol.resolvePath()
        env.forEach { (key, value) -> processBuilder.environment()[key] = value }
        val process = try {
            processBuilder.start()
        } catch (error: IOException) {
            return SbxCommandResult(-1, error.message ?: error::class.java.simpleName)
        }
        // Drain the pipe concurrently: reading to EOF before waitFor makes the timeout ineffective.
        // Do not pass argv/env as the handler's diagnostic command line (they may contain secrets).
        val handler = CapturingProcessHandler(process, Charset.defaultCharset(), command.first())
        val pending = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType === ProcessOutputTypes.SYSTEM) return
                splitProcessOutputLines(pending, event.text.orEmpty(), onOutputLine)
            }
        })
        val output = handler.runProcess(timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(), true)
        flushProcessOutputLines(pending, onOutputLine)
        if (output.isTimeout || output.isCancelled) {
            return SbxCommandResult(-1, output.stdout + "\nCommand timed out or was cancelled after ${timeoutMillis}ms")
        }
        return SbxCommandResult(output.exitCode, output.stdout)
    }
}

internal fun emitCapturedOutputLines(output: String, onOutputLine: (String) -> Unit) {
    val pending = StringBuilder()
    splitProcessOutputLines(pending, output, onOutputLine)
    flushProcessOutputLines(pending, onOutputLine)
}

internal fun splitProcessOutputLines(pending: StringBuilder, chunk: String, onOutputLine: (String) -> Unit) {
    pending.append(chunk)
    while (true) {
        val range = indexOfCliLineBreak(pending) ?: return
        emitSanitizedCliLine(pending.substring(0, range.first), onOutputLine)
        pending.delete(0, range.last + 1)
    }
}

internal fun flushProcessOutputLines(pending: StringBuilder, onOutputLine: (String) -> Unit) {
    val raw = pending.toString()
    pending.setLength(0)
    emitSanitizedCliLine(raw, onOutputLine)
}

internal fun sanitizeCliOutputLine(raw: String): String {
    var text = ANSI_SEQUENCE.replace(raw, "")
    text = ORPHAN_CSI.replace(text, "")
    text = text.replace("\u0008", "").replace("\u007F", "")
    text = WHITESPACE.replace(text, " ").trim()
    if (text.isEmpty() || text.matches(SPINNER_ONLY)) return ""
    return text
}

private fun emitSanitizedCliLine(raw: String, onOutputLine: (String) -> Unit) {
    val line = sanitizeCliOutputLine(raw)
    if (line.isNotEmpty()) onOutputLine(line)
}

/** `\n` / `\r` plus in-place progress CSI (cursor-back / erase), which CLIs use instead of CR. */
internal fun indexOfCliLineBreak(text: CharSequence): IntRange? {
    var i = 0
    while (i < text.length) {
        when (val c = text[i]) {
            '\n' -> return i until i + 1
            '\r' -> {
                val end = if (i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                return i until end
            }
            '\u001B' -> {
                if (i + 1 >= text.length) return null
                if (text[i + 1] != '[') {
                    i++
                    continue
                }
                val final = indexOfCsiFinalByte(text, i + 2) ?: return null
                val end = final + 1
                if (text[final] in PROGRESS_CSI_FINALS) return i until end
                i = end
            }
            else -> i++
        }
    }
    return null
}

private fun indexOfCsiFinalByte(text: CharSequence, from: Int): Int? {
    var i = from
    while (i < text.length) {
        val c = text[i]
        if (c in '@'..'~') return i
        if (i - from > 24) return null
        i++
    }
    return null
}

private const val PROGRESS_CSI_FINALS = "DJKGH"
private val ANSI_SEQUENCE = Regex(
    "\u001B\\[[\\d;?=]*[ -/]*[@-~]|" +
        "\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\u001B\\\\)|" +
        "\u001B[()].|" +
        "\u001B[@-Z\\\\-_]",
)
private val ORPHAN_CSI = Regex("\\[(?:\\?\\d+[lh]|\\d+D|\\d*[JK](?![A-Za-z]))")
private val SPINNER_ONLY = Regex("^[|/\\\\-]+\$")
private val WHITESPACE = Regex("\\s+")
