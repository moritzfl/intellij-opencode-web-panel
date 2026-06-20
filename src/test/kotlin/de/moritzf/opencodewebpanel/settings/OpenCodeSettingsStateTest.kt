package de.moritzf.opencodewebpanel.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeSettingsStateTest {

    @Test
    fun portArgumentUsesDynamicPortByDefault() {
        assertEquals("0", OpenCodeSettingsState().portArgument())
    }

    @Test
    fun portArgumentUsesSanitizedFixedPort() {
        val settings = OpenCodeSettingsState().apply {
            portMode = OpenCodePortMode.FIXED.name
            fixedPort = 8181
        }

        assertEquals("8181", settings.portArgument())
    }

    @Test
    fun invalidFixedPortFallsBackToDefault() {
        val settings = OpenCodeSettingsState().apply {
            portMode = OpenCodePortMode.FIXED.name
            fixedPort = 99999
        }

        assertEquals(OpenCodeSettingsState.DEFAULT_FIXED_PORT.toString(), settings.portArgument())
    }

    @Test
    fun unknownPortModeFallsBackToAuto() {
        val settings = OpenCodeSettingsState().apply {
            portMode = "legacy-value"
            fixedPort = 8181
        }

        assertEquals(OpenCodePortMode.AUTO, settings.portModeValue())
        assertEquals("0", settings.portArgument())
    }

    @Test
    fun executablePathUsesOpencodeByDefault() {
        assertEquals("opencode", OpenCodeSettingsState().executablePath())
    }

    @Test
    fun executablePathUsesCustomPathWhenConfigured() {
        val settings = OpenCodeSettingsState().apply {
            binaryMode = OpenCodeBinaryMode.CUSTOM.name
            binaryPath = "/custom/bin/opencode"
        }

        assertEquals("/custom/bin/opencode", settings.executablePath())
    }

    @Test
    fun customBinaryWithoutPathFallsBackToOpencode() {
        val settings = OpenCodeSettingsState().apply {
            binaryMode = OpenCodeBinaryMode.CUSTOM.name
            binaryPath = ""
        }

        assertEquals("opencode", settings.executablePath())
    }

    @Test
    fun unknownBinaryModeFallsBackToAuto() {
        val settings = OpenCodeSettingsState().apply {
            binaryMode = "legacy-value"
            binaryPath = "/custom/bin/opencode"
        }

        assertEquals(OpenCodeBinaryMode.AUTO, settings.binaryModeValue())
        assertEquals("opencode", settings.executablePath())
    }

    @Test
    fun openMostRecentConversationOnStartupIsDisabledByDefault() {
        assertEquals(false, OpenCodeSettingsState().openMostRecentConversationOnStartup)
    }
}
