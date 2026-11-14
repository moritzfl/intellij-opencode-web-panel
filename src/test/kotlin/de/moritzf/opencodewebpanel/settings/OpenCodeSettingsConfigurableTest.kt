package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.replaceService
import de.moritzf.opencodewebpanel.configuration.OpenCodeBinaryMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import javax.swing.AbstractButton
import javax.swing.SwingUtilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCodeSettingsConfigurableTest {
    companion object {
        @ClassRule @JvmField val application = ApplicationRule()
    }

    @get:Rule val disposable = DisposableRule()
    @get:Rule val temp = TemporaryFolder()

    private val settings = OpenCodeSettingsState()
    private lateinit var configurable: OpenCodeSettingsConfigurable

    @Before
    fun setUp() {
        ApplicationManager.getApplication()
            .replaceService(OpenCodeSettingsState::class.java, settings, disposable.disposable)
    }

    @After
    fun tearDown() {
        if (::configurable.isInitialized) {
            SwingUtilities.invokeAndWait { configurable.disposeUIResources() }
        }
    }

    private fun open() {
        configurable = OpenCodeSettingsConfigurable()
        configurable.createComponent()
    }

    @Test
    fun applyRejectsANewCustomPathThatIsNotAnExecutable() {
        SwingUtilities.invokeAndWait {
            open()
            field<AbstractButton>("customBinaryRadioButton").isSelected = true
            field<TextFieldWithBrowseButton>("binaryPathField").text =
                temp.root.resolve("missing").absolutePath
            val error = assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertTrue(
                error.messageHtml.toString(),
                error.messageHtml.toString().contains("not found"),
            )
            assertEquals(OpenCodeBinaryMode.AUTO, settings.binaryModeValue())
        }
    }

    @Test
    fun anUnchangedSavedPathIsNotRevalidated() {
        settings.binaryMode = OpenCodeBinaryMode.CUSTOM.name
        settings.binaryPath = temp.root.resolve("uninstalled").absolutePath
        SwingUtilities.invokeAndWait {
            open()
            // A zoom change must not fail because an untouched saved path is gone.
            call("validateChangedExecutables", settings)
        }
    }

    @Test
    fun customPathStartsFromTheDetectedOneAndAutoDropsTheUntouchedPrefill() {
        SwingUtilities.invokeAndWait {
            open()
            set("detectedOpenCodePath", "/detected/opencode")
            field<AbstractButton>("customBinaryRadioButton").isSelected = true
            assertEquals(
                "/detected/opencode",
                field<TextFieldWithBrowseButton>("binaryPathField").text,
            )
            assertTrue(configurable.isModified)
            field<AbstractButton>("autoBinaryRadioButton").isSelected = true
            assertEquals("", field<TextFieldWithBrowseButton>("binaryPathField").text)
            assertFalse("Toggling back changes nothing", configurable.isModified)
        }
    }

    @Test
    fun resetNeverPrefillsAndAStrayAutoPathChangesNoLaunch() {
        settings.binaryMode = OpenCodeBinaryMode.CUSTOM.name
        SwingUtilities.invokeAndWait {
            open()
            set("detectedOpenCodePath", "/detected/opencode")
            configurable.reset()
            assertEquals("", field<TextFieldWithBrowseButton>("binaryPathField").text)
            assertFalse(configurable.isModified)
            field<AbstractButton>("autoBinaryRadioButton").isSelected = true
            field<TextFieldWithBrowseButton>("binaryPathField").text = "/typed/but/unused"
            assertFalse(
                "Auto detect ignores the disabled field",
                call("hostLaunchChanged", OpenCodeSettingsState().apply { binaryMode = "AUTO" })
                    as Boolean,
            )
        }
    }

    @Test
    fun restoreDefaultsResetsOnlyThePanelTab() {
        settings.forceCompactLayout = false
        settings.warnForeignSession = true
        settings.enableServerLogs = false
        settings.uiZoomPercent = 150
        SwingUtilities.invokeAndWait {
            open()
            configurable.javaClass
                .getDeclaredMethod("restorePanelDefaults")
                .apply { isAccessible = true }
                .invoke(configurable)
            assertTrue(field<AbstractButton>("forceCompactLayoutCheckBox").isSelected)
            assertFalse(
                "Off by default",
                field<AbstractButton>("warnForeignSessionCheckBox").isSelected,
            )
            assertEquals(100, field<javax.swing.JSpinner>("uiZoomSpinner").value)
            assertFalse(
                "Server tab options keep their value",
                field<AbstractButton>("enableServerLogsCheckBox").isSelected,
            )
            assertTrue("Restored values wait for Apply", configurable.isModified)
        }
    }

    @Test
    fun everyCheckBoxSitsOnTheTabItsBindingNames() {
        SwingUtilities.invokeAndWait {
            open()
            val tabs = field<javax.swing.JTabbedPane>("panel")
            val bindings = field<List<Any>>("checkBoxSettingBindings")
            assertTrue(bindings.isNotEmpty())
            for (binding in bindings) {
                val checkBox = binding.javaClass.getMethod("getCheckBox").invoke(binding)
                val tab = binding.javaClass.getMethod("getTab").invoke(binding)
                tab as OpenCodeSettingsTab
                checkBox as javax.swing.JCheckBox
                assertTrue(
                    "${checkBox.text} is not on the ${tab.title} tab",
                    SwingUtilities.isDescendingFrom(checkBox, tabs.getComponentAt(tab.ordinal)),
                )
            }
            assertEquals(
                OpenCodeSettingsTab.entries.map { it.title },
                (0 until tabs.tabCount).map { tabs.getTitleAt(it) },
            )
        }
    }

    @Test
    fun linksCanOpenATabBeforeAndAfterThePageExists() {
        SwingUtilities.invokeAndWait {
            configurable = OpenCodeSettingsConfigurable()
            configurable.selectTab(OpenCodeSettingsTab.DOCKER_SANDBOXES)
            configurable.createComponent()
            val tabs = field<javax.swing.JTabbedPane>("panel")
            assertEquals(OpenCodeSettingsTab.DOCKER_SANDBOXES.ordinal, tabs.selectedIndex)
            configurable.selectTab(OpenCodeSettingsTab.PANEL)
            assertEquals(OpenCodeSettingsTab.PANEL.ordinal, tabs.selectedIndex)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        OpenCodeSettingsConfigurable::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(configurable) as T

    private fun set(name: String, value: Any?) {
        OpenCodeSettingsConfigurable::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(configurable, value)
    }

    private fun call(name: String, settings: OpenCodeSettingsState): Any? =
        OpenCodeSettingsConfigurable::class
            .java
            .getDeclaredMethod(name, OpenCodeSettingsState::class.java)
            .apply { isAccessible = true }
            .invoke(configurable, settings)
}
