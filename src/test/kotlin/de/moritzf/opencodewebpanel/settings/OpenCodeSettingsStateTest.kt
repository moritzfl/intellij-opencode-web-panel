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
    fun openMostRecentConversationOnStartupIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().openMostRecentConversationOnStartup)
    }

    @Test
    fun uiZoomPercentUsesDefaultValue() {
        assertEquals(OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT, OpenCodeSettingsState().uiZoomPercent)
    }

    @Test
    fun uiZoomPercentIsSanitizedWhenLoadingState() {
        val settings = OpenCodeSettingsState()

        settings.loadState(OpenCodeSettingsState().apply { uiZoomPercent = 500 })

        assertEquals(OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT, settings.uiZoomPercent)
    }

    @Test
    fun openFileLinksInIdeIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().openFileLinksInIde)
    }

    @Test
    fun openExternalLinksInBrowserIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().openExternalLinksInBrowser)
    }

    @Test
    fun chatFileDropIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().enableChatFileDrop)
    }

    @Test
    fun forceCompactLayoutIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().forceCompactLayout)
    }

    @Test
    fun syncThemeWithIdeIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().syncThemeWithIde)
    }

    @Test
    fun syncThemeWithIdeLoadsPersistedValue() {
        val settings = OpenCodeSettingsState()

        settings.loadState(OpenCodeSettingsState().apply { syncThemeWithIde = false })

        assertEquals(false, settings.syncThemeWithIde)
    }

    @Test
    fun enableCodeNavigationIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().enableCodeNavigation)
    }

    @Test
    fun codeNavigationIsEffectiveOnlyWhenIdeNavigationIsEnabled() {
        val settings = OpenCodeSettingsState().apply {
            openFileLinksInIde = false
            enableCodeNavigation = true
        }

        assertEquals(false, settings.effectiveCodeNavigationEnabled())

        settings.openFileLinksInIde = true
        assertEquals(true, settings.effectiveCodeNavigationEnabled())

        settings.enableCodeNavigation = false
        assertEquals(false, settings.effectiveCodeNavigationEnabled())
    }

    @Test
    fun suppressProjectSwitchPromptsIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().suppressProjectSwitchPrompts)
    }

    @Test
    fun systemNotificationsAreEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().enableSystemNotifications)
    }

    @Test
    fun waitForIntellijMcpServerIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().waitForIntellijMcpServer)
    }

    @Test
    fun waitForIntellijMcpServerLoadsPersistedValue() {
        val settings = OpenCodeSettingsState()

        settings.loadState(OpenCodeSettingsState().apply { waitForIntellijMcpServer = false })

        assertEquals(false, settings.waitForIntellijMcpServer)
    }

    @Test
    fun openCodeLocalStorageSnapshotUsesEmptyObjectByDefault() {
        assertEquals("{}", OpenCodeSettingsState().openCodeLocalStorageSnapshot)
    }

    @Test
    fun invalidOpenCodeLocalStorageSnapshotFallsBackToEmptyObject() {
        val settings = OpenCodeSettingsState()

        settings.loadState(OpenCodeSettingsState().apply { openCodeLocalStorageSnapshot = "not-json" })

        assertEquals("{}", settings.openCodeLocalStorageSnapshot)
    }

    @Test
    fun projectSettingsUseIdeProjectRootByDefault() {
        assertEquals(OpenCodeProjectDirectoryMode.AUTO, OpenCodeProjectSettingsState().projectDirectoryModeValue())
        assertEquals("/tmp/project", OpenCodeProjectSettingsState().effectiveProjectDirectory("/tmp/project"))
    }

    @Test
    fun projectSettingsOverrideIdeProjectRoot() {
        val settings = OpenCodeProjectSettingsState().apply {
            projectDirectoryMode = OpenCodeProjectDirectoryMode.CUSTOM.name
            openCodeProjectDirectory = "/tmp/opencode-project"
        }

        assertEquals("/tmp/opencode-project", settings.effectiveProjectDirectory("/tmp/project"))
    }

    @Test
    fun projectSettingsSanitizeConfiguredDirectory() {
        val settings = OpenCodeProjectSettingsState()

        settings.loadState(
            OpenCodeProjectSettingsState().apply {
                projectDirectoryMode = OpenCodeProjectDirectoryMode.CUSTOM.name
                openCodeProjectDirectory = "  /tmp/opencode-project  "
            },
        )

        assertEquals(OpenCodeProjectDirectoryMode.CUSTOM, settings.projectDirectoryModeValue())
        assertEquals("/tmp/opencode-project", settings.openCodeProjectDirectory)
    }

    @Test
    fun projectSettingsStoreSystemIndependentDirectory() {
        assertEquals(
            "C:/Users/Alice/project",
            OpenCodeProjectSettingsState.sanitizeProjectDirectory("  C:\\Users\\Alice\\project  "),
        )
    }

    @Test
    fun projectSettingsUnknownModeFallsBackToAuto() {
        val settings = OpenCodeProjectSettingsState()

        settings.loadState(OpenCodeProjectSettingsState().apply { projectDirectoryMode = "legacy-value" })

        assertEquals(OpenCodeProjectDirectoryMode.AUTO, settings.projectDirectoryModeValue())
    }
}
