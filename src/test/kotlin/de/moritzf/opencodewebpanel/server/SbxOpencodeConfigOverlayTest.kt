package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxOpencodeConfigOverlayTest {
    @Test
    fun v1OverlayUsesFlatMcpWithoutProviderOverrides() {
        val overlay = com.google.gson.JsonParser.parseString(SbxOpencodeConfigOverlay.buildContent(
            version = SbxOpenCodeVersion.V1,
            shareHostConfig = true,
            ideaMcpPort = 64342,
            hostConfigJson = """{"mcp":{"other":{"type":"remote","url":"http://[::1]:9999/sse"}}}""",
        )).asJsonObject
        assertFalse(overlay.has("providers"))
        val mcp = overlay.getAsJsonObject("mcp")
        assertFalse(mcp.has("servers"))
        assertEquals("http://host.docker.internal:64342/sse", mcp.getAsJsonObject("idea").get("url").asString)
        assertEquals("http://host.docker.internal:9999/sse", mcp.getAsJsonObject("other").get("url").asString)
        assertFalse(overlay.has("provider"))
        assertNull(SbxOpencodeConfigOverlay.buildContent(SbxOpenCodeVersion.V1, shareHostConfig = false, ideaMcpPort = null))
    }

    @Test
    fun overlayMergesIdeaMcpAndRewritesLoopbackUrls() {
        val overlay = SbxOpencodeConfigOverlay.buildContent(
            version = SbxOpenCodeVersion.V2,
            shareHostConfig = true,
            ideaMcpPort = 64342,
            hostConfigJson = """
                {
                  "provider": { "groq": { "apiKey": "file-secret", "options": { "timeout": 1 } } },
                  "mcp": { "other": { "type": "remote", "url": "http://127.0.0.1:9999/sse" } }
                }
            """.trimIndent(),
        )
        assertTrue(overlay!!.contains("host.docker.internal:64342"))
        assertTrue(overlay.contains("host.docker.internal:9999"))
        assertFalse("Legacy MCP documents must remain legacy for OpenCode's migration", overlay.contains("\"servers\""))
        assertFalse("Host settings must load from their file, not a higher-precedence inline copy", overlay.contains("file-secret"))
        assertFalse(overlay.contains("\"provider\":"))
        assertFalse(overlay.contains("\"providers\":"))
    }

    @Test
    fun v2KeepsLegacyTimeoutEnabledAndOAuthTogether() {
        val overlay = com.google.gson.JsonParser.parseString(SbxOpencodeConfigOverlay.buildContent(
            version = SbxOpenCodeVersion.V2,
            shareHostConfig = true,
            ideaMcpPort = 64342,
            hostConfigJson = """{"mcp":{"other":{"type":"remote","url":"http://localhost:9999/sse","enabled":false,"timeout":1000,"oauth":{"clientId":"example"}}}}""",
        )).asJsonObject.getAsJsonObject("mcp")
        assertFalse(overlay.has("servers"))
        val other = overlay.getAsJsonObject("other")
        assertFalse(other.get("enabled").asBoolean)
        assertEquals(1000, other.get("timeout").asInt)
        assertEquals("example", other.getAsJsonObject("oauth").get("clientId").asString)
        assertTrue(overlay.getAsJsonObject("idea").get("enabled").asBoolean)
    }

    @Test
    fun v2KeepsNativeMcpFieldsAndGeneratesNativeIdeaEntry() {
        val overlay = com.google.gson.JsonParser.parseString(SbxOpencodeConfigOverlay.buildContent(
            version = SbxOpenCodeVersion.V2,
            shareHostConfig = true,
            ideaMcpPort = 64342,
            hostConfigJson = """{"mcp":{"servers":{"other":{"type":"remote","url":"http://localhost:9999/mcp","disabled":true,"timeout":{"startup":1000},"oauth":{"client_id":"example"}}}}}""",
        )).asJsonObject.getAsJsonObject("mcp").getAsJsonObject("servers")
        val other = overlay.getAsJsonObject("other")
        assertEquals("http://host.docker.internal:9999/mcp", other.get("url").asString)
        assertTrue(other.get("disabled").asBoolean)
        assertEquals(1000, other.getAsJsonObject("timeout").get("startup").asInt)
        assertEquals("example", other.getAsJsonObject("oauth").get("client_id").asString)
        assertFalse(overlay.getAsJsonObject("idea").has("enabled"))
        assertFalse(overlay.getAsJsonObject("idea").get("disabled").asBoolean)
    }

    @Test
    fun v2IdeaOnlyUsesNativeSchema() {
        val overlay = com.google.gson.JsonParser.parseString(SbxOpencodeConfigOverlay.buildContent(
            version = SbxOpenCodeVersion.V2, shareHostConfig = false, ideaMcpPort = 64342,
        )).asJsonObject.getAsJsonObject("mcp").getAsJsonObject("servers").getAsJsonObject("idea")
        assertEquals("remote", overlay.get("type").asString)
        assertFalse(overlay.has("enabled"))
        assertFalse(overlay.get("disabled").asBoolean)
    }

    @Test
    fun v2WithoutMcpNeedsNoOverlay() {
        assertNull(SbxOpencodeConfigOverlay.buildContent(SbxOpenCodeVersion.V2, shareHostConfig = false, ideaMcpPort = null))
    }

    @Test
    fun hostConfigShareMountsTheConfigDirectory() {
        val extra = SbxExtraMount("/tmp/docs", "/home/agent/docs")
        assertEquals(listOf(extra), SbxOpencodeConfigOverlay.withHostConfigShare(listOf(extra), share = false))
        val shared = SbxOpencodeConfigOverlay.hostConfigShareMount { true }!!
        assertEquals(SbxOpencodeConfigOverlay.hostConfigDir().toString(), shared.hostPath)
        assertEquals("Do not replace the sandbox agent's managed config directory", shared.hostPath, shared.sandboxPath)
        assertTrue("Host OpenCode config must be a read-only extra workspace", shared.readOnly)
        assertFalse(SbxCli.needsSandboxLink(shared))
        val writableSamePath = SbxExtraMount(shared.hostPath, shared.sandboxPath)
        assertEquals(
            listOf(extra, writableSamePath),
            SbxOpencodeConfigOverlay.withHostConfigShare(listOf(extra, writableSamePath), share = true, exists = { false }),
        )
        assertEquals(
            listOf(extra, shared),
            SbxOpencodeConfigOverlay.withHostConfigShare(listOf(extra, writableSamePath), share = true, exists = { true }),
        )
    }

    @Test
    fun stripJsoncRemovesTrailingCommas() {
        val jsonc = """
            {
              // loopback MCP
              "mcp": { "other": { "type": "remote", "url": "https://127.0.0.1:9443/mcp", }, },
            }
        """.trimIndent()
        val overlay = SbxOpencodeConfigOverlay.buildContent(
            version = SbxOpenCodeVersion.V2,
            shareHostConfig = true,
            ideaMcpPort = null,
            hostConfigJson = SbxOpencodeConfigOverlay.stripJsonc(jsonc),
        )
        assertTrue(overlay!!.contains("https://host.docker.internal:9443/mcp"))
    }

    @Test
    fun rewriteLoopbackUrlKeepsHttps() {
        assertEquals(
            "https://host.docker.internal:9443/mcp",
            SbxOpencodeConfigOverlay.rewriteLoopbackUrl("https://localhost:9443/mcp"),
        )
        assertEquals(
            "http://host.docker.internal:9999/sse",
            SbxOpencodeConfigOverlay.rewriteLoopbackUrl("http://127.0.0.1:9999/sse"),
        )
        assertNull(SbxOpencodeConfigOverlay.rewriteLoopbackUrl("ftp://localhost/mcp"))
        assertEquals(
            "http://user:tok@host.docker.internal:8080/mcp?a=1#frag",
            SbxOpencodeConfigOverlay.rewriteLoopbackUrl("http://user:tok@localhost:8080/mcp?a=1#frag"),
        )
    }

    @Test
    fun readHostConfigPrefersJsonThenJsonc() {
        val root = java.nio.file.Files.createTempDirectory("opencode-overlay-config")
        try {
            val jsonc = root.resolve("opencode.jsonc")
            java.nio.file.Files.writeString(
                jsonc,
                """
                {
                  // loopback MCP
                  "mcp": { "other": { "type": "remote", "url": "http://127.0.0.1:9999/sse" } }
                }
                """.trimIndent(),
            )
            val overlay = SbxOpencodeConfigOverlay.buildContent(
                version = SbxOpenCodeVersion.V2,
                shareHostConfig = true,
                ideaMcpPort = null,
                hostConfigJson = SbxOpencodeConfigOverlay.readHostConfig(
                    jsonPath = root.resolve("missing.json"),
                    jsoncPath = jsonc,
                ),
            )
            assertTrue(overlay!!.contains("host.docker.internal:9999"))
            java.nio.file.Files.writeString(
                root.resolve("opencode.json"),
                """{"${'$'}schema":"https://opencode.ai/config.json","mcp":{"other":{"type":"remote","url":"http://127.0.0.1:1111/sse"}}}""",
            )
            java.nio.file.Files.writeString(
                root.resolve("config.json"),
                """{"mcp":{"legacy":{"type":"remote","url":"http://localhost:2222/mcp"}}}""",
            )
            // OpenCode merges config.json, opencode.json, opencode.jsonc; later files win.
            val merged = SbxOpencodeConfigOverlay.buildContent(
                version = SbxOpenCodeVersion.V2,
                shareHostConfig = true,
                ideaMcpPort = null,
                hostConfigJson = SbxOpencodeConfigOverlay.readHostConfig(
                    jsonPath = root.resolve("opencode.json"),
                    jsoncPath = jsonc,
                    legacyPath = root.resolve("config.json"),
                ),
            )
            assertTrue(merged!!.contains("host.docker.internal:9999"))
            assertTrue(merged.contains("host.docker.internal:2222"))
            assertFalse(merged.contains(":1111"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileBasedConfigIsNotReplayedAsInlineOverrides() {
        val overlay = SbxOpencodeConfigOverlay.buildContent(
            version = SbxOpenCodeVersion.V2,
            shareHostConfig = true,
            ideaMcpPort = null,
            hostConfigJson = """{
                "model": "openai/model",
                "plugin": ["./plugin.ts"],
                "instructions": ["./instructions.md"],
                "mcp": { "local": { "type": "local", "command": ["./server"] } }
            }""",
        )
        assertNull(overlay)
    }

    @Test
    fun providerSettingsDoNotGenerateAnOverlay() {
        val overlay = SbxOpencodeConfigOverlay.buildContent(
            version = SbxOpenCodeVersion.V2,
            shareHostConfig = true,
            ideaMcpPort = null,
            hostConfigJson = """{"providers":{"xai":{"settings":{"transport":"websocket"},"models":{"future-model":{}}}}}""",
        )
        assertNull(overlay)
    }
}
