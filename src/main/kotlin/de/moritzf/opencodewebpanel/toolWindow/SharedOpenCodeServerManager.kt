package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import de.moritzf.opencodewebpanel.settings.OpenCodePasswordStore
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.jetbrains.annotations.TestOnly
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SharedOpenCodeServerManager : Disposable {

    companion object {
        private const val SERVER_START_TIMEOUT_MILLIS = 60_000L
        private const val SERVER_START_POLL_MILLIS = 1_000L

        fun getInstance(): SharedOpenCodeServerManager {
            return ApplicationManager.getApplication().getService(SharedOpenCodeServerManager::class.java)
        }
    }

    private data class StartCallback(
        val isActive: () -> Boolean,
        val onStarted: () -> Unit,
        val onFailed: () -> Unit,
    )

    private data class ServerResourcesToStop(
        val future: ScheduledFuture<*>?,
        val process: Process?,
        val processDescendants: List<ProcessHandle>,
    )

    private val lock = Any()
    private val pendingStarts = mutableListOf<StartCallback>()
    private var startSequence = 0L
    private var starting = false
    private var serverRunning = false
    private var lifecycleState = OpenCodeServerLifecycleState.STOPPED
    private var serverProcess: Process? = null
    private var serverUrl: String? = null
    private var serverPassword: String? = null
    private var serverVersion: String? = null
    private var serverGeneration = 0L
    private var launcherExitNoticeLogged = false

    // Descendants of the launcher process, captured while it is still alive. On Windows the
    // launcher exits after spawning the real server; a dead process reports no descendants,
    // so these handles are the only way to stop the server later.
    private var serverProcessDescendants: List<ProcessHandle> = emptyList()
    private var checkScheduledFuture: ScheduledFuture<*>? = null
    private var preferredBasePath: String? = null
    private var consecutiveStartFailures = 0
    private var nextStartAllowedAtMillis = 0L
    private val processTerminator = OpenCodeProcessTerminator()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "OpenCode-Server-Checker").apply { isDaemon = true }
    }
    private val serverLogBuffer = OpenCodeServerLogBuffer()

    fun getServerLogFile(): Path? {
        return serverLogBuffer.currentOrLatestFile()
    }

    private fun appendServerLog(line: String) {
        serverLogBuffer.append(line)
    }

    fun ensureStarted(
        project: Project,
        projectBasePath: String?,
        callbackActive: () -> Boolean = { true },
        onStarted: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val basePath = rememberBasePath(projectBasePath)
        val callback = StartCallback(callbackActive, onStarted, onFailed)

        val url = getServerUrl()
        if (url != null) {
            val validationId = synchronized(lock) {
                pendingStarts.add(callback)
                if (starting) return
                starting = true
                ++startSequence
            }
            // Keep RUNNING while merely validating a healthy server; flipping to STARTING here
            // would flash the status strip in every open panel on each tool-window load.
            if (getLifecycleState() != OpenCodeServerLifecycleState.RUNNING) {
                setLifecycleState(OpenCodeServerLifecycleState.STARTING)
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                validateExistingServerOrStart(project, basePath, url, validationId)
            }
            return
        }

        val startId = synchronized(lock) {
            pendingStarts.add(callback)
            if (starting) return
            starting = true
            ++startSequence
        }

        setLifecycleState(OpenCodeServerLifecycleState.STARTING)
        destroyCurrentProcess()

        val backoffMillis = remainingStartBackoffMillis()
        if (backoffMillis > 0) {
            thisLogger().warn("Delaying OpenCode server start after recent failure by ${backoffMillis}ms")
            scheduler.schedule(
                {
                    if (isCurrentStart(startId)) {
                        startOpenCodeServer(project.takeUnless { it.isDisposed }, basePath, startId)
                    }
                },
                backoffMillis,
                TimeUnit.MILLISECONDS,
            )
            return
        }
        startOpenCodeServer(project, basePath, startId)
    }

    private fun validateExistingServerOrStart(project: Project, projectBasePath: String?, url: String, startId: Long) {
        if (!isCurrentStart(startId)) return
        if (checkServerResponding(url)) {
            if (getServerVersion() == null) refreshServerVersion()
            setServerRunningForStart(startId)
            finishStart(startId, success = true)
            return
        }
        if (!isCurrentStart(startId)) return
        setLifecycleState(OpenCodeServerLifecycleState.RESTARTING)
        destroyCurrentProcess()
        startOpenCodeServer(project, projectBasePath, startId)
    }

    fun isServerRunning(): Boolean = synchronized(lock) { serverRunning }

    /**
     * Verifies right now that the server responds. If it does, [onHealthy] runs on the EDT so the
     * caller can reload its page; otherwise the regular health-check recovery (restart with
     * backoff) is triggered immediately instead of waiting for the next periodic check.
     */
    fun verifyServerNow(callbackActive: () -> Boolean = { true }, onHealthy: () -> Unit) {
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
                thisLogger().warn("OpenCode health check after browser failure failed: ${e.message}")
            }
        }
    }

    fun getLifecycleState(): OpenCodeServerLifecycleState = synchronized(lock) { lifecycleState }

    @TestOnly
    fun setServerRunning(running: Boolean) {
        synchronized(lock) {
            serverRunning = running
        }
        setLifecycleState(if (running) OpenCodeServerLifecycleState.RUNNING else OpenCodeServerLifecycleState.STOPPED)
    }

    fun getServerProcess(): Process? = synchronized(lock) { serverProcess }

    @TestOnly
    fun setServerProcess(process: Process?) {
        synchronized(lock) {
            serverProcess = process
        }
    }

    fun getServerUrl(): String? = synchronized(lock) { serverUrl }

    @TestOnly
    fun setServerUrl(url: String?) {
        synchronized(lock) {
            serverUrl = url
        }
    }

    fun getServerPassword(): String? = synchronized(lock) { serverPassword }

    fun getServerVersion(): String? = synchronized(lock) { serverVersion }

    /**
     * Increments each time a new server process is launched (0 while none was ever started).
     * Lets callers distinguish "the server actually restarted" from mere revalidation of a
     * healthy server, e.g. to run one-shot recovery work per server process.
     */
    fun getServerGeneration(): Long = synchronized(lock) { serverGeneration }

    /** Best-effort, informational only: refreshes the reported OpenCode version off the EDT. */
    private fun refreshServerVersion() {
        val url = getServerUrl() ?: return
        val password = getServerPassword() ?: return
        val version = OpenCodeServerProtocol.fetchServerVersion(url, OpenCodeServerProtocol.buildBasicAuthHeader(password))
        synchronized(lock) {
            if (serverUrl == url) serverVersion = version
        }
    }

    @TestOnly
    fun setServerPassword(password: String?) {
        synchronized(lock) {
            serverPassword = password
        }
    }

    fun getCheckScheduledFuture(): ScheduledFuture<*>? = synchronized(lock) { checkScheduledFuture }

    @TestOnly
    fun setCheckScheduledFuture(future: ScheduledFuture<*>?) {
        synchronized(lock) {
            checkScheduledFuture = future
        }
    }

    fun stopServer() {
        try {
            val callbacks: List<StartCallback>
            val resources = synchronized(lock) {
                startSequence++
                starting = false
                callbacks = pendingStarts.toList()
                pendingStarts.clear()
                detachServerResources()
            }

            setLifecycleState(OpenCodeServerLifecycleState.STOPPED)
            notifyStartCallbacks(callbacks, success = false)
            stopResources(resources)
        } catch (e: Exception) {
            thisLogger().error("Error stopping OpenCode server: ${e.message}")
        }
    }

    fun restartServer(
        project: Project,
        projectBasePath: String?,
        callbackActive: () -> Boolean = { true },
        onStarted: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val basePath = rememberBasePath(projectBasePath)
        val callback = StartCallback(callbackActive, onStarted, onFailed)
        val resources: ServerResourcesToStop
        val startId = synchronized(lock) {
            pendingStarts.add(callback)
            if (starting) return
            starting = true
            resources = detachServerResources()
            ++startSequence
        }

        setLifecycleState(OpenCodeServerLifecycleState.RESTARTING)
        stopResources(resources)
        startOpenCodeServer(project, basePath, startId)
    }

    private fun detachServerResources(): ServerResourcesToStop {
        val resources = ServerResourcesToStop(checkScheduledFuture, serverProcess, serverProcessDescendants)
        checkScheduledFuture = null
        serverProcess = null
        serverProcessDescendants = emptyList()
        serverRunning = false
        serverUrl = null
        serverPassword = null
        serverVersion = null
        consecutiveStartFailures = 0
        nextStartAllowedAtMillis = 0L
        return resources
    }

    private fun stopResources(resources: ServerResourcesToStop) {
        resources.future?.cancel(true)
        processTerminator.destroy(resources.process, resources.processDescendants)
    }

    override fun dispose() {
        stopServer()
        scheduler.shutdownNow()
    }

    private fun startPeriodicCheck() {
        synchronized(lock) {
            if (checkScheduledFuture != null) return

            checkScheduledFuture = scheduler.scheduleAtFixedRate(
                {
                    try {
                        checkServerHealth()
                    } catch (e: Exception) {
                        thisLogger().error("Error during periodic check: ${e.message}")
                    }
                },
                OpenCodeServerProtocol.CHECK_INTERVAL_SECONDS,
                OpenCodeServerProtocol.CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
        thisLogger().info("Started periodic server health check")
    }

    private fun cancelPeriodicCheck(mayInterruptIfRunning: Boolean = false) {
        val future = synchronized(lock) {
            checkScheduledFuture.also { checkScheduledFuture = null }
        }
        future?.cancel(mayInterruptIfRunning)
    }

    private fun checkServerHealth() {
        val url = getServerUrl()
        val responding = url != null && checkServerResponding(url)
        if (!OpenCodeServerProtocol.shouldRestartServer(url, responding)) return

        val backoffMillis = remainingStartBackoffMillis()
        if (backoffMillis > 0) {
            thisLogger().warn("Skipping OpenCode server restart during startup backoff for ${backoffMillis}ms")
            return
        }

        thisLogger().warn("Server is not responding, attempting to restart...")
        val basePath: String?
        val startId = synchronized(lock) {
            if (starting) return
            starting = true
            basePath = preferredBasePath
            ++startSequence
        }
        setLifecycleState(OpenCodeServerLifecycleState.RESTARTING)
        cancelPeriodicCheck()
        destroyCurrentProcess()
        startOpenCodeServer(null, basePath, startId)
    }

    private fun startOpenCodeServer(project: Project?, projectBasePath: String?, startId: Long) {
        if (project != null) {
            val task = object : Backgroundable(project, "Starting OpenCode server", true) {
                override fun run(indicator: ProgressIndicator) {
                    runOpenCodeServerStart(projectBasePath, startId)
                }
            }
            ProgressManager.getInstance().run(task)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            runOpenCodeServerStart(projectBasePath, startId)
        }
    }

    private fun runOpenCodeServerStart(projectBasePath: String?, startId: Long) {
        var process: Process? = null
        var capturedDescendants: List<ProcessHandle> = emptyList()
        try {
            if (!waitForIntellijMcpServerIfNeeded(startId)) return
            val password = OpenCodePasswordStore.getInstance().ensurePasswordBlocking()
            val settings = OpenCodeSettingsState.getInstance()
            val port = settings.portArgument()
            val executable = settings.executablePath()
            val processBuilder = OpenCodeServerProtocol.createProcessBuilder(projectBasePath, password, port, executable)
            serverLogBuffer.startNewFile()
            process = processBuilder.start()

            if (!setStartedProcess(startId, process, password)) {
                processTerminator.destroy(process)
                return
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val urlLatch = CountDownLatch(1)
            val logThread = Thread({
                try {
                    reader.useLines { lines ->
                        lines.forEach { line ->
                            thisLogger().info(line)
                            appendServerLog(line)
                            OpenCodeServerProtocol.parseServerUrl(line)?.let { url ->
                                if (setServerUrlForStart(startId, url)) {
                                    urlLatch.countDown()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    thisLogger().info("Stopped reading OpenCode output: ${e.message}")
                    appendServerLog("Stopped reading OpenCode output: ${e.message}")
                }
                if (isCurrentStart(startId)) {
                    thisLogger().warn("OpenCode process output stream ended; triggering immediate health check")
                    ApplicationManager.getApplication().executeOnPooledThread {
                        if (isCurrentStart(startId)) checkServerHealth()
                    }
                }
            }, "OpenCode-Output-Reader")
            logThread.isDaemon = true
            logThread.start()

            val startTime = System.currentTimeMillis()

            while (isCurrentStart(startId) && (System.currentTimeMillis() - startTime) < SERVER_START_TIMEOUT_MILLIS) {
                urlLatch.await(SERVER_START_POLL_MILLIS, TimeUnit.MILLISECONDS)
                capturedDescendants = captureDescendants(startId, process, capturedDescendants)
                val url = getServerUrl()
                if (url != null && checkServerResponding(url)) {
                    break
                }
                if (!process.isAlive && url == null) {
                    break
                }
                // Once the latch is open, await() returns immediately; without a pause the
                // loop would hammer failing health checks back-to-back until the timeout.
                if (urlLatch.count == 0L) {
                    Thread.sleep(SERVER_START_POLL_MILLIS)
                }
            }
            capturedDescendants = captureDescendants(startId, process, capturedDescendants)

            if (!isCurrentStart(startId)) {
                processTerminator.destroy(process, capturedDescendants)
                return
            }

            val url = getServerUrl()
            if (url != null && checkServerResponding(url)) {
                thisLogger().info("OpenCode server started successfully at $url")
                refreshServerVersion()
                setServerRunningForStart(startId)
                finishStart(startId, success = true)
            } else {
                thisLogger().error("Failed to start OpenCode server")
                destroyCurrentProcess()
                clearServerStateForStart(startId)
                finishStart(startId, success = false)
            }
        } catch (e: Exception) {
            thisLogger().error("Error starting OpenCode server: ${e.message}")
            if (isCurrentStart(startId)) {
                destroyCurrentProcess()
                clearServerStateForStart(startId)
                finishStart(startId, success = false)
            } else {
                processTerminator.destroy(process, capturedDescendants)
            }
        }
    }

    /**
     * Refreshes the descendant snapshot of the launcher process while it is still alive.
     * On Windows the launcher exits after spawning the real server, so this snapshot is
     * the only handle for stopping the server later; keep the last non-empty snapshot.
     */
    private fun captureDescendants(
        startId: Long,
        process: Process,
        previous: List<ProcessHandle>,
    ): List<ProcessHandle> {
        if (!process.isAlive) return previous
        val snapshot = processTerminator.descendantHandles(process)
        if (snapshot.isEmpty()) return previous
        if (snapshot.map { it.pid() }.toSet() != previous.map { it.pid() }.toSet()) {
            setServerDescendantsForStart(startId, snapshot)
        }
        return snapshot
    }

    private fun waitForIntellijMcpServerIfNeeded(startId: Long): Boolean {
        val initialStatus = IntellijMcpServerStartup.currentStatus()
        if (!IntellijMcpServerStartup.shouldWaitFor(initialStatus, OpenCodeSettingsState.getInstance().waitForIntellijMcpServer)) {
            if (initialStatus.state == IntellijMcpServerStartupState.UNAVAILABLE) {
                thisLogger().warn(initialStatus.message)
            } else {
                thisLogger().info(initialStatus.message)
            }
            return true
        }

        thisLogger().info("Waiting for ${initialStatus.message} before starting OpenCode")
        return when (
            IntellijMcpServerStartup.waitUntilReady(
                initialStatus,
                shouldWaitForStatus = { status ->
                    IntellijMcpServerStartup.shouldWaitFor(status, OpenCodeSettingsState.getInstance().waitForIntellijMcpServer)
                },
                isStillCurrent = { isCurrentStart(startId) },
            )
        ) {
            IntellijMcpServerWaitResult.READY -> true
            IntellijMcpServerWaitResult.TIMED_OUT -> {
                thisLogger().warn("Timed out waiting for IntelliJ MCP server; starting OpenCode anyway")
                true
            }
            IntellijMcpServerWaitResult.CANCELLED -> false
        }
    }

    private fun finishStart(startId: Long, success: Boolean) {
        val callbacks = synchronized(lock) {
            if (startId != startSequence) return
            starting = false
            pendingStarts.toList().also { pendingStarts.clear() }
        }

        setLifecycleState(if (success) OpenCodeServerLifecycleState.RUNNING else OpenCodeServerLifecycleState.FAILED)

        if (success) {
            recordStartSuccess()
            startPeriodicCheck()
        } else {
            cancelPeriodicCheck()
            recordStartFailure()
        }

        notifyStartCallbacks(callbacks, success)
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

    private fun rememberBasePath(projectBasePath: String?): String? {
        return synchronized(lock) {
            if (!projectBasePath.isNullOrBlank()) {
                preferredBasePath = projectBasePath
            }
            projectBasePath ?: preferredBasePath
        }
    }

    private fun checkServerResponding(serverUrl: String): Boolean {
        val password = getServerPassword()
        if (password.isNullOrBlank()) {
            thisLogger().info("OpenCode health check skipped because no server password is available")
            return false
        }
        val responding = OpenCodeServerProtocol.checkServerResponding(serverUrl, OpenCodeServerProtocol.buildBasicAuthHeader(password))
        val processAlive = getServerProcess()?.isAlive == true
        if (responding && !processAlive) {
            // Normal steady state on Windows, where the launcher exits after spawning the
            // real server - log once per start instead of on every periodic health check.
            val firstNotice = synchronized(lock) {
                (!launcherExitNoticeLogged).also { launcherExitNoticeLogged = true }
            }
            if (firstNotice) {
                thisLogger().info("OpenCode server is responding although the tracked launcher process has exited")
            }
        } else if (!responding) {
            val reason = if (processAlive) "server did not respond" else "tracked process is not alive"
            thisLogger().info("OpenCode health check failed for $serverUrl ($reason)")
        }
        return responding
    }

    private fun remainingStartBackoffMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        return synchronized(lock) {
            (nextStartAllowedAtMillis - nowMillis).coerceAtLeast(0L)
        }
    }

    private fun recordStartSuccess() {
        synchronized(lock) {
            consecutiveStartFailures = 0
            nextStartAllowedAtMillis = 0L
        }
    }

    private fun recordStartFailure(nowMillis: Long = System.currentTimeMillis()) {
        val delayMillis = synchronized(lock) {
            consecutiveStartFailures += 1
            OpenCodeServerProtocol.startFailureBackoffMillis(consecutiveStartFailures).also { delay ->
                nextStartAllowedAtMillis = nowMillis + delay
            }
        }
        thisLogger().warn("OpenCode server start failed; next automatic start allowed in ${delayMillis}ms")
    }

    private fun destroyCurrentProcess() {
        val (process, descendants) = synchronized(lock) {
            val detached = serverProcess to serverProcessDescendants
            serverProcess = null
            serverProcessDescendants = emptyList()
            detached
        }
        processTerminator.destroy(process, descendants)
    }

    private fun setStartedProcess(startId: Long, process: Process, password: String): Boolean {
        return synchronized(lock) {
            if (startId != startSequence) return@synchronized false
            serverProcess = process
            serverProcessDescendants = emptyList()
            serverUrl = null
            serverPassword = password
            serverGeneration++
            launcherExitNoticeLogged = false
            true
        }
    }

    private fun setServerDescendantsForStart(startId: Long, descendants: List<ProcessHandle>) {
        synchronized(lock) {
            if (startId != startSequence) return
            serverProcessDescendants = descendants
        }
    }

    private fun setServerUrlForStart(startId: Long, url: String): Boolean {
        return synchronized(lock) {
            if (startId != startSequence) return@synchronized false
            serverUrl = url
            true
        }
    }

    private fun setServerRunningForStart(startId: Long) {
        synchronized(lock) {
            if (startId == startSequence) {
                serverRunning = true
            }
        }
    }

    private fun isCurrentStart(startId: Long): Boolean {
        return synchronized(lock) { startId == startSequence }
    }

    private fun clearServerStateForStart(startId: Long) {
        synchronized(lock) {
            if (startId != startSequence) return
            serverRunning = false
            serverUrl = null
            serverPassword = null
            serverVersion = null
            serverProcessDescendants = emptyList()
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
            try {
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(OpenCodeServerLifecycleListener.TOPIC)
                    .stateChanged(state)
            } catch (e: Exception) {
                thisLogger().warn("Could not publish OpenCode server lifecycle state ${state.name}: ${e.message}")
            }
        }
    }
}
