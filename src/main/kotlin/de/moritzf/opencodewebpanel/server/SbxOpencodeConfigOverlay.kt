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
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "127.0.0.1" && host != "localhost" && host != "::1") return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://host.docker.internal$port$path$query"
    }

    internal fun readHostConfig(
        jsonPath: Path = hostConfigPath(),
        jsoncPath: Path = hostConfigJsoncPath(),
    ): String? {
        jsonPath.takeIf { Files.isRegularFile(it) }?.let { path ->
            runCatching { Files.readString(path) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        jsoncPath.takeIf { Files.isRegularFile(it) }?.let { path ->
            runCatching { Files.readString(path) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return stripJsonc(it) }
        }
        return null
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

    private fun parseObject(json: String?): JsonObject? {
        if (json.isNullOrBlank()) return null
        return runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?: runCatching { JsonParser.parseString(stripJsonc(json)) }.getOrNull()
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
