package de.moritzf.opencodewebpanel.server

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.EdtInvocationManager
import de.moritzf.opencodewebpanel.settings.OpenCodePasswordStore
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class SbxCommandFailure(
    stage: String,
    exitCode: Int,
    val output: String,
    /** False for OpenCode's own output, whose provider errors must not read as an sbx login problem. */
    val fromSbx: Boolean = true,
) : Exception("$stage failed (exit $exitCode).")

internal fun checkSbxServeAlive(process: Process, output: () -> String) {
    if (!process.isAlive) {
        throw SbxCommandFailure("OpenCode serve exited before health", process.exitValue(), output(), fromSbx = false)
    }
}

internal fun healthyPublishedSandboxUrl(
    ports: List<SbxPortMapping>,
    desiredHostPort: Int?,
    password: String,
    responding: (serverUrl: String, basicAuthHeader: String) -> Boolean = { url, auth ->
        OpenCodeServerProtocol.checkServerResponding(url, auth)
    },
): String? {
    val auth = OpenCodeServerProtocol.buildBasicAuthHeader(password)
    for (port in SbxCli.publishedHostPorts(ports, desiredHostPort = desiredHostPort)) {
        val url = OpenCodeServerProtocol.publishedSandboxUrl(port)
        if (OpenCodeServerProtocol.isLoopbackServerUrl(url) && responding(url, auth)) return url
    }
    return null
}

internal enum class SbxFailureKind {
    NONE,
    SBX_NOT_FOUND,
    NOT_AUTHENTICATED,
    UNSUPPORTED_OS,
    POLICY_UNINITIALIZED,
    FOREIGN_SANDBOX,
    SERVE_UNHEALTHY,
    COMMAND_FAILED,
    UPGRADE_FAILED,
    INSTALL_V2_FAILED,
    INVALID_V2_BINARY,
    CANCELLED,
    UNTRUSTED_PROJECT,
    EXPOSURE_UNCONFIRMED,
    RECREATE_REQUIRED,
}

private fun defaultSandboxTrustCheck(project: Project?, directory: String): Boolean = runCatching {
    if (project != null && !project.isDisposed) {
        TrustedProjects.isProjectTrusted(project)
    } else {
        TrustedProjects.isProjectTrusted(Path.of(directory))
    }
}.getOrDefault(false)

internal class SbxOpenCodeServerBackend(
    private val canonicalDirectory: String,
    private val commandRunner: SbxCommandRunner = SbxProcessRunner,
    private val recordStore: () -> SbxSandboxRecordStore = { SbxSandboxRecordStore.getInstance() },
    private val lifecycleExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenCode-Sbx-Lifecycle").apply { isDaemon = true }
    },
    private val trustCheck: (Project?, String) -> Boolean = ::defaultSandboxTrustCheck,
) : OpenCodeServerBackend {

    override val backendId: String = "sbx:${SbxCli.sandboxName(canonicalDirectory)}"
    override val offersHostPortControls: Boolean = true

    private data class StartCallback(
        val isActive: () -> Boolean,
        val onStarted: () -> Unit,
        val onFailed: () -> Unit,
    )

    private data class PendingBinaryUpgrade(
        val startId: Long,
        val sandboxName: String,
        val installV2: Boolean = false,
    )

    private val lock = Any()
    private val pendingStarts = mutableListOf<StartCallback>()
    private var startSequence = 0L
    private var starting = false
    private var disposed = false
    private var allowHealthRestart = true
    private var lifecycleState = OpenCodeServerLifecycleState.STOPPED
    private var serverProcess: Process? = null
    private var serverUrl: String? = null
    private var serverPassword: String? = null
    private var authServerUrl: String? = null
    private var authServerPassword: String? = null
    private var serverVersion: String? = null
    private var unsupportedVersionWarningShownFor: String? = null
    private var wireProtocol = OpenCodeWireProtocol.UNKNOWN
    private var v2ProtocolWarningShown = false
    private var serverGeneration = 0L
    private var serverGenerationStartedAtMillis = 0L
    private var lastFailure = SbxFailureKind.NONE
    private var lastFailureDetails: String? = null
    private var startupStage: String? = null
    private var pendingBinaryUpgrade: PendingBinaryUpgrade? = null
    private var lastRecovery: OpenCodeRecoveryNotice? = null
    private var lastForeignSandbox: SbxSandboxListEntry? = null
    private var pendingCreateStaleReasons: List<String> = emptyList()
    private var warnedCreateSnapshot: String? = null
    private var pendingExposure: SbxExposure? = null
    private var pendingRecreateReasons: List<String> = emptyList()

    /** Indicator of the running start; cancelling it stops the current sbx command. */
    private var activeIndicator: ProgressIndicator? = null

    /** Trust of the project that last asked for a start; health restarts have no project. */
    @Volatile
    private var requesterTrusted: Boolean? = null

    private var checkScheduledFuture: ScheduledFuture<*>? = null
    private val globalEventStream = OpenCodeGlobalEventStream()
    private val processTerminator = OpenCodeProcessTerminator()
    private val serverLogBuffer = OpenCodeServerLogBuffer()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "OpenCode-Sbx-Checker").apply { isDaemon = true }
    }

    fun lastFailure(): SbxFailureKind = synchronized(lock) { lastFailure }

    fun foreignSandbox(): SbxSandboxListEntry? = synchronized(lock) { lastForeignSandbox }

    fun adoptForeignSandbox(): Boolean {
        val entry = synchronized(lock) { lastForeignSandbox } ?: return false
        if (entry.agent != SbxCli.AGENT) return false
        if (ownedByAnotherDirectory(entry)) return false
        if (entry.workspaces.none { OpenCodeServerProtocol.isSameFilesystemPath(it, canonicalDirectory) }) {
            return false
        }
        recordStore().save(
            canonicalDirectory,
            SbxSandboxRecord(
                sandboxId = entry.id,
                name = entry.name,
                agent = entry.agent,
                workspace = canonicalDirectory,
                adopted = true,
            ),
        )
        synchronized(lock) {
            lastForeignSandbox = null
            lastFailure = SbxFailureKind.NONE
        }
        return true
    }

    fun discardForeignSandbox() {
        val entry = synchronized(lock) { lastForeignSandbox } ?: return
        // Never remove a VM this plugin records for another directory.
        if (ownedByAnotherDirectory(entry)) return
        synchronized(lock) {
            if (disposed) return
            runOnLifecycle {
                try {
                    requiredCommand("Remove sandbox", SbxCli.buildRmForceCommand(sbxExecutable(), entry.name), 60_000L)
                    synchronized(lock) { lastForeignSandbox = null }
                } catch (e: SbxCommandFailure) {
                    recordCommandFailure(e)
                }
            }
        }
    }

    override fun startFailureMessage(): String? {
        return when (lastFailure()) {
            SbxFailureKind.NONE -> null
            SbxFailureKind.SBX_NOT_FOUND ->
                "The sbx CLI was not found. Install Docker Sandboxes (brew install docker/tap/sbx or winget install Docker.sbx) and Detect its path."
            SbxFailureKind.NOT_AUTHENTICATED ->
                "Docker Sandboxes is not signed in. Run sbx login in a terminal, then Retry."
            SbxFailureKind.UNSUPPORTED_OS ->
                "Docker Sandboxes is not supported on this machine. Switch runtime to Host (native CLI)."
            SbxFailureKind.POLICY_UNINITIALIZED ->
                "Sandbox network policy is not set up. Open OpenCode Web Panel settings and set up the default network policy, or stay on Host."
            SbxFailureKind.FOREIGN_SANDBOX ->
                "A sandbox already exists at this name or workspace that this panel does not own. Adopt it or create a new one from settings."
            SbxFailureKind.SERVE_UNHEALTHY ->
                "The sandbox started but OpenCode did not become available on the published loopback port."
            SbxFailureKind.COMMAND_FAILED -> synchronized(lock) { lastFailureDetails }
                ?: "A Docker Sandboxes command failed. Check the server log."
            SbxFailureKind.UPGRADE_FAILED ->
                "opencode upgrade failed inside the sandbox. Check the server log. Sessions were kept."
            SbxFailureKind.INSTALL_V2_FAILED ->
                "Installing OpenCode 2.x inside the sandbox failed. Check the server log " +
                    "(needs network to opencode.ai and registry.npmjs.org). Sessions were kept."
            SbxFailureKind.INVALID_V2_BINARY ->
                "The guest OpenCode 2.x binary is missing, cannot run, or has the wrong version. " +
                    "Reinstall OpenCode 2.x in the sandbox, then Retry.\n" +
                    synchronized(lock) { lastFailureDetails.orEmpty() }
            SbxFailureKind.CANCELLED ->
                "Start was cancelled."
            SbxFailureKind.UNTRUSTED_PROJECT ->
                "This project is not trusted, so its opencode-sbx.yaml is not used to create a Docker Sandbox. " +
                    "Trust the project, or switch the runtime to Host (native CLI)."
            SbxFailureKind.EXPOSURE_UNCONFIRMED -> {
                val items = synchronized(lock) { pendingExposure?.items }.orEmpty()
                "opencode-sbx.yaml gives this sandbox access beyond the project:\n" +
                    items.joinToString("\n") { "• $it" } +
                    "\nReview the file, then choose Allow and Start."
            }
            SbxFailureKind.RECREATE_REQUIRED -> {
                val reasons = synchronized(lock) { pendingRecreateReasons }
                "This sandbox was created with different settings than opencode-sbx.yaml:\n" +
                    reasons.joinToString("\n") { "• $it" } +
                    "\nRecreate the sandbox to apply them, or revert the file. Recreating drops VM-only data."
            }
        }
    }

    fun pendingRecreateReasons(): List<String> = synchronized(lock) { pendingRecreateReasons }

    fun pendingExposure(): SbxExposure? = synchronized(lock) { pendingExposure }

    /**
     * Records the user's consent to the grants in the current project spec. Called from the
     * failure card and from settings Apply (the user edited those values themselves).
     */
    fun acknowledgeExposure(spec: SbxLaunchSpec? = SbxLaunchSpec.load(canonicalDirectory)) {
        val exposure = spec?.let { SbxExposure.of(it, canonicalDirectory) } ?: return
        recordStore().acknowledgeExposure(canonicalDirectory, exposure.fingerprint)
        synchronized(lock) {
            pendingExposure = null
            if (lastFailure == SbxFailureKind.EXPOSURE_UNCONFIRMED) lastFailure = SbxFailureKind.NONE
        }
    }

    fun startupStage(): String? = synchronized(lock) { startupStage }

    fun lastRecoveryNotice(): OpenCodeRecoveryNotice? = synchronized(lock) { lastRecovery }

    fun diagnostics(): SbxDiagnosticsSnapshot {
        val record = recordStore().recordFor(canonicalDirectory)
        return SbxDiagnosticsSnapshot.from(
            specName = SbxCli.sandboxName(canonicalDirectory),
            record = record,
            foreign = lastFailure() == SbxFailureKind.FOREIGN_SANDBOX || foreignSandbox() != null,
            serverUrl = getServerUrl(),
            version = getServerVersion(),
            persistEnabled = SbxLaunchSpec.load(canonicalDirectory)?.persistSandboxSessions ?: true,
        )
    }

    fun checkSetup(): CompletableFuture<List<SbxSetupStep>> {
        val result = CompletableFuture<List<SbxSetupStep>>()
        synchronized(lock) {
            if (disposed) return CompletableFuture.completedFuture(emptyList())
            runOnLifecycle(onReject = { result.complete(emptyList()) }) {
                try {
                    val spec = SbxLaunchSpec.load(canonicalDirectory)
                    result.complete(if (spec == null) {
                        listOf(SbxSetupStep("Project spec", false, "Apply valid sandbox settings before checking setup"))
                    } else {
                        SbxSetupDiagnostics.check(sbxExecutable(), spec, recordStore().recordFor(canonicalDirectory), commandRunner)
                    })
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
        }
        return result
    }

    override fun getServerLogFile(): Path? = serverLogBuffer.currentOrLatestFile()

    override fun ensureStarted(
        project: Project,
        projectBasePath: String?,
        callbackActive: () -> Boolean,
        onStarted: () -> Unit,
        onFailed: () -> Unit,
    ) {
        enqueueStart(project, StartCallback(callbackActive, onStarted, onFailed), OpenCodeServerLifecycleState.STARTING) { startId ->
            val url = getServerUrl()
            if (url != null && checkServerResponding(url)) {
                finishStart(startId, success = true)
                false
            } else {
                // A RUNNING state that no longer answers restarts: say so, and let the RUNNING
                // transition after it re-point the event stream at the new mapping.
                setLifecycleState(OpenCodeServerLifecycleState.RESTARTING)
                stopOwnedServe(stopVm = false)
                true
            }
        }
    }

    override fun isServerReadyForAuth(): Boolean = synchronized(lock) {
        !authServerUrl.isNullOrBlank() && !authServerPassword.isNullOrBlank()
    }

    override fun getAuthServerUrl(): String? = synchronized(lock) { authServerUrl }

    override fun getAuthPassword(): String? = synchronized(lock) { authServerPassword }

    override fun verifyServerNow(callbackActive: () -> Boolean, onHealthy: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val url = getServerUrl()
            if (url != null && checkServerResponding(url)) {
                ApplicationManager.getApplication().invokeLater {
                    if (callbackActive()) onHealthy()
                }
                return@executeOnPooledThread
            }
            try {
                checkServerHealth()
            } catch (e: Exception) {
                thisLogger().warn("OpenCode SBX health check after browser failure failed: ${e.message}")
            }
        }
    }

    override fun getLifecycleState(): OpenCodeServerLifecycleState = synchronized(lock) { lifecycleState }

    override fun getServerUrl(): String? = synchronized(lock) { serverUrl }

    override fun getServerPassword(): String? = synchronized(lock) { serverPassword }

    override fun getServerVersion(): String? = synchronized(lock) { serverVersion }

    override fun getWireProtocol(): OpenCodeWireProtocol = synchronized(lock) { wireProtocol }

    override fun consumeUnsupportedServerVersionWarning(): String? = synchronized(lock) {
        val version = serverVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return@synchronized null
        if (!OpenCodeServerProtocol.isOpenCodeVersionUnsupported(version) || unsupportedVersionWarningShownFor == version) {
            return@synchronized null
        }
        unsupportedVersionWarningShownFor = version
        version
    }

    override fun consumeV2ProtocolWarning(): Boolean = synchronized(lock) {
        if (v2ProtocolWarningShown || wireProtocol != OpenCodeWireProtocol.V1_18_EMBEDDED_V2) {
            return@synchronized false
        }
        v2ProtocolWarningShown = true
        true
    }

    override fun consumeCreateStaleWarning(): List<String> = synchronized(lock) {
        val reasons = pendingCreateStaleReasons
        pendingCreateStaleReasons = emptyList()
        reasons
    }

    private fun noteStaleCreate(
        record: SbxSandboxRecord,
        memory: String,
        cpus: String,
        protectSandboxFiles: Boolean,
        extraCreateArgs: List<String>,
        shareHostConfig: Boolean,
        persistSandboxSessions: Boolean,
    ) {
        val desired = SbxCli.createSnapshot(memory, cpus, protectSandboxFiles, extraCreateArgs)
        val reasons = SbxCli.staleCreateReasons(
            record, memory, cpus, protectSandboxFiles, extraCreateArgs, shareHostConfig,
            persistSandboxSessions,
        )
        synchronized(lock) {
            if (reasons.isEmpty()) {
                pendingCreateStaleReasons = emptyList()
                return
            }
            if (warnedCreateSnapshot == desired) return
            pendingCreateStaleReasons = reasons
            warnedCreateSnapshot = desired
        }
    }

    override fun getServerGeneration(): Long = synchronized(lock) { serverGeneration }

    override fun getServerGenerationStartedAtMillis(): Long = synchronized(lock) { serverGenerationStartedAtMillis }

    override fun stopServer(onStopped: () -> Unit) {
        synchronized(lock) {
            if (disposed) {
                onStopped()
                return
            }
            cancelPendingStarts()
            allowHealthRestart = false
            // Enqueue while holding the same lock as Start. A later Start cannot overtake this stop.
            runOnLifecycle(
                onReject = onStopped,
            ) {
                try {
                    if (!stopOwnedServe(stopVm = true)) {
                        setLifecycleState(OpenCodeServerLifecycleState.FAILED)
                    }
                } finally {
                    onStopped()
                }
            }
            // Publish only after enqueue: a synchronous lifecycle subscriber may request Start.
            setLifecycleState(OpenCodeServerLifecycleState.STOPPED)
        }
    }

    /** [onDone] receives an error message when a live change (kit add, port remap) failed. */
    fun applyLiveSettings(onDone: (error: String?) -> Unit = {}) {
        synchronized(lock) {
            if (disposed) {
                onDone(null)
                return
            }
            runOnLifecycle(onReject = { onDone(null) }) {
                var error: String? = null
                try {
                    error = applyLiveOnWorker()
                } catch (e: SbxCommandFailure) {
                    recordCommandFailure(e)
                    error = synchronized(lock) { lastFailureDetails } ?: e.message
                } catch (e: Exception) {
                    thisLogger().warn("Live sandbox apply failed: ${e.message}")
                    error = e.message ?: e::class.java.simpleName
                } finally {
                    onDone(error)
                }
            }
        }
    }

    override fun restartServer(
        project: Project,
        projectBasePath: String?,
        callbackActive: () -> Boolean,
        onStarted: () -> Unit,
        onFailed: () -> Unit,
    ) {
        enqueueStart(project, StartCallback(callbackActive, onStarted, onFailed), OpenCodeServerLifecycleState.RESTARTING) {
            stopOwnedServe(stopVm = false)
            true
        }
    }

    fun upgradeOpenCodeBinary(
        project: Project,
        callbackActive: () -> Boolean = { true },
        onStarted: () -> Unit = {},
        onFailed: () -> Unit = {},
    ) {
        requestBinaryChange(project, callbackActive, onStarted, onFailed, installV2 = false)
    }

    fun installOpenCodeV2(
        project: Project,
        callbackActive: () -> Boolean = { true },
        onStarted: () -> Unit = {},
        onFailed: () -> Unit = {},
    ) {
        requestBinaryChange(project, callbackActive, onStarted, onFailed, installV2 = true)
    }

    private fun requestBinaryChange(
        project: Project,
        callbackActive: () -> Boolean,
        onStarted: () -> Unit,
        onFailed: () -> Unit,
        installV2: Boolean,
    ) {
        enqueueStart(
            project,
            StartCallback(callbackActive, onStarted, onFailed),
            OpenCodeServerLifecycleState.RESTARTING,
            replaceCurrent = true,
        ) { startId ->
            stopOwnedServe(stopVm = false)
            if (!isCurrentStart(startId)) return@enqueueStart false
            val executable = sbxExecutable()
            val listed = parseSandboxList(
                requiredCommand("List sandbox before upgrade", SbxCli.buildLsCommand(executable), 30_000L),
            )
            val record = recordStore().recordFor(canonicalDirectory)
            val owned = record?.let { SbxCli.findOwnedSandbox(listed, it) }
            if (owned == null) {
                val missing = if (installV2) SbxFailureKind.INSTALL_V2_FAILED else SbxFailureKind.UPGRADE_FAILED
                fail(startId, if (record == null) missing else SbxFailureKind.FOREIGN_SANDBOX)
                return@enqueueStart false
            }
            synchronized(lock) {
                pendingBinaryUpgrade = PendingBinaryUpgrade(startId, owned.name, installV2)
            }
            true
        }
    }

    fun dropCachedOfficialOpencodeTemplates(): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        synchronized(lock) {
            if (disposed) {
                result.complete(false)
                return result
            }
            runOnLifecycle(onReject = { result.complete(false) }) {
                try {
                    val executable = sbxExecutable()
                    val listed = requiredCommand("List templates", SbxCli.buildTemplateLsCommand(executable), 30_000L)
                    SbxCli.officialOpencodeTemplateRefs(SbxCli.parseTemplateLsJson(listed.stdout)).forEach { ref ->
                        requiredCommand("Remove template", SbxCli.buildTemplateRmCommand(executable, ref), 60_000L)
                    }
                    result.complete(true)
                } catch (e: SbxCommandFailure) {
                    recordCommandFailure(e)
                    result.complete(false)
                } catch (e: Exception) {
                    thisLogger().warn("Could not drop cached OpenCode sandbox templates: ${e.message}")
                    result.complete(false)
                }
            }
        }
        return result
    }

    /**
     * Removes the owned VM and starts a fresh one from the current spec. Reset also drops the
     * 2.x binary copy; a settings-driven recreate keeps it.
     */
    fun resetSandbox(
        project: Project,
        callbackActive: () -> Boolean = { true },
        onStarted: () -> Unit,
        onFailed: () -> Unit,
        dropGuestOpenCode: Boolean = true,
    ) {
        synchronized(lock) {
            cancelPendingStarts()
            enqueueStart(project, StartCallback(callbackActive, onStarted, onFailed), OpenCodeServerLifecycleState.RESTARTING) {
                val name = recordStore().recordFor(canonicalDirectory)?.name
                stopOwnedServe(stopVm = false)
                removeOwnedSandbox()
                synchronized(lock) { pendingRecreateReasons = emptyList() }
                if (name != null && dropGuestOpenCode) SbxCli.deleteGuestOpenCode(name)
                true
            }
        }
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            cancelPendingStarts()
            disposed = true
            allowHealthRestart = false
            authServerUrl = null
            authServerPassword = null
            setLifecycleState(OpenCodeServerLifecycleState.STOPPED)
            runOnLifecycle(onReject = { scheduler.shutdownNow() }) {
                try {
                    stopOwnedServe(stopVm = true)
                } finally {
                    scheduler.shutdownNow()
                }
            }
            lifecycleExecutor.shutdown()
        }
    }

    private fun cancelPendingStarts() {
        if (starting) lastFailure = SbxFailureKind.CANCELLED
        startSequence++
        activeIndicator?.cancel()
        starting = false
        pendingBinaryUpgrade = null
        val callbacks = pendingStarts.toList()
        pendingStarts.clear()
        notifyStartCallbacks(callbacks, success = false)
    }

    private fun runOnLifecycle(onReject: () -> Unit = {}, action: () -> Unit) {
        try {
            lifecycleExecutor.execute(action)
        } catch (_: RejectedExecutionException) {
            onReject()
        }
    }

    private fun enqueueStart(
        project: Project?,
        callback: StartCallback,
        state: OpenCodeServerLifecycleState,
        replaceCurrent: Boolean = false,
        prepare: (Long) -> Boolean,
    ) {
        synchronized(lock) {
            if (disposed) {
                notifyStartCallbacks(listOf(callback), success = false)
                return
            }
            if (project != null && !project.isDisposed) {
                requesterTrusted = trustCheck(project, canonicalDirectory)
            }
            if (replaceCurrent && starting) {
                startSequence++
                starting = false
                activeIndicator?.cancel()
            }
            pendingStarts.add(callback)
            if (starting) return
            starting = true
            allowHealthRestart = true
            lastFailureDetails = null
            val startId = ++startSequence
            runOnLifecycle(
                onReject = {
                    starting = false
                    val callbacks = pendingStarts.toList()
                    pendingStarts.clear()
                    notifyStartCallbacks(callbacks, success = false)
                },
            ) {
                if (!isCurrentStart(startId)) return@runOnLifecycle
                try {
                    if (prepare(startId) && isCurrentStart(startId)) startServe(project, startId)
                } catch (e: SbxCommandFailure) {
                    recordCommandFailure(e)
                    fail(startId, SbxFailureKind.COMMAND_FAILED)
                } catch (e: Exception) {
                    thisLogger().warn("Error starting OpenCode sandbox", e)
                    fail(startId, SbxFailureKind.SERVE_UNHEALTHY)
                }
            }
            // A healthy ensureStarted call should not interrupt the running page/event stream.
            if (state != OpenCodeServerLifecycleState.STARTING || lifecycleState != OpenCodeServerLifecycleState.RUNNING) {
                setLifecycleState(state)
            }
        }
    }

    /** Called only by the lifecycle worker; progress must not reschedule startup onto another pool. */
    private fun startServe(project: Project?, startId: Long) {
        if (project != null && !project.isDisposed) {
            val upgrading = synchronized(lock) { pendingBinaryUpgrade?.startId == startId }
            val title = if (upgrading) "Upgrading OpenCode in sandbox" else "Starting OpenCode sandbox"
            val task = object : Backgroundable(project, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    runStart(startId, indicator)
                }
            }
            val indicator = progressIndicatorOnEdt(task)
            try {
                runWithCancellableIndicator(startId, indicator) { ProgressManager.getInstance().runProcess({ task.run(indicator) }, indicator) }
            } finally {
                // ProgressWindow owns Swing UI; queue disposal after its EDT initialization.
                EdtInvocationManager.invokeLaterIfNeeded { Disposer.dispose(indicator) }
            }
            return
        }
        // Health restarts have no project UI; an indicator still lets Stop cancel sbx commands.
        val indicator = EmptyProgressIndicator()
        runWithCancellableIndicator(startId, indicator) {
            ProgressManager.getInstance().runProcess({ runStart(startId, indicator = null) }, indicator)
        }
    }

    private fun runWithCancellableIndicator(startId: Long, indicator: ProgressIndicator, action: () -> Unit) {
        synchronized(lock) {
            if (startId != startSequence) return
            activeIndicator = indicator
        }
        try {
            action()
        } catch (_: ProcessCanceledException) {
            // A superseding start reports its own outcome; a user cancel still has to finish this one.
            if (isCurrentStart(startId)) fail(startId, SbxFailureKind.CANCELLED)
        } finally {
            synchronized(lock) { if (activeIndicator === indicator) activeIndicator = null }
        }
    }

    private fun progressIndicatorOnEdt(task: Backgroundable): BackgroundableProcessIndicator {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return BackgroundableProcessIndicator(task)
        var indicator: BackgroundableProcessIndicator? = null
        // Only constructs the indicator; must not wait for a modal Settings dialog to close.
        app.invokeAndWait({ indicator = BackgroundableProcessIndicator(task) }, ModalityState.any())
        return checkNotNull(indicator)
    }

    private fun runStart(startId: Long, indicator: ProgressIndicator?) {
        try {
            if (!isCurrentStart(startId)) return
            serverLogBuffer.startNewFile()
            val upgrade = consumePendingBinaryUpgrade(startId)
            if (upgrade != null && !runOpenCodeBinaryUpgrade(startId, upgrade, indicator)) return
            noteStartupStage("Checking Docker Sandboxes…")
            indicator?.text = "Checking Docker Sandboxes…"
            val sbx = OpenCodeServerProtocol.detectExecutablePath(sbxExecutable())
            if (sbx == null) {
                fail(startId, SbxFailureKind.SBX_NOT_FOUND)
                return
            }
            val diagnose = commandRunner.run(SbxCli.buildDiagnoseJsonCommand(sbx), emptyMap(), 30_000L)
            if (SbxCli.diagnoseReportsUnsupported(diagnose.stdout) ||
                SbxCli.looksUnsupportedHost(
                    System.getProperty("os.name").orEmpty(),
                    System.getProperty("os.arch").orEmpty(),
                )
            ) {
                dumpDiagnose(diagnose.output)
                fail(startId, SbxFailureKind.UNSUPPORTED_OS)
                return
            }
            noteStartupStage("Starting sandbox daemon…")
            indicator?.text = "Starting sandbox daemon…"
            startSandboxDaemon(sbx)
            val ls = requiredCommand("List sandboxes", SbxCli.buildLsCommand(sbx), 30_000L)
            if (!isCurrentStart(startId)) return
            val settings = OpenCodeSettingsState.getInstance()
            if (SbxLaunchSpec.persist(settings, canonicalDirectory) == null) {
                val inspection = SbxLaunchSpec.inspect(canonicalDirectory)
                val details = if (inspection is SbxLaunchSpecInspection.Invalid) {
                    "Invalid ${inspection.path}: ${inspection.reason}"
                } else {
                    "Could not read or write ${SbxLaunchSpec.PROJECT_SPEC_NAME} in $canonicalDirectory."
                }
                throw SbxCommandFailure("Save sandbox settings", -1, details)
            }
            if (!settings.sbxNetworkPolicyConsent) {
                // A policy set up in a terminal or by the organisation counts as consent.
                val policies = commandRunner.run(SbxCli.buildPolicyLsCommand(sbx), emptyMap(), 30_000L)
                if (policies.exitCode == 0 && SbxCli.policyIsInitialized(policies.stdout)) {
                    settings.sbxNetworkPolicyConsent = true
                } else {
                    fail(startId, SbxFailureKind.POLICY_UNINITIALIZED)
                    return
                }
            }
            var name = SbxCli.sandboxName(canonicalDirectory)
            var record = recordStore().recordFor(canonicalDirectory)
            val listed = parseSandboxList(ls)
            val owned = record?.let { SbxCli.findOwnedSandbox(listed, it) }
            if (owned != null) name = owned.name
            val conflicting = SbxCli.conflictingSandbox(listed, name, canonicalDirectory, ::ownedByAnotherDirectory)
            if (owned == null && conflicting != null) {
                synchronized(lock) { lastForeignSandbox = conflicting }
                fail(startId, SbxFailureKind.FOREIGN_SANDBOX)
                return
            }
            val spec = when (val inspection = SbxLaunchSpec.inspect(canonicalDirectory)) {
                is SbxLaunchSpecInspection.Valid -> inspection.spec
                is SbxLaunchSpecInspection.Invalid ->
                    throw SbxCommandFailure("Read sandbox settings", -1, "Invalid ${inspection.path}: ${inspection.reason}")
                SbxLaunchSpecInspection.Missing ->
                    throw SbxCommandFailure("Read sandbox settings", -1, "Invalid ${SbxLaunchSpec.PROJECT_SPEC_NAME} in $canonicalDirectory.")
            }
            val shareHostConfig = spec.shareHostOpencodeConfig
            val sharedConfigMount = if (shareHostConfig) SbxOpencodeConfigOverlay.hostConfigShareMount() else null
            val extraMounts = existingExtraMounts(
                SbxOpencodeConfigOverlay.withHostConfigShare(
                    SbxCli.resolveExtraMounts(spec.extraMounts, canonicalDirectory),
                    shareHostConfig,
                ),
            )
            val protectMounts = if (spec.protectSandboxFiles) {
                SbxCli.sandboxProtectMounts(canonicalDirectory, spec.kits)
            } else {
                emptyList()
            }
            val persistMounts = if (spec.persistSandboxSessions) {
                listOf(SbxCli.persistSandboxMount(name))
            } else {
                emptyList()
            }
            val guestOpenCodeMounts = if (spec.openCodeVersion.prefersGuestV2()) {
                listOf(SbxCli.guestOpenCodeMount(name))
            } else {
                emptyList()
            }
            val extraCreateArgs = SbxCli.extraMountCreateArgs(
                protectMounts + persistMounts + guestOpenCodeMounts + extraMounts,
                canonicalDirectory,
            )
            val kitRefs = SbxCli.parseKitRefs(spec.kits.joinToString("\n"))
            val kitsText = SbxCli.normalizeLineList(spec.kits.joinToString("\n"))
            val memory = spec.memory
            val cpus = spec.cpus
            val desiredHostPort = spec.hostPort
            var listedWorkspaces = owned?.workspaces.orEmpty()
            val desiredKits = SbxCli.parseLineList(kitsText)
            if (owned != null && record != null) {
                // Plugin-owned stores count as known even when their option is off now.
                val pluginMounts = protectMounts + listOf(
                    SbxExtraMount(SbxCli.sandboxPersistDataHome(name), SbxCli.persistSandboxGuestPath()),
                    SbxExtraMount(SbxCli.guestOpenCodeDataHome(name), SbxCli.guestOpenCodeGuestPath()),
                )
                val reasons = SbxCli.recreateReasons(
                    record, listedWorkspaces, canonicalDirectory, kitsText, shareHostConfig,
                    extraMounts, pluginMounts, sharedConfigMount?.hostPath,
                )
                if (reasons.isNotEmpty()) {
                    synchronized(lock) { pendingRecreateReasons = reasons }
                    fail(startId, SbxFailureKind.RECREATE_REQUIRED)
                    return
                }
                if (appendsKits(record, desiredKits) && !gateSandboxProvisioning(startId, spec)) return
                record = appendUniqueKits(sbx, name, record, desiredKits, startId)
            }
            if ((record == null || owned == null) && !gateSandboxProvisioning(startId, spec)) return
            var createdNow = false
            if (record == null || owned == null) {
                if (!isCurrentStart(startId)) return
                noteStartupStage("Creating sandbox…")
                indicator?.text = "Creating sandbox…"
                requiredCommand(
                    "Create sandbox",
                    SbxCli.buildCreateCommand(
                        sbx,
                        name,
                        canonicalDirectory,
                        memory = memory,
                        cpus = cpus,
                        hostPort = desiredHostPort,
                        extraWorkspaces = extraCreateArgs,
                        kits = kitRefs,
                    ),
                    FIRST_START_TIMEOUT_MILLIS,
                    Path.of(canonicalDirectory),
                )
                val afterCreate = parseSandboxList(
                    requiredCommand("List created sandbox", SbxCli.buildLsCommand(sbx), 30_000L),
                )
                val createdEntry = afterCreate.firstOrNull {
                    it.name == name && it.agent == SbxCli.AGENT &&
                        it.workspaces.any { workspace ->
                            OpenCodeServerProtocol.isSameFilesystemPath(workspace, canonicalDirectory)
                        }
                }
                if (createdEntry == null) {
                    commandRunner.run(SbxCli.buildRmForceCommand(sbx, name), emptyMap(), 60_000L)
                    fail(startId, SbxFailureKind.SERVE_UNHEALTHY)
                    return
                }
                record = SbxSandboxRecord(
                    sandboxId = createdEntry.id,
                    name = createdEntry.name,
                    agent = createdEntry.agent,
                    workspace = canonicalDirectory,
                    kits = kitsText,
                    shareHostConfig = shareHostConfig,
                    hostPort = desiredHostPort,
                    createSnapshot = SbxCli.createSnapshot(memory, cpus, spec.protectSandboxFiles, extraCreateArgs),
                )
                recordStore().save(canonicalDirectory, record)
                listedWorkspaces = createdEntry.workspaces
                createdNow = true
            } else {
                noteStaleCreate(
                    record, memory, cpus, spec.protectSandboxFiles, extraCreateArgs, shareHostConfig,
                    spec.persistSandboxSessions,
                )
            }
            if (!isCurrentStart(startId)) return
            if (spec.enableIntellijMcp && !waitForIntellijMcpServerIfNeeded(startId)) {
                failStartIfCurrent(startId)
                return
            }
            for (mount in extraMounts + persistMounts + guestOpenCodeMounts) {
                if (!SbxCli.needsSandboxLink(mount)) continue
                val replace = persistMounts.any {
                    OpenCodeServerProtocol.isSameFilesystemPath(it.hostPath, mount.hostPath)
                } || guestOpenCodeMounts.any {
                    OpenCodeServerProtocol.isSameFilesystemPath(it.hostPath, mount.hostPath)
                }
                if (replace && !SbxCli.persistMountIsAttached(listedWorkspaces, mount)) continue
                if (!isCurrentStart(startId)) return
                requiredCommand(
                    "Link extra mount",
                    SbxCli.buildLinkExtraMountCommand(
                        sbx,
                        name,
                        mount,
                        replaceExistingDirectory = replace,
                    ),
                    30_000L,
                )
            }
            val mcpPort = if (spec.enableIntellijMcp) {
                SbxCli.ideMcpLoopbackPort(IntellijMcpServerStartup.currentStatus().message)
            } else {
                null
            }
            if (mcpPort != null) {
                requiredCommand(
                    "Allow IntelliJ MCP connection",
                    SbxCli.buildPolicyAllowCommand(sbx, name, "localhost:$mcpPort"),
                    15_000L,
                )
            }
            if (spec.openCodeVersion.prefersGuestV2() && !ensureGuestOpenCodeV2(startId, name, indicator)) return
            val overlay = SbxOpencodeConfigOverlay.buildContent(
                version = spec.openCodeVersion,
                shareHostConfig = shareHostConfig,
                ideaMcpPort = mcpPort,
            )
            val extraEnv = linkedMapOf<String, String>()
            if (overlay != null) {
                extraEnv[SbxCli.OPENCODE_CONFIG_CONTENT_ENV] = overlay
            }
            // Only when the config directory is actually mounted; otherwise OpenCode would read an
            // empty path instead of the sandbox agent's own config.
            if (sharedConfigMount != null) {
                extraEnv[SbxOpencodeConfigOverlay.XDG_CONFIG_HOME_ENV] =
                    SbxCli.guestBindPath(Path.of(sharedConfigMount.hostPath).parent.toString())
            }
            val password = OpenCodePasswordStore.getInstance().ensurePasswordBlocking()
            val processBuilder = ProcessBuilder(
                SbxCli.buildExecServeCommand(
                    sbx,
                    name,
                    spec.hostWorkingDirectory(),
                    extraEnvKeys = extraEnv.keys.toList(),
                    preferGuestV2 = spec.openCodeVersion.prefersGuestV2(),
                ),
            ).redirectErrorStream(true)
            processBuilder.environment()["PATH"] = OpenCodeServerProtocol.resolvePath()
            processBuilder.environment()[SbxCli.OPENCODE_SERVER_PASSWORD_ENV] = password
            extraEnv.forEach { (key, value) -> processBuilder.environment()[key] = value }
            processBuilder.directory(java.io.File(canonicalDirectory))
            if (!isCurrentStart(startId)) return
            // A serve from an earlier IDE session, superseded start, or host-side timeout can
            // outlive its `sbx exec` client. It would hold 4096 or answer health with stale env.
            killLeftoverGuestServe(sbx, name)
            if (!isCurrentStart(startId)) return
            val process = processBuilder.start()
            if (!setStartedProcess(startId, process, password)) {
                processTerminator.destroy(process)
                return
            }
            val serveOutput = StringBuffer()
            val outputReader = Thread({
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                        lines.forEach { line ->
                            val safeLine = line.replace(password, "[redacted]")
                            synchronized(serveOutput) {
                                serveOutput.append(safeLine).append('\n')
                                if (serveOutput.length > 8192) serveOutput.delete(0, serveOutput.length - 8192)
                            }
                            thisLogger().debug(safeLine)
                            serverLogBuffer.append(safeLine)
                        }
                    }
                } catch (e: Exception) {
                    thisLogger().info("Stopped reading OpenCode sandbox output: ${e.message}")
                }
            }, "OpenCode-Sbx-Output-Reader").apply {
                isDaemon = true
                start()
            }
            noteStartupStage("Starting OpenCode server…")
            indicator?.text = "Starting OpenCode server…"
            val timeout = if (createdNow) FIRST_START_TIMEOUT_MILLIS else RECONNECT_TIMEOUT_MILLIS
            val launchedAt = System.currentTimeMillis()
            val deadline = launchedAt + timeout
            var healthyUrl: String? = null
            var extraPublishes = 0
            var publishFailures = 0
            while (isCurrentStart(startId) && System.currentTimeMillis() < deadline) {
                checkSbxServeAlive(process) {
                    outputReader.join(1_000L)
                    serveOutput.toString()
                }
                if (indicator?.isCanceled == true) {
                    fail(startId, SbxFailureKind.CANCELLED)
                    return
                }
                var ports = listPublishedPorts(sbx, name)
                if (!process.isAlive || !isCurrentStart(startId)) continue
                healthyUrl = healthyPublishedSandboxUrl(ports, desiredHostPort, password)
                if (healthyUrl != null) break
                val known = SbxCli.publishedHostPorts(ports, desiredHostPort = desiredHostPort)
                val waitText = if (known.isEmpty()) {
                    "Publishing sandbox port…"
                } else {
                    "Waiting for OpenCode on ${known.joinToString { "127.0.0.1:$it" }}…"
                }
                noteStartupStage(waitText)
                indicator?.text = waitText
                // A mapping that exists but does not answer is usually serve still booting; only
                // republish (a stale mapping can reset) once serve had time to listen.
                val booting = System.currentTimeMillis() - launchedAt < REPUBLISH_GRACE_MILLIS
                if (known.isEmpty() || (!booting && extraPublishes < 3)) {
                    val result = ensurePublishedHostPort(sbx, name, desiredHostPort, ports, force = known.isNotEmpty())
                    if (result.error != null && desiredHostPort != null && ++publishFailures >= 2) {
                        // A taken fixed port does not free itself; do not wait out the full timeout.
                        recordCommandFailure(SbxCommandFailure("Publish sandbox port", -1, result.error))
                        fail(startId, SbxFailureKind.COMMAND_FAILED)
                        return
                    }
                    if (result.published) {
                        extraPublishes++
                        ports = listPublishedPorts(sbx, name)
                        healthyUrl = healthyPublishedSandboxUrl(ports, desiredHostPort, password)
                        if (healthyUrl != null) break
                    }
                }
                thisLogger().info(
                    "Sandbox serve is not healthy yet on ${known.ifEmpty { listOf("no published port") }}",
                )
                Thread.sleep(1_000L)
            }
            if (!isCurrentStart(startId)) {
                processTerminator.destroy(process)
                return
            }
            if (healthyUrl == null || !process.isAlive) {
                thisLogger().warn("Failed to start OpenCode sandbox server")
                fail(startId, SbxFailureKind.SERVE_UNHEALTHY)
                return
            }
            record.takeIf { it.hostPort != desiredHostPort }?.let { current ->
                recordStore().save(canonicalDirectory, current.copy(hostPort = desiredHostPort))
            }
            synchronized(lock) {
                if (startId != startSequence) return
                serverUrl = healthyUrl
                rememberBrowserAuth(url = healthyUrl)
            }
            refreshServerVersion()
            finishStart(startId, success = true)
        } catch (e: InterruptedException) {
            if (isCurrentStart(startId)) fail(startId, SbxFailureKind.CANCELLED)
            Thread.currentThread().interrupt()
        } catch (e: SbxCommandFailure) {
            if (!isCurrentStart(startId)) return
            if (indicator?.isCanceled == true) {
                fail(startId, SbxFailureKind.CANCELLED)
                return
            }
            recordCommandFailure(e)
            fail(startId, if (e.fromSbx && looksUnauthenticated(e.output)) SbxFailureKind.NOT_AUTHENTICATED else SbxFailureKind.COMMAND_FAILED)
        } catch (e: Exception) {
            if (looksLikeMissingSbx(e)) {
                fail(startId, SbxFailureKind.SBX_NOT_FOUND)
                return
            }
            thisLogger().warn("Error starting OpenCode sandbox: ${e.message}")
            fail(startId, SbxFailureKind.SERVE_UNHEALTHY)
        }
    }

    private fun listPublishedPorts(sbx: String, name: String): List<SbxPortMapping> {
        val listed = commandRunner.run(SbxCli.buildPortsCommand(sbx, name), emptyMap(), 15_000L)
        val fromPorts = SbxCli.parsePortsJson(listed.stdout)
        if (fromPorts.isNotEmpty()) return fromPorts
        val inventory = commandRunner.run(SbxCli.buildLsCommand(sbx), emptyMap(), 15_000L)
        return SbxCli.parseLsJson(inventory.stdout).firstOrNull { it.name == name }?.ports.orEmpty()
    }

    private data class PublishResult(val published: Boolean, val error: String? = null)

    /**
     * Makes the VM's 4096 reachable on loopback. A new mapping is published before old ones are
     * removed, so a taken fixed port leaves the running server on its previous mapping.
     * [force] republishes when the current mappings do not answer (a stale mapping can reset).
     */
    private fun ensurePublishedHostPort(
        sbx: String,
        name: String,
        desiredHostPort: Int?,
        ports: List<SbxPortMapping>,
        force: Boolean = false,
    ): PublishResult {
        val loopback = SbxCli.loopbackPortMappings(ports)
        val desiredPresent = desiredHostPort != null && loopback.any { it.hostPort == desiredHostPort }
        if (!force && (if (desiredHostPort == null) loopback.isNotEmpty() else desiredPresent)) {
            return PublishResult(false)
        }
        fun unpublish(mapping: SbxPortMapping): Boolean {
            val result = commandRunner.run(
                SbxCli.buildPortsUnpublishCommand(sbx, name, SbxCli.unpublishSpec(mapping)),
                emptyMap(),
                15_000L,
            )
            if (result.exitCode != 0) {
                thisLogger().warn("Could not unpublish sandbox port ${SbxCli.unpublishSpec(mapping)}: ${result.output}")
            }
            return result.exitCode == 0
        }
        fun publish(): SbxCommandResult = commandRunner.run(
            SbxCli.buildPortsPublishCommand(sbx, name, SbxCli.publishSpec(desiredHostPort)),
            emptyMap(),
            15_000L,
        )
        // The same fixed mapping cannot be published twice: reset it in place.
        if (desiredPresent) loopback.filter { it.hostPort == desiredHostPort }.forEach(::unpublish)
        val published = publish()
        if (published.exitCode != 0) {
            val spec = SbxCli.publishSpec(desiredHostPort)
            thisLogger().warn("Could not publish sandbox port $spec: ${published.output}")
            if (desiredPresent) publish()
            return PublishResult(false, "Could not publish sandbox port $spec (exit ${published.exitCode}).\n${published.output}".trim())
        }
        loopback.filter { desiredHostPort == null || it.hostPort != desiredHostPort }.forEach(::unpublish)
        return PublishResult(true)
    }

    /** A teammate's mount that does not exist on this machine is skipped, like the launcher does. */
    private fun existingExtraMounts(mounts: List<SbxExtraMount>): List<SbxExtraMount> = mounts.filter { mount ->
        val exists = runCatching { java.nio.file.Files.exists(Path.of(mount.hostPath)) }.getOrDefault(false)
        if (!exists) {
            val message = "Skipping sandbox mount ${mount.hostPath}: it does not exist on this machine."
            serverLogBuffer.append(message)
            thisLogger().warn(message)
        }
        exists
    }

    private fun appendsKits(record: SbxSandboxRecord, desiredKits: List<String>): Boolean {
        // Adopted VMs keep unknown provisioning: their kits may already be installed.
        if (record.adopted) return false
        val previousKits = SbxCli.parseLineList(record.kits)
        return desiredKits.size > previousKits.size && desiredKits.take(previousKits.size) == previousKits
    }

    /** Fails the start unless the project is trusted and the spec's host grants were acknowledged. */
    private fun gateSandboxProvisioning(startId: Long, spec: SbxLaunchSpec): Boolean {
        val trusted = requesterTrusted ?: trustCheck(null, canonicalDirectory)
        if (!trusted) {
            fail(startId, SbxFailureKind.UNTRUSTED_PROJECT)
            return false
        }
        val exposure = SbxExposure.of(spec, canonicalDirectory)
        if (recordStore().isExposureAcknowledged(canonicalDirectory, exposure.fingerprint)) return true
        synchronized(lock) { pendingExposure = exposure }
        fail(startId, SbxFailureKind.EXPOSURE_UNCONFIRMED)
        return false
    }

    private fun appendUniqueKits(
        sbx: String,
        name: String,
        record: SbxSandboxRecord,
        desiredKits: List<String>,
        startId: Long? = null,
    ): SbxSandboxRecord {
        if (!appendsKits(record, desiredKits)) return record
        val previousKits = SbxCli.parseLineList(record.kits)
        var current = record
        for (index in previousKits.size until desiredKits.size) {
            if (startId != null && !isCurrentStart(startId)) return current
            requiredCommand(
                "Add sandbox kit",
                SbxCli.buildAddKitCommand(sbx, name, SbxCli.parseKitRefs(desiredKits[index]).single()),
                FIRST_START_TIMEOUT_MILLIS,
                Path.of(canonicalDirectory),
            )
            current = current.copy(kits = desiredKits.take(index + 1).joinToString("\n"))
            recordStore().save(canonicalDirectory, current)
            val refreshed = parseSandboxList(
                requiredCommand("Verify sandbox after kit addition", SbxCli.buildLsCommand(sbx), 30_000L),
            )
            if (SbxCli.findOwnedSandbox(refreshed, current) == null) {
                throw SbxCommandFailure("Add sandbox kit", -1, "Sandbox identity changed; refusing to adopt it automatically.")
            }
        }
        return current
    }

    /** Returns a user-facing error, or null when the live changes were applied (or nothing to do). */
    private fun applyLiveOnWorker(): String? {
        val spec = when (val inspection = SbxLaunchSpec.inspect(canonicalDirectory)) {
            is SbxLaunchSpecInspection.Valid -> inspection.spec
            else -> return null
        }
        var record = recordStore().recordFor(canonicalDirectory) ?: return null
        val sbx = OpenCodeServerProtocol.detectExecutablePath(sbxExecutable()) ?: return "The sbx CLI was not found."
        val listed = parseSandboxList(
            requiredCommand("List sandbox for live apply", SbxCli.buildLsCommand(sbx), 30_000L),
        )
        val owned = SbxCli.findOwnedSandbox(listed, record) ?: return null
        val desiredKits = SbxCli.parseLineList(SbxCli.normalizeLineList(spec.kits.joinToString("\n")))
        record = appendUniqueKits(sbx, owned.name, record, desiredKits)
        if (owned.status != "running") {
            // The next start publishes the spec's port.
            if (record.hostPort != spec.hostPort) recordStore().save(canonicalDirectory, record.copy(hostPort = spec.hostPort))
            return null
        }
        val ports = listPublishedPorts(sbx, owned.name)
        val published = ensurePublishedHostPort(sbx, owned.name, spec.hostPort, ports)
        if (published.error != null) return published.error
        if (record.hostPort != spec.hostPort) recordStore().save(canonicalDirectory, record.copy(hostPort = spec.hostPort))
        val password = getServerPassword() ?: return null
        val healthy = healthyPublishedSandboxUrl(listPublishedPorts(sbx, owned.name), spec.hostPort, password)
            ?: return "OpenCode did not answer on the new sandbox port yet; Restart the server if the panel stays blank."
        synchronized(lock) {
            serverUrl = healthy
            rememberBrowserAuth(url = healthy)
        }
        if (getLifecycleState() == OpenCodeServerLifecycleState.RUNNING) {
            updateGlobalEventStream(OpenCodeServerLifecycleState.RUNNING)
        }
        return null
    }

    private fun fail(startId: Long, kind: SbxFailureKind) {
        synchronized(lock) {
            if (startId != startSequence) return
            lastFailure = kind
        }
        stopOwnedServe(stopVm = false)
        finishStart(startId, success = false)
    }

    private fun requiredCommand(
        stage: String,
        command: List<String>,
        timeoutMillis: Long,
        workingDirectory: Path? = null,
    ): SbxCommandResult {
        val result = commandRunner.run(command, emptyMap(), timeoutMillis, workingDirectory)
        if (result.exitCode != 0) throw SbxCommandFailure(stage, result.exitCode, result.output)
        return result
    }

    private fun parseSandboxList(result: SbxCommandResult): List<SbxSandboxListEntry> =
        SbxCli.parseLsJsonOrNull(result.stdout)
            ?: throw SbxCommandFailure("Read sandbox list", result.exitCode, "Invalid sbx ls JSON; sandbox ownership is unknown.")

    private fun startSandboxDaemon(sbx: String) {
        val result = commandRunner.run(SbxCli.buildDaemonStartCommand(sbx), emptyMap(), 60_000L)
        if (result.exitCode == 0) return
        if (SbxCli.daemonDetachUnsupported(result.output)) {
            requiredCommand("Start sandbox daemon", SbxCli.buildLegacyDaemonStartCommand(sbx), 60_000L)
            return
        }
        throw SbxCommandFailure("Start sandbox daemon", result.exitCode, result.output)
    }

    private fun recordCommandFailure(error: SbxCommandFailure) {
        val password = getServerPassword()
        val output = error.output.takeLast(8192).let {
            if (password.isNullOrBlank()) it else it.replace(password, "[redacted]")
        }
        val details = "${error.message}\n$output".trim()
        synchronized(lock) {
            lastFailure = SbxFailureKind.COMMAND_FAILED
            lastFailureDetails = details
        }
        serverLogBuffer.append(details)
        thisLogger().warn(details)
    }

    /** Keep the ownership record until removal succeeds; never delete a same-name replacement. */
    private fun removeOwnedSandbox() {
        val record = recordStore().recordFor(canonicalDirectory) ?: return
        val executable = sbxExecutable()
        val listed = parseSandboxList(requiredCommand("List sandbox before removal", SbxCli.buildLsCommand(executable), 30_000L))
        val owned = SbxCli.findOwnedSandbox(listed, record)
        if (owned != null) {
            requiredCommand("Remove sandbox", SbxCli.buildRmForceCommand(executable, owned.name), 60_000L)
        } else if (SbxCli.conflictingSandbox(listed, record.name, canonicalDirectory, ::ownedByAnotherDirectory) != null) {
            throw SbxCommandFailure("Remove sandbox", -1, "Sandbox ownership changed; nothing was removed.")
        }
        recordStore().remove(canonicalDirectory)
    }

    private fun ownedByAnotherDirectory(entry: SbxSandboxListEntry): Boolean {
        val owner = recordStore().directoryForSandboxId(entry.id) ?: return false
        return !OpenCodeServerProtocol.isSameFilesystemPath(owner, canonicalDirectory)
    }

    private fun dumpDiagnose(output: String) {
        if (output.isBlank()) return
        serverLogBuffer.append("========== sbx diagnose -o json ==========")
        output.lineSequence().forEach { line -> serverLogBuffer.append(line) }
    }

    private fun failStartIfCurrent(startId: Long) {
        if (isCurrentStart(startId)) {
            finishStart(startId, success = false)
        }
    }

    private fun waitForIntellijMcpServerIfNeeded(startId: Long): Boolean {
        val settings = OpenCodeSettingsState.getInstance()
        val initialStatus = IntellijMcpServerStartup.currentStatus()
        if (!IntellijMcpServerStartup.shouldWaitFor(initialStatus, settings.waitForIntellijMcpServer)) {
            return true
        }
        return when (
            IntellijMcpServerStartup.waitUntilReady(
                stillWaiting = {
                    IntellijMcpServerStartup.shouldWaitFor(
                        IntellijMcpServerStartup.currentStatus(),
                        OpenCodeSettingsState.getInstance().waitForIntellijMcpServer,
                    )
                },
                isStillCurrent = { isCurrentStart(startId) },
            )
        ) {
            IntellijMcpServerWaitResult.READY -> true
            IntellijMcpServerWaitResult.TIMED_OUT -> true
            IntellijMcpServerWaitResult.CANCELLED -> false
        }
    }

    private fun finishStart(startId: Long, success: Boolean) {
        synchronized(lock) {
            if (startId != startSequence) return
            starting = false
            startupStage = null
            if (success) lastFailure = SbxFailureKind.NONE
            val callbacks = pendingStarts.toList().also { pendingStarts.clear() }
            setLifecycleState(if (success) OpenCodeServerLifecycleState.RUNNING else OpenCodeServerLifecycleState.FAILED)
            if (success) startPeriodicCheck() else cancelPeriodicCheck()
            notifyStartCallbacks(callbacks, success)
        }
    }

    private fun notifyStartCallbacks(callbacks: List<StartCallback>, success: Boolean) {
        if (callbacks.isEmpty()) return
        ApplicationManager.getApplication().invokeLater {
            callbacks.forEach { callback ->
                if (!callback.isActive()) return@forEach
                if (success) callback.onStarted() else callback.onFailed()
            }
        }
    }

    private fun startPeriodicCheck() {
        synchronized(lock) {
            if (checkScheduledFuture != null) return
            checkScheduledFuture = scheduler.scheduleAtFixedRate(
                {
                    try {
                        checkServerHealth()
                    } catch (e: Exception) {
                        thisLogger().error("Error during sandbox periodic check: ${e.message}")
                    }
                },
                OpenCodeServerProtocol.CHECK_INTERVAL_SECONDS,
                OpenCodeServerProtocol.CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun cancelPeriodicCheck() {
        val future = synchronized(lock) {
            checkScheduledFuture.also { checkScheduledFuture = null }
        }
        future?.cancel(false)
    }

    private fun checkServerHealth() {
        val generation: Long
        val url: String
        synchronized(lock) {
            url = serverUrl ?: return
            generation = serverGeneration
            if (!allowHealthRestart || disposed) return
        }
        if (checkServerResponding(url)) return
        thisLogger().warn("Sandbox OpenCode server is not responding, restarting serve")
        synchronized(lock) {
            if (starting || disposed || !allowHealthRestart) return
            if (serverGeneration != generation || serverUrl != url) return
            lastRecovery = OpenCodeRecoveryNotice("sandbox serve was not responding", System.currentTimeMillis())
            enqueueStart(null, StartCallback({ false }, {}, {}), OpenCodeServerLifecycleState.RESTARTING) {
                stopOwnedServe(stopVm = false)
                true
            }
        }
    }

    private fun checkServerResponding(serverUrl: String): Boolean {
        val password = getServerPassword() ?: return false
        return OpenCodeServerProtocol.checkServerResponding(
            serverUrl,
            OpenCodeServerProtocol.buildBasicAuthHeader(password),
        )
    }

    private fun refreshServerVersion() {
        val url = getServerUrl() ?: return
        val password = getServerPassword() ?: return
        val auth = OpenCodeServerProtocol.buildBasicAuthHeader(password)
        val version = OpenCodeServerProtocol.fetchServerVersion(url, auth)
        val protocol = OpenCodeServerProtocol.detectWireProtocol(url, auth)
        synchronized(lock) {
            serverVersion = version
            wireProtocol = protocol
        }
    }

    /** Cleanup must finish even when it runs for a cancelled start. */
    private fun stopOwnedServe(stopVm: Boolean): Boolean {
        var ok = true
        ProgressManager.getInstance().executeNonCancelableSection { ok = stopOwnedServeNow(stopVm) }
        return ok
    }

    private fun stopOwnedServeNow(stopVm: Boolean): Boolean {
        cancelPeriodicCheck()
        val url: String?
        val password: String?
        val process: Process?
        val protocol: OpenCodeWireProtocol
        synchronized(lock) {
            url = serverUrl
            password = serverPassword
            process = serverProcess
            protocol = wireProtocol
            serverProcess = null
            serverUrl = null
            serverPassword = null
            serverVersion = null
            wireProtocol = OpenCodeWireProtocol.UNKNOWN
        }
        if (!url.isNullOrBlank() && !password.isNullOrBlank()) {
            OpenCodeServerProtocol.disposeServer(
                url,
                OpenCodeServerProtocol.buildBasicAuthHeader(password),
                wireProtocol = protocol,
            )
        }
        var ok = true
        try {
            // exec auto-starts a stopped VM. A failed/dead launch must not boot it just to pkill.
            if (process?.isAlive != true && !stopVm) return true
            val record = recordStore().recordFor(canonicalDirectory) ?: return true
            val executable = sbxExecutable()
            val listed = requiredCommand("List sandbox before stop", SbxCli.buildLsCommand(executable), 30_000L)
            val owned = SbxCli.findOwnedSandbox(parseSandboxList(listed), record)
                ?.takeIf { it.status == "running" }
            if (owned == null) return true
            if (process?.isAlive == true) {
                val killed = commandRunner.run(SbxCli.buildRemotePkillCommand(executable, owned.name), emptyMap(), 15_000L)
                // pkill returns 1 when serve already exited.
                if (killed.exitCode !in 0..1) {
                    recordCommandFailure(SbxCommandFailure("Stop OpenCode serve", killed.exitCode, killed.output))
                    ok = false
                }
            }
            if (stopVm) {
                try {
                    requiredCommand("Stop sandbox", SbxCli.buildStopCommand(executable, owned.name), 60_000L)
                } catch (e: SbxCommandFailure) {
                    recordCommandFailure(e)
                    ok = false
                }
            }
        } catch (e: SbxCommandFailure) {
            recordCommandFailure(e)
            ok = false
        } finally {
            processTerminator.destroy(process)
        }
        return ok
    }

    private fun killLeftoverGuestServe(sbx: String, name: String) {
        val killed = commandRunner.run(SbxCli.buildRemotePkillCommand(sbx, name), emptyMap(), 15_000L)
        // pkill returns 1 when nothing matched.
        if (killed.exitCode !in 0..1) {
            throw SbxCommandFailure("Stop leftover OpenCode serve", killed.exitCode, killed.output)
        }
    }

    private fun setStartedProcess(startId: Long, process: Process, password: String): Boolean {
        return synchronized(lock) {
            if (startId != startSequence) return@synchronized false
            serverProcess = process
            serverUrl = null
            serverPassword = password
            rememberBrowserAuth(password = password)
            serverGeneration++
            // Interrupted-session recovery treats unfinished turns older than this as cut off;
            // it must be the launch of this serve, not the latest (possibly healthy) start request.
            serverGenerationStartedAtMillis = System.currentTimeMillis()
            true
        }
    }

    private fun setLifecycleState(state: OpenCodeServerLifecycleState) {
        val changed = synchronized(lock) {
            if (lifecycleState == state) {
                false
            } else {
                lifecycleState = state
                true
            }
        }
        if (changed) {
            updateGlobalEventStream(state)
            try {
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(OpenCodeServerLifecycleListener.TOPIC)
                    .stateChanged(state, backendId)
            } catch (e: Exception) {
                thisLogger().warn("Could not publish OpenCode sandbox lifecycle state ${state.name}: ${e.message}")
            }
        }
    }

    private fun updateGlobalEventStream(state: OpenCodeServerLifecycleState) {
        if (state != OpenCodeServerLifecycleState.RUNNING) {
            globalEventStream.stop()
            return
        }
        val url = getServerUrl()
        val password = getServerPassword()
        if (url != null && !password.isNullOrBlank()) {
            globalEventStream.start(
                url,
                OpenCodeServerProtocol.buildBasicAuthHeader(password),
                backendId,
                getWireProtocol(),
                SbxLaunchSpec.load(canonicalDirectory)?.hostWorkingDirectory()?.let(SbxCli::guestBindPath)
                    ?: SbxCli.guestBindPath(canonicalDirectory),
            )
        }
    }

    private fun sbxExecutable(): String {
        return OpenCodeServerProtocol.resolveExecutableForLaunch(
            OpenCodeSettingsState.getInstance().sbxExecutablePath(),
        )
    }

    private fun looksLikeMissingSbx(error: Throwable): Boolean {
        val text = listOfNotNull(error.message, error.cause?.message).joinToString(" ")
        return text.contains("No such file or directory", ignoreCase = true)
    }

    private fun consumePendingBinaryUpgrade(startId: Long): PendingBinaryUpgrade? = synchronized(lock) {
        val pending = pendingBinaryUpgrade?.takeIf { it.startId == startId } ?: return@synchronized null
        pendingBinaryUpgrade = null
        pending
    }

    private fun ensureGuestOpenCodeV2(
        startId: Long,
        sandboxName: String,
        indicator: ProgressIndicator?,
    ): Boolean {
        if (!isCurrentStart(startId)) return false
        noteStartupStage("Checking OpenCode 2.x…")
        indicator?.text = "Checking OpenCode 2.x…"
        val present = probeGuestOpenCodeV2(sandboxName)
        if (!isCurrentStart(startId)) return false
        if (present.exitCode == 0) return true
        if (present.exitCode != SbxCli.GUEST_V2_MISSING_EXIT_CODE) {
            failGuestOpenCodeV2(startId, present)
            return false
        }
        return runOpenCodeBinaryUpgrade(
            startId,
            PendingBinaryUpgrade(startId, sandboxName, installV2 = true),
            indicator,
        )
    }

    private fun probeGuestOpenCodeV2(sandboxName: String): SbxCommandResult = commandRunner.run(
        SbxCli.buildExecGuestV2VersionCommand(sbxExecutable(), sandboxName), emptyMap(), GUEST_V2_VERSION_TIMEOUT_MILLIS,
    )

    private fun failGuestOpenCodeV2(startId: Long, result: SbxCommandResult) {
        recordCommandFailure(SbxCommandFailure("Validate guest OpenCode 2.x", result.exitCode, result.output))
        fail(startId, SbxFailureKind.INVALID_V2_BINARY)
    }

    private fun runOpenCodeBinaryUpgrade(
        startId: Long,
        pending: PendingBinaryUpgrade,
        indicator: ProgressIndicator?,
    ): Boolean {
        if (!isCurrentStart(startId)) return false
        val installingV2 = pending.installV2
        val headline = if (installingV2) "Installing OpenCode 2.x…" else "Upgrading OpenCode…"
        noteStartupStage(headline)
        indicator?.text = headline
        val command = if (installingV2) {
            SbxCli.buildExecInstallV2Command(sbxExecutable(), pending.sandboxName)
        } else {
            val version = SbxLaunchSpec.load(canonicalDirectory)?.openCodeVersion ?: SbxOpenCodeVersion.V1
            SbxCli.buildExecUpgradeCommand(
                sbxExecutable(),
                pending.sandboxName,
                preferGuestV2 = version.prefersGuestV2(),
            )
        }
        val timeout = if (installingV2) INSTALL_V2_TIMEOUT_MILLIS else UPGRADE_TIMEOUT_MILLIS
        val upgraded = commandRunner.run(
            command,
            emptyMap(),
            timeout,
            null,
        ) { line ->
            serverLogBuffer.append(line)
            val parsed = parseCliProgress(line)
            val stage = nextCliProgressStage(synchronized(lock) { startupStage } ?: headline, parsed)
                .take(STAGE_MAX_CHARS)
            noteStartupStage(stage)
            indicator?.text = stage
            val fraction = parsed.fraction
            if (indicator != null && fraction != null) {
                indicator.isIndeterminate = false
                indicator.fraction = fraction
            }
        }
        if (!isCurrentStart(startId)) return false
        if (upgraded.exitCode != 0) {
            val kind = if (installingV2) "OpenCode 2.x install" else "opencode upgrade"
            thisLogger().warn("$kind failed (exit ${upgraded.exitCode}): ${upgraded.output}")
            fail(startId, if (installingV2) SbxFailureKind.INSTALL_V2_FAILED else SbxFailureKind.UPGRADE_FAILED)
            return false
        }
        if (installingV2 || SbxLaunchSpec.load(canonicalDirectory)?.openCodeVersion == SbxOpenCodeVersion.V2) {
            val verified = probeGuestOpenCodeV2(pending.sandboxName)
            if (!isCurrentStart(startId)) return false
            if (verified.exitCode != 0) {
                failGuestOpenCodeV2(startId, verified)
                return false
            }
        }
        return true
    }

    private fun noteStartupStage(stage: String) {
        synchronized(lock) { startupStage = stage }
    }

    /** Caller holds [lock]. Live URL/password may be nulled on stop; this snapshot must not. */
    private fun rememberBrowserAuth(url: String? = null, password: String? = null) {
        if (!url.isNullOrBlank()) authServerUrl = url
        if (!password.isNullOrBlank()) authServerPassword = password
    }

    private fun isCurrentStart(startId: Long): Boolean = synchronized(lock) { startId == startSequence }

    private fun looksUnauthenticated(output: String): Boolean {
        val text = output.lowercase()
        return text.contains("not logged in") ||
            text.contains("please run") && text.contains("login") ||
            text.contains("unauthorized") ||
            text.contains("authentication required")
    }

    companion object {
        private const val FIRST_START_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val RECONNECT_TIMEOUT_MILLIS = 60_000L
        private const val UPGRADE_TIMEOUT_MILLIS = 180_000L
        private const val INSTALL_V2_TIMEOUT_MILLIS = 5 * 60 * 1000L
        private const val GUEST_V2_VERSION_TIMEOUT_MILLIS = 60_000L
        private const val STAGE_MAX_CHARS = 120
        private const val REPUBLISH_GRACE_MILLIS = 10_000L
    }
}
