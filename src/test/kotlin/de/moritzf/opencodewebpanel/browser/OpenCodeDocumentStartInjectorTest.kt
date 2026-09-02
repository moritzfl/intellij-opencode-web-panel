package de.moritzf.opencodewebpanel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeDocumentStartInjectorTest {
    @Test
    fun sourcePayloadJsonEscapesTheScript() {
        val script = "window.x = \"a\nb\";"
        val payload = OpenCodeDocumentStartInjector.sourcePayload(script)
        val parsed = com.google.gson.JsonParser.parseString(payload).asJsonObject
        assertEquals(script, parsed.get("source").asString)
    }

    @Test
    fun parseIdentifierAcceptsBareAndResultWrappedResponses() {
        assertEquals("id-1", OpenCodeDocumentStartInjector.parseIdentifier("""{"identifier":"id-1"}"""))
        assertEquals(
            "id-2",
            OpenCodeDocumentStartInjector.parseIdentifier("""{"result":{"identifier":"id-2"}}"""),
        )
        assertNull(OpenCodeDocumentStartInjector.parseIdentifier("""{"error":{"message":"nope"}}"""))
        assertNull(OpenCodeDocumentStartInjector.parseIdentifier("not-json"))
        assertNull(OpenCodeDocumentStartInjector.parseIdentifier(null))
    }

    @Test
    fun identifierPayloadTargetsTheInstalledScript() {
        assertEquals(
            """{"identifier":"script-3"}""",
            OpenCodeDocumentStartInjector.identifierPayload("script-3"),
        )
    }

    @Test
    fun safeCefBooleanTreatsHasDocumentUnboxFailuresAsFalse() {
        assertTrue(OpenCodeDocumentStartInjector.safeCefBoolean { true })
        assertFalse(OpenCodeDocumentStartInjector.safeCefBoolean { false })
        assertFalse(
            OpenCodeDocumentStartInjector.safeCefBoolean {
                throw NullPointerException(
                    "Cannot invoke \"java.lang.Boolean.booleanValue()\" because the return value of " +
                        "\"com.jetbrains.cef.remote.RpcContext.execObj\" is null",
                )
            },
        )
    }

    @Test
    fun originGuardLimitsDocumentStartScriptToTheOpenCodeServer() {
        val guarded = OpenCodeDocumentStartInjector.guardForOrigin(
            "window.__installed = true;",
            "http://127.0.0.1:4096/",
        )

        assertTrue(guarded.contains("location.origin !== \"http://127.0.0.1:4096\""))
        assertTrue(guarded.contains("window.__installed = true;"))
        assertFalse(guarded.contains("http://127.0.0.1:4096/\""))
        assertEquals("", OpenCodeDocumentStartInjector.guardForOrigin("  ", "http://127.0.0.1:4096"))
    }
}
