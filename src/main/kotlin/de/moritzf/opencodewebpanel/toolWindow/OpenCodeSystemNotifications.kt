package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState

internal class OpenCodeSystemNotifications(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
) {
    fun show(payload: String?) {
        val openCodeNotification = OpenCodeServerProtocol.parseSystemNotificationPayload(payload) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !OpenCodeSettingsState.getInstance().enableSystemNotifications) return@invokeLater
            val serverUrl = serverManager.getServerUrl() ?: return@invokeLater
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return@invokeLater
            val group = NotificationGroupManager.getInstance()
                .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
                ?: return@invokeLater
            val title = notificationText(openCodeNotification.title, 200)
            val body = notificationText(openCodeNotification.body, 1000)
            val ideNotification = group.createNotification(title, body, NotificationType.INFORMATION)
            ideNotification.addAction(object : NotificationAction("Open in OpenCode") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    notification.expire()
                    if (project.isDisposed) return
                    toolWindow.activate({
                        if (!project.isDisposed) {
                            browser.component.requestFocusInWindow()
                            triggerSystemNotificationClick(openCodeNotification.id)
                        }
                    }, true)
                }
            })
            ideNotification.notify(project)
        }
    }

    private fun triggerSystemNotificationClick(notificationId: String) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
        val script = OpenCodeServerProtocol.buildSystemNotificationClickScript(notificationId)
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
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
}
