package de.moritzf.opencodewebpanel.server

internal data class SbxCreateSettingStatus(
    val label: String,
    val desired: String,
    val installed: String?,
) {
    val pending: Boolean get() = installed != null && installed != desired
}

internal object SbxInstalledVmStatus {
    fun settings(spec: SbxLaunchSpec, record: SbxSandboxRecord?): List<SbxCreateSettingStatus> {
        val snapshot = record?.let { SbxCli.parseCreateSnapshot(it.createSnapshot) }
        val persistHost = SbxCli.sandboxPersistDataHome(record?.name ?: spec.name)
        val persistInstalled = record?.let { SbxCli.recordHasPersistMount(it, persistHost) }
        return listOf(
            SbxCreateSettingStatus("Memory", spec.memory, snapshot?.memory),
            SbxCreateSettingStatus("CPUs", spec.cpus, snapshot?.cpus),
            SbxCreateSettingStatus(
                "Protect sandbox files",
                yesNo(spec.protectSandboxFiles),
                snapshot?.let { yesNo(it.protectSandboxFiles) },
            ),
            SbxCreateSettingStatus(
                "Persist sandbox sessions",
                yesNo(spec.persistSandboxSessions),
                persistInstalled?.let { yesNo(it) },
            ),
        )
    }

    fun hint(statuses: List<SbxCreateSettingStatus>, adopted: Boolean = false): String? {
        if (adopted) {
            return "Adopted sandbox: create-time settings are unknown until Reset Sandbox."
        }
        val pending = statuses.filter { it.pending }
        if (pending.isEmpty()) return null
        return "Pending until Reset Sandbox: " + pending.joinToString("; ") { status ->
            "${status.label} (installed ${status.installed}, desired ${status.desired})"
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "on" else "off"
}
