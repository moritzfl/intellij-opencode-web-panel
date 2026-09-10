package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxOpencodeConfigOverlayTest {

    @Test
    fun overlayMergesIdeaMcpAndRewritesLoopbackUrls() {
        val overlay = SbxOpencodeConfigOverlay.buildContent(
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
        assertFalse("Host settings must load from their file, not a higher-precedence inline copy", overlay.contains("file-secret"))
        assertFalse(overlay.contains("provider"))
    }

    @Test
    fun overlayIsNullWhenNothingToShare() {
        assertNull(SbxOpencodeConfigOverlay.buildContent(shareHostConfig = false, ideaMcpPort = null))
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
            listOf(extra, shared),
            SbxOpencodeConfigOverlay.withHostConfigShare(listOf(extra, writableSamePath), share = true),
        )
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
                """{"mcp":{"other":{"type":"remote","url":"http://127.0.0.1:1111/sse"}}}""",
            )
            val jsonWins = SbxOpencodeConfigOverlay.buildContent(
                shareHostConfig = true,
                ideaMcpPort = null,
                hostConfigJson = SbxOpencodeConfigOverlay.readHostConfig(
                    jsonPath = root.resolve("opencode.json"),
                    jsoncPath = jsonc,
                ),
            )
            assertTrue(jsonWins!!.contains("host.docker.internal:1111"))
            assertFalse(jsonWins.contains(":9999"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileBasedConfigIsNotReplayedAsInlineOverrides() {
        assertNull(SbxOpencodeConfigOverlay.buildContent(
            shareHostConfig = true,
            ideaMcpPort = null,
            hostConfigJson = """{
                "model": "openai/model",
                "plugin": ["./plugin.ts"],
                "instructions": ["./instructions.md"],
                "mcp": { "local": { "type": "local", "command": ["./server"] } }
            }""",
        ))
    }
}
