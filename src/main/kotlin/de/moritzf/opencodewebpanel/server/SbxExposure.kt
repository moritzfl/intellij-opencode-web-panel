package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.progress.ProgressManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

        internal fun localKitDigest(
            ref: String,
            workspace: String,
            hostHome: String,
            maxEntries: Int = 10_000,
            maxBytes: Long = 64L * 1024 * 1024,
        ): String {
            if (!SbxCli.isLocalKitRef(ref)) return ""
            val base = Path.of(workspace).resolve(SbxCli.expandUserHome(ref, hostHome)).normalize()
            val files = ArrayList<Path>()
            // Follow links because sbx can consume their targets. Cycles, missing targets and
            // unreadable inputs must fail, never produce a reusable partial consent digest.
            Files.walk(base, FileVisitOption.FOLLOW_LINKS).use { stream ->
                val iterator = stream.iterator()
                var entries = 0
                while (iterator.hasNext()) {
                    ProgressManager.checkCanceled()
                    if (++entries > maxEntries) throw IOException("Kit $ref exceeds $maxEntries entries; cannot verify consent.")
                    val path = iterator.next()
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                    when {
                        attributes.isRegularFile -> files.add(path)
                        attributes.isDirectory -> Unit
                        else -> throw IOException("Unsupported kit input: $path")
                    }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytes = 0L
            files.sorted().forEach { file ->
                digest.update(base.relativize(file).toString().toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                // Hash each file separately so embedded delimiters cannot alias file boundaries.
                val content = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(file).use { input ->
                    while (true) {
                        ProgressManager.checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > maxBytes) throw IOException("Kit $ref exceeds $maxBytes bytes; cannot verify consent.")
                        content.update(buffer, 0, count)
                    }
                }
                digest.update(content.digest())
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
