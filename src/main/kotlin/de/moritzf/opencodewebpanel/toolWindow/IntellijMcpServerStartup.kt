package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

internal object IntellijMcpServerStartup {
    private const val MCP_PLUGIN_ID = "com.intellij.mcpServer"
    private const val MCP_SETTINGS_CLASS = "com.intellij.mcpserver.settings.McpServerSettings"
    private const val MCP_SERVICE_CLASS = "com.intellij.mcpserver.impl.McpServerService"
    private const val WAIT_TIMEOUT_MILLIS = 30_000L
    private const val POLL_INTERVAL_MILLIS = 500L

    fun currentStatus(): IntellijMcpServerStartupStatus {
        return runCatching {
            val classLoader = mcpPluginClassLoader()
                ?: return IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED,
                    "IntelliJ MCP server plugin is not installed or is disabled",
                )
            val enabled = isMcpServerEnabled(classLoader)
                ?: return IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.UNAVAILABLE,
                    "IntelliJ MCP server settings are unavailable",
                )
            if (!enabled) {
                return IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED,
                    "IntelliJ MCP server is disabled",
                )
            }

            val service = mcpServerService(classLoader)
                ?: return IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.UNAVAILABLE,
                    "IntelliJ MCP server service is unavailable",
                )
            val serviceClass = service.javaClass
            val running = serviceClass.getMethod("isRunning").invoke(service) as? Boolean ?: false
            if (!running) {
                return IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                    "IntelliJ MCP server is enabled but not running yet",
                )
            }

            val url = runCatching { serviceClass.getMethod("getServerSseUrl").invoke(service) as? String }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.RUNNING,
                if (url == null) "IntelliJ MCP server is running" else "IntelliJ MCP server is running at $url",
            )
        }.getOrElse { error ->
            if (error.isMissingMcpServer()) {
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED,
                    "IntelliJ MCP server plugin is not installed, disabled, or incompatible",
                )
            } else {
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.UNAVAILABLE,
                    "IntelliJ MCP server status is unavailable: ${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    fun shouldWaitFor(status: IntellijMcpServerStartupStatus): Boolean {
        return status.state == IntellijMcpServerStartupState.ENABLED_NOT_RUNNING
    }

    fun waitUntilReady(
        initialStatus: IntellijMcpServerStartupStatus = currentStatus(),
        statusProvider: () -> IntellijMcpServerStartupStatus = ::currentStatus,
        isStillCurrent: () -> Boolean = { true },
        nowMillis: () -> Long = System::currentTimeMillis,
        sleepMillis: (Long) -> Unit = Thread::sleep,
        timeoutMillis: Long = WAIT_TIMEOUT_MILLIS,
        pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    ): IntellijMcpServerWaitResult {
        var status = initialStatus
        if (!shouldWaitFor(status)) return IntellijMcpServerWaitResult.READY

        val deadlineMillis = nowMillis() + timeoutMillis
        while (isStillCurrent()) {
            val remainingMillis = deadlineMillis - nowMillis()
            if (remainingMillis <= 0L) return IntellijMcpServerWaitResult.TIMED_OUT
            try {
                sleepMillis(minOf(pollIntervalMillis, remainingMillis))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return IntellijMcpServerWaitResult.CANCELLED
            }
            status = statusProvider()
            if (!shouldWaitFor(status)) return IntellijMcpServerWaitResult.READY
        }
        return IntellijMcpServerWaitResult.CANCELLED
    }

    private fun mcpPluginClassLoader(): ClassLoader? {
        val pluginId = PluginId.getId(MCP_PLUGIN_ID)
        val descriptor = PluginManagerCore.getPlugin(pluginId) ?: return null
        if (PluginManagerCore.isDisabled(pluginId)) return null
        return descriptor.pluginClassLoader
    }

    private fun isMcpServerEnabled(classLoader: ClassLoader): Boolean? {
        val settingsClass = Class.forName(MCP_SETTINGS_CLASS, true, classLoader)
        val settings = settingsClass.getMethod("getInstance").invoke(null) ?: return null
        val state = settingsClass.getMethod("getState").invoke(settings) ?: return null
        return state.javaClass.getMethod("getEnableMcpServer").invoke(state) as? Boolean
    }

    private fun mcpServerService(classLoader: ClassLoader): Any? {
        val serviceClass = Class.forName(MCP_SERVICE_CLASS, true, classLoader)
        val companion = serviceClass.getField("Companion").get(null) ?: return null
        return companion.javaClass.getMethod("getInstance").invoke(companion)
    }

    private fun Throwable.isMissingMcpServer(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ClassNotFoundException || current is NoClassDefFoundError) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

internal data class IntellijMcpServerStartupStatus(
    val state: IntellijMcpServerStartupState,
    val message: String,
)

internal enum class IntellijMcpServerStartupState {
    RUNNING,
    ENABLED_NOT_RUNNING,
    NOT_CONFIGURED_OR_DISABLED,
    UNAVAILABLE,
}

internal enum class IntellijMcpServerWaitResult {
    READY,
    TIMED_OUT,
    CANCELLED,
}
