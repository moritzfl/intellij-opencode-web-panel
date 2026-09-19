package de.moritzf.opencodewebpanel

import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeRuntimeMode
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.toolWindow.OPEN_CODE_RESET_ZOOM_ACTION_ID
import de.moritzf.opencodewebpanel.toolWindow.OPEN_CODE_ZOOM_IN_ACTION_ID
import de.moritzf.opencodewebpanel.toolWindow.OPEN_CODE_ZOOM_OUT_ACTION_ID
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeBrowserCommand
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeEditorFileEditor
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeEditorFileEditorProvider
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeEditorManager
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeEditorVirtualFile
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeOpenInEditorAction
import de.moritzf.opencodewebpanel.toolWindow.OpenCodePanelCoordinator
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeWebToolWindowFactoryImpl
import de.moritzf.opencodewebpanel.toolWindow.editorFileToCloseOnToolWindowShown
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

class OpenCodePluginTest : BasePlatformTestCase() {

    fun testToolWindowFactoryIsAvailableDuringIndexing() {
        assertTrue(DumbAware::class.java.isAssignableFrom(OpenCodeWebToolWindowFactoryImpl::class.java))
    }

    fun testNativeBackendIdIsPrefixedAndDistinct() {
        val first = OpenCodeServerBackend.nativeBackendId("/tmp/project-a")
        val second = OpenCodeServerBackend.nativeBackendId("/tmp/project-b")
        assertTrue(OpenCodeServerBackend.isNative(OpenCodeServerBackend.NATIVE_ID))
        assertTrue(OpenCodeServerBackend.isNative(first))
        assertTrue(first.startsWith(OpenCodeServerBackend.NATIVE_ID_PREFIX))
        assertFalse(first == second)
        assertFalse(OpenCodeServerBackend.isNative("sbx:ide-ocwp-deadbeef"))
    }

    fun testOpenCodeServerBackendRegistryIsApplicationScoped() {
        assertSame(OpenCodeServerBackendRegistry.getInstance(), OpenCodeServerBackendRegistry.getInstance())
    }

    fun testHostRuntimeReturnsSeparateNativeBackendsPerDirectory() {
        val registry = OpenCodeServerBackendRegistry.getInstance()
        val first = registry.backendForCanonicalDirectory("/tmp/project-a")
        val second = registry.backendForCanonicalDirectory("/tmp/project-b")
        val same = registry.backendForCanonicalDirectory("/tmp/project-a")
        assertSame(first, same)
        assertNotSame(first, second)
        assertTrue(OpenCodeServerBackend.isNative(first.backendId))
        assertTrue(first.backendId.startsWith(OpenCodeServerBackend.NATIVE_ID_PREFIX))
        assertFalse(first.backendId == second.backendId)
        assertTrue(first.offersHostPortControls)
        assertSame(first, registry.backend(first.backendId))
        assertSame(second, registry.backend(second.backendId))
    }

    fun testSbxRuntimeReturnsSeparateBackendsPerDirectory() {
        val settings = OpenCodeSettingsState.getInstance()
        val previous = settings.runtimeMode
        settings.runtimeMode = OpenCodeRuntimeMode.DOCKER_SANDBOX.name
        try {
            val registry = OpenCodeServerBackendRegistry.getInstance()
            val first = registry.backendForCanonicalDirectory("/tmp/project-a")
            val second = registry.backendForCanonicalDirectory("/tmp/project-b")
            assertNotSame(first, second)
            assertFalse(first.backendId == second.backendId)
            assertTrue(first.offersHostPortControls)
            assertTrue(OpenCodeServerBackend.isNative(registry.nativeBackend().backendId))
            assertSame(first, registry.backend(first.backendId))
        } finally {
            settings.runtimeMode = previous
            OpenCodeServerBackendRegistry.getInstance().stopAllSbxBackends()
        }
    }

    fun testPluginDescriptorRegistersRightSidebarToolWindowAndSharedServerManager() {
        val pluginXml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()

        assertTrue(pluginXml.contains("<id>de.moritzf.opencodewebpanel</id>"))
        assertTrue(pluginXml.contains("<name>OpenCode Web Panel</name>"))
        assertTrue(pluginXml.contains("<depends>com.intellij.modules.platform</depends>"))
        assertTrue(pluginXml.contains("<depends>com.intellij.modules.jcef</depends>"))
        assertTrue(pluginXml.contains("id=\"OpenCode Web Panel\""))
        assertTrue(pluginXml.contains("anchor=\"right\""))
        assertTrue(pluginXml.contains("icon=\"/icons/opencode.svg\""))
        assertTrue(pluginXml.contains("factoryClass=\"de.moritzf.opencodewebpanel.toolWindow.OpenCodeWebToolWindowFactoryImpl\""))
        assertTrue(pluginXml.contains("fileEditorProvider implementation=\"de.moritzf.opencodewebpanel.toolWindow.OpenCodeEditorFileEditorProvider\""))
        assertTrue(pluginXml.contains("applicationService"))
        assertFalse(pluginXml.contains("serviceImplementation=\"de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager\""))
        assertTrue(pluginXml.contains("serviceImplementation=\"de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry\""))
        assertTrue(pluginXml.contains("applicationConfigurable"))
        assertTrue(pluginXml.contains("instance=\"de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable\""))
        assertTrue(pluginXml.contains("projectConfigurable"))
        assertTrue(pluginXml.contains("instance=\"de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsConfigurable\""))
        assertTrue(pluginXml.contains("notificationGroup"))
        assertTrue(pluginXml.contains("displayType=\"BALLOON\""))
        assertFalse(pluginXml.contains("postStartupActivity"))
    }

    fun testOpenCodeEditorProviderAcceptsOnlyItsVirtualFile() {
        val provider = OpenCodeEditorFileEditorProvider()
        val file = OpenCodeEditorVirtualFile(project, "ses_test")

        assertTrue(DumbAware::class.java.isAssignableFrom(provider.javaClass))
        assertTrue(provider.accept(project, file))
        assertFalse(provider.accept(project, LightVirtualFile("other")))
        assertEquals("opencode.editor", provider.editorTypeId)
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)
        assertEquals("Open in Editor", OpenCodeOpenInEditorAction().templatePresentation.text)
    }

    fun testOpenCodeEditorExplicitlyImplementsTheFileEditorFileContract() {
        val file = OpenCodeEditorVirtualFile(project, "ses_test")
        val editor = OpenCodeEditorFileEditor(project, file, initializePanel = false)

        try {
            assertSame(file, editor.getFile())
        } finally {
            editor.dispose()
        }
    }

    fun testEditorShellsShareOneComponentAndInactiveEditorsShowPlaceholders() {
        val sharedComponent = JPanel()
        val coordinator = OpenCodePanelCoordinator(
            panelComponent = sharedComponent,
            disposePanel = {},
            parkingContainer = JPanel(),
        )
        val file = OpenCodeEditorVirtualFile(project, "ses_test")

        val first = OpenCodeEditorFileEditor(
            project,
            file,
            initializePanel = false,
            panelCoordinator = coordinator,
        )
        val second = OpenCodeEditorFileEditor(
            project,
            file,
            initializePanel = false,
            panelCoordinator = coordinator,
        )

        try {
            first.selectNotify()
            assertSame(sharedComponent, first.component.getComponent(0))
            assertFalse(second.component.getComponent(0) === sharedComponent)

            second.selectNotify()
            assertSame(sharedComponent, second.component.getComponent(0))
            assertFalse(first.component.getComponent(0) === sharedComponent)
        } finally {
            first.dispose()
            second.dispose()
            coordinator.dispose()
        }
    }

    fun testEditorVirtualFileKeepsProjectScopedSessionState() {
        val file = OpenCodeEditorVirtualFile(project, "ses_test")

        assertSame(project, file.owner)
        assertEquals("ses_test", file.sessionId)
        assertTrue(file.isValid)

        file.sessionId = null

        assertNull(file.sessionId)
    }

    fun testEditorManagerReusesProjectFileAndUpdatesItsSessionTarget() {
        val first = OpenCodeEditorManager.fileFor(project, "ses_first")
        val otherProject = ProjectManager.getInstance().defaultProject
        val other = OpenCodeEditorManager.fileFor(otherProject, "ses_first")

        val reused = OpenCodeEditorManager.fileFor(project, "ses_second")

        assertSame(first, reused)
        assertEquals("ses_second", reused.sessionId)
        assertNotSame(project, otherProject)
        assertNotSame(first, other)
        assertSame(otherProject, other.owner)
    }

    fun testDisposingOneEditorKeepsProjectFileForOtherEditors() {
        val file = OpenCodeEditorManager.fileFor(project, "ses_test")
        val first = OpenCodeEditorFileEditor(project, file, initializePanel = false)
        val second = OpenCodeEditorFileEditor(project, file, initializePanel = false)

        first.openSession(null)
        assertNull(file.sessionId)
        assertSame(file, OpenCodeEditorManager.trackedFile(project))

        first.dispose()
        assertSame(file, OpenCodeEditorManager.trackedFile(project))

        second.dispose()
        assertSame(file, OpenCodeEditorManager.trackedFile(project))
        assertSame(file, OpenCodeEditorManager.fileFor(project, "ses_reopened"))
    }

    fun testEditorProviderRejectsAFileOwnedByAnotherProject() {
        val provider = OpenCodeEditorFileEditorProvider()
        val file = OpenCodeEditorVirtualFile(project, "ses_test")
        val otherProject = ProjectManager.getInstance().defaultProject

        assertFalse(provider.accept(otherProject, file))
        try {
            provider.createEditor(otherProject, file)
            fail("A provider must not create an editor for another project's file")
        } catch (_: IllegalArgumentException) {
            // Expected ownership guard.
        }
    }

    fun testToolWindowActivationSelectsOnlyTheMatchingTrackedEditor() {
        val trackedFile = OpenCodeEditorVirtualFile(project, "ses_test")
        val otherProject = ProjectManager.getInstance().defaultProject

        assertSame(
            trackedFile,
            editorFileToCloseOnToolWindowShown("OpenCode", project, trackedFile),
        )
        assertNull(editorFileToCloseOnToolWindowShown("Project", project, trackedFile))
        assertNull(editorFileToCloseOnToolWindowShown("OpenCode", otherProject, trackedFile))
    }

    fun testSettingsConfigurableIsRegistered() {
        val pluginXml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()

        assertTrue(pluginXml.contains("displayName=\"OpenCode Web Panel\""))
        assertEquals("OpenCode Web Panel", OpenCodeSettingsConfigurable().displayName)

        // Both pages sit under Tools; the project-scoped one is suffixed so the tree does not
        // show the same label twice. The descriptor supplies the tree label before the class is
        // instantiated, so the two spellings must match.
        val projectDisplayName = OpenCodeProjectSettingsConfigurable.PROJECT_SETTINGS_DISPLAY_NAME
        assertEquals("OpenCode Web Panel (Project)", projectDisplayName)
        assertEquals(projectDisplayName, OpenCodeProjectSettingsConfigurable(project).displayName)
        assertTrue(pluginXml.contains("displayName=\"$projectDisplayName\""))
    }

    fun testBrowserShortcutActionsAreRegisteredForKeymapCustomization() {
        val pluginXml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()
        assertEquals(
            "Mac shortcut declarations must replace inherited Ctrl variants",
            11,
            Regex("""keymap="Mac OS X"[^>]*replace-all="true"""").findAll(pluginXml).count(),
        )
        val actionIDs = OpenCodeBrowserCommand.entries.map { it.intellijActionID } + listOf(
            OPEN_CODE_ZOOM_IN_ACTION_ID,
            OPEN_CODE_ZOOM_OUT_ACTION_ID,
            OPEN_CODE_RESET_ZOOM_ACTION_ID,
        )

        actionIDs.forEach { actionID ->
            val action = ActionManager.getInstance().getAction(actionID)
            assertNotNull(actionID, action)
            assertTrue(actionID, action.shortcutSet.shortcuts.isNotEmpty())
        }

        val expectedModifier = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        val newSessionShortcuts = ActionManager.getInstance()
            .getAction(OpenCodeBrowserCommand.NEW_SESSION.intellijActionID)
            .shortcutSet
            .shortcuts
            .filterIsInstance<KeyboardShortcut>()
        assertTrue(
            newSessionShortcuts.any {
                it.firstKeyStroke.keyCode == KeyEvent.VK_T && it.firstKeyStroke.modifiers and expectedModifier != 0
            },
        )
        assertTrue(
            newSessionShortcuts.any {
                it.firstKeyStroke.keyCode == KeyEvent.VK_N && it.firstKeyStroke.modifiers and expectedModifier != 0
            },
        )
        val chooseModelShortcuts = ActionManager.getInstance()
            .getAction(OpenCodeBrowserCommand.CHOOSE_MODEL.intellijActionID)
            .shortcutSet
            .shortcuts
            .filterIsInstance<KeyboardShortcut>()
        assertTrue(
            chooseModelShortcuts.any {
                it.firstKeyStroke.keyCode == KeyEvent.VK_QUOTE && it.firstKeyStroke.modifiers and expectedModifier != 0
            },
        )
        assertTrue(
            chooseModelShortcuts.any {
                it.firstKeyStroke.keyCode == KeyEvent.VK_NUMBER_SIGN &&
                    it.firstKeyStroke.modifiers and expectedModifier != 0 &&
                    it.firstKeyStroke.modifiers and InputEvent.SHIFT_DOWN_MASK != 0
            },
        )
        val cycleAgentReverseShortcuts = ActionManager.getInstance()
            .getAction(OpenCodeBrowserCommand.CYCLE_AGENT_REVERSE.intellijActionID)
            .shortcutSet
            .shortcuts
            .filterIsInstance<KeyboardShortcut>()
        assertTrue(
            cycleAgentReverseShortcuts.any {
                it.firstKeyStroke.keyCode == KeyEvent.VK_PERIOD &&
                    it.firstKeyStroke.modifiers and expectedModifier != 0 &&
                    it.firstKeyStroke.modifiers and InputEvent.SHIFT_DOWN_MASK != 0
            },
        )
        val zoomInShortcuts = ActionManager.getInstance()
            .getAction(OPEN_CODE_ZOOM_IN_ACTION_ID)
            .shortcutSet
            .shortcuts
            .filterIsInstance<KeyboardShortcut>()
        assertTrue(
            zoomInShortcuts.any {
                it.firstKeyStroke.keyCode == KeyEvent.VK_PLUS && it.firstKeyStroke.modifiers and expectedModifier != 0
            },
        )
    }

    fun testSharedServerManagerStopsServerAndClearsLifecycleState() {
        val service = nativeManager()
        val process = RecordingProcess()
        val future = RecordingFuture()
        service.installTestServerState(
            process = process,
            url = "http://127.0.0.1:60482",
            password = "secret-password",
            checkFuture = future,
        )
        service.setServerRunning(true)

        service.stopServer()

        assertTrue(process.destroyed)
        assertTrue(future.cancelled)
        assertFalse(service.isServerRunning())
        assertEquals(OpenCodeServerLifecycleState.STOPPED, service.getLifecycleState())
        assertNull(service.getServerProcess())
        assertNull(service.getServerUrl())
        assertNull(service.getServerPassword())
        assertTrue(service.isServerReadyForAuth())
        assertEquals("http://127.0.0.1:60482", service.getAuthServerUrl())
        assertEquals("secret-password", service.getAuthPassword())
    }

    fun testStopCannotBeOvertakenByReservedHealthRestartPublication() {
        val service = nativeManager()
        val url = "http://127.0.0.1:60482"
        service.installTestServerState(url = url, password = "secret-password")
        service.setServerRunning(true)
        val restartingPublished = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        connection.subscribe(
            OpenCodeServerLifecycleListener.TOPIC,
            object : OpenCodeServerLifecycleListener {
                override fun stateChanged(state: OpenCodeServerLifecycleState, backendId: String) {
                    if (state != OpenCodeServerLifecycleState.RESTARTING) return
                    restartingPublished.countDown()
                    assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reserve = executor.submit<Boolean> { service.reserveHealthRestartForTests(url) }
            assertTrue(restartingPublished.await(5, TimeUnit.SECONDS))
            val stop = executor.submit { service.stopServer() }
            assertFalse("stop must wait until restart publication leaves the manager lock", stop.isDone)
            releasePublication.countDown()
            assertTrue(reserve.get(5, TimeUnit.SECONDS))
            stop.get(5, TimeUnit.SECONDS)
            assertEquals(OpenCodeServerLifecycleState.STOPPED, service.getLifecycleState())
        } finally {
            releasePublication.countDown()
            executor.shutdownNow()
        }
    }

    fun testSharedServerManagerForceKillsStubbornServerProcess() {
        val service = nativeManager()
        val process = StubbornProcess()
        service.installTestServerState(
            process = process,
            url = "http://127.0.0.1:60482",
            password = "secret-password",
        )
        service.setServerRunning(true)

        service.stopServer()

        assertTrue(process.destroyed)
        assertTrue(process.forceDestroyed)
        assertFalse(process.isAlive)
        assertNull(service.getServerProcess())
    }

    fun testSharedServerManagerTracksManualRunningLifecycleState() {
        val service = nativeManager()

        service.stopServer()
        assertEquals(OpenCodeServerLifecycleState.STOPPED, service.getLifecycleState())

        service.setServerRunning(true)
        assertEquals(OpenCodeServerLifecycleState.RUNNING, service.getLifecycleState())

        service.setServerRunning(false)
        assertEquals(OpenCodeServerLifecycleState.STOPPED, service.getLifecycleState())
    }

    fun testServerReadyForAuthWhenUrlAndPasswordPresentEvenIfLauncherDead() {
        val service = nativeManager()
        val process = RecordingProcess()
        process.destroy()
        assertFalse(process.isAlive)

        service.installTestServerState(
            process = process,
            url = "http://127.0.0.1:60482",
            password = "secret-password",
        )
        service.setServerRunning(true)

        assertTrue(service.isServerReadyForAuth())
        assertFalse(service.getServerProcess()?.isAlive == true)
    }

    fun testServerNotReadyForAuthWithoutCredentials() {
        val service = OpenCodeServerBackendRegistry.getInstance()
            .backendForCanonicalDirectory("/tmp/opencode-plugin-test-no-auth") as SharedOpenCodeServerManager
        assertFalse(service.isServerReadyForAuth())
    }

    override fun tearDown() {
        try {
            OpenCodeServerBackendRegistry.getInstance().stopAllNativeBackends()
        } finally {
            super.tearDown()
        }
    }

    private fun nativeManager(): SharedOpenCodeServerManager {
        return OpenCodeServerBackendRegistry.getInstance()
            .backendForCanonicalDirectory("/tmp/opencode-plugin-test") as SharedOpenCodeServerManager
    }

    private class RecordingProcess : Process() {
        var destroyed = false
            private set
        private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("Process is still alive")
            return 0
        }

        override fun destroy() {
            destroyed = true
            alive = false
        }

        override fun isAlive(): Boolean = alive
    }

    private class StubbornProcess : Process() {
        var destroyed = false
            private set
        var forceDestroyed = false
            private set
        private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return !alive
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("Process is still alive")
            return 0
        }

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            forceDestroyed = true
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    private class RecordingFuture : ScheduledFuture<Unit> {
        var cancelled = false
            private set

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled = true
            return true
        }

        override fun isCancelled(): Boolean = cancelled

        override fun isDone(): Boolean = cancelled

        override fun get(): Unit = Unit

        override fun get(timeout: Long, unit: TimeUnit): Unit = Unit

        override fun getDelay(unit: TimeUnit): Long = 0

        override fun compareTo(other: java.util.concurrent.Delayed): Int = 0
    }
}
