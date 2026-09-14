package de.moritzf.opencodewebpanel.settings

import com.intellij.mock.MockProject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.replaceService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

class OpenCodeProjectSettingsStatePersistenceTest {
    companion object {
        @ClassRule @JvmField val application = ApplicationRule()
    }

    @get:Rule val disposable = DisposableRule()

    @Test
    fun getInstanceDoesNotPersistDefaultPortImport() {
        val loaded = getInstance(OpenCodeSettingsState())
        assertTrue(loaded.portImportedFromApplication)
        assertEquals(OpenCodePortMode.AUTO, loaded.portModeValue())
        assertNull(loaded.getState())
    }

    @Test
    fun getInstancePersistsCopiedLegacyAppPort() {
        val appSettings = OpenCodeSettingsState().apply {
            portMode = OpenCodePortMode.FIXED.name
            fixedPort = 49123
        }
        val loaded = getInstance(appSettings)
        assertEquals(OpenCodePortMode.FIXED, loaded.portModeValue())
        assertEquals(49123, loaded.fixedPort)
        assertSame(loaded, loaded.getState())
        assertNotNull(loaded.getState())
    }

    private fun getInstance(appSettings: OpenCodeSettingsState): OpenCodeProjectSettingsState {
        val app = ApplicationManager.getApplication()
        app.replaceService(OpenCodeSettingsState::class.java, appSettings, disposable.disposable)
        val project = MockProject(null, disposable.disposable)
        val state = OpenCodeProjectSettingsState()
        project.registerService(OpenCodeProjectSettingsState::class.java, state)
        return OpenCodeProjectSettingsState.getInstance(project)
    }
}
