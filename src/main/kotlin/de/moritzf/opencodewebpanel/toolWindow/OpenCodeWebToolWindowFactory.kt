package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefAuthCallback
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.swing.JComponent
import javax.swing.TransferHandler

class OpenCodeWebToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowContent = OpenCodeWebToolWindowContent(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            val content = ContentFactory.getInstance().createContent(toolWindowContent.getContent(), null, false)
            content.setDisposer(toolWindowContent)
            toolWindow.contentManager.addContent(content)
            toolWindowContent.checkAndLoadContent()
        }
    }

    override fun shouldBeAvailable(project: Project) = true

    class OpenCodeWebToolWindowContent(private val toolWindow: ToolWindow) : Disposable {

        private companion object {
            private const val MAX_DROPPED_FILE_BYTES = 5L * 1024L * 1024L
            private const val MAX_DROPPED_FILES_TOTAL_BYTES = 10L * 1024L * 1024L
        }

        private val project = toolWindow.project
        private val browser = JBCefBrowser()
        private val lifecycleStatusLabel = JBLabel()
        private val lifecycleStatusPanel = BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            addToLeft(lifecycleStatusLabel)
        }
        private val contentPanel = BorderLayoutPanel().apply {
            isOpaque = false
            addToTop(lifecycleStatusPanel)
            addToCenter(browser.component)
        }
        private val openFileLinkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        private val openCodeReferenceQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        private val openCodeLocalStorageQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        private val systemNotificationQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        private val serverManager = SharedOpenCodeServerManager.getInstance()
        private val openProjectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
        private var openProjectScriptScheduled = false
        private var fileLinkScriptScheduled = false
        private var codeNavigationScriptScheduled = false
        private var compactLayoutScriptScheduled = false
        private var projectSwitchPromptSuppressionScriptScheduled = false
        private var systemNotificationBridgeScriptScheduled = false
        @Volatile
        private var disposed = false
        private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
            override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
                val password = serverManager.getServerPassword() ?: return false
                val requestUrl = request?.url ?: return false
                if (serverManager.getServerProcess()?.isAlive == true &&
                    OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverManager.getServerUrl(), requestUrl)
                ) {
                    request.setHeaderByName("Authorization", OpenCodeServerProtocol.buildBasicAuthHeader(password), true)
                }
                return false
            }
        }
        private val authHandler = object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                userGesture: Boolean,
                isRedirect: Boolean,
            ): Boolean {
                val requestUrl = request?.url ?: return false
                if (!OpenCodeServerProtocol.isOpenFileLinkRequest(requestUrl)) return false
                if (OpenCodeSettingsState.getInstance().openFileLinksInIde) {
                    openFileLinkInIde(OpenCodeServerProtocol.openFileLinkHref(requestUrl))
                }
                return true
            }

            override fun getResourceRequestHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                isNavigation: Boolean,
                isDownload: Boolean,
                requestInitiator: String?,
                disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler {
                return resourceRequestHandler
            }

            override fun getAuthCredentials(
                browser: CefBrowser?,
                originUrl: String?,
                isProxy: Boolean,
                host: String?,
                port: Int,
                realm: String?,
                scheme: String?,
                callback: CefAuthCallback?,
            ): Boolean {
                val password = serverManager.getServerPassword() ?: return false
                if (serverManager.getServerProcess()?.isAlive != true) return false
                if (!OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverManager.getServerUrl(), isProxy, host, port)) {
                    return false
                }
                callback?.Continue(OpenCodeServerProtocol.BASIC_AUTH_USERNAME, password)
                return callback != null
            }
        }
        private val loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                if (frame?.isMain == true) {
                    fileLinkScriptScheduled = false
                    codeNavigationScriptScheduled = false
                    compactLayoutScriptScheduled = false
                    projectSwitchPromptSuppressionScriptScheduled = false
                    systemNotificationBridgeScriptScheduled = false
                    restoreOpenCodeLocalStorage(frame.url)
                    installOpenCodeLocalStorageSync(frame.url)
                    injectCompactLayoutEarly()
                }
            }

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                if (httpStatusCode !in 200..399) return
                if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), frame.url)) return

                scheduleOpenProjectScript()
                installOpenCodeLocalStorageSync(frame.url)
                scheduleFileLinkScript()
                scheduleCodeNavigationScript()
                scheduleProjectSwitchPromptSuppressionScript()
                scheduleSystemNotificationBridgeScript()
            }
        }
        init {
            openFileLinkQuery.addHandler { href ->
                if (OpenCodeSettingsState.getInstance().openFileLinksInIde) {
                    openFileLinkInIde(href)
                }
                null
            }
            openCodeReferenceQuery.addHandler { ref ->
                if (OpenCodeSettingsState.getInstance().enableCodeNavigation) {
                    openCodeReferenceInIde(ref)
                }
                null
            }
            openCodeLocalStorageQuery.addHandler { snapshot ->
                syncOpenCodeLocalStorage(snapshot)
                null
            }
            systemNotificationQuery.addHandler { payload ->
                if (OpenCodeSettingsState.getInstance().enableSystemNotifications) {
                    showSystemNotification(payload)
                }
                null
            }
            browser.jbCefClient.addRequestHandler(authHandler, browser.cefBrowser)
            browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
            installFileDropTransferHandler()
            updateLifecycleIndicator(serverManager.getLifecycleState())
            ApplicationManager.getApplication().messageBus.connect(this).subscribe(
                OpenCodeServerLifecycleListener.TOPIC,
                object : OpenCodeServerLifecycleListener {
                    override fun stateChanged(state: OpenCodeServerLifecycleState) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!isContentDisposed()) updateLifecycleIndicator(state)
                        }
                    }
                },
            )
            ApplicationManager.getApplication().messageBus.connect(this).subscribe(
                OpenCodeSettingsListener.TOPIC,
                object : OpenCodeSettingsListener {
                    override fun uiZoomChanged(zoomPercent: Int) {
                        applyBrowserZoom(zoomPercent)
                        if (OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), browser.cefBrowser.url)) {
                            browser.cefBrowser.reload()
                        }
                    }

                    override fun fileLinkNavigationChanged(enabled: Boolean) {
                        applyFileLinkNavigation(enabled)
                    }

                    override fun codeNavigationChanged(enabled: Boolean) {
                        applyCodeNavigation(enabled)
                    }

                    override fun compactLayoutChanged(enabled: Boolean) {
                        applyCompactLayout(enabled)
                    }

                    override fun projectSwitchPromptSuppressionChanged(enabled: Boolean) {
                        applyProjectSwitchPromptSuppression(enabled)
                    }

                    override fun systemNotificationsChanged(enabled: Boolean) {
                        applySystemNotifications(enabled)
                    }
                },
            )
        }

        private fun installFileDropTransferHandler() {
            val handler = object : TransferHandler() {
                override fun canImport(support: TransferSupport): Boolean {
                    if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return false
                    if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), browser.cefBrowser.url)) return false
                    if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                    if (support.isDrop) support.dropAction = COPY
                    return true
                }

                override fun importData(support: TransferSupport): Boolean {
                    if (!canImport(support)) return false
                    val droppedFiles = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                    }.getOrNull().orEmpty()
                    return dispatchDroppedFiles(droppedFiles)
                }
            }
            installTransferHandler(browser.component, handler)
            (browser.getBrowserComponent() as? JComponent)?.let { installTransferHandler(it, handler) }
        }

        private fun installTransferHandler(component: JComponent, handler: TransferHandler) {
            component.transferHandler = handler
            component.components
                .filterIsInstance<JComponent>()
                .forEach { installTransferHandler(it, handler) }
        }

        private fun dispatchDroppedFiles(files: List<File>): Boolean {
            val selection = selectDroppedFiles(files)
            if (selection.rejectionMessages.isNotEmpty()) {
                showFileDropWarning(selection.rejectionMessages)
            }
            if (selection.acceptedFiles.isEmpty()) return selection.rejectionMessages.isNotEmpty()

            ApplicationManager.getApplication().executeOnPooledThread {
                if (isContentDisposed()) return@executeOnPooledThread
                val payloads = selection.acceptedFiles.mapNotNull { droppedFilePayload(it) }
                val script = OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
                    payloads,
                    enabled = OpenCodeSettingsState.getInstance().enableChatFileDrop,
                ) ?: return@executeOnPooledThread
                val rootUrl = serverManager.getServerUrl()?.let { OpenCodeServerProtocol.buildServerRootUrl(it) }
                    ?: return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater {
                    if (!isContentDisposed() && OpenCodeSettingsState.getInstance().enableChatFileDrop) {
                        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                    }
                }
            }
            return true
        }

        private fun selectDroppedFiles(files: List<File>): DroppedFileSelection {
            val acceptedFiles = mutableListOf<File>()
            val rejectionMessages = mutableListOf<String>()
            var totalBytes = 0L
            files.forEach { file ->
                val path = file.toPath()
                if (!Files.isRegularFile(path)) {
                    rejectionMessages += "${file.name} is not a regular file."
                    return@forEach
                }
                val size = runCatching { Files.size(path) }.getOrNull()
                if (size == null) {
                    rejectionMessages += "${file.name} could not be read."
                    return@forEach
                }
                if (size > MAX_DROPPED_FILE_BYTES) {
                    rejectionMessages += "${file.name} is larger than ${formatFileSize(MAX_DROPPED_FILE_BYTES)}."
                    return@forEach
                }
                if (totalBytes + size > MAX_DROPPED_FILES_TOTAL_BYTES) {
                    rejectionMessages += "${file.name} would exceed the total drop limit of ${formatFileSize(MAX_DROPPED_FILES_TOTAL_BYTES)}."
                    return@forEach
                }
                acceptedFiles += file
                totalBytes += size
            }
            return DroppedFileSelection(acceptedFiles, rejectionMessages)
        }

        private fun showFileDropWarning(rejectionMessages: List<String>) {
            val group = NotificationGroupManager.getInstance()
                .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
                ?: return
            val visibleMessages = rejectionMessages.take(3)
            val remaining = rejectionMessages.size - visibleMessages.size
            val suffix = if (remaining > 0) "; $remaining more skipped" else ""
            group.createNotification(
                "Some files were not added to OpenCode",
                notificationText(visibleMessages.joinToString("; ") + suffix, 1000),
                NotificationType.WARNING,
            ).notify(project)
        }

        private fun formatFileSize(bytes: Long): String {
            return "${bytes / (1024L * 1024L)} MiB"
        }

        private data class DroppedFileSelection(
            val acceptedFiles: List<File>,
            val rejectionMessages: List<String>,
        )

        private fun droppedFilePayload(file: File): OpenCodeServerProtocol.DroppedFilePayload? {
            return runCatching {
                val path = file.toPath()
                OpenCodeServerProtocol.DroppedFilePayload(
                    name = file.name,
                    mime = Files.probeContentType(path) ?: "application/octet-stream",
                    lastModified = file.lastModified(),
                    base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path)),
                )
            }.getOrNull()
        }

        fun getContent() = contentPanel

        private fun updateLifecycleIndicator(state: OpenCodeServerLifecycleState) {
            lifecycleStatusLabel.text = formatOpenCodeServerLifecycleStatusText(state)
            lifecycleStatusLabel.toolTipText = "OpenCode server is ${state.displayLabel.lowercase()}"
            lifecycleStatusPanel.isVisible = isOpenCodeServerLifecycleStatusVisible(state)
            contentPanel.revalidate()
            contentPanel.repaint()
        }

        fun checkAndLoadContent() {
            if (isContentDisposed()) return
            serverManager.ensureStarted(
                project,
                project.basePath,
                callbackActive = { !isContentDisposed() },
                onStarted = { loadProjectPage() },
                onFailed = { showErrorInBrowser() },
            )
        }

        private fun loadProjectPage() {
            if (isContentDisposed()) return
            val serverUrl = serverManager.getServerUrl() ?: return
            val url = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)

            thisLogger().info("Loading OpenCode project page")
            openProjectScriptScheduled = false
            fileLinkScriptScheduled = false
            compactLayoutScriptScheduled = false
            projectSwitchPromptSuppressionScriptScheduled = false
            systemNotificationBridgeScriptScheduled = false
            openProjectAlarm.cancelAllRequests()
            applyBrowserZoom()
            browser.loadURL(url)
            scheduleOpenProjectScript()
            scheduleFileLinkScript()
            scheduleProjectSwitchPromptSuppressionScript()
            scheduleSystemNotificationBridgeScript()
        }

        private fun applyBrowserZoom(zoomPercent: Int = OpenCodeSettingsState.getInstance().uiZoomPercent) {
            val zoomPercent = OpenCodeSettingsState.sanitizeUiZoomPercent(zoomPercent)
            browser.cefBrowser.setZoomLevel(OpenCodeServerProtocol.toCefZoomLevel(zoomPercent))
        }

        private fun scheduleOpenProjectScript() {
            if (openProjectScriptScheduled) return

            val serverUrl = serverManager.getServerUrl() ?: return
            val settings = OpenCodeSettingsState.getInstance()
            val script = OpenCodeServerProtocol.buildOpenProjectScript(
                project.basePath,
                serverUrl,
                settings.openMostRecentConversationOnStartup,
            ) ?: return
            val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
            openProjectScriptScheduled = true

            listOf(250, 750, 1500, 3000, 5000, 8000, 12000).forEach { delayMillis ->
                openProjectAlarm.addRequest(
                    {
                        if (!project.isDisposed) {
                            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                        }
                    },
                    delayMillis,
                )
            }
        }

        private fun scheduleFileLinkScript() {
            if (fileLinkScriptScheduled) return
            if (!OpenCodeSettingsState.getInstance().openFileLinksInIde) return

            val serverUrl = serverManager.getServerUrl() ?: return
            val script = OpenCodeServerProtocol.buildFileLinkHandlerScript(
                project.basePath,
                enabled = true,
                openFileCallback = openFileLinkQuery.inject("rawHref"),
            ) ?: return
            val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
            fileLinkScriptScheduled = true

            listOf(250, 750, 1500, 3000, 5000, 8000, 12000).forEach { delayMillis ->
                openProjectAlarm.addRequest(
                    {
                        if (!project.isDisposed && OpenCodeSettingsState.getInstance().openFileLinksInIde) {
                            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                        }
                    },
                    delayMillis,
                )
            }
        }

        private fun openFileLinkInIde(href: String?) {
            val routeBasePath = OpenCodeServerProtocol.routeDirectoryFromUrl(browser.cefBrowser.url)
            val target = OpenCodeServerProtocol.resolveFileLink(href, project.basePath, routeBasePath) ?: return
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path) ?: return
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                OpenFileDescriptor(project, virtualFile, target.line ?: -1, target.column ?: -1).navigate(true)
            }
        }

        private fun restoreOpenCodeLocalStorage(frameUrl: String?) {
            val serverUrl = serverManager.getServerUrl() ?: return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) return
            val snapshot = OpenCodeSettingsState.getInstance().openCodeLocalStorageSnapshot
            val script = OpenCodeServerProtocol.buildRestoreOpenCodeLocalStorageScript(snapshot) ?: return
            browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        }

        private fun installOpenCodeLocalStorageSync(frameUrl: String?) {
            val serverUrl = serverManager.getServerUrl() ?: return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) return
            val script = OpenCodeServerProtocol.buildSyncOpenCodeLocalStorageScript(openCodeLocalStorageQuery.inject("payload")) ?: return
            browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
        }

        private fun syncOpenCodeLocalStorage(snapshot: String?) {
            val text = snapshot?.trim() ?: return
            val sanitized = OpenCodeSettingsState.sanitizeOpenCodeLocalStorageSnapshot(text)
            if (sanitized == "{}" && text != "{}") return
            val settings = OpenCodeSettingsState.getInstance()
            if (settings.openCodeLocalStorageSnapshot != sanitized) {
                settings.openCodeLocalStorageSnapshot = sanitized
            }
        }

        private fun showSystemNotification(payload: String?) {
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

        private fun openCodeReferenceInIde(ref: String?) {
            val text = ref?.trim()?.ifBlank { null } ?: return
            val parsed = OpenCodeServerProtocol.parseCodeReference(text) ?: return
            val directVirtualFile = resolveCodeReferencePath(parsed)
            if (directVirtualFile != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    OpenFileDescriptor(project, directVirtualFile, parsed.line ?: -1, -1).navigate(true)
                }
                return
            }
            com.intellij.openapi.application.ReadAction.nonBlocking<com.intellij.openapi.vfs.VirtualFile?> {
                val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
                resolveCodeReferenceClass(parsed, scope)
                    ?: resolveCodeReferenceFileName(parsed, scope)
            }.finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { virtualFile ->
                if (project.isDisposed || virtualFile == null) return@finishOnUiThread
                OpenFileDescriptor(project, virtualFile, parsed.line ?: -1, -1).navigate(true)
            }.coalesceBy(this@OpenCodeWebToolWindowContent)
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
        }

        private fun resolveCodeReferencePath(parsed: OpenCodeServerProtocol.ParsedCodeReference): com.intellij.openapi.vfs.VirtualFile? {
            if (!parsed.hasPath) return null
            val projectBasePath = project.basePath?.takeIf { it.isNotBlank() }
            val candidates = buildList {
                runCatching { Path.of(parsed.path) }.getOrNull()?.let { path ->
                    if (path.isAbsolute) add(path)
                }
                if (projectBasePath != null) {
                    runCatching { Path.of(projectBasePath).resolve(parsed.path).normalize() }.getOrNull()?.let(::add)
                }
            }
            val path = candidates.firstOrNull { Files.isRegularFile(it) } ?: return null
            return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        }

        private fun resolveCodeReferenceClass(
            parsed: OpenCodeServerProtocol.ParsedCodeReference,
            scope: com.intellij.psi.search.GlobalSearchScope,
        ): com.intellij.openapi.vfs.VirtualFile? {
            if (parsed.extension != null || parsed.hasPath) return null
            val cacheClass = runCatching { Class.forName("com.intellij.psi.search.PsiShortNamesCache") }.getOrNull() ?: return null
            val cache = runCatching { cacheClass.getMethod("getInstance", Project::class.java).invoke(null, project) }.getOrNull()
                ?: return null
            val classes = runCatching {
                cacheClass.getMethod("getClassesByName", String::class.java, com.intellij.psi.search.GlobalSearchScope::class.java)
                    .invoke(cache, parsed.fileName, scope) as? Array<*>
            }.getOrNull() ?: return null
            return classes.asSequence()
                .filter { psiClass -> parsed.qualifiedName == null || psiClass.qualifiedName() == parsed.qualifiedName }
                .mapNotNull { psiClass -> psiClass.containingVirtualFile() }
                .firstOrNull()
        }

        private fun resolveCodeReferenceFileName(
            parsed: OpenCodeServerProtocol.ParsedCodeReference,
            scope: com.intellij.psi.search.GlobalSearchScope,
        ): com.intellij.openapi.vfs.VirtualFile? {
            val fileNames = if (parsed.extension == null && !parsed.hasPath) {
                listOf(
                    parsed.fileName,
                    "${parsed.fileName}.kt",
                    "${parsed.fileName}.kts",
                    "${parsed.fileName}.java",
                    "${parsed.fileName}.ts",
                    "${parsed.fileName}.tsx",
                    "${parsed.fileName}.js",
                    "${parsed.fileName}.jsx",
                )
            } else {
                listOf(parsed.fileName)
            }
            return fileNames.asSequence()
                .flatMap { fileName -> com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(fileName, scope).asSequence() }
                .firstOrNull()
        }

        private fun Any?.qualifiedName(): String? {
            return this?.javaClass?.methods
                ?.firstOrNull { it.name == "getQualifiedName" && it.parameterCount == 0 }
                ?.let { method -> runCatching { method.invoke(this) as? String }.getOrNull() }
        }

        private fun Any?.containingVirtualFile(): com.intellij.openapi.vfs.VirtualFile? {
            val containingFile = this?.javaClass?.methods
                ?.firstOrNull { it.name == "getContainingFile" && it.parameterCount == 0 }
                ?.let { method -> runCatching { method.invoke(this) }.getOrNull() }
                ?: return null
            return containingFile.javaClass.methods
                .firstOrNull { it.name == "getVirtualFile" && it.parameterCount == 0 }
                ?.let { method -> runCatching { method.invoke(containingFile) as? com.intellij.openapi.vfs.VirtualFile }.getOrNull() }
        }

        private fun scheduleCodeNavigationScript() {
            if (codeNavigationScriptScheduled) return
            if (!OpenCodeSettingsState.getInstance().enableCodeNavigation) return

            val serverUrl = serverManager.getServerUrl() ?: return
            val script = OpenCodeServerProtocol.buildCodeNavigationScript(
                enabled = true,
                openCodeCallback = openCodeReferenceQuery.inject("ref"),
            ) ?: return
            val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
            codeNavigationScriptScheduled = true

            listOf(250, 750, 1500, 3000, 5000, 8000, 12000).forEach { delayMillis ->
                openProjectAlarm.addRequest(
                    {
                        if (!project.isDisposed && OpenCodeSettingsState.getInstance().enableCodeNavigation) {
                            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                        }
                    },
                    delayMillis,
                )
            }
        }

        private fun scheduleProjectSwitchPromptSuppressionScript() {
            if (projectSwitchPromptSuppressionScriptScheduled) return
            if (!OpenCodeSettingsState.getInstance().suppressProjectSwitchPrompts) return

            val serverUrl = serverManager.getServerUrl() ?: return
            val script = OpenCodeServerProtocol.buildProjectSwitchPromptSuppressionScript(enabled = true) ?: return
            val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
            projectSwitchPromptSuppressionScriptScheduled = true

            listOf(250, 750, 1500, 3000, 5000, 8000, 12000).forEach { delayMillis ->
                openProjectAlarm.addRequest(
                    {
                        if (!project.isDisposed && OpenCodeSettingsState.getInstance().suppressProjectSwitchPrompts) {
                            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                        }
                    },
                    delayMillis,
                )
            }
        }

        private fun scheduleSystemNotificationBridgeScript() {
            if (systemNotificationBridgeScriptScheduled) return
            if (!OpenCodeSettingsState.getInstance().enableSystemNotifications) return

            val serverUrl = serverManager.getServerUrl() ?: return
            val script = OpenCodeServerProtocol.buildSystemNotificationBridgeScript(
                enabled = true,
                notificationCallback = systemNotificationQuery.inject("payload"),
            ) ?: return
            val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
            systemNotificationBridgeScriptScheduled = true

            listOf(250, 750, 1500, 3000, 5000, 8000, 12000).forEach { delayMillis ->
                openProjectAlarm.addRequest(
                    {
                        if (!project.isDisposed &&
                            OpenCodeSettingsState.getInstance().enableSystemNotifications &&
                            OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)
                        ) {
                            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                        }
                    },
                    delayMillis,
                )
            }
        }

        private fun applyCodeNavigation(enabled: Boolean) {
            val serverUrl = serverManager.getServerUrl() ?: return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
            codeNavigationScriptScheduled = false
            if (!enabled) {
                browser.cefBrowser.reload()
                return
            }
            val script = OpenCodeServerProtocol.buildCodeNavigationScript(
                enabled = true,
                openCodeCallback = openCodeReferenceQuery.inject("ref"),
            ) ?: return
            browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
            codeNavigationScriptScheduled = true
        }

        private fun applyProjectSwitchPromptSuppression(enabled: Boolean) {
            val serverUrl = serverManager.getServerUrl() ?: return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
            projectSwitchPromptSuppressionScriptScheduled = false
            if (!enabled) {
                browser.cefBrowser.reload()
                return
            }
            val script = OpenCodeServerProtocol.buildProjectSwitchPromptSuppressionScript(enabled = true) ?: return
            browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
            projectSwitchPromptSuppressionScriptScheduled = true
        }

        private fun applySystemNotifications(enabled: Boolean) {
            val serverUrl = serverManager.getServerUrl() ?: return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
            systemNotificationBridgeScriptScheduled = false
            if (!enabled) {
                browser.cefBrowser.reload()
                return
            }
            val script = OpenCodeServerProtocol.buildSystemNotificationBridgeScript(
                enabled = true,
                notificationCallback = systemNotificationQuery.inject("payload"),
            ) ?: return
            browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
            systemNotificationBridgeScriptScheduled = true
        }

        private fun applyFileLinkNavigation(enabled: Boolean) {
            val serverUrl = serverManager.getServerUrl() ?: return
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) return
            fileLinkScriptScheduled = false
            if (!enabled) {
                browser.cefBrowser.reload()
                return
            }
            val script = OpenCodeServerProtocol.buildFileLinkHandlerScript(
                project.basePath,
                enabled = true,
                openFileCallback = openFileLinkQuery.inject("rawHref"),
            ) ?: return
            browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
            fileLinkScriptScheduled = true
        }

        private fun injectCompactLayoutEarly() {
            if (compactLayoutScriptScheduled) return
            if (!OpenCodeSettingsState.getInstance().forceCompactLayout) return

            val serverUrl = serverManager.getServerUrl() ?: return
            val script = OpenCodeServerProtocol.buildCompactLayoutScript(enabled = true) ?: return
            val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
            compactLayoutScriptScheduled = true

            // Inject immediately (onLoadStart — before SPA bundle executes)
            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
            // Re-inject on delays in case the early injection ran before JS context was ready
            listOf(50, 250, 750, 1500, 3000).forEach { delayMillis ->
                openProjectAlarm.addRequest(
                    {
                        if (!project.isDisposed && OpenCodeSettingsState.getInstance().forceCompactLayout) {
                            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                        }
                    },
                    delayMillis,
                )
            }
        }

        private fun applyCompactLayout(enabled: Boolean) {
            // Toggling requires a page reload — early injection on next load start
            compactLayoutScriptScheduled = false
            val serverUrl = serverManager.getServerUrl() ?: return
            if (OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)) {
                browser.cefBrowser.reload()
            }
        }

        private fun showErrorInBrowser() {
            if (isContentDisposed()) return
            browser.loadHTML(OpenCodeServerProtocol.buildStartupErrorPageHtml(OpenCodeSettingsState.getInstance().executablePath()))
        }

        private fun isContentDisposed(): Boolean {
            return disposed || project.isDisposed
        }

        override fun dispose() {
            disposed = true
            openProjectAlarm.cancelAllRequests()
            Disposer.dispose(browser)
        }
    }
}
