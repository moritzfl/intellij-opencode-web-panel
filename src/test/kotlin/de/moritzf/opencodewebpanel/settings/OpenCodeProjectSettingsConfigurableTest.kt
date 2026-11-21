package de.moritzf.opencodewebpanel.settings

import com.intellij.mock.MockProject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.ui.table.TableView
import de.moritzf.opencodewebpanel.configuration.OpenCodePortMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectDirectoryMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectSettingsListener
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.server.OpenCodeProjectRuntime
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxExtraMount
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import de.moritzf.opencodewebpanel.server.SbxOpenCodeVersion
import de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.SwingUtilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCodeProjectSettingsConfigurableTest {
    companion object {
        @ClassRule @JvmField val application = ApplicationRule()
    }

    @get:Rule val disposable = DisposableRule()
    @get:Rule val temp = TemporaryFolder()

    private val appSettings = OpenCodeSettingsState()
    private val projectSettings =
        OpenCodeProjectSettingsState().apply { portImportedFromApplication = true }
    private val registry = OpenCodeServerBackendRegistry()
    private lateinit var project: MockProject
    private lateinit var configurable: OpenCodeProjectSettingsConfigurable
    private var restarts = 0
    private var reloads = 0

    @Before
    fun setUp() {
        val app = ApplicationManager.getApplication()
        app.replaceService(OpenCodeSettingsState::class.java, appSettings, disposable.disposable)
        app.replaceService(
            OpenCodeServerBackendRegistry::class.java,
            registry,
            disposable.disposable,
        )
        app.replaceService(
            SbxSandboxRecordStore::class.java,
            SbxSandboxRecordStore(),
            disposable.disposable,
        )
        project =
            object : MockProject(null, disposable.disposable) {
                override fun getBasePath(): String = temp.root.toPath().toRealPath().toString()
            }
        project.registerService(OpenCodeProjectSettingsState::class.java, projectSettings)
        val runtime = OpenCodeProjectRuntime(project)
        project.registerService(OpenCodeProjectRuntime::class.java, runtime)
        runtime.bindPanelRestart(disposable.disposable) {
            restarts++
            true
        }
        project.messageBus
            .connect(disposable.disposable)
            .subscribe(
                OpenCodeProjectSettingsListener.TOPIC,
                object : OpenCodeProjectSettingsListener {
                    override fun serverReloadRequested() {
                        reloads++
                    }
                },
            )
        SwingUtilities.invokeAndWait {
            configurable = OpenCodeProjectSettingsConfigurable(project)
            configurable.reset()
        }
    }

    @After
    fun tearDown() {
        if (::configurable.isInitialized)
            SwingUtilities.invokeAndWait { configurable.disposeUIResources() }
        registry.dispose()
    }

    @Test
    fun runtimeSwitchStopsPreviousBackendAndPublishesRestartInBothDirections() {
        ApplicationManager.getApplication().invokeAndWait {
            val native = registry.backendFor(project) as SharedOpenCodeServerManager
            native.setServerRunning(true) // No actual process or server is launched by this test.
            field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
            field<JTextField>("sbxMemoryField").text = "8g"
            field<javax.swing.JSpinner>("sbxCpusField").value = 4
            field<AbstractButton>("sbxShareHostConfigCheckBox").isSelected = true
            field<AbstractButton>("sbxOpenCodeV2RadioButton").isSelected = true
            field<AbstractButton>("sbxEnableIntellijMcpCheckBox").isSelected = false
            field<AbstractButton>("fixedPortRadioButton").isSelected = true
            field<JTextField>("fixedPortField").text = "49123"
            configurable.apply()
            assertEquals(OpenCodeServerLifecycleState.STOPPED, native.getLifecycleState())
            PlatformTestUtil.waitWithEventsDispatching(
                "Missing Host to Sandbox restart",
                { restarts == 1 },
                5,
            )
            val sandbox = registry.backendFor(project)
            val expected = SbxLaunchSpec.load(project.basePath)!!
            assertEquals("8g", expected.memory)
            assertEquals("4", expected.cpus)
            assertEquals(49123, expected.hostPort)
            assertTrue(expected.shareHostOpencodeConfig)
            assertEquals(SbxOpenCodeVersion.V2, expected.openCodeVersion)
            assertFalse(expected.enableIntellijMcp)
            assertTrue(expected.useSandbox)
            assertFalse(configurable.isModified())
            SbxLaunchSpec.persist(project.basePath!!, hostPort = appSettings.hostPortOrNull())
            assertEquals(expected, SbxLaunchSpec.load(project.basePath))

            field<AbstractButton>("hostRuntimeRadioButton").isSelected = true
            configurable.apply()
            PlatformTestUtil.waitWithEventsDispatching(
                "Missing Sandbox to Host restart",
                { restarts == 2 },
                5,
            )
            assertSame(native, registry.backendFor(project))
            assertSame(
                "Switching back must retain sandbox ownership/backend",
                sandbox,
                registry.backend(sandbox.backendId),
            )
            assertEquals(expected.copy(useSandbox = false), SbxLaunchSpec.load(project.basePath))
            assertFalse(configurable.isModified())
        }
    }

    @Test
    fun sandboxPortChangeDoesNotStopOrRestart() {
        ApplicationManager.getApplication().invokeAndWait {
            field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
            configurable.apply()
            PlatformTestUtil.waitWithEventsDispatching(
                "Missing Host to Sandbox restart",
                { restarts == 1 },
                5,
            )
            assertEquals(0, reloads)
            field<AbstractButton>("fixedPortRadioButton").isSelected = true
            field<JTextField>("fixedPortField").text = "49123"
            configurable.apply()
            assertEquals("LIVE port remap must not stop the VM or restart serve", 1, restarts)
            assertEquals(0, reloads)
            assertEquals(49123, SbxLaunchSpec.load(project.basePath)!!.hostPort)
            assertTrue(
                registry.backendFor(project)
                    is de.moritzf.opencodewebpanel.server.SbxOpenCodeServerBackend,
            )
        }
    }

    @Test
    fun unreadableKitBlocksApplyBeforeSavingOrAcknowledging() {
        val spec =
            SbxLaunchSpec.defaults(project.basePath!!, hostPort = appSettings.hostPortOrNull())
                .copy(
                    useSandbox = true,
                    kits = listOf("./missing-kit"),
                )
        assertNotNull(SbxLaunchSpec.persist(spec))
        ApplicationManager.getApplication().invokeAndWait {
            configurable.reset()
            field<JTextField>("sbxMemoryField").text = "8g"
            val error = assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertTrue(
                error.messageHtml.toString().contains("Could not verify sandbox kit contents"),
            )
            assertEquals(spec.memory, SbxLaunchSpec.load(project.basePath)!!.memory)
            assertTrue(SbxSandboxRecordStore.getInstance().state.acknowledgedExposure.isEmpty())
            assertEquals(0, restarts)
        }
    }

    @Test
    fun sandboxWorkingDirectoryHydratesAndRestartsWithoutChangingTheMountRoot() {
        val workdir = Files.createDirectory(temp.root.toPath().resolve("app")).toRealPath()
        ApplicationManager.getApplication().invokeAndWait {
            field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
            configurable.apply()
            PlatformTestUtil.waitWithEventsDispatching(
                "Missing initial sandbox restart",
                { restarts == 1 },
                5,
            )
            field<TextFieldWithBrowseButton>("sbxWorkingDirectoryField").text = "./app"
            assertTrue(configurable.isModified())
            configurable.apply()
            PlatformTestUtil.waitWithEventsDispatching(
                "Missing cwd-only restart",
                { restarts == 2 },
                5,
            )
            val spec = SbxLaunchSpec.load(project.basePath)!!
            assertEquals(project.basePath, spec.canonicalDirectory)
            assertEquals(workdir.toString(), spec.hostWorkingDirectory())
            assertFalse(configurable.isModified())

            field<TextFieldWithBrowseButton>("sbxWorkingDirectoryField").text = "../elsewhere"
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals("./app", SbxLaunchSpec.load(project.basePath)!!.workingDirectory)
            configurable.reset()
            assertEquals("./app", field<TextFieldWithBrowseButton>("sbxWorkingDirectoryField").text)
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
                SbxLaunchSpec.persist(project.basePath!!, hostPort = appSettings.hostPortOrNull())
                configurable.disposeUIResources()
                configurable = OpenCodeProjectSettingsConfigurable(project)
                configurable.createComponent()
                assertEquals(shared, field<AbstractButton>("sbxShareHostConfigCheckBox").isSelected)
                assertFalse(configurable.isModified())
            }
        }
    }

    @Test
    fun resourcesSectionOpensWhenItHoldsSomethingWorthSeeing() {
        val specPath = SbxLaunchSpec.projectSpecPath(temp.root.toPath().toString())
        val defaults =
            SbxLaunchSpec.defaults(project.basePath!!, hostPort = appSettings.hostPortOrNull())
                .copy(useSandbox = true)
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            val group = { field<com.intellij.ui.dsl.builder.CollapsibleRow>("resourcesGroup") }

            assertNotNull(SbxLaunchSpec.persist(defaults))
            configurable.reset()
            assertFalse("Only defaults: folded away", group().expanded)

            assertNotNull(SbxLaunchSpec.persist(defaults.copy(kits = listOf("./kit"))))
            configurable.reset()
            assertTrue("A kit is configured", group().expanded)

            assertNotNull(SbxLaunchSpec.persist(defaults))
            configurable.reset()
            assertFalse(group().expanded)
            Files.writeString(specPath, "not a spec")
            configurable.reset()
            assertTrue("Apply can replace an invalid file with this form", group().expanded)
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
    fun dockerImagePinRoundTripsThroughTheForm() {
        // Sandbox Apply runs modal progress, which needs the IDE's write-intent EDT.
        ApplicationManager.getApplication().invokeAndWait {
            configurable.createComponent() // installs the control listeners
            field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
            field<AbstractButton>("sbxOpenCodeV2RadioButton").isSelected = true
            val image = field<javax.swing.JComboBox<*>>("sbxOpenCodeImageComboBox")
            assertTrue("Shown for 2.x", image.isVisible)
            assertEquals(SbxCli.LATEST_IMAGE, image.editor.item)
            image.editor.item = "1.18.31"
            val error = assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertTrue(error.message!!.contains("Docker image"))
            image.editor.item = "2.2.7"
            configurable.apply()
            assertEquals("2.2.7", SbxLaunchSpec.load(project.basePath)!!.openCodeImage)
            assertFalse(configurable.isModified())
            configurable.reset()
            assertEquals("2.2.7", image.editor.item)
            image.editor.item = SbxCli.LATEST_IMAGE
            assertTrue(configurable.isModified())
            configurable.apply()
            assertEquals(null, SbxLaunchSpec.load(project.basePath)!!.openCodeImage)
            assertFalse(
                Files.readString(SbxLaunchSpec.projectSpecPath(project.basePath!!))
                    .contains("openCodeImage"),
            )
            field<AbstractButton>("sbxOpenCodeV1RadioButton").isSelected = true
            assertFalse("1.x has no image to pin", image.isVisible)
        }
    }

    @Test
    fun invalidFixedPortIsRejectedOnApply() {
        SwingUtilities.invokeAndWait {
            field<AbstractButton>("fixedPortRadioButton").isSelected = true
            for (bad in listOf("abc", "0", "65536", "-1")) {
                field<javax.swing.JTextField>("fixedPortField").text = bad
                assertThrows("port '$bad' must be rejected", ConfigurationException::class.java) {
                    configurable.apply()
                }
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
            val spec =
                SbxLaunchSpec.defaults(directory, hostPort = appSettings.hostPortOrNull())
                    .copy(
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
            val spec =
                SbxLaunchSpec.defaults(directory, hostPort = appSettings.hostPortOrNull())
                    .copy(extraMounts = listOf(SbxExtraMount("/tmp/host", "/tmp/guest")))
            assertNotNull(SbxLaunchSpec.persist(spec))
            configurable.createComponent()
            val table = field<TableView<*>>("extraMountTable")
            assertTrue(table.editCellAt(0, 0))
            assertTrue(table.isEditing)
            configurable.isModified
            assertTrue(
                "Settings polls isModified to enable Apply; that must not end cell editing",
                table.isEditing,
            )
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
            // inline text-field extension (mac/Darcula). Read the real fields instead of walking
            // children.
            val myBrowseButton =
                directoryField.javaClass.superclass
                    .getDeclaredField("myBrowseButton")
                    .apply { isAccessible = true }
                    .get(directoryField) as AbstractButton
            val browseEvent =
                java.awt.event.ActionEvent(
                    directoryField.textField,
                    java.awt.event.ActionEvent.ACTION_PERFORMED,
                    "action",
                )
            myBrowseButton.actionListeners.forEach { listener ->
                if (listener.javaClass.name.contains("BrowseFolderActionListener")) return@forEach
                listener.actionPerformed(browseEvent)
            }
            assertTrue("browsing must select the custom radio", customRadio.isSelected)
            // Mode toggles must never disable the browse affordance; only the text field follows
            // the mode.
            for (custom in listOf(false, true, false, true)) {
                (if (custom) customRadio else autoRadio).isSelected = true
                assertTrue(
                    "wrapper must never be disabled (hides the browse affordance)",
                    directoryField.isEnabled,
                )
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
            assertFalse(
                "a hand-written name must not mark the form modified",
                configurable.isModified(),
            )
            // Persisting the form must carry the honored name into the machine copy path:
            // adoptStoredName keeps it instead of silently switching to the derived default.
            val spec = SbxLaunchSpec.defaults(directory, hostPort = appSettings.hostPortOrNull())
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
                    SbxLaunchSpec.defaults(oldDirectory, hostPort = appSettings.hostPortOrNull())
                        .copy(kits = listOf("./old-kit")),
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
            java.awt.event
                .FocusEvent(
                    directoryField.textField,
                    java.awt.event.FocusEvent.FOCUS_LOST,
                )
                .let { event ->
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

    @Test
    fun directorySwitchAlwaysLoadsDestinationSpecWhenPresent() {
        SwingUtilities.invokeAndWait {
            val oldDirectory = temp.newFolder("old with kits").toPath().toRealPath().toString()
            val newDirectory = temp.newFolder("new with kits").toPath().toRealPath().toString()
            assertNotNull(
                SbxLaunchSpec.persist(
                    SbxLaunchSpec.defaults(oldDirectory, hostPort = appSettings.hostPortOrNull())
                        .copy(kits = listOf("./old-kit")),
                ),
            )
            assertNotNull(
                SbxLaunchSpec.persist(
                    SbxLaunchSpec.defaults(newDirectory, hostPort = appSettings.hostPortOrNull())
                        .copy(kits = listOf("./dest-kit")),
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
            java.awt.event
                .FocusEvent(
                    directoryField.textField,
                    java.awt.event.FocusEvent.FOCUS_LOST,
                )
                .let { event ->
                    directoryField.textField.focusListeners.forEach { it.focusLost(event) }
                }
            configurable.apply()
            assertEquals(listOf("./dest-kit"), SbxLaunchSpec.load(newDirectory)!!.kits)
            assertEquals(listOf("./old-kit"), SbxLaunchSpec.load(oldDirectory)!!.kits)
            assertFalse(configurable.isModified())
        }
    }

    @Test
    fun applyBeforeTheDirectoryFieldLostFocusLoadsTheDestinationInsteadOfFailing() {
        SwingUtilities.invokeAndWait {
            val oldDirectory = temp.newFolder("typed old").toPath().toRealPath().toString()
            val newDirectory = temp.newFolder("typed new").toPath().toRealPath().toString()
            assertNotNull(
                SbxLaunchSpec.persist(
                    SbxLaunchSpec.defaults(newDirectory, hostPort = appSettings.hostPortOrNull())
                        .copy(kits = listOf("./dest-kit")),
                ),
            )
            projectSettings.projectDirectoryMode = OpenCodeProjectDirectoryMode.CUSTOM.name
            projectSettings.openCodeProjectDirectory = oldDirectory
            configurable.disposeUIResources()
            configurable = OpenCodeProjectSettingsConfigurable(project)
            configurable.createComponent()
            field<JTextField>("sbxMemoryField").text = "16g"
            // Enter in the field applies before focus-lost or the queued reload ran.
            field<TextFieldWithBrowseButton>("projectDirectoryField").text = newDirectory
            configurable.apply()
            val written = SbxLaunchSpec.load(newDirectory)!!
            assertEquals(listOf("./dest-kit"), written.kits)
            assertEquals("The old form must not leak into the destination", "4g", written.memory)
            assertEquals(newDirectory, projectSettings.openCodeProjectDirectory)
            assertFalse(configurable.isModified())
        }
    }

    @Test
    fun customDirectoryStartsFromTheProjectRootAndAutoDropsTheUntouchedPrefill() {
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            val directoryField = field<TextFieldWithBrowseButton>("projectDirectoryField")
            field<AbstractButton>("customProjectDirectoryRadioButton").isSelected = true
            assertEquals(project.basePath, directoryField.text)
            field<AbstractButton>("autoProjectDirectoryRadioButton").isSelected = true
            assertEquals("", directoryField.text)
            assertFalse("Toggling back changes nothing", configurable.isModified())
        }
    }

    @Test
    fun anInvalidFixedPortIsMarkedWhileTyping() {
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            field<AbstractButton>("fixedPortRadioButton").isSelected = true
            val portField = field<JTextField>("fixedPortField")
            portField.text = "70000"
            assertEquals("error", portField.getClientProperty("JComponent.outline"))
            portField.text = ""
            assertEquals(
                "Blank means the default port",
                null,
                portField.getClientProperty("JComponent.outline"),
            )
        }
    }

    @Test
    fun badMemoryAndMissingMountPathsAreMarkedBeforeApply() {
        val existing = temp.newFolder("shared").toPath().toRealPath().toString()
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            val memory = field<JTextField>("sbxMemoryField")
            memory.text = "8GB"
            assertEquals("error", memory.getClientProperty("JComponent.outline"))
            memory.text = "8g"
            assertEquals(null, memory.getClientProperty("JComponent.outline"))
            val problem =
                OpenCodeProjectSettingsConfigurable::class
                    .java
                    .getDeclaredMethod("hostPathProblem", String::class.java, String::class.java)
                    .apply { isAccessible = true }
            val workspace = project.basePath!!
            assertEquals(null, problem.invoke(configurable, existing, workspace))
            assertEquals(null, problem.invoke(configurable, "$existing:ro", workspace))
            assertTrue(
                (problem.invoke(configurable, "./not-here", workspace) as String).startsWith(
                    "Not on this computer:",
                ),
            )
            assertTrue(
                "An unset required variable explains itself",
                problem.invoke(configurable, "\${OCWP_SURELY_UNSET_VARIABLE}", workspace) != null,
            )
        }
    }

    @Test
    fun resetSandboxWaitsForApplyAndSaysWhy() {
        assertNotNull(
            SbxLaunchSpec.persist(
                SbxLaunchSpec.defaults(project.basePath!!, hostPort = appSettings.hostPortOrNull())
                    .copy(useSandbox = true),
            ),
        )
        SwingUtilities.invokeAndWait {
            configurable.disposeUIResources()
            configurable = OpenCodeProjectSettingsConfigurable(project)
            configurable.createComponent()
            val reset = field<AbstractButton>("resetSandboxButton")
            val hint = field<JComponent>("actionsHintLabel")
            assertFalse(configurable.isModified())
            assertTrue("Like the gear action: any sandbox backend", reset.isEnabled)
            assertFalse(hint.isVisible)
            field<JTextField>("sbxMemoryField").text = "8g"
            assertTrue(configurable.isModified())
            assertFalse("Reset must not run on unsaved settings", reset.isEnabled)
            assertTrue(reset.toolTipText, reset.toolTipText.startsWith("Apply first"))
            assertTrue(hint.isVisible)
        }
    }

    @Test
    fun aFolderPickedWithoutFocusLoadsItsSettingsOnce() {
        val picked = temp.newFolder("picked").toPath().toRealPath().toString()
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            assertFalse("reset() queues no reload", field<Boolean>("directoryReloadQueued"))
            field<AbstractButton>("customProjectDirectoryRadioButton").isSelected = true
            // The folder chooser sets the text while focus is elsewhere.
            field<TextFieldWithBrowseButton>("projectDirectoryField").text = picked
            assertTrue(field<Boolean>("directoryReloadQueued"))
        }
        SwingUtilities.invokeAndWait {
            assertFalse(field<Boolean>("directoryReloadQueued"))
            assertEquals(picked, field<String>("loadedSpecDirectory"))
        }
    }

    @Test
    fun applyRightAfterTypingADirectoryWithAnInvalidSpecStopsForReview() {
        val broken = temp.newFolder("broken").toPath().toRealPath().toString()
        val specPath = SbxLaunchSpec.projectSpecPath(broken)
        Files.createDirectories(specPath.parent)
        Files.writeString(specPath, "not a spec")
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            field<AbstractButton>("customProjectDirectoryRadioButton").isSelected = true
            field<TextFieldWithBrowseButton>("projectDirectoryField").text = broken
            val error = assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertTrue(
                error.messageHtml.toString(),
                error.messageHtml.toString().contains("Review"),
            )
            assertEquals("not a spec", Files.readString(specPath))
            assertEquals(
                OpenCodeProjectDirectoryMode.AUTO,
                projectSettings.projectDirectoryModeValue(),
            )
        }
    }

    @Test
    fun applyShowsTheWrittenSandboxFilesToTheIdeWithoutAManualRefresh() {
        val root = temp.root.toPath().toRealPath()
        ApplicationManager.getApplication().invokeAndWait {
            val folder = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
            // Like a Project view that has listed the folder: new entries need a refresh.
            folder.children
            assertNull(folder.findChild("opencode-sbx"))
            field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
            configurable.apply()
            PlatformTestUtil.waitWithEventsDispatching(
                "The IDE never saw opencode-sbx/opencode-sbx.yaml",
                { folder.findChild("opencode-sbx")?.findChild("opencode-sbx.yaml") != null },
                10,
            )
        }
    }

    @Test
    fun aChosenKitFolderDescriptorOrZipIsAddedRelativeToTheProject() {
        val root = temp.root.toPath().toRealPath()
        val kit = Files.createDirectories(root.resolve("kits/tools"))
        val descriptor = Files.writeString(kit.resolve("spec.yaml"), "schemaVersion: \"3\"\n")
        val archive = Files.writeString(root.resolve("kits/team.zip"), "zip")
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            val starts = ArrayList<Path?>()
            var chooser: FileChooserDescriptor? = null
            var next = listOf(kit)
            configurable.chooseFiles = { descriptor, start ->
                chooser = descriptor
                starts += start
                next
            }
            call("addKitFromChooser")
            assertEquals("Without opencode-sbx/ the chooser opens the project", root, starts.last())
            // What the chooser lets through: kit folders, their descriptor, and kit ZIP files.
            val files = LocalFileSystem.getInstance()
            fun virtual(path: Path) = files.refreshAndFindFileByNioFile(path)!!
            val notes = virtual(Files.writeString(root.resolve("kits/notes.txt"), "x"))
            val descriptorFile = chooser!!
            for (path in listOf(kit, descriptor, archive)) {
                assertTrue("$path", descriptorFile.isFileSelectable(virtual(path)))
                descriptorFile.validateSelectedFiles(arrayOf(virtual(path)))
                assertEquals(
                    "A ZIP is chosen as the file it is, not opened as a folder",
                    path,
                    descriptorFile.getFileToSelect(virtual(path))!!.toNioPath(),
                )
            }
            for (wrong in listOf(notes, virtual(root.resolve("kits")))) {
                val refused =
                    assertThrows(Exception::class.java) {
                        descriptorFile.validateSelectedFiles(arrayOf(wrong))
                    }
                assertTrue(refused.message, refused.message!!.contains("is not a kit"))
            }
            next = listOf(descriptor)
            call("addKitFromChooser")
            assertEquals("The descriptor stands for its folder", listOf("./kits/tools"), kitRefs())
            next = listOf(archive)
            call("addKitFromChooser")
            assertEquals(listOf("./kits/tools", "./kits/team.zip"), kitRefs())
            assertEquals(1, field<TableView<*>>("kitTable").selectedRow)
            next = emptyList()
            call("addKitFromChooser")
            assertEquals("Cancel adds nothing", 2, kitRefs().size)
            assertTrue(configurable.isModified())
        }
    }

    @Test
    fun theExtraNetworkKitIsWrittenOnceAddedAndOpenedForEditing() {
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            val edited = ArrayList<Pair<Path, String?>>()
            configurable.editKitSpec = { spec, template -> edited += spec to template }
            val spec = SbxLaunchSpec.networkKitSpecPath(project.basePath!!)
            call("addNetworkKit")
            assertEquals(SbxCli.networkKitTemplateYaml(), Files.readString(spec))
            assertEquals(listOf(SbxCli.networkKitRef()), kitRefs())
            assertEquals(listOf(spec to SbxCli.networkKitTemplateYaml()), edited)

            Files.writeString(spec, "edited")
            call("addNetworkKit")
            assertEquals("An existing kit is never overwritten", "edited", Files.readString(spec))
            assertEquals("And not listed twice", 1, kitRefs().size)
            assertEquals(2, edited.size)

            field<TableView<*>>("kitTable").selectionModel.setSelectionInterval(0, 0)
            call("editSelectedKitSpec")
            assertEquals("Edit spec.yaml opens the selected local kit", 3, edited.size)
            assertEquals(spec, edited.last().first)
        }
    }

    @Test
    fun chosenMountsArePortableAndTheirSandboxPathFollows() {
        val root = temp.root.toPath().toRealPath()
        val inside = Files.createDirectories(root.resolve("shared"))
        val outside = Files.createTempDirectory("ocwp-mount").toRealPath()
        try {
            ApplicationManager.getApplication().invokeAndWait {
                configurable.createComponent()
                configurable.chooseFiles = { _, _ -> listOf(inside, outside) }
                call("addMountsFromChooser")
                call("addMountsFromChooser")
                val table = field<TableView<*>>("extraMountTable")
                val outsideText = SbxCli.portableHostPath(outside.toString(), root.toString())
                assertEquals("Choosing a mount again does not list it twice", 2, table.rowCount)
                assertEquals("./shared", table.getValueAt(0, 0))
                assertEquals(outsideText, table.getValueAt(1, 0))
                assertEquals("Empty means the host path", "", table.getValueAt(0, 1))
                field<AbstractButton>("sbxRuntimeRadioButton").isSelected = true
                configurable.apply()
                assertEquals(
                    listOf(
                        SbxExtraMount("./shared", "./shared", false),
                        SbxExtraMount(outsideText, outsideText, false),
                    ),
                    SbxLaunchSpec.load(project.basePath)!!.extraMounts,
                )
                assertFalse(configurable.isModified())
                // A loaded row whose sandbox path is the host path keeps following an edit.
                table.setValueAt("./other", 0, 0)
                configurable.apply()
                assertEquals(
                    SbxExtraMount("./other", "./other", false),
                    SbxLaunchSpec.load(project.basePath)!!.extraMounts.first(),
                )
            }
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun theWorkingDirectoryChooserStartsInTheProjectAndWritesARelativePath() {
        val root = temp.root.toPath().toRealPath()
        val service = Files.createDirectories(root.resolve("services/api"))
        SwingUtilities.invokeAndWait {
            configurable.createComponent()
            val starts = ArrayList<Path?>()
            var next = service
            configurable.chooseFiles = { _, start ->
                starts += start
                listOf(next)
            }
            var chooser: FileChooserDescriptor? = null
            configurable.chooseFiles = { descriptor, start ->
                chooser = descriptor
                starts += start
                listOf(next)
            }
            val workdir = field<TextFieldWithBrowseButton>("sbxWorkingDirectoryField")
            call("browseWorkingDirectory")
            assertEquals("./services/api", workdir.text)
            assertEquals(
                "Only the project is offered",
                listOf(root),
                chooser!!.roots.map { it.toNioPath() },
            )
            assertEquals(root, starts.last())
            next = root
            call("browseWorkingDirectory")
            assertEquals("./", workdir.text)
            assertEquals("The chooser reopens at the current choice", service, starts.last())
        }
    }

    private fun kitRefs(): List<String> {
        val table = field<TableView<*>>("kitTable")
        return (0 until table.rowCount).map { table.getValueAt(it, 0) as String }
    }

    private fun call(name: String) {
        OpenCodeProjectSettingsConfigurable::class
            .java
            .getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(configurable)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        OpenCodeProjectSettingsConfigurable::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(configurable) as T
}
