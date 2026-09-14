package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeSettingsStateTest {

    @Test
    fun runtimeModeDefaultsToHost() {
        assertEquals(OpenCodeRuntimeMode.HOST, OpenCodeSettingsState().runtimeModeValue())
        val settings = OpenCodeSettingsState()
        settings.loadState(OpenCodeSettingsState().apply { runtimeMode = "legacy-value" })
        assertEquals(OpenCodeRuntimeMode.HOST, settings.runtimeModeValue())
    }

    @Test
    fun sbxShareAndKitsDefaultEmpty() {
        val settings = OpenCodeSettingsState()
        assertFalse(settings.sbxShareHostOpencodeConfig)
        assertTrue(settings.sbxEnableIntellijMcp)
        assertEquals("", settings.sbxExtraWorkspaces)
        assertEquals("", settings.sbxExtraKits)
    }

    @Test
    fun sbxMemoryAndCpusDefaultAndSanitize() {
        val settings = OpenCodeSettingsState()
        assertEquals("4g", settings.sbxMemoryValue())
        assertEquals("2", settings.sbxCpusValue())
        settings.loadState(
            OpenCodeSettingsState().apply {
                sbxMemory = "8G"
                sbxCpus = "4"
            },
        )
        assertEquals("8g", settings.sbxMemoryValue())
        assertEquals("4", settings.sbxCpusValue())
        settings.loadState(
            OpenCodeSettingsState().apply {
                sbxMemory = "huge"
                sbxCpus = "99"
            },
        )
        assertEquals("4g", settings.sbxMemoryValue())
        assertEquals("2", settings.sbxCpusValue())
    }

    @Test
    fun sbxExecutablePathUsesSbxByDefault() {
        assertEquals("sbx", OpenCodeSettingsState().sbxExecutablePath())
        val settings = OpenCodeSettingsState().apply {
            sbxBinaryMode = OpenCodeBinaryMode.CUSTOM.name
            sbxBinaryPath = "/opt/homebrew/bin/sbx"
        }
        assertEquals("/opt/homebrew/bin/sbx", settings.sbxExecutablePath())
    }

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
    fun proxyModeUsesIdeByDefault() {
        assertEquals(OpenCodeProxyMode.IDE, OpenCodeSettingsState().proxyModeValue())
    }

    @Test
    fun unknownProxyModeFallsBackToIde() {
        val settings = OpenCodeSettingsState().apply { proxyMode = "legacy-value" }

        assertEquals(OpenCodeProxyMode.IDE, settings.proxyModeValue())
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
    fun hideWebsiteButtonIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().hideWebsiteButton)
    }

    @Test
    fun hideWebsiteButtonLoadsPersistedValue() {
        val settings = OpenCodeSettingsState()

        settings.loadState(OpenCodeSettingsState().apply { hideWebsiteButton = false })

        assertEquals(false, settings.hideWebsiteButton)
    }

    @Test
    fun fasterPathHoverPreviewIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().fasterPathHoverPreview)
    }

    @Test
    fun fasterPathHoverPreviewLoadsPersistedValue() {
        val settings = OpenCodeSettingsState()

        settings.loadState(OpenCodeSettingsState().apply { fasterPathHoverPreview = false })

        assertEquals(false, settings.fasterPathHoverPreview)
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
    fun recoverFailedChunkLoadsIsEnabledByDefault() {
        assertEquals(true, OpenCodeSettingsState().recoverFailedChunkLoads)
    }

    @Test
    fun recoverFailedChunkLoadsLoadsPersistedValue() {
        val settings = OpenCodeSettingsState()

        settings.loadState(OpenCodeSettingsState().apply { recoverFailedChunkLoads = false })

        assertEquals(false, settings.recoverFailedChunkLoads)
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
    fun nativeLocalStorageApiWritesExistingField() {
        val settings = OpenCodeSettingsState()
        settings.setLocalStorageSnapshot(OpenCodeServerBackend.NATIVE_ID, """{"a":1}""")
        assertEquals("""{"a":1}""", settings.openCodeLocalStorageSnapshot)
        assertEquals("""{"a":1}""", settings.localStorageSnapshot())
        assertEquals("""{"a":1}""", settings.localStorageSnapshot(OpenCodeServerBackend.NATIVE_ID))
    }

    @Test
    fun localStorageSnapshotIsSharedAcrossBackends() {
        val settings = OpenCodeSettingsState()
        settings.setLocalStorageSnapshot("sbx:abc", """{"x":1}""")
        assertEquals("""{"x":1}""", settings.openCodeLocalStorageSnapshot)
        assertEquals("""{"x":1}""", settings.localStorageSnapshot())
        assertEquals("""{"x":1}""", settings.localStorageSnapshot("native:other"))
        assertTrue(settings.openCodeLocalStorageSnapshotsByBackend.isEmpty())
        settings.setLocalStorageSnapshot("sbx:abc", "{}")
        assertEquals("{}", settings.localStorageSnapshot())
    }

    @Test
    fun legacyBackendSnapshotsFallBackWhenSharedIsEmpty() {
        val settings = OpenCodeSettingsState()
        settings.loadState(
            OpenCodeSettingsState().apply {
                openCodeLocalStorageSnapshotsByBackend = hashMapOf(
                    OpenCodeServerBackend.NATIVE_ID to """{"leak":true}""",
                    "sbx:bad" to "not-json",
                    "sbx:ok" to """{"ok":true}""",
                )
            },
        )
        assertTrue(settings.openCodeLocalStorageSnapshotsByBackend.isEmpty())
        assertEquals("""{"ok":true}""", settings.openCodeLocalStorageSnapshot)
        assertEquals("""{"ok":true}""", settings.localStorageSnapshot())
        assertEquals("""{"ok":true}""", settings.localStorageSnapshot("sbx:other"))
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

    @Test
    fun defaultProjectSettingsAreNotPersisted() {
        assertNull(OpenCodeProjectSettingsState().getState())
    }

    @Test
    fun importedDefaultPortFlagIsNotPersisted() {
        val settings = OpenCodeProjectSettingsState().apply { portImportedFromApplication = true }
        assertNull(settings.getState())
        settings.loadState(OpenCodeProjectSettingsState().apply { portImportedFromApplication = true })
        assertNull(settings.getState())
    }

    @Test
    fun customProjectDirectoryIsPersisted() {
        val settings = OpenCodeProjectSettingsState().apply {
            projectDirectoryMode = OpenCodeProjectDirectoryMode.CUSTOM.name
            openCodeProjectDirectory = "/tmp/opencode"
        }
        assertSame(settings, settings.getState())
    }

    @Test
    fun nonDefaultProjectPortIsPersisted() {
        val settings = OpenCodeProjectSettingsState().apply {
            portMode = OpenCodePortMode.FIXED.name
            fixedPort = 8181
        }
        assertNotNull(settings.getState())
        assertSame(settings, settings.getState())
    }

    @Test
    fun projectPortArgumentUsesDynamicPortByDefault() {
        assertEquals("0", OpenCodeProjectSettingsState().portArgument())
    }

    @Test
    fun projectPortArgumentUsesSanitizedFixedPort() {
        val settings = OpenCodeProjectSettingsState().apply {
            portMode = OpenCodePortMode.FIXED.name
            fixedPort = 8181
        }

        assertEquals("8181", settings.portArgument())
    }

    @Test
    fun projectInvalidFixedPortFallsBackToDefault() {
        val settings = OpenCodeProjectSettingsState().apply {
            portMode = OpenCodePortMode.FIXED.name
            fixedPort = 99999
        }

        assertEquals(OpenCodeSettingsState.DEFAULT_FIXED_PORT.toString(), settings.portArgument())
    }

    @Test
    fun projectUnknownPortModeFallsBackToAuto() {
        val settings = OpenCodeProjectSettingsState()
        settings.loadState(
            OpenCodeProjectSettingsState().apply {
                portMode = "legacy-value"
                fixedPort = 8181
            },
        )

        assertEquals(OpenCodePortMode.AUTO, settings.portModeValue())
        assertEquals("0", settings.portArgument())
    }
}
