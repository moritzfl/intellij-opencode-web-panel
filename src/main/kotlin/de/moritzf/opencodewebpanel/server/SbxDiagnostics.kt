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
    /** Label and value per line, for a key/value block the user can read and copy. */
    fun rows(): List<Pair<String, String>> {
        val id = shortId?.takeIf { it.isNotBlank() }?.let { " (${it.take(8)})" }.orEmpty()
        val own =
            when (ownership) {
                SbxOwnership.NONE -> "not created yet"
                SbxOwnership.OWNED -> "created by the plugin"
                SbxOwnership.ADOPTED -> "adopted"
                SbxOwnership.FOREIGN -> "another sandbox uses this name or folder"
            }
        val conversations =
            when {
                persistActive && !persistPath.isNullOrBlank() ->
                    "kept on this computer: $persistPath"
                persistPath != null -> "inside the VM; kept on this computer after Reset Sandbox"
                else -> "inside the VM only"
            }
        return listOf(
            "Sandbox" to "$name$id, $own",
            "Server" to (serverUrl?.takeIf { it.isNotBlank() } ?: "—"),
            "OpenCode" to (version?.takeIf { it.isNotBlank() } ?: "version unknown"),
            "Conversations" to conversations,
        )
    }

    fun format(): String = rows().joinToString("\n") { (label, value) -> "$label: $value" }

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
            // A VM created with the store keeps it; with persistence off and no store it is
            // VM-local.
            val persistPath = storePath.takeIf { persistEnabled || persistActive }
            val ownership =
                when {
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
