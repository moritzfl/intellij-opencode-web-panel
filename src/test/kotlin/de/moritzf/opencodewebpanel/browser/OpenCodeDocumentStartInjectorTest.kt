package de.moritzf.opencodewebpanel.browser

import org.junit.Assert.assertEquals
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
}
