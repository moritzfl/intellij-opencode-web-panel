package de.moritzf.opencodewebpanel.server

import com.intellij.execution.process.CapturingProcessHandler
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
}

internal object SbxProcessRunner : SbxCommandRunner {
    override fun run(
        command: List<String>,
        env: Map<String, String>,
        timeoutMillis: Long,
        workingDirectory: Path?,
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
        val output = handler.runProcess(timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(), true)
        if (output.isTimeout || output.isCancelled) {
            return SbxCommandResult(-1, output.stdout + "\nCommand timed out or was cancelled after ${timeoutMillis}ms")
        }
        return SbxCommandResult(output.exitCode, output.stdout)
    }
}
