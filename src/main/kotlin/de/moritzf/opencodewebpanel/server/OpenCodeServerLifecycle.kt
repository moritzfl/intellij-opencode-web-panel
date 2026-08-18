package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.util.text.StringUtil
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

/** [detail] is plain text and gets HTML-escaped here. */
internal fun formatOpenCodeServerLifecycleStatusText(state: OpenCodeServerLifecycleState, detail: String = ""): String {
    return "<html><span style=\"color: ${state.colorHex}\">&#9679;</span>&nbsp;" +
        "OpenCode server: ${state.displayLabel}${StringUtil.escapeXmlEntities(detail)}</html>"
}

internal fun isOpenCodeServerLifecycleStatusVisible(state: OpenCodeServerLifecycleState): Boolean {
    return state != OpenCodeServerLifecycleState.RUNNING
}

/** Keep the strip up after the server is running until the embedded page actually paints. */
internal fun isOpenCodeLifecycleStripVisible(
    state: OpenCodeServerLifecycleState,
    pageOpening: Boolean = false,
): Boolean {
    return (pageOpening && state == OpenCodeServerLifecycleState.RUNNING) ||
        isOpenCodeServerLifecycleStatusVisible(state)
}

/** Hide "Opening…" on later in-app navigations once a page has already painted. */
internal fun shouldShowPageOpeningStatus(
    pageLoadInProgress: Boolean,
    openCodePagePainted: Boolean,
): Boolean = pageLoadInProgress && !openCodePagePainted

internal fun formatOpenCodePageOpeningStatusText(): String {
    return "<html><span style=\"color: ${OpenCodeServerLifecycleState.STARTING.colorHex}\">&#9679;</span>&nbsp;" +
        "Opening the OpenCode page…</html>"
}

internal fun shouldShowStartupError(state: OpenCodeServerLifecycleState): Boolean {
    return state == OpenCodeServerLifecycleState.FAILED
}

internal fun isOpenCodeServerRetryVisible(state: OpenCodeServerLifecycleState): Boolean {
    return state == OpenCodeServerLifecycleState.FAILED || state == OpenCodeServerLifecycleState.STOPPED
}

internal fun openCodeServerRetryLabel(state: OpenCodeServerLifecycleState): String {
    return if (state == OpenCodeServerLifecycleState.STOPPED) "Start" else "Retry"
}

internal fun isOpenCodePageReloadEnabled(state: OpenCodeServerLifecycleState): Boolean {
    return state != OpenCodeServerLifecycleState.STOPPED
}

internal fun isOpenCodeServerStopEnabled(state: OpenCodeServerLifecycleState): Boolean {
    return state == OpenCodeServerLifecycleState.STARTING ||
        state == OpenCodeServerLifecycleState.RUNNING ||
        state == OpenCodeServerLifecycleState.RESTARTING
}

/** Drop lifecycle events that were queued before a newer state replaced them. */
internal fun shouldApplyPublishedLifecycleState(
    published: OpenCodeServerLifecycleState,
    current: OpenCodeServerLifecycleState,
): Boolean = published == current

/**
 * Hide the embedded page with a native card. Do not navigate CEF to about:blank — sitting on
 * that document (the Stop-then-Start path) leaves JCEF blank on Windows after the renderer
 * is discarded. Restart stays healthy because its blank interval is only the process kill.
 */
internal fun shouldHideEmbeddedPage(state: OpenCodeServerLifecycleState): Boolean {
    return state == OpenCodeServerLifecycleState.STOPPED ||
        state == OpenCodeServerLifecycleState.RESTARTING
}

/** CEF reports 0 for some successful document loads, especially after basic-auth on Windows. */
internal fun isSuccessfulOpenCodeDocumentLoad(httpStatusCode: Int): Boolean {
    return httpStatusCode == 0 || httpStatusCode in 200..399
}
