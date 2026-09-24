package de.moritzf.opencodewebpanel.server

internal object OpenCodeHostPaths {
    fun pathHome(backendId: String): String? {
        return if (OpenCodeServerBackend.isNative(backendId)) {
            System.getProperty("user.home")
        } else {
            SbxCli.SANDBOX_HOME
        }
    }

    fun serverDirectory(backendId: String, hostDirectory: String?): String? {
        val directory = hostDirectory?.takeIf { it.isNotBlank() } ?: return null
        return if (OpenCodeServerBackend.isNative(backendId)) directory else SbxCli.guestBindPath(directory)
    }

    fun guestToHostPrefixes(
        backendId: String,
        projectDirectory: String?,
        mountedWorkspace: String? = projectDirectory,
    ): List<Pair<String, String>> {
        val dir = mountedWorkspace?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (OpenCodeServerBackend.isNative(backendId)) return emptyList()
        val spec = (SbxLaunchSpec.inspect(dir) as? SbxLaunchSpecInspection.Valid)?.spec
        val extra = spec?.let { SbxCli.resolveExtraMounts(it.extraMounts, dir) }.orEmpty()
        val persist = spec?.takeIf { it.persistSandboxSessions }?.let { SbxCli.sandboxPersistDataHome(it.name) }
        return SbxCli.guestToHostPathMappings(dir, extra, persist)
    }
}
