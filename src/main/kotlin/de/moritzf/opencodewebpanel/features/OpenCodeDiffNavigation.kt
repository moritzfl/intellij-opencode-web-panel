package de.moritzf.opencodewebpanel.features

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeUnifiedDiff
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager

/**
 * Opens the IDE's native diff viewer for a diff target the user Alt+Clicked in the OpenCode page
 * (see `OpenCodeBrowserSnippets.buildDiffNavigationScript`). The page sends
 * `messageID\nfilePath\npartID` (each optional). Chat edit/write/patch uses [partID] (`prt_…`)
 * and the tool part's own patch; review/turn-summary uses [messageID] + `session.diff`.
 * Session id and directory are derived here.
 */
internal class OpenCodeDiffNavigation(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val projectDirectory: () -> String?,
) {
    fun openDiff(payload: String?) {
        val parts = (payload ?: return).split('\n')
        val messageID = parts.getOrNull(0)?.trim()?.ifBlank { null }
        val filePath = parts.getOrNull(1)?.trim()?.ifBlank { null }
        val partID = parts.getOrNull(2)?.trim()?.ifBlank { null }
        val serverUrl = serverManager.getServerUrl() ?: return
        val password = serverManager.getServerPassword() ?: return
        val sessionID = OpenCodeServerProtocol.sessionIdFromUrl(browser.cefBrowser.url) ?: return
        val directory = projectDirectory()?.takeIf { it.isNotBlank() } ?: return
        val auth = OpenCodeServerProtocol.buildBasicAuthHeader(password)

        ApplicationManager.getApplication().executeOnPooledThread {
            val diffs = loadDiffs(serverUrl, auth, directory, sessionID, messageID, filePath, partID)
                ?: run {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) notifyDiffLoadFailed()
                    }
                    return@executeOnPooledThread
                }
            val requests = diffs.mapNotNull(::buildDiffRequest)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (requests.isEmpty()) {
                    notifyNoDiff()
                } else {
                    showDiffRequests(requests)
                }
            }
        }
    }

    private fun loadDiffs(
        serverUrl: String,
        auth: String,
        directory: String,
        sessionID: String,
        messageID: String?,
        filePath: String?,
        partID: String?,
    ): List<OpenCodeServerProtocol.SnapshotFileDiff>? {
        if (partID != null) {
            val partResult = OpenCodeServerProtocol.fetchToolPartChange(
                serverUrl,
                auth,
                directory,
                sessionID,
                partID,
            )
            if (partResult is OpenCodeProtocolResult.Failure) return null
            val change = (partResult as OpenCodeProtocolResult.Success).value
            if (change.diffs.isNotEmpty()) {
                return resolvePartDiffs(change.diffs, filePath)
            }
            val hint = change.fileHint ?: return emptyList()
            val snapshot = OpenCodeServerProtocol.fetchSessionDiffResult(
                serverUrl,
                auth,
                directory,
                sessionID,
                messageID,
            )
            if (snapshot is OpenCodeProtocolResult.Failure) return null
            return selectDiffs((snapshot as OpenCodeProtocolResult.Success).value, hint)
        }
        val snapshot = OpenCodeServerProtocol.fetchSessionDiffResult(
            serverUrl,
            auth,
            directory,
            sessionID,
            messageID,
        )
        if (snapshot is OpenCodeProtocolResult.Failure) return null
        return selectDiffs((snapshot as OpenCodeProtocolResult.Success).value, filePath)
    }

    companion object {
        internal fun resolvePartDiffs(
            diffs: List<OpenCodeServerProtocol.SnapshotFileDiff>,
            filePath: String?,
            caseSensitive: Boolean = SystemInfo.isFileSystemCaseSensitive,
        ): List<OpenCodeServerProtocol.SnapshotFileDiff> {
            if (diffs.size <= 1 || filePath == null) return diffs
            return selectDiffs(diffs, filePath, caseSensitive)
        }

        /**
         * Keeps only the requested file's diff. A path that cannot be matched yields empty — never
         * the whole turn (basename-only matching used to open the wrong sibling file).
         */
        internal fun selectDiffs(
            diffs: List<OpenCodeServerProtocol.SnapshotFileDiff>,
            filePath: String?,
            caseSensitive: Boolean = SystemInfo.isFileSystemCaseSensitive,
        ): List<OpenCodeServerProtocol.SnapshotFileDiff> {
            if (filePath == null) return diffs
            val exact = diffs.filter { pathsEqual(it.file, filePath, caseSensitive) }
            if (exact.isNotEmpty()) return exact
            return diffs.filter { matchesFile(it.file, filePath, caseSensitive) }
                .singleOrNull()
                ?.let(::listOf)
                .orEmpty()
        }

        /** Match a unique relative-path suffix after exact matches have been exhausted. */
        internal fun matchesFile(
            diffFile: String?,
            requested: String,
            caseSensitive: Boolean = SystemInfo.isFileSystemCaseSensitive,
        ): Boolean {
            val a = normalizePath(diffFile) ?: return false
            val b = normalizePath(requested) ?: return false
            if (a.isEmpty() || b.isEmpty()) return false
            return a.endsWith("/$b", ignoreCase = !caseSensitive) ||
                b.endsWith("/$a", ignoreCase = !caseSensitive)
        }

        private fun pathsEqual(first: String?, second: String, caseSensitive: Boolean): Boolean {
            val a = normalizePath(first) ?: return false
            val b = normalizePath(second) ?: return false
            return a.equals(b, ignoreCase = !caseSensitive)
        }

        private fun normalizePath(path: String?): String? = path?.replace('\\', '/')?.trim(' ', '/')
    }

    private fun buildDiffRequest(diff: OpenCodeServerProtocol.SnapshotFileDiff): DiffRequest? {
        val sides = OpenCodeUnifiedDiff.sides(diff.patch) ?: return null
        val name = diff.file?.takeIf { it.isNotBlank() } ?: "diff"
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val factory = DiffContentFactory.getInstance()
        return SimpleDiffRequest(
            name,
            factory.create(project, sides.before, fileType),
            factory.create(project, sides.after, fileType),
            "Before",
            "After",
        )
    }

    private fun showDiffRequests(requests: List<DiffRequest>) {
        val manager = DiffManager.getInstance()
        if (requests.size == 1) {
            manager.showDiff(project, requests.first())
        } else {
            manager.showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
        }
    }

    private fun notifyNoDiff() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
            ?.createNotification("No diff to show for this change", NotificationType.INFORMATION)
            ?.notify(project)
    }

    private fun notifyDiffLoadFailed() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
            ?.createNotification("Could not load this diff from OpenCode", NotificationType.WARNING)
            ?.notify(project)
    }
}
