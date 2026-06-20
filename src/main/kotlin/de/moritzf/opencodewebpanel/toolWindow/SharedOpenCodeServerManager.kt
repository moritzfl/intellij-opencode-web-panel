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
import java.io.BufferedReader
import java.io.InputStreamReader
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

    private val lock = Any()
    private val pendingStarts = mutableListOf<StartCallback>()
    private var startSequence = 0L
    private var starting = false
    private var serverRunning = false
    private var serverProcess: Process? = null
    private var serverUrl: String? = null
    private var serverPassword: String? = null
    private var checkScheduledFuture: ScheduledFuture<*>? = null
    private var preferredBasePath: String? = null
    private var consecutiveStartFailures = 0
    private var nextStartAllowedAtMillis = 0L
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "OpenCode-Server-Checker").apply { isDaemon = true }
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
            ApplicationManager.getApplication().executeOnPooledThread {
                validateExistingServerOrStart(project, basePath, url, validationId)
            }
            return
        }

        val backoffMillis = remainingStartBackoffMillis()
        if (backoffMillis > 0) {
            thisLogger().warn("Delaying OpenCode server start after recent failure for ${backoffMillis}ms")
            ApplicationManager.getApplication().invokeLater(onFailed)
            return
        }

        val startId = synchronized(lock) {
            pendingStarts.add(callback)
            if (starting) return
            starting = true
            ++startSequence
        }

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

    fun setServerRunning(running: Boolean) {
        synchronized(lock) {
            serverRunning = running
        }
    }

    fun getServerProcess(): Process? = synchronized(lock) { serverProcess }

    fun setServerProcess(process: Process?) {
        synchronized(lock) {
            serverProcess = process
        }
    }

    fun getServerUrl(): String? = synchronized(lock) { serverUrl }

    fun setServerUrl(url: String?) {
        synchronized(lock) {
            serverUrl = url
        }
    }

    fun getServerPassword(): String? = synchronized(lock) { serverPassword }

    fun setServerPassword(password: String?) {
        synchronized(lock) {
            serverPassword = password
        }
    }

    fun getCheckScheduledFuture(): ScheduledFuture<*>? = synchronized(lock) { checkScheduledFuture }

    fun setCheckScheduledFuture(future: ScheduledFuture<*>?) {
        synchronized(lock) {
            checkScheduledFuture = future
        }
    }

    fun stopServer() {
        try {
            val futureToCancel: ScheduledFuture<*>?
            val processToDestroy: Process?
            synchronized(lock) {
                startSequence++
                starting = false
                pendingStarts.clear()
                futureToCancel = checkScheduledFuture
                checkScheduledFuture = null
                processToDestroy = serverProcess
                serverProcess = null
                serverRunning = false
                serverUrl = null
                serverPassword = null
                consecutiveStartFailures = 0
                nextStartAllowedAtMillis = 0L
            }

            futureToCancel?.cancel(true)
            destroyProcess(processToDestroy)
        } catch (e: Exception) {
            thisLogger().error("Error stopping OpenCode server: ${e.message}")
        }
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
        cancelPeriodicCheck()
        destroyCurrentProcess()
        startOpenCodeServer(null, basePath, startId)
    }

    private fun startOpenCodeServer(project: Project?, projectBasePath: String?, startId: Long) {
        if (project != null) {
            val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
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
            val password = OpenCodePasswordStore.getInstance().ensurePasswordBlocking()
            val settings = OpenCodeSettingsState.getInstance()
            val port = settings.portArgument()
            val executable = settings.executablePath()
            val processBuilder = OpenCodeServerProtocol.createProcessBuilder(projectBasePath, password, port, executable)
            process = processBuilder.start()

            if (!setStartedProcess(startId, process, password)) {
                destroyProcess(process)
                return
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val urlLatch = CountDownLatch(1)
            val logThread = Thread({
                try {
                    reader.useLines { lines ->
                        lines.forEach { line ->
                            thisLogger().info(line)
                            OpenCodeServerProtocol.parseServerUrl(line)?.let { url ->
                                if (setServerUrlForStart(startId, url)) {
                                    urlLatch.countDown()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    thisLogger().info("Stopped reading OpenCode output: ${e.message}")
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
                destroyProcess(process)
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
                destroyProcess(process)
            }
        }
    }

    private fun finishStart(startId: Long, success: Boolean) {
        val callbacks = synchronized(lock) {
            if (startId != startSequence) return
            starting = false
            pendingStarts.toList().also { pendingStarts.clear() }
        }

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
        val responding = OpenCodeServerProtocol.checkServerResponding(serverUrl)
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
        destroyProcess(process)
    }

    private fun destroyProcess(process: Process?) {
        if (process?.isAlive == true) {
            process.destroy()
            thisLogger().info("OpenCode server stopped")
        }
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
}
