package de.moritzf.opencodewebpanel.features

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Frame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Collapses whitespace, truncates, and HTML-escapes a value for use in notification HTML. */
internal fun notificationText(value: String, maxLength: Int = 1000): String {
    return StringUtil.escapeXmlEntities(value.replace(Regex("\\s+"), " ").trim().take(maxLength))
}

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
    private val navigate: (String) -> Unit,
    parentDisposable: Disposable,
) {
    init {
        synchronized(targets) {
            targets.add(this)
        }
        ensureGlobalEventSubscription()
        reconcilePendingRequests()
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
        identity: OpenCodeNotificationServerIdentity,
    ): NotificationAction {
        return object : NotificationAction(title) {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                notification.expire()
                replyToPermission(openCodeNotification, response, identity)
            }
        }
    }

    private fun replyToPermission(
        openCodeNotification: OpenCodeServerProtocol.SystemNotificationPayload,
        response: OpenCodeServerProtocol.PermissionResponse,
        identity: OpenCodeNotificationServerIdentity,
    ) {
        if (!isCurrentServer(identity)) return
        val serverUrl = serverManager.getServerUrl() ?: return
        val password = serverManager.getServerPassword() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!isCurrentServer(identity)) return@executeOnPooledThread
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

    private fun isCurrentServer(identity: OpenCodeNotificationServerIdentity): Boolean {
        return serverManager.getLifecycleState() == OpenCodeServerLifecycleState.RUNNING &&
            serverManager.getServerGeneration() == identity.generation &&
            serverManager.getServerUrl() == identity.serverUrl &&
            notificationEpoch.get() == identity.notificationEpoch
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

    private fun openSession(sessionID: String) {
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
        // Always use the 1.18 server session URL. Legacy directory routes force a reload/redirect
        // even when the panel is already on the same session under /server/.../session/<id>.
        val targetUrl = OpenCodeServerProtocol.buildServerSessionUrl(
            serverUrl,
            sessionID.takeIf(OpenCodeServerProtocol::isSessionId),
        )
        toolWindow.activate({
            if (project.isDisposed) return@activate
            if (!OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(serverUrl, browser.cefBrowser.url, targetUrl)) {
                navigate(targetUrl)
            }
            browser.component.requestFocusInWindow()
            // The user is now looking at the notified session; its other notifications
            // (e.g. an earlier "response ready") are obsolete too.
            sessionID.takeIf(OpenCodeServerProtocol::isSessionId)?.let { dismissByKey("session:$it") }
        }, true)
    }

    companion object {
        private const val RECENT_NOTIFICATION_MILLIS = 30_000L
        private val targets = mutableSetOf<OpenCodeSystemNotifications>()
        private val recentNotificationIds = linkedMapOf<String, Long>()

        // Static because a dismissal applies to whichever panel created the notification.
        private val activeNotifications = OpenCodeActiveNotificationRegistry<Notification>()

        private val globalEventSubscriptionInstalled = AtomicBoolean()
        private val notificationEpoch = AtomicLong()
        private val eventProcessor = OpenCodeNotificationEventProcessor(::fetchSessionForNotification)
        private val notificationInvalidator = OpenCodeNotificationInvalidator(
            registry = activeNotifications,
            resetReducedState = {
                notificationEpoch.incrementAndGet()
                eventProcessor.reset()
                synchronized(recentNotificationIds) { recentNotificationIds.clear() }
            },
            expire = { notifications ->
                ApplicationManager.getApplication().invokeLater {
                    notifications.forEach(Notification::expire)
                }
            },
        )

        // Single-threaded so events are processed in stream order: a slow session lookup for
        // "permission.asked" must not finish after the matching "permission.replied" dismissal,
        // which would re-create an already-answered actionable notification.
        private val eventExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor(
            "OpenCode Notification Events",
            1,
        )
        private val eventDispatcher = OpenCodeNotificationEventDispatcher(
            enabled = { OpenCodeSettingsState.getInstance().enableSystemNotifications },
            serverIdentity = ::currentServerIdentity,
            process = eventProcessor::process,
            dispatch = ::dispatchOutcome,
            executeAsync = { task -> eventExecutor.execute { task() } },
        )
        private val pendingReconciler = OpenCodePendingNotificationReconciler(
            enabled = { OpenCodeSettingsState.getInstance().enableSystemNotifications },
            serverIdentity = ::currentServerIdentity,
            directories = ::targetDirectories,
            load = ::loadPendingNotificationRequests,
            activeRequestKeys = { activeNotifications.keys("request:") },
            process = eventProcessor::process,
            dispatch = ::dispatchOutcome,
            executeAsync = { task -> eventExecutor.execute { task() } },
        )

        /**
         * Installs the single application-wide consumer of the Kotlin event stream. Bound to
         * the [SharedOpenCodeServerManager] service so the connection is released on plugin
         * unload; routing and the notifications setting are re-checked per event, so an
         * orphaned subscription cannot show stale notifications.
         */
        private fun ensureGlobalEventSubscription() {
            if (!globalEventSubscriptionInstalled.compareAndSet(false, true)) return
            val connection = ApplicationManager.getApplication().messageBus
                .connect(SharedOpenCodeServerManager.getInstance())
            connection.subscribe(
                OpenCodeGlobalEventListener.TOPIC,
                object : OpenCodeGlobalEventListener {
                    override fun connected() {
                        reconcilePendingRequests()
                    }

                    override fun eventReceived(event: OpenCodeGlobalEvent) {
                        handleGlobalEvent(event)
                    }
                },
            )
            connection.subscribe(
                OpenCodeServerLifecycleListener.TOPIC,
                object : OpenCodeServerLifecycleListener {
                    override fun stateChanged(state: OpenCodeServerLifecycleState) {
                        if (state != OpenCodeServerLifecycleState.RUNNING) notificationInvalidator.invalidate()
                    }
                },
            )
            connection.subscribe(
                OpenCodeSettingsListener.TOPIC,
                object : OpenCodeSettingsListener {
                    override fun systemNotificationsChanged(enabled: Boolean) {
                        if (enabled) {
                            reconcilePendingRequests()
                        } else {
                            notificationInvalidator.invalidate()
                        }
                    }
                },
            )
        }

        private fun handleGlobalEvent(event: OpenCodeGlobalEvent) {
            eventDispatcher.eventReceived(event)
        }

        private fun reconcilePendingRequests() {
            pendingReconciler.reconcile()
        }

        private fun dispatchOutcome(
            outcome: OpenCodeNotificationEventProcessor.Outcome,
            identity: OpenCodeNotificationServerIdentity,
        ) {
            when (outcome) {
                is OpenCodeNotificationEventProcessor.Outcome.Dismiss -> dismissByKey(outcome.key)
                is OpenCodeNotificationEventProcessor.Outcome.Notify -> show(outcome.payload, identity)
            }
        }

        private fun currentServerIdentity(): OpenCodeNotificationServerIdentity? {
            val serverManager = SharedOpenCodeServerManager.getInstance()
            if (serverManager.getLifecycleState() != OpenCodeServerLifecycleState.RUNNING) return null
            val serverUrl = serverManager.getServerUrl() ?: return null
            val generation = serverManager.getServerGeneration().takeIf { it > 0L } ?: return null
            return OpenCodeNotificationServerIdentity(generation, serverUrl, notificationEpoch.get())
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

        private fun loadPendingNotificationRequests(
            identity: OpenCodeNotificationServerIdentity,
            directory: String,
        ): OpenCodePendingNotificationLoad {
            if (currentServerIdentity() != identity) return OpenCodePendingNotificationLoad(emptyList(), false)
            val serverManager = SharedOpenCodeServerManager.getInstance()
            val password = serverManager.getServerPassword()
                ?: return OpenCodePendingNotificationLoad(emptyList(), false)
            val authHeader = OpenCodeServerProtocol.buildBasicAuthHeader(password)
            val permissions = OpenCodeServerProtocol.fetchPendingRequestsResult(
                identity.serverUrl,
                authHeader,
                OpenCodeServerProtocol.PERMISSION_LIST_PATH,
                directory,
            )
            if (currentServerIdentity() != identity) return OpenCodePendingNotificationLoad(emptyList(), false)
            val questions = OpenCodeServerProtocol.fetchPendingRequestsResult(
                identity.serverUrl,
                authHeader,
                OpenCodeServerProtocol.QUESTION_LIST_PATH,
                directory,
            )
            val permissionValues = (permissions as? OpenCodeProtocolResult.Success)?.value
            val questionValues = (questions as? OpenCodeProtocolResult.Success)?.value
            listOfNotNull(
                permissions as? OpenCodeProtocolResult.Failure,
                questions as? OpenCodeProtocolResult.Failure,
            ).forEach { failure ->
                val status = failure.statusCode?.let { ", HTTP $it" }.orEmpty()
                thisLogger().warn("Failed to reconcile pending OpenCode requests (${failure.kind}$status)")
            }
            val requests = permissionValues.orEmpty().map { request ->
                OpenCodePendingNotificationRequest(directory, "permission.asked", request.id, request.sessionID)
            } + questionValues.orEmpty().map { request ->
                OpenCodePendingNotificationRequest(directory, "question.asked", request.id, request.sessionID)
            }
            return OpenCodePendingNotificationLoad(
                requests = requests,
                authoritative = permissionValues != null && questionValues != null,
            )
        }

        private fun dismissByKey(key: String) {
            val toExpire = activeNotifications.removeByKey(key)
            if (toExpire.isEmpty()) return
            ApplicationManager.getApplication().invokeLater {
                toExpire.forEach { it.expire() }
            }
        }

        private fun show(
            openCodeNotification: OpenCodeServerProtocol.SystemNotificationPayload,
            identity: OpenCodeNotificationServerIdentity,
        ) {
            ApplicationManager.getApplication().invokeLater {
                if (!OpenCodeSettingsState.getInstance().enableSystemNotifications) return@invokeLater
                val target = targetFor(openCodeNotification.directory) ?: return@invokeLater
                if (!target.isCurrentServer(identity)) return@invokeLater
                if (!target.isProjectOpen()) return@invokeLater
                // The user is looking at the panel; the OpenCode UI itself shows the state.
                if (target.isPanelInView()) return@invokeLater
                val requestKey = openCodeNotification.requestID
                    .takeIf { OpenCodeServerProtocol.isOpenCodeRecordId(it) }
                    ?.let { "request:$it" }
                if (requestKey != null && activeNotifications.containsKey(requestKey)) return@invokeLater
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
                    ideNotification.addAction(target.permissionReplyAction("Allow", openCodeNotification, OpenCodeServerProtocol.PermissionResponse.ONCE, identity))
                    ideNotification.addAction(target.permissionReplyAction("Always Allow", openCodeNotification, OpenCodeServerProtocol.PermissionResponse.ALWAYS, identity))
                    ideNotification.addAction(target.permissionReplyAction("Deny", openCodeNotification, OpenCodeServerProtocol.PermissionResponse.REJECT, identity))
                }
                ideNotification.addAction(object : NotificationAction("Show in OpenCode") {
                    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                        notification.expire()
                        target.openSession(openCodeNotification.sessionID)
                    }
                })
                OpenCodeServerProtocol.notificationDismissKeys(openCodeNotification).forEach { key ->
                    registerForAutoDismiss(key, ideNotification)
                }
                if (!target.isCurrentServer(identity)) {
                    ideNotification.expire()
                    return@invokeLater
                }
                ideNotification.notify(target.project)
            }
        }

        private fun registerForAutoDismiss(key: String, notification: Notification) {
            activeNotifications.register(key, notification)
            notification.whenExpired {
                activeNotifications.remove(key, notification)
            }
        }

        private fun targetFor(directory: String): OpenCodeSystemNotifications? {
            if (directory.isBlank()) return null
            val openProjects = ProjectManager.getInstance().openProjects.toSet()
            return synchronized(targets) {
                targets.firstOrNull { target ->
                    !target.project.isDisposed &&
                        openProjects.contains(target.project) &&
                        OpenCodeServerProtocol.isSameFilesystemPath(target.projectDirectory(), directory)
                }
            }
        }

        private fun targetDirectories(): List<String> {
            return synchronized(targets) {
                targets.asSequence()
                    .filter { !it.project.isDisposed }
                    .mapNotNull { it.projectDirectory()?.takeIf(String::isNotBlank) }
                    .distinctBy { OpenCodeServerProtocol.filesystemPathKey(it) ?: it }
                    .toList()
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
    }
}
