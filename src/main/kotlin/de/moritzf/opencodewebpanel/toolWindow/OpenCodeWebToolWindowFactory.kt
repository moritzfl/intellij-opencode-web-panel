package de.moritzf.opencodewebpanel.toolWindow

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
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
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

    class OpenCodeWebToolWindowContent(toolWindow: ToolWindow) : Disposable {

        private val project = toolWindow.project
        private val browser = JBCefBrowser()
        private val openFileLinkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        private val openCodeReferenceQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        private val serverManager = SharedOpenCodeServerManager.getInstance()
        private val openProjectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
        private var openProjectScriptScheduled = false
        private var fileLinkScriptScheduled = false
        private var codeNavigationScriptScheduled = false
        private var compactLayoutScriptScheduled = false
        private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
            override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
                val password = serverManager.getServerPassword() ?: return false
                val requestUrl = request?.url ?: return false
                if (OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverManager.getServerUrl(), requestUrl)) {
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
                    injectCompactLayoutEarly()
                }
            }

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                if (httpStatusCode !in 200..399) return
                if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), frame.url)) return

                scheduleOpenProjectScript()
                scheduleFileLinkScript()
                scheduleCodeNavigationScript()
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
            browser.jbCefClient.addRequestHandler(authHandler, browser.cefBrowser)
            browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
            installFileDropTransferHandler()
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
            val regularFiles = files.filter { it.isFile }
            if (regularFiles.isEmpty()) return false

            ApplicationManager.getApplication().executeOnPooledThread {
                val payloads = regularFiles.mapNotNull { droppedFilePayload(it) }
                val script = OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
                    payloads,
                    enabled = OpenCodeSettingsState.getInstance().enableChatFileDrop,
                ) ?: return@executeOnPooledThread
                val rootUrl = serverManager.getServerUrl()?.let { OpenCodeServerProtocol.buildServerRootUrl(it) }
                    ?: return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && OpenCodeSettingsState.getInstance().enableChatFileDrop) {
                        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                    }
                }
            }
            return true
        }

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

        fun getContent() = browser.component

        fun checkAndLoadContent() {
            serverManager.ensureStarted(
                project,
                project.basePath,
                onStarted = { loadProjectPage() },
                onFailed = { showErrorInBrowser() },
            )
        }

        private fun loadProjectPage() {
            val serverUrl = serverManager.getServerUrl() ?: return
            val url = OpenCodeServerProtocol.buildAuthenticatedServerRootUrl(serverUrl, serverManager.getServerPassword())

            thisLogger().info("Loading OpenCode project page")
            openProjectScriptScheduled = false
            fileLinkScriptScheduled = false
            compactLayoutScriptScheduled = false
            openProjectAlarm.cancelAllRequests()
            applyBrowserZoom()
            browser.loadURL(url)
            scheduleOpenProjectScript()
            scheduleFileLinkScript()
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
            val html = """
                <html>
                <body style="background-color: #2B2B2B; color: #A9B7C6; font-family: sans-serif; padding: 20px;">
                    <h2>Failed to start OpenCode server</h2>
                    <p>Please make sure 'opencode' is installed and available in your PATH.</p>
                    <p>Run the following command to start the server manually:</p>
                    <pre style="background: #3C3F41; padding: 10px; border-radius: 4px;">opencode serve --hostname 127.0.0.1 --port 0 --print-logs</pre>
                </body>
                </html>
            """.trimIndent()
            browser.loadHTML(html)
        }

        override fun dispose() {
            openProjectAlarm.cancelAllRequests()
            Disposer.dispose(browser)
        }
    }
}
