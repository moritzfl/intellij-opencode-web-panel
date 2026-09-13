package de.moritzf.opencodewebpanel.server

internal object OpenCodeHostPaths {
    fun pathHome(backendId: String): String? {
        return if (OpenCodeServerBackend.isNative(backendId)) {
            System.getProperty("user.home")
        } else {
            SbxCli.SANDBOX_HOME
        }
    }

    fun guestToHostPrefixes(backendId: String, projectDirectory: String?): List<Pair<String, String>> {
        val dir = projectDirectory?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (OpenCodeServerBackend.isNative(backendId)) return emptyList()
        val spec = (SbxLaunchSpec.inspect(dir) as? SbxLaunchSpecInspection.Valid)?.spec
        val extra = spec?.let { SbxCli.resolveExtraMounts(it.extraMounts, dir) }.orEmpty()
        val persist = spec?.takeIf { it.persistSandboxSessions }?.let { SbxCli.sandboxPersistDataHome(it.name) }
        return SbxCli.guestToHostPathMappings(dir, extra, persist)
    }
}
