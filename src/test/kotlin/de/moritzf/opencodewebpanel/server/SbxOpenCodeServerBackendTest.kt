package de.moritzf.opencodewebpanel.server

import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.replaceService
import de.moritzf.opencodewebpanel.settings.OpenCodeBinaryMode
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

class SbxOpenCodeServerBackendTest {
    companion object {
        @ClassRule
        @JvmField
        val application = ApplicationRule()
    }

    @get:Rule
    val disposable = DisposableRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private val store = SbxSandboxRecordStore()
    private lateinit var directory: String
    private lateinit var record: SbxSandboxRecord
    private lateinit var backend: SbxOpenCodeServerBackend
    private var createWorkingDirectory: Path? = null
    private var behavior: (List<String>) -> SbxCommandResult = { command ->
        when (command[1]) {
            "ls" -> listed()
            "daemon" -> SbxCommandResult(17, "daemon unavailable")
            else -> SbxCommandResult(0, "")
        }
    }

    // The production path already supports projects disposed before the queued start executes.
    // This avoids creating progress UI while still exercising ensure/restart/reset themselves.
    private val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> true
            "toString" -> "Disposed sandbox-test project"
            else -> error("Unexpected project access: ${method.name}")
        }
    } as Project

    @Before
    fun setUp() {
        directory = temp.root.toPath().toRealPath().toString()
        record = SbxSandboxRecord("owned-id", SbxCli.sandboxName(directory), "opencode", directory, createSnapshot = "")
        store.save(directory, record)
        val settings = OpenCodeSettingsState().apply {
            enableServerLogs = false
            sbxBinaryMode = OpenCodeBinaryMode.CUSTOM.name
            // Only executable detection touches this file. Every CLI command goes to the fake below.
            sbxBinaryPath = Path.of(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java").toString()
        }
        ApplicationManager.getApplication().replaceService(OpenCodeSettingsState::class.java, settings, disposable.disposable)
        backend = SbxOpenCodeServerBackend(
            directory,
            SbxCommandRunner { command, _, _, cwd ->
                assertFalse("CLI must never run on EDT", SwingUtilities.isEventDispatchThread())
                if (command[1] == "create") createWorkingDirectory = cwd
                calls += command[1]
                behavior(command)
            },
            { store },
            executor,
        )
    }

    @After
    fun tearDown() {
        backend.dispose()
        executor.shutdown()
        assertTrue("Lifecycle worker leaked", executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    @Test
    fun queuedStopCannotKillALaterStart() = assertStopBeforeStart {
        backend.ensureStarted(project, directory, { false }, {}, {})
    }

    @Test
    fun restartAlsoWaitsForQueuedStop() = assertStopBeforeStart {
        backend.restartServer(project, directory, { false }, {}, {})
    }

    @Test
    fun resetWaitsForStopThenRemovesBeforeStarting() {
        assertStopBeforeStart(expected = listOf("ls", "stop", "ls", "rm", "diagnose", "daemon")) {
            backend.resetSandbox(project, { false }, {}, {})
        }
        assertNull(store.recordFor(directory))
    }

    @Test
    fun resetLeavesBackendAbleToStartAgain() {
        backend.resetSandbox(project, { false }, {}, {})
        drain()
        calls.clear()
        backend.ensureStarted(project, directory, { false }, {}, {})
        drain()
        assertEquals(listOf("diagnose", "daemon"), calls.toList())
    }

    @Test
    fun appendedKitsPreserveSandboxAndCheckpointSuccessfulAdditions() {
        val settings = OpenCodeSettingsState.getInstance().apply { sbxNetworkPolicyConsent = true }
        val spec = SbxLaunchSpec.fromSettings(settings, directory).copy(
            useSandbox = true, kits = listOf("./first-kit", "./second-kit"), enableIntellijMcp = false,
        )
        assertNotNull(SbxLaunchSpec.persist(spec))
        val added = mutableListOf<String>()
        behavior = { command ->
            when (command[1]) {
                "ls" -> listed("stopped")
                "kit" -> {
                    assertEquals("add", command[2])
                    added += command.last()
                    if (command.last() == "./second-kit") SbxCommandResult(21, "unsupported kit field")
                    else SbxCommandResult(0, "kit added")
                }
                else -> SbxCommandResult(0, "")
            }
        }
        backend.ensureStarted(project, directory, { false }, {}, {})
        drain()
        assertEquals(listOf("./first-kit", "./second-kit"), added)
        assertEquals(record.copy(kits = "./first-kit"), store.recordFor(directory))
        assertFalse(calls.contains("rm"))
        assertFalse(calls.contains("create"))
        assertTrue(backend.startFailureMessage()!!.contains("Add sandbox kit failed (exit 21)"))

        backend.ensureStarted(project, directory, { false }, {}, {})
        drain()
        assertEquals(listOf("./first-kit", "./second-kit", "./second-kit"), added)
        assertFalse("Do not retry an already installed kit or delete the VM", calls.contains("rm"))
    }

    @Test
    fun projectSpecControlsProvisioningAndCreateFailureIsReported() {
        store.remove(directory)
        val settings = OpenCodeSettingsState.getInstance().apply { sbxNetworkPolicyConsent = true }
        val spec = SbxLaunchSpec.fromSettings(settings, directory).copy(
            useSandbox = true, memory = "8g", cpus = "4", kits = listOf("./opencode-network-kit"),
            hostPort = 49123, enableIntellijMcp = false,
        )
        assertNotNull(SbxLaunchSpec.persist(spec))
        var createArgs: List<String>? = null
        behavior = { command ->
            when (command[1]) {
                "ls" -> SbxCommandResult(0, """{"sandboxes":[]}""")
                "create" -> {
                    createArgs = command
                    SbxCommandResult(21, "kit source not allowed")
                }
                else -> SbxCommandResult(0, "")
            }
        }
        backend.ensureStarted(project, directory, { false }, {}, {})
        drain()
        assertEquals(listOf("diagnose", "daemon", "ls", "create"), calls.toList())
        assertEquals(Path.of(directory), createWorkingDirectory)
        assertEquals(
            SbxCli.buildCreateCommand(
                settings.sbxBinaryPath, record.name, directory,
                memory = "8g", cpus = "4", hostPort = 49123, kits = spec.kits,
                extraWorkspaces = SbxCli.extraMountCreateArgs(
                    SbxCli.sandboxProtectMounts(directory, spec.kits) +
                        listOf(SbxCli.persistSandboxMount(record.name)),
                    directory,
                ),
            ),
            createArgs,
        )
        assertEquals(spec, SbxLaunchSpec.load(directory))
        assertTrue(backend.startFailureMessage()!!.contains("Create sandbox failed (exit 21)"))
        assertTrue(backend.startFailureMessage()!!.contains("kit source not allowed"))
    }

    @Test
    fun stopCompletionRunsAfterCleanup() {
        backend.stopServer { calls += "stopped callback" }
        drain()
        assertEquals(listOf("ls", "stop", "stopped callback"), calls.toList())
    }

    @Test
    fun failedVmStopDoesNotSignalSuccessfulHandoff() {
        behavior = { command ->
            when (command[1]) {
                "ls" -> listed()
                "stop" -> SbxCommandResult(2, "vm still running")
                else -> SbxCommandResult(0, "")
            }
        }
        backend.stopServer { calls += "stopped callback" }
        drain()
        assertEquals(listOf("ls", "stop"), calls.toList())
        assertEquals(OpenCodeServerLifecycleState.FAILED, backend.getLifecycleState())
        assertTrue(backend.startFailureMessage()!!.contains("vm still running"))
    }

    @Test
    fun adoptedSandboxIsNotDeletedWhenProvisioningDiffers() {
        val settings = OpenCodeSettingsState.getInstance().apply { sbxNetworkPolicyConsent = true }
        val spec = SbxLaunchSpec.fromSettings(settings, directory).copy(
            useSandbox = true,
            kits = listOf("./new-kit"),
            extraMounts = listOf(SbxExtraMount("/tmp/docs", "/home/agent/docs")),
            enableIntellijMcp = false,
        )
        assertNotNull(SbxLaunchSpec.persist(spec))
        store.save(directory, record.copy(adopted = true, kits = ""))
        behavior = { command ->
            when (command[1]) {
                "ls" -> listed("stopped")
                "exec" -> SbxCommandResult(1, "link skipped")
                else -> SbxCommandResult(0, "")
            }
        }
        backend.ensureStarted(project, directory, { false }, {}, {})
        drain()
        assertFalse(calls.contains("rm"))
        assertFalse(calls.contains("create"))
        assertEquals(record.copy(adopted = true, kits = ""), store.recordFor(directory))
    }

    @Test
    fun liveProjectProgressIsDisposedOnEdtWithoutMaskingStartupResult() {
        val liveProject = MockProject(null, disposable.disposable)
        val disposed = CountDownLatch(1)
        val disposedOnEdt = AtomicBoolean()
        val original = behavior
        behavior = { command ->
            if (command[1] == "diagnose") {
                val indicator = ProgressManager.getInstance().progressIndicator
                assertTrue("Exercise the real UI indicator, not the disposed-project fallback", indicator is BackgroundableProcessIndicator)
                Disposer.register(indicator as Disposable, Disposable {
                    disposedOnEdt.set(SwingUtilities.isEventDispatchThread())
                    disposed.countDown()
                })
            }
            original(command)
        }
        backend.ensureStarted(liveProject, directory, { false }, {}, {})
        drain()
        assertTrue("Progress indicator leaked", disposed.await(5, TimeUnit.SECONDS))
        assertTrue("Progress UI must be disposed on EDT", disposedOnEdt.get())
        assertEquals(listOf("diagnose", "daemon"), calls.toList())
        assertEquals(SbxFailureKind.COMMAND_FAILED, backend.lastFailure())
        assertTrue(backend.startFailureMessage()!!.contains("exit 17"))
    }

    @Test
    fun failedDaemonDoesNotContinueOrBootVmDuringCleanup() {
        backend.ensureStarted(project, directory, { false }, {}, {})
        drain()
        assertEquals(listOf("diagnose", "daemon"), calls.toList())
        assertEquals(OpenCodeServerLifecycleState.FAILED, backend.getLifecycleState())
        assertTrue(backend.startFailureMessage()!!.contains("exit 17"))
        assertTrue(backend.startFailureMessage()!!.contains("daemon unavailable"))
    }

    @Test
    fun destroyReturnsOnEdtWithoutWaitingForCli() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        behavior = { command ->
            if (command[1] == "ls") listed() else {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                SbxCommandResult(0, "")
            }
        }
        lateinit var result: CompletableFuture<Boolean>
        try {
            SwingUtilities.invokeAndWait { result = backend.destroySandbox() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            assertNotNull(store.recordFor(directory))
        } finally {
            release.countDown()
        }
        assertTrue(result.get(5, TimeUnit.SECONDS))
        assertNull(store.recordFor(directory))
        assertEquals(listOf("ls", "rm"), calls.toList())
    }

    @Test
    fun failedRemovalKeepsOwnershipAndDoesNotStart() {
        behavior = { command -> if (command[1] == "ls") listed() else SbxCommandResult(9, "remove refused") }
        assertThrows(ExecutionException::class.java) { backend.destroySandbox().get(5, TimeUnit.SECONDS) }
        assertEquals(record, store.recordFor(directory))
        assertEquals(listOf("ls", "rm"), calls.toList())
    }

    @Test
    fun malformedListCannotEraseOwnership() {
        behavior = { SbxCommandResult(0, "not JSON") }
        assertThrows(ExecutionException::class.java) { backend.destroySandbox().get(5, TimeUnit.SECONDS) }
        assertEquals(record, store.recordFor(directory))
        assertEquals(listOf("ls"), calls.toList())
    }

    @Test
    fun stopOfAnAlreadyStoppedVmNeverExecs() {
        behavior = { listed("stopped") }
        backend.stopServer()
        drain()
        assertEquals(listOf("ls"), calls.toList())
    }

    @Test
    fun healthyPublishedUrlSkipsDeadMappingsAndUsesALaterPort() {
        val ports = listOf(
            SbxPortMapping("127.0.0.1", 49154, 4096, "tcp4"),
            SbxPortMapping("127.0.0.1", 49156, 4096, "tcp4"),
        )
        val probed = mutableListOf<String>()
        val url = healthyPublishedSandboxUrl(ports, desiredHostPort = null, password = "probe") { candidate, _ ->
            probed += candidate
            candidate.endsWith(":49156")
        }
        assertEquals("http://127.0.0.1:49156", url)
        assertEquals(listOf("http://127.0.0.1:49154", "http://127.0.0.1:49156"), probed)
    }

    @Test
    fun exitedServeFailsBeforeAnotherHealthOrPortAttempt() {
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 23
            override fun exitValue() = 23
            override fun destroy() = Unit
            override fun isAlive() = false
        }
        val error = assertThrows(SbxCommandFailure::class.java) { checkSbxServeAlive(process) { "serve failed" } }
        assertTrue(error.message!!.contains("exit 23"))
        assertEquals("serve failed", error.output)
    }

    private fun assertStopBeforeStart(
        expected: List<String> = listOf("ls", "stop", "diagnose", "daemon"),
        start: () -> Unit,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starting = CountDownLatch(1)
        val original = behavior
        behavior = { command ->
            if (command[1] == "stop") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            if (command[1] == "diagnose") starting.countDown()
            original(command)
        }
        try {
            backend.stopServer()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            start()
            assertFalse("Start overtook pending Stop", starting.await(150, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        drain()
        assertEquals(expected, calls.toList())
    }

    private fun drain() = executor.submit {}.get(10, TimeUnit.SECONDS)

    private fun listed(status: String = "running"): SbxCommandResult {
        val workspace = directory.replace("\\", "\\\\")
        return SbxCommandResult(0, """{"sandboxes":[{"id":"owned-id","name":"${record.name}","agent":"opencode","status":"$status","workspaces":["$workspace"]}]}""")
    }
}
