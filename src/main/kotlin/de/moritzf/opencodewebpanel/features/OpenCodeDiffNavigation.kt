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
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeUnifiedDiff
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager

/**
 * Opens the IDE's native diff viewer for a diff target the user Alt+Clicked in the OpenCode page
 * (see `OpenCodeBrowserSnippets.buildDiffNavigationScript`). The page sends `messageID\nfilePath`
 * (both optional); the session id and directory are derived here. Diffs are fetched over REST and
 * rendered as a read-only patch preview reconstructed from each file's unified `patch` string.
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
        val serverUrl = serverManager.getServerUrl() ?: return
        val password = serverManager.getServerPassword() ?: return
        val sessionID = OpenCodeServerProtocol.sessionIdFromUrl(browser.cefBrowser.url) ?: return
        val directory = projectDirectory()?.takeIf { it.isNotBlank() } ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = OpenCodeServerProtocol.fetchSessionDiffResult(
                serverUrl,
                OpenCodeServerProtocol.buildBasicAuthHeader(password),
                directory,
                sessionID,
                messageID,
            )
            if (result is OpenCodeProtocolResult.Failure) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) notifyDiffLoadFailed()
                }
                return@executeOnPooledThread
            }
            val diffs = (result as OpenCodeProtocolResult.Success).value
            val requests = selectDiffs(diffs, filePath).mapNotNull(::buildDiffRequest)
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

    /** Keeps only the requested file's diff; falls back to all files when it cannot be matched. */
    private fun selectDiffs(
        diffs: List<OpenCodeServerProtocol.SnapshotFileDiff>,
        filePath: String?,
    ): List<OpenCodeServerProtocol.SnapshotFileDiff> {
        if (filePath == null) return diffs
        return diffs.filter { matchesFile(it.file, filePath) }.ifEmpty { diffs }
    }

    /** Match relative paths from the DOM against REST diff paths, tolerating separators, a leading
     *  root/slash, and case (Windows) — both sides come from the same OpenCode session. */
    private fun matchesFile(diffFile: String?, requested: String): Boolean {
        val a = diffFile?.replace('\\', '/')?.trim(' ', '/') ?: return false
        val b = requested.replace('\\', '/').trim(' ', '/')
        if (a.isEmpty() || b.isEmpty()) return false
        return a.equals(b, ignoreCase = true) ||
            a.endsWith("/$b", ignoreCase = true) || b.endsWith("/$a", ignoreCase = true) ||
            a.substringAfterLast('/').equals(b.substringAfterLast('/'), ignoreCase = true)
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
