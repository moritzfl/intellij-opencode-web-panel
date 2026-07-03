package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.awt.Frame
import java.nio.file.Path

internal class OpenCodeSystemNotifications(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val projectDirectory: () -> String?,
) {
    init {
        synchronized(targets) {
            targets.add(this)
        }
    }

    /**
     * Entry point for everything the browser-side notification bridge sends: either a
     * notification to show or a dismissal signal for notifications that became obsolete
     * (request answered in the OpenCode UI, or the user interacted with the notified session).
     */
    fun handle(payload: String?) {
        val dismissal = OpenCodeServerProtocol.parseSystemNotificationDismissal(payload)
        if (dismissal != null) {
            dismiss(dismissal)
            return
        }
        show(payload)
    }

    private fun dismiss(dismissal: OpenCodeServerProtocol.SystemNotificationDismissal) {
        val toExpire = synchronized(activeNotificationsByKey) {
            activeNotificationsByKey.remove(dismissal.key)?.toList()
        } ?: return
        ApplicationManager.getApplication().invokeLater {
            toExpire.forEach { it.expire() }
        }
    }

    fun show(payload: String?) {
        val openCodeNotification = OpenCodeServerProtocol.parseSystemNotificationPayload(payload) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (!OpenCodeSettingsState.getInstance().enableSystemNotifications) return@invokeLater
            val target = targetFor(openCodeNotification.directory) ?: return@invokeLater
            val serverUrl = target.serverManager.getServerUrl() ?: return@invokeLater
            if (!target.canShowNotification(serverUrl)) return@invokeLater
            if (!markRecent(openCodeNotification.id)) return@invokeLater
            val group = NotificationGroupManager.getInstance()
                .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
                ?: return@invokeLater
            val title = notificationText(openCodeNotification.title, 200)
            val body = notificationText(openCodeNotification.body, 1000)
            val ideNotification = group.createNotification(title, body, NotificationType.INFORMATION)
            if (OpenCodeServerProtocol.isPermissionNotification(openCodeNotification) &&
                OpenCodeSettingsState.getInstance().enablePermissionNotificationActions
            ) {
                ideNotification.addAction(target.permissionReplyAction("Allow", openCodeNotification, OpenCodeServerProtocol.PermissionResponse.ONCE))
                ideNotification.addAction(target.permissionReplyAction("Always Allow", openCodeNotification, OpenCodeServerProtocol.PermissionResponse.ALWAYS))
                ideNotification.addAction(target.permissionReplyAction("Deny", openCodeNotification, OpenCodeServerProtocol.PermissionResponse.REJECT))
            }
            ideNotification.addAction(object : NotificationAction("Show in OpenCode") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    notification.expire()
                    target.openRoute(openCodeNotification.route)
                }
            })
            OpenCodeServerProtocol.notificationDismissKeys(openCodeNotification).forEach { key ->
                registerForAutoDismiss(key, ideNotification)
            }
            ideNotification.notify(target.project)
        }
    }

    private fun registerForAutoDismiss(key: String, notification: Notification) {
        synchronized(activeNotificationsByKey) {
            activeNotificationsByKey.getOrPut(key) { mutableListOf() }.add(notification)
        }
        notification.whenExpired {
            synchronized(activeNotificationsByKey) {
                val remaining = activeNotificationsByKey[key] ?: return@whenExpired
                remaining.remove(notification)
                if (remaining.isEmpty()) activeNotificationsByKey.remove(key)
            }
        }
    }

    private fun permissionReplyAction(
        title: String,
        openCodeNotification: OpenCodeServerProtocol.SystemNotificationPayload,
        response: OpenCodeServerProtocol.PermissionResponse,
    ): NotificationAction {
        return object : NotificationAction(title) {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                notification.expire()
                replyToPermission(openCodeNotification, response)
            }
        }
    }

    private fun replyToPermission(openCodeNotification: OpenCodeServerProtocol.SystemNotificationPayload, response: OpenCodeServerProtocol.PermissionResponse) {
        val serverUrl = serverManager.getServerUrl() ?: return
        val password = serverManager.getServerPassword() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val accepted = OpenCodeServerProtocol.replyToPermission(
                serverUrl,
                OpenCodeServerProtocol.buildBasicAuthHeader(password),
                openCodeNotification.directory,
                openCodeNotification.sessionID,
                openCodeNotification.requestID,
                response,
            )
            if (accepted) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
                    ?.createNotification(
                        "Could not send the permission response",
                        "The request may already have been answered in OpenCode.",
                        NotificationType.WARNING,
                    )
                    ?.notify(project)
            }
        }
    }

    fun dispose() {
        synchronized(targets) {
            targets.remove(this)
        }
    }

    private fun canShowNotification(serverUrl: String): Boolean {
        return !project.isDisposed &&
            ProjectManager.getInstance().openProjects.contains(project) &&
            OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)
    }

    private fun openRoute(route: String) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (project.isDisposed || !ProjectManager.getInstance().openProjects.contains(project)) return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        val frame = WindowManager.getInstance().getFrame(project)
        if (frame != null) {
            if (frame.extendedState and Frame.ICONIFIED != 0) {
                frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
            }
            frame.toFront()
            frame.requestFocus()
        }
        toolWindow.activate({
            if (project.isDisposed) return@activate
            if (!OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(serverUrl, browser.cefBrowser.url, route)) {
                browser.loadURL(OpenCodeServerProtocol.buildServerRootUrl(serverUrl) + route)
            }
            browser.component.requestFocusInWindow()
        }, true)
    }

    private fun notificationText(value: String, maxLength: Int): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    companion object {
        private const val RECENT_NOTIFICATION_MILLIS = 30_000L
        private val targets = mutableSetOf<OpenCodeSystemNotifications>()
        private val recentNotificationIds = linkedMapOf<String, Long>()

        // Live notifications by dismiss key ("request:<id>" / "session:<id>"). Static because a
        // dismissal can arrive through any panel's bridge, not just the one that created the
        // notification; entries are removed via whenExpired, so the map cannot grow stale.
        private val activeNotificationsByKey = mutableMapOf<String, MutableList<Notification>>()

        private fun targetFor(directory: String): OpenCodeSystemNotifications? {
            val normalizedDirectory = normalizeDirectory(directory) ?: return null
            val openProjects = ProjectManager.getInstance().openProjects.toSet()
            return synchronized(targets) {
                targets.firstOrNull { target ->
                    !target.project.isDisposed &&
                        openProjects.contains(target.project) &&
                        normalizeDirectory(target.projectDirectory()) == normalizedDirectory
                }
            }
        }

        private fun markRecent(id: String): Boolean {
            val now = System.currentTimeMillis()
            return synchronized(recentNotificationIds) {
                recentNotificationIds.entries.removeIf { now - it.value > RECENT_NOTIFICATION_MILLIS }
                if (recentNotificationIds.containsKey(id)) {
                    false
                } else {
                    recentNotificationIds[id] = now
                    true
                }
            }
        }

        private fun normalizeDirectory(directory: String?): String? {
            val text = directory?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { Path.of(text).toAbsolutePath().normalize().toString() }
                .getOrElse { text.trimEnd('/', '\\') }
        }
    }
}
