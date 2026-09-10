package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "OpenCodeWebPanelSbxSandboxes",
    storages = [Storage("opencode-web-panel-sbx.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
internal class SbxSandboxRecordStore : PersistentStateComponent<SbxSandboxRecordStore> {
    var records: MutableMap<String, SbxSandboxRecordBean> = HashMap()
    private val lock = Any()

    override fun getState(): SbxSandboxRecordStore {
        val snapshot = SbxSandboxRecordStore()
        synchronized(lock) {
            snapshot.records = HashMap()
            records.forEach { (key, value) -> snapshot.records[key] = value.copy() }
        }
        return snapshot
    }

    override fun loadState(state: SbxSandboxRecordStore) {
        synchronized(lock) {
            records = HashMap()
            state.records.forEach { (key, value) ->
                val directory = key.trim()
                if (directory.isEmpty()) return@forEach
                val bean = value.takeIf { it.sandboxId.isNotBlank() && it.name.isNotBlank() } ?: return@forEach
                records[directory] = bean.copy()
            }
        }
    }

    fun recordFor(canonicalDirectory: String): SbxSandboxRecord? {
        val key = OpenCodeServerProtocol.filesystemPathKey(canonicalDirectory) ?: canonicalDirectory
        return synchronized(lock) { records[key]?.toRecord() }
    }

    fun save(canonicalDirectory: String, record: SbxSandboxRecord) {
        val key = OpenCodeServerProtocol.filesystemPathKey(canonicalDirectory) ?: canonicalDirectory
        synchronized(lock) { records[key] = SbxSandboxRecordBean.from(record) }
    }

    fun remove(canonicalDirectory: String) {
        val key = OpenCodeServerProtocol.filesystemPathKey(canonicalDirectory) ?: canonicalDirectory
        synchronized(lock) { records.remove(key) }
    }

    companion object {
        fun getInstance(): SbxSandboxRecordStore {
            return ApplicationManager.getApplication().getService(SbxSandboxRecordStore::class.java)
        }
    }
}

internal class SbxSandboxRecordBean {
    var sandboxId: String = ""
    var name: String = ""
    var agent: String = ""
    var workspace: String = ""
    var kits: String = ""
    var shareHostConfig: Boolean = false
    var hostPort: Int = 0
    var createSnapshot: String = ""
    var adopted: Boolean = false

    fun copy(): SbxSandboxRecordBean {
        return SbxSandboxRecordBean().also {
            it.sandboxId = sandboxId
            it.name = name
            it.agent = agent
            it.workspace = workspace
            it.kits = kits
            it.shareHostConfig = shareHostConfig
            it.hostPort = hostPort
            it.createSnapshot = createSnapshot
            it.adopted = adopted
        }
    }

    fun toRecord(): SbxSandboxRecord? {
        if (sandboxId.isBlank() || name.isBlank() || workspace.isBlank()) return null
        return SbxSandboxRecord(
            sandboxId = sandboxId,
            name = name,
            agent = agent.ifBlank { SbxCli.AGENT },
            workspace = workspace,
            kits = SbxCli.normalizeLineList(kits),
            shareHostConfig = shareHostConfig,
            hostPort = hostPort.takeIf { it in 1..65535 },
            createSnapshot = createSnapshot,
            adopted = adopted,
        )
    }

    companion object {
        fun from(record: SbxSandboxRecord): SbxSandboxRecordBean {
            return SbxSandboxRecordBean().also {
                it.sandboxId = record.sandboxId
                it.name = record.name
                it.agent = record.agent
                it.workspace = record.workspace
                it.kits = record.kits
                it.shareHostConfig = record.shareHostConfig
                it.hostPort = record.hostPort ?: 0
                it.createSnapshot = record.createSnapshot
                it.adopted = record.adopted
            }
        }
    }
}
