package de.moritzf.opencodewebpanel.server

import com.intellij.util.messages.Topic

enum class OpenCodeServerLifecycleState(
    val displayLabel: String,
    val colorHex: String,
) {
    STARTING("Starting", "#FFC107"),
    RUNNING("Running", "#4CAF50"),
    FAILED("Failed", "#F44336"),
    RESTARTING("Restarting", "#FFC107"),
    STOPPED("Stopped", "#9E9E9E"),
}

interface OpenCodeServerLifecycleListener {
    fun stateChanged(state: OpenCodeServerLifecycleState)

    companion object {
        val TOPIC: Topic<OpenCodeServerLifecycleListener> = Topic.create(
            "OpenCode Web Panel server lifecycle",
            OpenCodeServerLifecycleListener::class.java,
        )
    }
}

/** [detail] is appended verbatim; callers must HTML-escape any untrusted content in it. */
internal fun formatOpenCodeServerLifecycleStatusText(state: OpenCodeServerLifecycleState, detail: String = ""): String {
    return "<html><span style=\"color: ${state.colorHex}\">&#9679;</span>&nbsp;" +
        "OpenCode server: ${state.displayLabel}$detail</html>"
}

internal fun isOpenCodeServerLifecycleStatusVisible(state: OpenCodeServerLifecycleState): Boolean {
    return state != OpenCodeServerLifecycleState.RUNNING
}

internal fun isOpenCodeServerRetryVisible(state: OpenCodeServerLifecycleState): Boolean {
    return state == OpenCodeServerLifecycleState.FAILED || state == OpenCodeServerLifecycleState.STOPPED
}

internal fun openCodeServerRetryLabel(state: OpenCodeServerLifecycleState): String {
    return if (state == OpenCodeServerLifecycleState.STOPPED) "Start" else "Retry"
}
