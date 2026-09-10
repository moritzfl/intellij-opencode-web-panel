package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal object SbxOpencodeConfigOverlay {
    const val XDG_CONFIG_HOME_ENV = "XDG_CONFIG_HOME"

    fun hostConfigDir(): Path {
        val base = System.getenv(XDG_CONFIG_HOME_ENV)?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".config").toString()
        return Path.of(base, "opencode").toAbsolutePath().normalize()
    }

    fun hostConfigPath(): Path = hostConfigDir().resolve("opencode.json")

    fun hostDataDir(): Path {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".local", "share").toString()
        return Path.of(base, "opencode").toAbsolutePath().normalize()
    }

    fun hostAuthJsonPath(): Path = hostDataDir().resolve("auth.json")

    fun hostConfigShareMount(
        exists: (Path) -> Boolean = { Files.isDirectory(it) },
    ): SbxExtraMount? {
        val host = hostConfigDir()
        if (!exists(host)) return null
        val hostStr = runCatching { host.toAbsolutePath().normalize().toString() }.getOrNull() ?: return null
        // sbx mounts the host path as-is, read-only (`path:ro`). Do not symlink over
        // the sandbox agent's managed config directory.
        return SbxExtraMount(hostStr, hostStr, readOnly = true)
    }

    fun withHostConfigShare(mounts: List<SbxExtraMount>, share: Boolean): List<SbxExtraMount> {
        if (!share) return mounts
        val extra = hostConfigShareMount() ?: return mounts
        val others = mounts.filterNot {
            OpenCodeServerProtocol.isSameFilesystemPath(it.hostPath, extra.hostPath)
        }
        return others + extra
    }

    fun readHostAuthJson(): String? {
        val path = hostAuthJsonPath()
        if (!Files.isRegularFile(path)) return null
        val text = runCatching { Files.readString(path) }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return parseObject(text)?.toString()
    }

    fun buildContent(
        shareHostConfig: Boolean,
        ideaMcpPort: Int?,
        hostConfigJson: String? = if (shareHostConfig) readHostConfig() else null,
    ): String? {
        if (!shareHostConfig && ideaMcpPort == null) return null
        val root = JsonObject()
        if (shareHostConfig) {
            val hostMcp = parseObject(hostConfigJson)?.get("mcp")?.takeIf { it.isJsonObject }?.asJsonObject
            hostMcp?.entrySet()?.forEach { (name, value) ->
                val server = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val url = server.stringMember("url") ?: return@forEach
                val rewritten = rewriteLoopbackUrl(url) ?: return@forEach
                val mcp = root.getAsJsonObject("mcp") ?: JsonObject().also { root.add("mcp", it) }
                mcp.add(name, server.deepCopy().apply { addProperty("url", rewritten) })
            }
        }
        if (ideaMcpPort != null) {
            val mcp = root.get("mcp")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { root.add("mcp", it) }
            mcp.add("idea", ideaMcpObject(ideaMcpPort))
        }
        return root.takeUnless { it.size() == 0 }?.toString()
    }

    fun rewriteLoopbackUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "127.0.0.1" && host != "localhost" && host != "::1") return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "http://host.docker.internal$port$path$query"
    }

    private fun readHostConfig(): String? {
        val path = hostConfigPath()
        if (!Files.isRegularFile(path)) return null
        return runCatching { Files.readString(path) }.getOrNull()
    }

    private fun parseObject(json: String?): JsonObject? {
        if (json.isNullOrBlank()) return null
        return runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun ideaMcpObject(port: Int): JsonObject {
        return JsonObject().apply {
            addProperty("type", "remote")
            addProperty("url", "http://host.docker.internal:$port/sse")
            addProperty("enabled", true)
        }
    }

}
