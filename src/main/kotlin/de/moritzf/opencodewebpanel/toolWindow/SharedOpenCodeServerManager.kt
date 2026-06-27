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
            setLifecycleState(OpenCodeServerLifecycleState.STARTING)
            ApplicationManager.getApplication().executeOnPooledThread {
                validateExistingServerOrStart(project, basePath, url, validationId)
            }
            return
        }

        val backoffMillis = remainingStartBackoffMillis()
        if (backoffMillis > 0) {
            thisLogger().warn("Delaying OpenCode server start after recent failure for ${backoffMillis}ms")
            setLifecycleState(OpenCodeServerLifecycleState.FAILED)
            ApplicationManager.getApplication().invokeLater(onFailed)
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
        startOpenCodeServer(project, basePath, startId)
    }

    private fun validateExistingServerOrStart(project: Project, projectBasePath: String?, url: String, startId: Long) {
        if (!isCurrentStart(startId)) return
        if (checkServerResponding(url)) {
            setServerRunningForStart(startId)
            finishStart(startId, success = true)
            return
        }
        if (!isCurrentStart(startId)) return
        destroyCurrentProcess()
        startOpenCodeServer(project, projectBasePath, startId)
    }

    fun isServerRunning(): Boolean = synchronized(lock) { serverRunning }

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
            val resources = synchronized(lock) {
                startSequence++
                starting = false
                pendingStarts.clear()
                detachServerResources()
            }

            setLifecycleState(OpenCodeServerLifecycleState.STOPPED)
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
        val resources = ServerResourcesToStop(checkScheduledFuture, serverProcess)
        checkScheduledFuture = null
        serverProcess = null
        serverRunning = false
        serverUrl = null
        serverPassword = null
        consecutiveStartFailures = 0
        nextStartAllowedAtMillis = 0L
        return resources
    }

    private fun stopResources(resources: ServerResourcesToStop) {
        resources.future?.cancel(true)
        processTerminator.destroy(resources.process)
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

            var maxAttempts = 30
            val startTime = System.currentTimeMillis()
            val timeout = 60000L

            while (isCurrentStart(startId) && maxAttempts-- > 0 && (System.currentTimeMillis() - startTime) < timeout) {
                urlLatch.await(1, TimeUnit.SECONDS)
                val url = getServerUrl()
                if (process.isAlive && url != null && checkServerResponding(url)) {
                    break
                }
                if (!process.isAlive) {
                    break
                }
            }

            if (!isCurrentStart(startId)) {
                processTerminator.destroy(process)
                return
            }

            val url = getServerUrl()
            if (url != null && checkServerResponding(url)) {
                thisLogger().info("OpenCode server started successfully at $url")
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
                processTerminator.destroy(process)
            }
        }
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
        val process = getServerProcess()
        if (process?.isAlive != true) {
            thisLogger().info("OpenCode health check skipped because the server process is not alive")
            return false
        }
        val password = getServerPassword()
        if (password.isNullOrBlank()) {
            thisLogger().info("OpenCode health check skipped because no server password is available")
            return false
        }
        val responding = OpenCodeServerProtocol.checkServerResponding(serverUrl, OpenCodeServerProtocol.buildBasicAuthHeader(password))
        if (!responding) thisLogger().info("OpenCode health check failed for $serverUrl")
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
        val process = synchronized(lock) {
            serverProcess.also { serverProcess = null }
        }
        processTerminator.destroy(process)
    }

    private fun setStartedProcess(startId: Long, process: Process, password: String): Boolean {
        return synchronized(lock) {
            if (startId != startSequence) return@synchronized false
            serverProcess = process
            serverUrl = null
            serverPassword = password
            true
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
