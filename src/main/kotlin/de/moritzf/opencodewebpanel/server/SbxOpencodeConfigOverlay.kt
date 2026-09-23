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

    fun hostConfigJsoncPath(): Path = hostConfigDir().resolve("opencode.jsonc")

    fun hostLegacyConfigPath(): Path = hostConfigDir().resolve("config.json")

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

    fun withHostConfigShare(
        mounts: List<SbxExtraMount>,
        share: Boolean,
        exists: (Path) -> Boolean = { Files.isDirectory(it) },
    ): List<SbxExtraMount> {
        if (!share) return mounts
        val extra = hostConfigShareMount(exists) ?: return mounts
        val others = mounts.filterNot {
            OpenCodeServerProtocol.isSameFilesystemPath(it.hostPath, extra.hostPath)
        }
        return others + extra
    }

    fun buildContent(
        version: SbxOpenCodeVersion,
        shareHostConfig: Boolean,
        ideaMcpPort: Int?,
        hostConfigJson: String? = if (shareHostConfig) readHostConfig() else null,
    ): String? {
        val root = JsonObject()
        val hostMcp = if (shareHostConfig) {
            parseObject(hostConfigJson)?.get("mcp")?.takeIf { it.isJsonObject }?.asJsonObject
        } else null
        val nested = hostMcp?.get("servers")?.takeIf { it.isJsonObject }?.asJsonObject
        // Preserve the source schema, including enabled/disabled, timeout and OAuth fields.
        // OpenCode 2 migrates legacy documents itself; wrapping legacy entries in mcp.servers
        // creates an invalid hybrid that it silently discards.
        val nativeV2 = if (hostMcp != null) nested != null else version == SbxOpenCodeVersion.V2
        (nested ?: hostMcp)?.entrySet()?.forEach { (name, value) ->
            val server = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val rewritten = server.stringMember("url")?.let(::rewriteLoopbackUrl) ?: return@forEach
            mcpServersObject(root, nativeV2).add(name, server.deepCopy().apply { addProperty("url", rewritten) })
        }
        if (ideaMcpPort != null) {
            val servers = mcpServersObject(root, nativeV2)
            servers.add("idea", ideaMcpObject(ideaMcpPort, nativeV2))
        }
        return root.takeUnless { it.size() == 0 }?.toString()
    }

    fun rewriteLoopbackUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return null
        if (host != "127.0.0.1" && host != "localhost" && host != "::1") return null
        val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://${userInfo}host.docker.internal$port$path$query$fragment"
    }

    /**
     * The host global config as OpenCode sees it: `config.json`, then `opencode.json`, then
     * `opencode.jsonc`, deep-merged with later files winning (OpenCode's own load order).
     */
    internal fun readHostConfig(
        jsonPath: Path = hostConfigPath(),
        jsoncPath: Path = hostConfigJsoncPath(),
        legacyPath: Path = hostLegacyConfigPath(),
    ): String? {
        var merged: JsonObject? = null
        for (path in listOf(legacyPath, jsonPath, jsoncPath)) {
            if (!Files.isRegularFile(path)) continue
            val text = runCatching { Files.readString(path) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val parsed = parseObject(text) ?: continue
            merged = merged?.let { deepMerge(it, parsed) } ?: parsed
        }
        return merged?.toString()
    }

    private fun deepMerge(target: JsonObject, source: JsonObject): JsonObject {
        val result = target.deepCopy()
        for ((key, value) in source.entrySet()) {
            val existing = result.get(key)
            if (existing != null && existing.isJsonObject && value.isJsonObject) {
                result.add(key, deepMerge(existing.asJsonObject, value.asJsonObject))
            } else {
                result.add(key, value.deepCopy())
            }
        }
        return result
    }

    internal fun stripJsonc(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        var inString = false
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            if (inString) {
                out.append(char)
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                index++
                continue
            }
            if (char == '"') {
                inString = true
                out.append(char)
                index++
                continue
            }
            if (char == '/' && index + 1 < text.length) {
                when (text[index + 1]) {
                    '/' -> {
                        index += 2
                        while (index < text.length && text[index] != '\n') index++
                        continue
                    }
                    '*' -> {
                        index += 2
                        while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) index++
                        index = (index + 2).coerceAtMost(text.length)
                        continue
                    }
                }
            }
            out.append(char)
            index++
        }
        return stripTrailingCommas(out.toString())
    }

    private fun stripTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        var inString = false
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            if (inString) {
                out.append(char)
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                index++
                continue
            }
            if (char == '"') {
                inString = true
                out.append(char)
                index++
                continue
            }
            if (char == ',') {
                var look = index + 1
                while (look < text.length && text[look].isWhitespace()) look++
                if (look < text.length && (text[look] == '}' || text[look] == ']')) {
                    index++
                    continue
                }
            }
            out.append(char)
            index++
        }
        return out.toString()
    }

    private fun mcpServersObject(root: JsonObject, nativeV2: Boolean): JsonObject {
        val mcp = objectChild(root, "mcp")
        return if (nativeV2) objectChild(mcp, "servers") else mcp
    }

    private fun objectChild(parent: JsonObject, name: String): JsonObject {
        val existing = parent.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
        if (existing != null) return existing
        return JsonObject().also { parent.add(name, it) }
    }

    private fun parseObject(json: String?): JsonObject? {
        if (json.isNullOrBlank()) return null
        return runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?: runCatching { JsonParser.parseString(stripJsonc(json)) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun ideaMcpObject(port: Int, nativeV2: Boolean): JsonObject {
        return JsonObject().apply {
            addProperty("type", "remote")
            addProperty("url", "http://host.docker.internal:$port/sse")
            if (nativeV2) addProperty("disabled", false) else addProperty("enabled", true)
        }
    }

}
