package de.moritzf.opencodewebpanel.server

internal object IntellijMcpServerStartup {
    private const val MCP_PLUGIN_ID = "com.intellij.mcpServer"
    private const val PLUGIN_MANAGER_CLASS = "com.intellij.ide.plugins.PluginManager"
    private const val MCP_SETTINGS_CLASS = "com.intellij.mcpserver.settings.McpServerSettings"
    private const val MCP_SERVICE_CLASS = "com.intellij.mcpserver.impl.McpServerService"
    private const val WAIT_TIMEOUT_MILLIS = 30_000L
    private const val POLL_INTERVAL_MILLIS = 500L

    fun currentStatus(): IntellijMcpServerStartupStatus {
        return runCatching {
            val classLoader = mcpClassLoader()
                ?: return IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED,
                    "IntelliJ MCP server plugin is not installed, disabled, or unavailable",
                )
            statusForRuntimeState(
                enabled = isMcpServerEnabled(classLoader),
                service = mcpServerService(classLoader),
            )
        }.getOrElse { error ->
            IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.UNAVAILABLE,
                "IntelliJ MCP server status is unavailable: ${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    fun shouldWaitFor(status: IntellijMcpServerStartupStatus, enabled: Boolean = true): Boolean {
        return enabled && status.state == IntellijMcpServerStartupState.ENABLED_NOT_RUNNING
    }

    /** Polls until [stillWaiting] turns false (READY), the timeout expires, or the start is superseded. */
    fun waitUntilReady(
        stillWaiting: () -> Boolean = { shouldWaitFor(currentStatus()) },
        isStillCurrent: () -> Boolean = { true },
        nowMillis: () -> Long = System::currentTimeMillis,
        sleepMillis: (Long) -> Unit = Thread::sleep,
        timeoutMillis: Long = WAIT_TIMEOUT_MILLIS,
        pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    ): IntellijMcpServerWaitResult {
        if (!stillWaiting()) return IntellijMcpServerWaitResult.READY

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
            if (!stillWaiting()) return IntellijMcpServerWaitResult.READY
        }
        return IntellijMcpServerWaitResult.CANCELLED
    }

    internal fun statusForRuntimeState(enabled: Boolean?, service: Any?): IntellijMcpServerStartupStatus {
        return when {
            enabled == false -> IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED,
                "IntelliJ MCP server is disabled",
            )
            enabled == null -> IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.UNAVAILABLE,
                "IntelliJ MCP server settings are unavailable",
            )
            service == null -> IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.UNAVAILABLE,
                "IntelliJ MCP server service is unavailable",
            )
            isMcpServerRunning(service) -> {
                val url = mcpServerSseUrl(service)
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED,
                    if (url == null) "IntelliJ MCP server is running" else "IntelliJ MCP server is running at $url",
                )
            }
            else -> IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                "IntelliJ MCP server is enabled but not running yet",
            )
        }
    }

    private fun mcpClassLoader(): ClassLoader? {
        // Direct plugin descriptor lookup APIs are verifier-visible internal APIs in 2026.2.
        val pluginManagerClass = Class.forName(PLUGIN_MANAGER_CLASS)
        val loadedPlugins = pluginManagerClass.getMethod("getLoadedPlugins").invoke(null) as? Iterable<*>
            ?: return null
        return loadedPlugins.firstNotNullOfOrNull { descriptor ->
            if (descriptor?.reflectValue("getPluginId")?.toString() != MCP_PLUGIN_ID) return@firstNotNullOfOrNull null
            descriptor.reflectClassLoader("getPluginClassLoader")
        }
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

    private fun isMcpServerRunning(service: Any): Boolean {
        return service.javaClass.getMethod("isRunning").invoke(service) as? Boolean ?: false
    }

    private fun mcpServerSseUrl(service: Any): String? {
        return runCatching { service.javaClass.getMethod("getServerSseUrl").invoke(service) as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Any.reflectValue(methodName: String): Any? {
        return javaClass.getMethod(methodName).invoke(this)
    }

    private fun Any.reflectClassLoader(methodName: String): ClassLoader? {
        return javaClass.getMethod(methodName).invoke(this) as? ClassLoader
    }
}

internal data class IntellijMcpServerStartupStatus(
    val state: IntellijMcpServerStartupState,
    val message: String,
)

internal enum class IntellijMcpServerStartupState {
    ENABLED,
    ENABLED_NOT_RUNNING,
    NOT_CONFIGURED_OR_DISABLED,
    UNAVAILABLE,
}

internal enum class IntellijMcpServerWaitResult {
    READY,
    TIMED_OUT,
    CANCELLED,
}
