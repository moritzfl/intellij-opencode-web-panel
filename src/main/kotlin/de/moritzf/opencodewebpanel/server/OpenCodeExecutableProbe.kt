package de.moritzf.opencodewebpanel.server

/**
 * The executable a launch would use and the version it reports. [path] is null when nothing
 * runnable was found; [version] is null when it could not be read. [failed] marks an executable
 * whose version command did not succeed, [tooOld] an sbx below [SbxCli.MINIMUM_SBX_VERSION], and
 * [desktopApp] a macOS app bundle, which is never run.
 */
internal data class OpenCodeExecutableProbe(
    val path: String?,
    val version: String?,
    val tooOld: Boolean = false,
    val failed: Boolean = false,
    val desktopApp: Boolean = false,
)

/** Blocking probes for the settings pages; call off the EDT. */
internal object OpenCodeExecutableProbes {
    private const val TIMEOUT_MILLIS = 10_000L
    private val VERSION = Regex("""(?<![\w.])v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)""")
    private val APP_BUNDLE = Regex("""\.app[/\\]Contents[/\\]MacOS[/\\]""", RegexOption.IGNORE_CASE)

    fun openCode(
        executable: String,
        runner: SbxCommandRunner = SbxProcessRunner,
    ): OpenCodeExecutableProbe {
        val path =
            OpenCodeServerProtocol.detectExecutablePath(executable)
                ?: return OpenCodeExecutableProbe(null, null)
        // `--version` would open the desktop app's window instead of printing a version.
        if (APP_BUNDLE.containsMatchIn(path)) {
            return OpenCodeExecutableProbe(path, null, desktopApp = true)
        }
        val result = runner.run(listOf(path, "--version"), emptyMap(), TIMEOUT_MILLIS)
        if (result.exitCode != 0) return OpenCodeExecutableProbe(path, null, failed = true)
        // OpenCode 2 also logs its version on stderr; stdout carries the answer.
        return OpenCodeExecutableProbe(path, VERSION.find(result.stdout)?.groupValues?.get(1))
    }

    fun sbx(
        executable: String,
        runner: SbxCommandRunner = SbxProcessRunner,
    ): OpenCodeExecutableProbe {
        val path =
            OpenCodeServerProtocol.detectExecutablePath(executable)
                ?: return OpenCodeExecutableProbe(null, null)
        val result = runner.run(SbxCli.buildVersionCommand(path), emptyMap(), TIMEOUT_MILLIS)
        if (result.exitCode != 0) return OpenCodeExecutableProbe(path, null, failed = true)
        return OpenCodeExecutableProbe(
            path,
            SbxCli.parseSbxVersion(result.stdout)?.joinToString("."),
            tooOld = SbxCli.unsupportedSbxVersion(result.stdout) != null,
        )
    }
}
