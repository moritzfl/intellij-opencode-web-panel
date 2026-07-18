package de.moritzf.opencodewebpanel.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeSoundSettingsTest {

    @Test
    fun defaultsWhenSnapshotMissing() {
        val settings = parseOpenCodeSoundSettings(null)
        assertTrue(settings.agentEnabled)
        assertTrue(settings.permissionsEnabled)
        assertTrue(settings.errorsEnabled)
        assertEquals(OpenCodeSoundSettings.DEFAULT_AGENT, settings.agent)
        assertEquals(OpenCodeSoundSettings.DEFAULT_PERMISSIONS, settings.permissions)
        assertEquals(OpenCodeSoundSettings.DEFAULT_ERRORS, settings.errors)
    }

    @Test
    fun readsSoundsFromSettingsV3() {
        val snapshot = """
            {
              "settings.v3":"{\"sounds\":{\"agentEnabled\":false,\"agent\":\"alert-03\",\"permissionsEnabled\":true,\"permissions\":\"yup-02\",\"errorsEnabled\":false,\"errors\":\"nope-01\"}}"
            }
        """.trimIndent()
        val settings = parseOpenCodeSoundSettings(snapshot)
        assertFalse(settings.agentEnabled)
        assertEquals("alert-03", settings.agent)
        assertTrue(settings.permissionsEnabled)
        assertEquals("yup-02", settings.permissions)
        assertFalse(settings.errorsEnabled)
        assertEquals("nope-01", settings.errors)
    }

    @Test
    fun unknownSoundIdsFallBackToDefaults() {
        val snapshot = """
            {
              "settings.v3":"{\"sounds\":{\"agent\":\"not-a-real-sound\",\"permissions\":\"\",\"errors\":\"alert-99\"}}"
            }
        """.trimIndent()
        val settings = parseOpenCodeSoundSettings(snapshot)
        assertEquals(OpenCodeSoundSettings.DEFAULT_AGENT, settings.agent)
        assertEquals(OpenCodeSoundSettings.DEFAULT_PERMISSIONS, settings.permissions)
        assertEquals(OpenCodeSoundSettings.DEFAULT_ERRORS, settings.errors)
    }

    @Test
    fun knownSoundIdsCoverOpenCodeCatalog() {
        assertEquals(45, OpenCodeSoundSettings.KNOWN_SOUND_IDS.size)
        assertTrue("staplebops-01" in OpenCodeSoundSettings.KNOWN_SOUND_IDS)
        assertTrue("nope-03" in OpenCodeSoundSettings.KNOWN_SOUND_IDS)
    }

    @Test
    fun bundledWavResourcesExistForEveryKnownId() {
        for (id in OpenCodeSoundSettings.KNOWN_SOUND_IDS) {
            val url = javaClass.getResource("/sounds/$id.wav")
            assertTrue("missing /sounds/$id.wav", url != null)
        }
    }
}
