package de.moritzf.opencodewebpanel.server

/**
 * The executable a launch would use and the version it reports. [path] is null when nothing
 * runnable was found; [version] is null when it could not be read. [tooOld] marks an sbx below
 * [SbxCli.MINIMUM_SBX_VERSION].
 */
internal data class OpenCodeExecutableProbe(
    val path: String?,
    val version: String?,
    val tooOld: Boolean = false,
)

/** Blocking probes for the settings pages; call off the EDT. */
internal object OpenCodeExecutableProbes {
    private const val TIMEOUT_MILLIS = 10_000L
    private val VERSION = Regex("""(?<![\w.])v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)""")

    fun openCode(
        executable: String,
        runner: SbxCommandRunner = SbxProcessRunner,
    ): OpenCodeExecutableProbe {
        val path =
            OpenCodeServerProtocol.detectExecutablePath(executable)
                ?: return OpenCodeExecutableProbe(null, null)
        val result = runner.run(listOf(path, "--version"), emptyMap(), TIMEOUT_MILLIS)
        // OpenCode 2 also logs its version on stderr; stdout carries the answer.
        val version =
            if (result.exitCode == 0) VERSION.find(result.stdout)?.groupValues?.get(1) else null
        return OpenCodeExecutableProbe(path, version)
    }

    fun sbx(
        executable: String,
        runner: SbxCommandRunner = SbxProcessRunner,
    ): OpenCodeExecutableProbe {
        val path =
            OpenCodeServerProtocol.detectExecutablePath(executable)
                ?: return OpenCodeExecutableProbe(null, null)
        val result = runner.run(SbxCli.buildVersionCommand(path), emptyMap(), TIMEOUT_MILLIS)
        if (result.exitCode != 0) return OpenCodeExecutableProbe(path, null)
        return OpenCodeExecutableProbe(
            path,
            SbxCli.parseSbxVersion(result.stdout)?.joinToString("."),
            tooOld = SbxCli.unsupportedSbxVersion(result.stdout) != null,
        )
    }
}
