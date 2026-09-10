package de.moritzf.opencodewebpanel.settings

import com.intellij.mock.MockProject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.ui.table.TableView
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.SbxExtraMount
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import javax.swing.AbstractButton
import javax.swing.JTextField
import javax.swing.SwingUtilities

class OpenCodeProjectSettingsConfigurableTest {
    companion object {
        @ClassRule @JvmField val application = ApplicationRule()
    }

    @get:Rule val disposable = DisposableRule()
    @get:Rule val temp = TemporaryFolder()

    private val appSettings = OpenCodeSettingsState()
    private val projectSettings = OpenCodeProjectSettingsState().apply { portImportedFromApplication = true }
    private val registry = OpenCodeServerBackendRegistry()
    private lateinit var project: MockProject
    private lateinit var configurable: OpenCodeProjectSettingsConfigurable
    private var restarts = 0
    private var reloads = 0

    @Before
    fun setUp() {
        val app = ApplicationManager.getApplication()
        app.replaceService(OpenCodeSettingsState::class.java, appSettings, disposable.disposable)
        app.replaceService(OpenCodeServerBackendRegistry::class.java, registry, disposable.disposable)
        app.replaceService(SbxSandboxRecordStore::class.java, SbxSandboxRecordStore(), disposable.disposable)
        project = object : MockProject(null, disposable.disposable) {
            override fun getBasePath(): String = temp.root.toPath().toRealPath().toString()
        }
        project.registerService(OpenCodeProjectSettingsState::class.java, projectSettings)
        project.messageBus.connect(disposable.disposable).subscribe(
            OpenCodeProjectSettingsListener.TOPIC,
            object : OpenCodeProjectSettingsListener {
                override fun serverRestartRequested() { restarts++ }
                override fun serverReloadRequested() { reloads++ }
            },
        )
        SwingUtilities.invokeAndWait {
            configurable = OpenCodeProjectSettingsConfigurable(project)
            configurable.reset()
        }
    }

    @After
    fun tearDown() {
        if (::configurable.isInitialized) SwingUtilities.invokeAndWait { configurable.disposeUIResources() }
        registry.dispose()
    }

    @Test
    fun runtimeSwitchStopsPreviousBackendAndPublishesRestartInBothDirections() {
        SwingUtilities.invokeAndWait {
            val native = registry.backendFor(project) as SharedOpenCodeServerManager
            native.setServerRunning(true) // No actual process or server is launched by this test.
            field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
            field<JTextField>("sbxMemoryField").text = "8g"
            field<JTextField>("sbxCpusField").text = "4"
            field<AbstractButton>("sbxShareHostConfigCheckBox").isSelected = true
            field<AbstractButton>("sbxEnableIntellijMcpCheckBox").isSelected = false
            field<AbstractButton>("fixedPortRadioButton").isSelected = true
            field<JTextField>("fixedPortField").text = "49123"
            configurable.apply()
            assertEquals(OpenCodeServerLifecycleState.STOPPED, native.getLifecycleState())
            PlatformTestUtil.waitWithEventsDispatching("Missing Host to Sandbox restart", { restarts == 1 }, 5)
            val sandbox = registry.backendFor(project)
            val expected = SbxLaunchSpec.load(project.basePath)!!
            assertEquals("8g", expected.memory)
            assertEquals("4", expected.cpus)
            assertEquals(49123, expected.hostPort)
            assertTrue(expected.shareHostOpencodeConfig)
            assertFalse(expected.enableIntellijMcp)
            assertTrue(expected.useSandbox)
            assertFalse(configurable.isModified())
            SbxLaunchSpec.persist(appSettings, project.basePath!!)
            assertEquals(expected, SbxLaunchSpec.load(project.basePath))

            field<AbstractButton>("hostRuntimeRadioButton").isSelected = true
            configurable.apply()
            PlatformTestUtil.waitWithEventsDispatching("Missing Sandbox to Host restart", { restarts == 2 }, 5)
            assertSame(native, registry.backendFor(project))
            assertSame("Switching back must retain sandbox ownership/backend", sandbox, registry.backend(sandbox.backendId))
            assertEquals(expected.copy(useSandbox = false), SbxLaunchSpec.load(project.basePath))
            assertFalse(configurable.isModified())
        }
    }

    @Test
    fun sandboxPortChangeDoesNotStopOrRestart() {
        SwingUtilities.invokeAndWait {
            field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
            configurable.apply()
            PlatformTestUtil.waitWithEventsDispatching("Missing Host to Sandbox restart", { restarts == 1 }, 5)
            assertEquals(0, reloads)
            field<AbstractButton>("fixedPortRadioButton").isSelected = true
            field<JTextField>("fixedPortField").text = "49123"
            configurable.apply()
            assertEquals("LIVE port remap must not stop the VM or restart serve", 1, restarts)
            assertEquals(0, reloads)
            assertEquals(49123, SbxLaunchSpec.load(project.basePath)!!.hostPort)
            assertTrue(registry.backendFor(project) is de.moritzf.opencodewebpanel.server.SbxOpenCodeServerBackend)
        }
    }

    @Test
    fun sharingFlagSurvivesApplyStartupAndReopeningSettings() {
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            for (shared in listOf(true, false, true)) {
                field<AbstractButton>("sbxShareHostConfigCheckBox").isSelected = shared
                assertTrue(configurable.isModified())
                configurable.apply()
                assertEquals(shared, SbxLaunchSpec.load(project.basePath)!!.shareHostOpencodeConfig)
                SbxLaunchSpec.persist(appSettings, project.basePath!!)
                configurable.disposeUIResources()
                configurable = OpenCodeProjectSettingsConfigurable(project)
                configurable.createComponent()
                assertEquals(shared, field<AbstractButton>("sbxShareHostConfigCheckBox").isSelected)
                assertFalse(configurable.isModified())
            }
        }
    }

    @Test
    fun failedSaveDoesNotChangeSettingsOrStopPreviousServer() {
        val specPath = SbxLaunchSpec.projectSpecPath(temp.root.toPath().toString())
        SwingUtilities.invokeAndWait {
            val native = registry.backendFor(project) as SharedOpenCodeServerManager
            native.setServerRunning(true)
            Files.createDirectories(specPath.parent)
            Files.writeString(specPath, "not a spec")
            field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
            field<AbstractButton>("fixedPortRadioButton").isSelected = true
            field<JTextField>("fixedPortField").text = "49123"
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals("not a spec", Files.readString(specPath))
            assertEquals(OpenCodePortMode.AUTO, projectSettings.portModeValue())
            assertEquals(OpenCodeServerLifecycleState.RUNNING, native.getLifecycleState())
            assertEquals(0, restarts)
        }
    }

    @Test
    fun invalidFixedPortIsRejectedOnApply() {
        SwingUtilities.invokeAndWait {
            field<AbstractButton>("fixedPortRadioButton").isSelected = true
            for (bad in listOf("abc", "0", "65536", "-1")) {
                field<javax.swing.JTextField>("fixedPortField").text = bad
                assertThrows("port '$bad' must be rejected", ConfigurationException::class.java) { configurable.apply() }
            }
            // Blank stays lenient and falls back to the default port.
            field<javax.swing.JTextField>("fixedPortField").text = ""
            configurable.apply()
            assertEquals(OpenCodePortMode.FIXED, projectSettings.portModeValue())
            assertEquals(OpenCodeSettingsState.DEFAULT_FIXED_PORT, projectSettings.fixedPort)
        }
    }

    @Test
    fun yamlHostPortHydratesHostCliAndIsNotModified() {
        SwingUtilities.invokeAndWait {
            val directory = temp.root.toPath().toRealPath().toString()
            val spec = SbxLaunchSpec.fromSettings(appSettings, directory).copy(
                useSandbox = false,
                hostPort = 49123,
            )
            assertNotNull(SbxLaunchSpec.persist(spec))
            projectSettings.portMode = OpenCodePortMode.AUTO.name
            configurable.disposeUIResources()
            configurable = OpenCodeProjectSettingsConfigurable(project)
            configurable.reset()
            assertTrue(field<AbstractButton>("hostRuntimeRadioButton").isSelected)
            assertTrue(field<AbstractButton>("fixedPortRadioButton").isSelected)
            assertEquals("49123", field<JTextField>("fixedPortField").text)
            assertFalse(configurable.isModified())
        }
    }

    @Test
    fun isModifiedDoesNotStopMountTableEditing() {
        SwingUtilities.invokeAndWait {
            val directory = temp.root.toPath().toRealPath().toString()
            val spec = SbxLaunchSpec.fromSettings(appSettings, directory).copy(
                extraMounts = listOf(SbxExtraMount("/tmp/host", "/tmp/guest")),
            )
            assertNotNull(SbxLaunchSpec.persist(spec))
            configurable.createComponent()
            val table = field<TableView<*>>("extraMountTable")
            assertTrue(table.editCellAt(0, 0))
            assertTrue(table.isEditing)
            configurable.isModified
            assertTrue("Settings polls isModified to enable Apply; that must not end cell editing", table.isEditing)
            table.cellEditor.cancelCellEditing()
        }
    }

    @Test
    fun browseButtonStaysUsableAcrossAutoCustomToggles() {
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            val customRadio = field<AbstractButton>("customProjectDirectoryRadioButton")
            val autoRadio = field<AbstractButton>("autoProjectDirectoryRadioButton")
            val directoryField = field<TextFieldWithBrowseButton>("projectDirectoryField")
            // Picking a folder while auto is selected must switch the mode to custom.
            assertTrue(autoRadio.isSelected)
            // The browse control is either a detached FixedSizeButton (non-extendable LaF) or an
            // inline text-field extension (mac/Darcula). Read the real fields instead of walking children.
            val myBrowseButton = directoryField.javaClass.superclass
                .getDeclaredField("myBrowseButton").apply { isAccessible = true }.get(directoryField) as AbstractButton
            val browseEvent = java.awt.event.ActionEvent(directoryField.textField, java.awt.event.ActionEvent.ACTION_PERFORMED, "action")
            myBrowseButton.actionListeners.forEach { listener ->
                if (listener.javaClass.name.contains("BrowseFolderActionListener")) return@forEach
                listener.actionPerformed(browseEvent)
            }
            assertTrue("browsing must select the custom radio", customRadio.isSelected)
            // Mode toggles must never disable the browse affordance; only the text field follows the mode.
            for (custom in listOf(false, true, false, true)) {
                (if (custom) customRadio else autoRadio).isSelected = true
                assertTrue("wrapper must never be disabled (hides the browse affordance)", directoryField.isEnabled)
                assertTrue("browse button must never be disabled", myBrowseButton.isEnabled)
                assertEquals(custom, directoryField.textField.isEnabled)
            }
        }
    }

    @Test
    fun handWrittenSandboxNameDoesNotKeepApplyEnabledAndSurvivesApply() {
        SwingUtilities.invokeAndWait {
            val directory = temp.root.toPath().toRealPath().toString()
            val customName = "my-own-sandbox-name"
            // A root-level spec with an absolute canonicalDirectory is the one layout where
            // load() honors a hand-written name (nested project specs derive it from the
            // directory on purpose, so a clone cannot hijack another clone's sandbox).
            Files.writeString(
                temp.root.toPath().resolve(SbxLaunchSpec.PROJECT_SPEC_NAME),
                "schemaVersion: 1\ncanonicalDirectory: $directory\nname: $customName\n",
            )
            assertEquals(customName, SbxLaunchSpec.load(directory)!!.name)
            configurable.disposeUIResources()
            configurable = OpenCodeProjectSettingsConfigurable(project)
            configurable.createComponent()
            assertFalse("a hand-written name must not mark the form modified", configurable.isModified())
            // Persisting the form must carry the honored name into the machine copy path:
            // adoptStoredName keeps it instead of silently switching to the derived default.
            val spec = SbxLaunchSpec.fromSettings(appSettings, directory)
            assertEquals(customName, spec.adoptStoredName(SbxLaunchSpec.load(directory)!!).name)
            assertFalse(configurable.isModified())
        }
    }

    @Test
    fun applyOnDirectorySwitchPreviewsTheDestinationInsteadOfRecreatingTheOldProject() {
        SwingUtilities.invokeAndWait {
            val oldDirectory = temp.newFolder("old workspace").toPath().toRealPath().toString()
            val newDirectory = temp.newFolder("new workspace").toPath().toRealPath().toString()
            // The old directory owns a spec with a kit; the destination has none.
            assertNotNull(
                SbxLaunchSpec.persist(
                    SbxLaunchSpec.fromSettings(appSettings, oldDirectory).copy(kits = listOf("./old-kit")),
                ),
            )
            projectSettings.projectDirectoryMode = OpenCodeProjectDirectoryMode.CUSTOM.name
            projectSettings.openCodeProjectDirectory = oldDirectory
            configurable.disposeUIResources()
            configurable = OpenCodeProjectSettingsConfigurable(project)
            configurable.createComponent()
            field<AbstractButton>("customProjectDirectoryRadioButton").isSelected = true
            val directoryField = field<TextFieldWithBrowseButton>("projectDirectoryField")
            directoryField.text = newDirectory
            // Clicking Apply blurs the directory field in the real UI; the reload that
            // loads the destination's settings hangs off that focus listener.
            java.awt.event.FocusEvent(
                directoryField.textField,
                java.awt.event.FocusEvent.FOCUS_LOST,
            ).let { event ->
                directoryField.textField.focusListeners.forEach { it.focusLost(event) }
            }
            configurable.apply()
            // Switching directories must move the panel without recreating anything: the
            // destination gets a fresh spec and the old project's VM is left alone.
            val written = SbxLaunchSpec.load(newDirectory)!!
            assertEquals(SbxCli.sandboxName(newDirectory), written.name)
            assertEquals(emptyList<String>(), written.kits)
            assertFalse(configurable.isModified())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = OpenCodeProjectSettingsConfigurable::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(configurable) as T
}
