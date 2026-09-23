package de.moritzf.opencodewebpanel.server

internal enum class SbxOwnership {
    NONE,
    OWNED,
    ADOPTED,
    FOREIGN,
}

internal data class SbxDiagnosticsSnapshot(
    val name: String,
    val shortId: String?,
    val ownership: SbxOwnership,
    val serverUrl: String?,
    val version: String?,
    val persistPath: String?,
    val persistActive: Boolean,
) {
    fun format(): String {
        val id = shortId?.takeIf { it.isNotBlank() }?.let { " (${it.take(8)})" }.orEmpty()
        val own = when (ownership) {
            SbxOwnership.NONE -> "no VM"
            SbxOwnership.OWNED -> "owned"
            SbxOwnership.ADOPTED -> "adopted"
            SbxOwnership.FOREIGN -> "foreign"
        }
        val persist = when {
            persistActive && !persistPath.isNullOrBlank() -> "sessions: $persistPath"
            persistPath != null -> "sessions: VM-local (persist store is mounted at Reset)"
            else -> "sessions: VM-local"
        }
        val url = serverUrl?.takeIf { it.isNotBlank() }?.let { "url: $it" } ?: "url: —"
        val ver = version?.takeIf { it.isNotBlank() }?.let { "OpenCode $it" } ?: "OpenCode version unknown"
        return "Sandbox $name$id · $own · $url · $ver · $persist"
    }

    companion object {
        fun from(
            specName: String,
            record: SbxSandboxRecord?,
            foreign: Boolean,
            serverUrl: String?,
            version: String?,
            persistEnabled: Boolean = true,
        ): SbxDiagnosticsSnapshot {
            val storePath = SbxCli.sandboxPersistDataHome(record?.name ?: specName)
            val persistActive = record != null && SbxCli.recordHasPersistMount(record, storePath)
            // A VM created with the store keeps it; with persistence off and no store it is VM-local.
            val persistPath = storePath.takeIf { persistEnabled || persistActive }
            val ownership = when {
                record == null && foreign -> SbxOwnership.FOREIGN
                record == null -> SbxOwnership.NONE
                record.adopted -> SbxOwnership.ADOPTED
                else -> SbxOwnership.OWNED
            }
            return SbxDiagnosticsSnapshot(
                name = record?.name ?: specName,
                shortId = record?.sandboxId,
                ownership = ownership,
                serverUrl = serverUrl,
                version = version,
                persistPath = persistPath,
                persistActive = persistActive,
            )
        }
    }
}
