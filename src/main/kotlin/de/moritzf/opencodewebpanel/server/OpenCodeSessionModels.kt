package de.moritzf.opencodewebpanel.server

internal data class OpenCodeSessionInfo(
    val title: String,
    val parentID: String?,
    val id: String = "",
    val directory: String? = null,
)

/** One file's diff returned by `GET /session/{id}/diff`; [patch] is a unified diff string. */
internal data class OpenCodeSnapshotFileDiff(
    val file: String?,
    val patch: String?,
    val additions: Long,
    val deletions: Long,
    val status: String?,
)

/**
 * Per-tool edit/write/apply_patch changes. [diffs] contains only the tool's own patches. Write
 * tools have no patch; [fileHint] lets callers fall back to the turn snapshot.
 */
internal data class OpenCodeToolPartChange(
    val diffs: List<OpenCodeSnapshotFileDiff>,
    val fileHint: String? = null,
)

internal data class OpenCodePendingRequestSummary(val id: String, val sessionID: String)

internal data class OpenCodeSessionSummary(
    val id: String,
    val updatedMillis: Long,
    val parentID: String? = null,
    val directory: String? = null,
)

internal enum class OpenCodePermissionResponse(val jsonValue: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}
