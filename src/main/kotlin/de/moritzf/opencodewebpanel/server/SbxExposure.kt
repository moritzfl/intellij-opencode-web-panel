package de.moritzf.opencodewebpanel.server

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * What a project spec lets its sandbox reach beyond the project tree. A committed
 * `opencode-sbx.yaml` comes from whoever last pushed it, so creating (or extending) a VM from
 * a spec with such grants needs one explicit acknowledgement per distinct set of grants.
 */
internal data class SbxExposure(
    val items: List<String>,
    val fingerprint: String,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        fun of(
            spec: SbxLaunchSpec,
            workspace: String,
            hostHome: String = System.getProperty("user.home").orEmpty(),
            hostConfigDir: Path = SbxOpencodeConfigOverlay.hostConfigDir(),
        ): SbxExposure {
            val items = ArrayList<String>()
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(item: String, extra: String = "") {
                items += item
                digest.update(item.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                digest.update(extra.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
            SbxCli.resolveExtraMounts(spec.extraMounts, workspace, hostHome).forEach { mount ->
                if (isInside(workspace, mount.hostPath)) return@forEach
                add(
                    if (mount.readOnly) "Host path mounted read-only: ${mount.hostPath}"
                    else "Host path mounted read-write: ${mount.hostPath}",
                )
            }
            if (spec.shareHostOpencodeConfig) {
                add("Host OpenCode config shared read-only: ${SbxCli.posixPath(hostConfigDir.toString())}")
            }
            spec.kits.forEach { ref ->
                // Kit YAML can grant network access and run setup in the VM. A local kit's
                // content is part of the grant, so editing it asks again before it is applied.
                add("Kit: $ref", localKitDigest(ref, workspace, hostHome))
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            return SbxExposure(items, if (items.isEmpty()) "" else hex)
        }

        private fun isInside(workspace: String, path: String): Boolean {
            val root = SbxCli.posixPath(workspace).trimEnd('/')
            val candidate = SbxCli.posixPath(path).trimEnd('/')
            if (OpenCodeServerProtocol.isSameFilesystemPath(root, candidate)) return true
            val rootKey = OpenCodeServerProtocol.filesystemPathKey(root) ?: root
            val candidateKey = OpenCodeServerProtocol.filesystemPathKey(candidate) ?: candidate
            return candidateKey.startsWith("$rootKey/")
        }

        private fun localKitDigest(ref: String, workspace: String, hostHome: String): String {
            if (!SbxCli.isLocalKitRef(ref)) return ""
            return runCatching {
                val base = Path.of(workspace).resolve(SbxCli.expandUserHome(ref, hostHome)).normalize()
                val files = if (Files.isDirectory(base)) {
                    Files.walk(base, 4).use { stream ->
                        stream.filter { Files.isRegularFile(it) }.sorted().toList()
                    }
                } else if (Files.isRegularFile(base)) {
                    listOf(base)
                } else {
                    emptyList()
                }
                val digest = MessageDigest.getInstance("SHA-256")
                files.take(MAX_KIT_FILES).forEach { file ->
                    digest.update(base.relativize(file).toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(0)
                    digest.update(Files.readAllBytes(file))
                    digest.update(0)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrDefault("unreadable")
        }

        private const val MAX_KIT_FILES = 256
    }
}
