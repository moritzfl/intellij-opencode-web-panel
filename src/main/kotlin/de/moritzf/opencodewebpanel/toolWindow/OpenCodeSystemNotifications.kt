package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.awt.Frame
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows IDE notifications for OpenCode events (response ready, session error, permission or
 * question asked) and dismisses them once they become obsolete. Events come from the
 * Kotlin-side `/global/event` stream via a single application-wide subscription; each
 * instance registers itself as the routing target for its project directory.
 *
 * A notification is only shown while its project's panel is not in view (tool window
 * visible and IDE frame active); viewing the notified session afterwards dismisses it.
 */
internal class OpenCodeSystemNotifications(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val projectDirectory: () -> String?,
    parentDisposable: Disposable,
) {
    init {
        synchronized(targets) {
            targets.add(this)
        }
        ensureGlobalEventSubscription()
        // Interacting with the panel that shows a session dismisses that session's
        // notifications: the user has seen what the notification pointed at. The tool-window
        // and application activation listeners cover the ways the panel can come into view.
        project.messageBus.connect(parentDisposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    dismissNotificationsForViewedSession()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    dismissNotificationsForViewedSession()
                }
            },
        )
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

    // Deliberately independent of the browser's page state: with events read on the JVM,
    // notifications matter most exactly while the page is blank, loading, or crashed.
    private fun isProjectOpen(): Boolean {
        return !project.isDisposed && ProjectManager.getInstance().openProjects.contains(project)
    }

    /**
     * IDE-side successor of the notification bridge's in-page focus check: the panel counts
     * as in view while its tool window is visible and its IDE frame is the active window.
     * Must run on the EDT.
     */
    private fun isPanelInView(): Boolean {
        if (!toolWindow.isVisible) return false
        return WindowManager.getInstance().getFrame(project)?.isActive == true
    }

    private fun dismissNotificationsForViewedSession() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (!isPanelInView()) return@invokeLater
            val sessionID = OpenCodeServerProtocol.sessionIdFromUrl(browser.cefBrowser.url) ?: return@invokeLater
            dismissByKey("session:$sessionID")
        }
    }

    private fun openRoute(route: String) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!isProjectOpen()) return
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
            // The user is now looking at the notified session; its other notifications
            // (e.g. an earlier "response ready") are obsolete too.
            OpenCodeServerProtocol.sessionIdFromUrl(route)?.let { dismissByKey("session:$it") }
        }, true)
    }

    companion object {
        private const val RECENT_NOTIFICATION_MILLIS = 30_000L
        private val targets = mutableSetOf<OpenCodeSystemNotifications>()
        private val recentNotificationIds = linkedMapOf<String, Long>()

        // Live notifications by dismiss key ("request:<id>" / "session:<id>"). Static because
        // a dismissal applies to whichever panel's instance created the notification; entries
        // are removed via whenExpired, so the map cannot grow stale.
        private val activeNotificationsByKey = mutableMapOf<String, MutableList<Notification>>()

        private val globalEventSubscriptionInstalled = AtomicBoolean()
        private val eventProcessor = OpenCodeNotificationEventProcessor(::fetchSessionForNotification)

        /**
         * Installs the single application-wide consumer of the Kotlin event stream. Kept for
         * the application's lifetime: routing and the notifications setting are re-checked
         * per event, so an orphaned subscription cannot show stale notifications.
         */
        private fun ensureGlobalEventSubscription() {
            if (!globalEventSubscriptionInstalled.compareAndSet(false, true)) return
            ApplicationManager.getApplication().messageBus.connect().subscribe(
                OpenCodeGlobalEventListener.TOPIC,
                object : OpenCodeGlobalEventListener {
                    override fun eventReceived(event: OpenCodeGlobalEvent) {
                        handleGlobalEvent(event)
                    }
                },
            )
        }

        private fun handleGlobalEvent(event: OpenCodeGlobalEvent) {
            if (event.type !in OpenCodeNotificationEventProcessor.RELEVANT_EVENT_TYPES) return
            if (!OpenCodeSettingsState.getInstance().enableSystemNotifications) return
            // The processor may fetch the session via REST; keep that off the stream thread.
            ApplicationManager.getApplication().executeOnPooledThread {
                when (val outcome = eventProcessor.process(event)) {
                    is OpenCodeNotificationEventProcessor.Outcome.Dismiss -> dismissByKey(outcome.key)
                    is OpenCodeNotificationEventProcessor.Outcome.Notify -> show(outcome.payload)
                    null -> Unit
                }
            }
        }

        private fun fetchSessionForNotification(directory: String, sessionID: String): OpenCodeServerProtocol.SessionInfo? {
            val serverManager = SharedOpenCodeServerManager.getInstance()
            val serverUrl = serverManager.getServerUrl() ?: return null
            val password = serverManager.getServerPassword() ?: return null
            return OpenCodeServerProtocol.fetchSessionInfo(
                serverUrl,
                OpenCodeServerProtocol.buildBasicAuthHeader(password),
                directory,
                sessionID,
            )
        }

        private fun dismissByKey(key: String) {
            val toExpire = synchronized(activeNotificationsByKey) {
                activeNotificationsByKey.remove(key)?.toList()
            } ?: return
            ApplicationManager.getApplication().invokeLater {
                toExpire.forEach { it.expire() }
            }
        }

        fun show(openCodeNotification: OpenCodeServerProtocol.SystemNotificationPayload) {
            ApplicationManager.getApplication().invokeLater {
                if (!OpenCodeSettingsState.getInstance().enableSystemNotifications) return@invokeLater
                val target = targetFor(openCodeNotification.directory) ?: return@invokeLater
                // A stopped server makes the notification pointless: its "Show in OpenCode"
                // and permission actions would have nothing to talk to.
                if (target.serverManager.getServerUrl() == null) return@invokeLater
                if (!target.isProjectOpen()) return@invokeLater
                // The user is looking at the panel; the OpenCode UI itself shows the state.
                if (target.isPanelInView()) return@invokeLater
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
