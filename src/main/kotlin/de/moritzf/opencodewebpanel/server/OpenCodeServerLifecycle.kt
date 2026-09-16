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
    fun stateChanged(state: OpenCodeServerLifecycleState, backendId: String)

    companion object {
        val TOPIC: Topic<OpenCodeServerLifecycleListener> = Topic.create(
            "OpenCode Web Panel server lifecycle",
            OpenCodeServerLifecycleListener::class.java,
        )
    }
}

internal fun formatOpenCodeServerRuntimeLabel(backendId: String): String {
    return if (OpenCodeServerBackend.isNative(backendId)) "native CLI" else "sbx"
}

internal data class OpenCodeRecoveryNotice(
    val reason: String,
    val atMillis: Long,
)

internal data class OpenCodeLifecycleStripModel(
    val state: OpenCodeServerLifecycleState,
    val pageOpening: Boolean = false,
    val cancelled: Boolean = false,
    val stage: String? = null,
    val elapsedMillis: Long? = null,
    val recovery: OpenCodeRecoveryNotice? = null,
)

internal fun formatElapsedMillis(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes == 0L) "${seconds}s" else "${minutes}m ${seconds.toString().padStart(2, '0')}s"
}

internal fun formatOpenCodeRecoveryLine(notice: OpenCodeRecoveryNotice, nowMillis: Long): String {
    val ago = formatElapsedMillis((nowMillis - notice.atMillis).coerceAtLeast(0L))
    return "Last recovery $ago ago: ${notice.reason}"
}

internal fun formatOpenCodeLifecycleStrip(model: OpenCodeLifecycleStripModel, nowMillis: Long = System.currentTimeMillis()): String {
    if (model.pageOpening && model.state == OpenCodeServerLifecycleState.RUNNING && !model.cancelled) {
        return formatOpenCodePageOpeningStatusText()
    }
    val state = if (model.cancelled) {
        OpenCodeServerLifecycleState.FAILED
    } else {
        model.state
    }
    val label = if (model.cancelled) "Cancelled" else state.displayLabel
    val color = if (model.cancelled) OpenCodeServerLifecycleState.STOPPED.colorHex else state.colorHex
    val extra = buildString {
        val stage = model.stage?.trim()?.takeIf { it.isNotEmpty() }
        if (stage != null && (model.state == OpenCodeServerLifecycleState.STARTING || model.state == OpenCodeServerLifecycleState.RESTARTING)) {
            append(" — ")
            append(stage)
        }
        val elapsed = model.elapsedMillis
        if (elapsed != null && (model.state == OpenCodeServerLifecycleState.STARTING || model.state == OpenCodeServerLifecycleState.RESTARTING)) {
            append(" (")
            append(formatElapsedMillis(elapsed))
            append(")")
        }
        val recovery = model.recovery
        if (recovery != null) {
            append(". ")
            append(formatOpenCodeRecoveryLine(recovery, nowMillis))
        }
    }
    return "<html><span style=\"color: $color\">&#9679;</span>&nbsp;" +
        "OpenCode server: ${StringUtil.escapeXmlEntities(label)}${StringUtil.escapeXmlEntities(extra)}</html>"
}

internal const val RECOVERY_BANNER_MILLIS = 15_000L

internal fun visibleRecoveryNotice(
    notice: OpenCodeRecoveryNotice?,
    state: OpenCodeServerLifecycleState,
    pagePainted: Boolean,
    pageLoadInProgress: Boolean,
    nowMillis: Long,
): OpenCodeRecoveryNotice? {
    if (notice == null) return null
    if (state == OpenCodeServerLifecycleState.RUNNING &&
        pagePainted &&
        !pageLoadInProgress &&
        nowMillis - notice.atMillis >= RECOVERY_BANNER_MILLIS
    ) {
        return null
    }
    return notice
}

internal fun isOpenCodeLifecycleStripVisible(model: OpenCodeLifecycleStripModel): Boolean {
    if (model.cancelled) return true
    if (model.pageOpening && model.state == OpenCodeServerLifecycleState.RUNNING) return true
    if (model.recovery != null && model.state == OpenCodeServerLifecycleState.RUNNING) return true
    return model.state == OpenCodeServerLifecycleState.STARTING ||
        model.state == OpenCodeServerLifecycleState.RESTARTING ||
        model.state == OpenCodeServerLifecycleState.FAILED
}

internal fun shouldTickLifecycleStrip(model: OpenCodeLifecycleStripModel): Boolean {
    if (model.state == OpenCodeServerLifecycleState.STARTING ||
        model.state == OpenCodeServerLifecycleState.RESTARTING
    ) {
        return true
    }
    return model.recovery != null && isOpenCodeLifecycleStripVisible(model)
}

internal fun formatOpenCodeServerStatusDetail(
    state: OpenCodeServerLifecycleState,
    serverUrl: String?,
    version: String?,
    backendId: String,
    wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.UNKNOWN,
): String {
    val runtime = formatOpenCodeServerRuntimeLabel(backendId)
    if (state == OpenCodeServerLifecycleState.RUNNING && !serverUrl.isNullOrBlank()) {
        val bits = listOfNotNull(
            version?.takeIf { it.isNotBlank() }?.let { "OpenCode $it" },
            wireProtocol.statusLabel(),
            runtime,
        )
        return ": $serverUrl (${bits.joinToString(", ")})"
    }
    return " ($runtime)"
}

/** [detail] is plain text and gets HTML-escaped here. */
internal fun formatOpenCodeServerLifecycleStatusText(state: OpenCodeServerLifecycleState, detail: String = ""): String {
    return "<html><span style=\"color: ${state.colorHex}\">&#9679;</span>&nbsp;" +
        "OpenCode server: ${state.displayLabel}${StringUtil.escapeXmlEntities(detail)}</html>"
}

internal fun isOpenCodeServerLifecycleStatusVisible(state: OpenCodeServerLifecycleState): Boolean {
    return state != OpenCodeServerLifecycleState.RUNNING
}

/** Keep the strip up after the server is running until the embedded page actually paints.
 *  Stopped uses the idle card (Start). Restarting keeps the strip for stage/log/cancel. */
internal fun isOpenCodeLifecycleStripVisible(
    state: OpenCodeServerLifecycleState,
    pageOpening: Boolean = false,
): Boolean {
    if (pageOpening && state == OpenCodeServerLifecycleState.RUNNING) return true
    return state == OpenCodeServerLifecycleState.STARTING ||
        state == OpenCodeServerLifecycleState.RESTARTING ||
        state == OpenCodeServerLifecycleState.FAILED
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
 * is discarded. Restart replaces the tool-window content (new JCEF) instead of parking this
 * document.
 */
internal fun shouldHideEmbeddedPage(state: OpenCodeServerLifecycleState): Boolean {
    return state == OpenCodeServerLifecycleState.STOPPED ||
        state == OpenCodeServerLifecycleState.RESTARTING
}

/**
 * Center card for a newly created panel (including Restart's fresh JCEF) before a page load.
 * `error` / `idle` match the tool-window card names; null keeps the browser card.
 */
internal fun parkedEmbeddedCenterCard(state: OpenCodeServerLifecycleState): String? {
    if (shouldShowStartupError(state)) return "error"
    if (shouldHideEmbeddedPage(state)) return "idle"
    return null
}

/** CEF reports 0 for some successful document loads, especially after basic-auth on Windows. */
internal fun isSuccessfulOpenCodeDocumentLoad(httpStatusCode: Int): Boolean {
    return httpStatusCode == 0 || httpStatusCode in 200..399
}
