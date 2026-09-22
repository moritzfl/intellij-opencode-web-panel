package de.moritzf.opencodewebpanel.jcef

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.project.impl.ProjectImpl
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.platform.util.coroutines.childScope
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.replaceService
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.browser.OpenCodeDocumentStartInjector
import de.moritzf.opencodewebpanel.browser.OpenCodeJsQuery
import de.moritzf.opencodewebpanel.browser.createOpenCodeBrowserBeforeReplacement
import de.moritzf.opencodewebpanel.features.OpenCodeChatInputService
import de.moritzf.opencodewebpanel.server.OpenCodeProcessTerminator
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeWireProtocol
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxProcessRunner
import de.moritzf.opencodewebpanel.toolWindow.OpenCodePanel
import de.moritzf.opencodewebpanel.toolWindow.OpenCodePanelController
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Opt-in, against two isolated real `opencode serve` processes, never the synthetic HTML fixture. */
class OpenCodeJcefLiveRuntimeSwitchTest {
    companion object {
        @ClassRule @JvmField val application = ApplicationRule()
    }
    @get:Rule val disposable = DisposableRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var origins: List<String>
    private lateinit var workspace: String
    private lateinit var nativeSession: String
    private val servers = mutableListOf<Process>()

    @Before
    fun setUp() {
        OpenCodeJcefTestHelper.assumeHarnessEnabled()
        val executable = System.getenv("OPENCODE_JCEF_EXECUTABLE") ?: OpenCodeServerProtocol.detectExecutablePath()
        assumeTrue("Install opencode to run the live JCEF tests", executable != null)
        workspace = temp.newFolder("workspace").toPath().toRealPath().toString()
        origins = listOf(startServer(executable!!, "native"), startServer(executable, "sandbox"))
        val v2 = OpenCodeServerProtocol.detectWireProtocol(origins[0], "Basic b3BlbmNvZGU6cHJvYmUtb25seQ==") == OpenCodeWireProtocol.V2_CLI
        val path = if (v2) "/api/session" else "/session?directory=${URLEncoder.encode(workspace, StandardCharsets.UTF_8)}"
        val connection = URI("${origins[0]}$path")
            .toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Basic b3BlbmNvZGU6cHJvYmUtb25seQ==")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val body = JsonObject().apply {
                addProperty("title", "Native fixture session")
                if (v2) add("location", JsonObject().apply { addProperty("directory", workspace) })
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            nativeSession = connection.inputStream.bufferedReader().use {
                val json = JsonParser.parseString(it.readText()).asJsonObject
                (if (v2) json.getAsJsonObject("data") else json).get("id").asString
            }
        } finally {
            connection.disconnect()
        }
    }

    @After
    fun tearDown() {
        try {
            ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(disposable.disposable) }
        } finally {
            servers.forEach { it.destroy() }
            servers.forEach { if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        }
    }

    private fun startServer(executable: String, name: String): String {
        val root = temp.newFolder(name).toPath().toRealPath()
        Files.createDirectories(root.resolve("config/opencode"))
        Files.createDirectories(root.resolve("home"))
        Files.writeString(root.resolve("config/opencode/opencode.json"), """{"plugin":[],"mcp":{}}""")
        val log = root.resolve("server.log")
        val process = ProcessBuilder(executable, "serve", "--hostname", "127.0.0.1", "--port", "0", "--print-logs")
            .directory(Path.of(workspace).toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            .apply {
                environment().clear()
                environment().putAll(mapOf(
                    "PATH" to OpenCodeServerProtocol.resolvePath(), "HOME" to root.resolve("home").toString(),
                    "OPENCODE_TEST_HOME" to root.resolve("home").toString(),
                    "XDG_CONFIG_HOME" to root.resolve("config").toString(), "XDG_DATA_HOME" to root.resolve("data").toString(),
                    "XDG_CACHE_HOME" to root.resolve("cache").toString(), "XDG_STATE_HOME" to root.resolve("state").toString(),
                    "OPENCODE_DISABLE_PROJECT_CONFIG" to "1", "OPENCODE_DISABLE_MODELS_FETCH" to "1",
                    "OPENCODE_DISABLE_AUTOUPDATE" to "1", "OPENCODE_SERVER_PASSWORD" to "probe-only",
                ))
            }.start()
        servers += process
        var origin: String? = null
        OpenCodeJcefTestHelper.awaitCondition("isolated $name server", 60) {
            val text = Files.readString(log)
            assertTrue("Isolated server exited: $text", process.isAlive)
            origin = Regex("listening on (http://127\\.0\\.0\\.1:[0-9]+)").find(text)?.groupValues?.get(1)
            origin != null
        }
        return checkNotNull(origin)
    }

    @Test
    fun idlessSessionRouteReallyRendersAnEmptyMain() {
        val origin = origins[0]
        val browser = open(origin, "$origin/server/${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(origin.toByteArray())}/session")
        OpenCodeJcefTestHelper.awaitCondition("SPA shell mounted") { evaluate(browser, "!!document.querySelector('main')") == "true" }
        Thread.sleep(1_000)
        assertEquals("0", evaluate(browser, "document.querySelector('main').children.length"))
    }

    @Test
    fun homeRouteSurvivesRepeatedBackendBrowserReplacement() {
        var previous: Disposable? = null
        repeat(6) { index ->
            val origin = origins[index % origins.size]
            val parent = Disposer.newDisposable("live runtime browser $index")
            Disposer.register(disposable.disposable, parent)
            val browser = open(origin, OpenCodeServerProtocol.buildServerSessionUrl(origin), parent) {
                previous?.let { old -> SwingUtilities.invokeAndWait { Disposer.dispose(old) } }
            }
            OpenCodeJcefTestHelper.awaitCondition("real home content after backend replacement $index", 30) {
                evaluate(browser, "!!document.querySelector('main [role=region]')") == "true"
            }
            assertTrue(evaluate(browser, "document.body.innerText.length").toInt() > 20)
            previous = parent
        }
    }

    @Test
    fun knownSessionStillOpensWithItsComposer() {
        val browser = open(origins[0], OpenCodeServerProtocol.buildServerSessionUrl(origins[0], nativeSession))
        OpenCodeJcefTestHelper.awaitCondition("known native session composer", 30) {
            evaluate(browser, "!!document.querySelector('main [contenteditable=true]')") == "true"
        }
        assertTrue(browser.cefBrowser.url.contains("/session/$nativeSession"))
    }

    @Test
    fun editorTransfersKeepRealComposerDraftAndCallbacksWithoutReload() {
        lateinit var query: OpenCodeJsQuery
        lateinit var chatQuery: OpenCodeJsQuery
        lateinit var chat: OpenCodeChatInputService
        val callbacks = AtomicInteger()
        val browser = open(origins[0], OpenCodeServerProtocol.buildServerSessionUrl(origins[0], nativeSession),
            onBrowserCreated = {
                query = OpenCodeJsQuery.create(it)
                assertTrue(query.isAvailable)
                query.addHandler { callbacks.incrementAndGet(); null }
                chatQuery = OpenCodeJsQuery.create(it)
                assertTrue(chatQuery.isAvailable)
                chatQuery.addHandler { reply ->
                    val fields = reply.split('\n')
                    chat.acknowledge(fields[0], fields.getOrNull(1) == "1")
                    null
                }
            })
        OpenCodeJcefTestHelper.awaitCondition("real composer") {
            evaluate(browser, "!!document.querySelector('main [contenteditable=true]')") == "true"
        }
        evaluate(browser, """(() => {
          const input = document.querySelector('main [contenteditable=true]');
          input.focus(); document.execCommand('insertText', false, 'unsent editor transfer draft');
          window.__transferMarker = 'same document';
          return input.textContent;
        })()""")
        val loads = AtomicInteger()
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(b: CefBrowser?, frame: CefFrame?, type: CefRequest.TransitionType?) {
                if (frame?.isMain == true) loads.incrementAndGet()
            }
        }, browser.cefBrowser)
        val project = requireNotNull(ProjectManagerEx.getInstanceEx().newProject(
            Path.of(workspace), OpenProjectTask.build().withProjectName("Live editor transfer"),
        ))
        chat = OpenCodeChatInputService.getInstance(project)
        // EditorWindow.closeFile has different selection semantics for closed projects.
        // Open the test project, but skip unrelated installed-IDE startup activities.
        project.putUserData(ProjectImpl.RUN_START_UP_ACTIVITIES, false)
        ProjectManagerEx.getInstanceEx().openProject(Path.of(workspace), OpenProjectTask.build().withProject(project))
        Disposer.register(disposable.disposable) {
            ProjectManagerEx.getInstanceEx().forceCloseProject(project)
        }
        lateinit var controller: OpenCodePanelController
        lateinit var source: JFrame
        lateinit var destination: JFrame
        ApplicationManager.getApplication().invokeAndWait {
            // Use the real manager as FileEditorManagerTestCase does.
            val editors = FileEditorManagerImpl(project, (project as ComponentManagerEx).getCoroutineScope().childScope("Live editor transfer"))
            project.replaceService(FileEditorManager::class.java, editors, disposable.disposable)
            Disposer.register(disposable.disposable, editors)
            controller = OpenCodePanelController(project) {
                object : OpenCodePanel {
                    override val component = browser.component
                    override val preferredFocus = browser.component
                    override fun prepareBrowserForReplacement() = createOpenCodeBrowserBeforeReplacement(browser)
                    override fun checkAndLoadContent() = Unit // Already on the real session.
                    override fun dispatchChatBatch(delivery: OpenCodeChatInputService.Delivery): Boolean {
                        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                            emptyList(), textPlain = listOf(delivery.batch.text), enabled = true,
                            batchId = delivery.attemptID,
                            resultCallback = chatQuery.inject("batchId + '\\n' + (accepted ? '1' : '0')"),
                        )!!
                        browser.cefBrowser.executeJavaScript(script, origins[0], 0)
                        return true
                    }
                    override fun onHostChanged() { browser.cefBrowser.notifyScreenInfoChanged() }
                    override fun dispose() = Unit // The test's disposable owns this browser.
                }
            }
            Disposer.register(disposable.disposable, controller)
            controller.ensurePanel()
            source = JFrame("OpenCode tool window host").apply {
                setSize(700, 600); add(controller.toolWindowComponent); isVisible = true
            }
            Disposer.register(disposable.disposable) { source.dispose() }
            destination = JFrame("OpenCode native editor host").apply {
                setSize(800, 600); setLocation(710, 0)
                glassPane = IdeGlassPaneImpl(rootPane)
                add(editors.component); isVisible = true
            }
            Disposer.register(disposable.disposable) { destination.dispose() }
        }
        try {
            repeat(3) { transfer ->
                ApplicationManager.getApplication().invokeAndWait {
                    controller.moveToEditor()
                    val file = requireNotNull(controller.editorFile)
                    val editors = FileEditorManager.getInstance(project)
                    assertTrue(editors.isFileOpen(file))
                    assertSame(destination, SwingUtilities.getWindowAncestor(controller.component))
                    assertTrue(chat.activatePanel())
                    assertSame(file, controller.editorFile)
                }
                assertEquals("unsent editor transfer draft", evaluate(browser, "document.querySelector('main [contenteditable=true]').textContent"))
                assertEquals("same document", evaluate(browser, "window.__transferMarker"))
                browser.cefBrowser.executeJavaScript(query.inject("'alive'")!!, browser.cefBrowser.url, 0)
                OpenCodeJcefTestHelper.awaitCondition("callback after editor transfer") { callbacks.get() > transfer }
                ApplicationManager.getApplication().invokeAndWait {
                    controller.moveToToolWindow()
                    assertSame(source, SwingUtilities.getWindowAncestor(controller.component))
                }
            }
            ApplicationManager.getApplication().invokeAndWait {
                controller.moveToEditor()
                assertTrue(chat.send(listOf(" context from IDE")))
                assertTrue(chat.activatePanel())
                assertTrue(controller.isInEditor)
            }
            OpenCodeJcefTestHelper.awaitCondition("IDE input acknowledged in editor") { chat.queuedCount() == 0 }
            val deliveredDraft = evaluate(browser, "document.querySelector('main [contenteditable=true]').textContent")
            assertTrue(deliveredDraft.contains("unsent editor transfer draft"))
            assertTrue(deliveredDraft.contains("context from IDE"))
            ApplicationManager.getApplication().invokeAndWait {
                FileEditorManager.getInstance(project).closeFile(requireNotNull(controller.editorFile))
            }
            ApplicationManager.getApplication().invokeAndWait {
                assertFalse(controller.isInEditor)
                assertSame(controller.toolWindowComponent, controller.component.parent)
            }
            assertEquals(0, loads.get())
            assertEquals(deliveredDraft, evaluate(browser, "document.querySelector('main [contenteditable=true]').textContent"))
        } finally {
            ApplicationManager.getApplication().invokeAndWait { controller.moveToToolWindow() }
        }
    }

    @Test
    fun firstDocumentDeliversRendererHeartbeatsWithoutReload() {
        val origin = origins[0]
        var previous: Disposable? = null
        repeat(3) { index ->
            val parent = Disposer.newDisposable("heartbeat browser $index")
            Disposer.register(disposable.disposable, parent)
            lateinit var browser: JBCefBrowser
            lateinit var created: CompletableFuture<Unit>
            val beats = List(10) { java.util.concurrent.atomic.AtomicInteger() }
            lateinit var heartbeats: List<String>
            SwingUtilities.invokeAndWait {
                browser = OpenCodeJcefTestHelper.createBrowser(parent)
                // Match the panel's nine channels plus file-drop result channel. Register all
                // routers before Chromium creation, including when a sibling is still alive.
                heartbeats = beats.map { count ->
                    val query = OpenCodeJsQuery.create(browser)
                    assertTrue(query.isAvailable)
                    query.addHandler { count.incrementAndGet(); null }
                    query.inject("visibility")!!
                }
                created = createOpenCodeBrowserBeforeReplacement(browser)
            }
            created.get(15, TimeUnit.SECONDS)
            SwingUtilities.invokeAndWait {
                previous?.let { Disposer.dispose(it) }
                OpenCodeJcefTestHelper.show(browser, "First document heartbeat $index", parent)
            }
            browser.jbCefClient.addRequestHandler(
                OpenCodeJcefAuthHandler(origin, "Basic b3BlbmNvZGU6cHJvYmUtb25seQ==", password = "probe-only"), browser.cefBrowser,
            )
            OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, "$origin/") { browser.loadURL("$origin/") }
            val heartbeat = OpenCodeBrowserSnippets.buildRendererHeartbeatScript(true, heartbeats.joinToString(";"))!!
            browser.cefBrowser.executeJavaScript(heartbeat, origin, 0)
            assertEquals("true", evaluate(browser, "window.__opencodeIntellijRendererHeartbeatInstalled === true"))
            OpenCodeJcefTestHelper.awaitCondition("all first-document heartbeat channels $index", 20) {
                beats.all { it.get() >= 2 }
            }
            previous = parent
        }
    }

    @Test
    fun realSandboxShutdownKeepsChromiumAndNativeReplacementAlive() {
        assumeTrue("Opt in to disposable sandbox creation with -PsbxJcef", System.getProperty("openCode.sbxJcefTests") == "true")
        val sbx = OpenCodeServerProtocol.detectExecutablePath("sbx")
        assumeTrue("Install sbx to run the sandbox test", sbx != null)
        val name = "ocwp-jcef-probe-${java.util.UUID.randomUUID().toString().take(8)}"
        val cwd = Path.of(workspace)
        fun run(command: List<String>): String {
            val result = SbxProcessRunner.run(command, emptyMap(), 120_000, cwd)
            assertEquals("Sandbox probe command failed: ${result.output}", 0, result.exitCode)
            return result.output
        }
        run(SbxCli.buildCreateCommand(sbx!!, name, workspace))
        val sandboxId = SbxCli.parseLsJson(run(SbxCli.buildLsCommand(sbx))).single { it.name == name }.id
        try {
            val nativeParent = Disposer.newDisposable("native before sandbox")
            Disposer.register(disposable.disposable, nativeParent)
            val native = open(origins[0], origins[0] + "/", nativeParent)
            assertEquals("2", evaluate(native, "1 + 1"))
            OpenCodeProcessTerminator().destroy(servers[0])

            val serve = ProcessBuilder(SbxCli.buildExecServeCommand(sbx, name, workspace))
                .directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(temp.newFile("sbx-serve.log"))
                .apply { environment()["OPENCODE_SERVER_PASSWORD"] = "probe-only" }.start()
            servers += serve
            var sandboxUrl: String? = null
            OpenCodeJcefTestHelper.awaitCondition("real sandbox health", 120) {
                assertTrue("Sandbox serve exited", serve.isAlive)
                sandboxUrl = SbxCli.publishedHostPorts(SbxCli.parsePortsJson(run(SbxCli.buildPortsCommand(sbx, name))))
                    .map(OpenCodeServerProtocol::publishedSandboxUrl)
                    .firstOrNull { OpenCodeServerProtocol.checkServerResponding(it, OpenCodeServerProtocol.buildBasicAuthHeader("probe-only")) }
                sandboxUrl != null
            }
            val sandboxParent = Disposer.newDisposable("real sandbox browser")
            Disposer.register(disposable.disposable, sandboxParent)
            val browser = open(sandboxUrl!!, sandboxUrl!! + "/", sandboxParent) {
                SwingUtilities.invokeAndWait { Disposer.dispose(nativeParent) }
            }
            assertEquals("2", evaluate(browser, "1 + 1"))
            OpenCodeServerProtocol.disposeServer(sandboxUrl!!, OpenCodeServerProtocol.buildBasicAuthHeader("probe-only"))
            assertEquals("JCEF after API disposal", "2", evaluate(browser, "1 + 1"))
            val killed = SbxProcessRunner.run(SbxCli.buildRemotePkillCommand(sbx, name), emptyMap(), 15_000)
            assertTrue("Remote pkill failed", killed.exitCode in 0..1)
            assertEquals("JCEF after remote serve stop", "2", evaluate(browser, "1 + 1"))
            run(SbxCli.buildStopCommand(sbx, name))
            assertEquals("JCEF after VM stop", "2", evaluate(browser, "1 + 1"))
            OpenCodeProcessTerminator().destroy(serve)
            assertEquals("JCEF after foreground client cleanup", "2", evaluate(browser, "1 + 1"))
            val returnedOrigin = startServer(OpenCodeServerProtocol.detectExecutablePath()!!, "returned-native")
            val returned = open(returnedOrigin, returnedOrigin + "/") {
                SwingUtilities.invokeAndWait { Disposer.dispose(sandboxParent) }
            }
            OpenCodeJcefTestHelper.awaitCondition("native home after real SBX stop", 30) {
                evaluate(returned, "!!document.querySelector('main [role=region]')") == "true"
            }
        } finally {
            val owned = SbxCli.parseLsJson(run(SbxCli.buildLsCommand(sbx))).any { it.name == name && it.id == sandboxId }
            if (owned) run(SbxCli.buildRmForceCommand(sbx, name))
        }
    }

    private fun open(
        origin: String,
        url: String,
        parent: Disposable = disposable.disposable,
        onBrowserCreated: (JBCefBrowser) -> Unit = {},
        beforeLoad: () -> Unit = {},
    ): JBCefBrowser {
        lateinit var browser: JBCefBrowser
        SwingUtilities.invokeAndWait {
            browser = OpenCodeJcefTestHelper.createBrowser(parent)
            onBrowserCreated(browser)
            repeat(9) { assertTrue("JCEF query channel creation", OpenCodeJsQuery.create(browser).isAvailable) }
        }
        browser.jbCefClient.addRequestHandler(
            OpenCodeJcefAuthHandler(origin, "Basic b3BlbmNvZGU6cHJvYmUtb25seQ==", password = "probe-only"), browser.cefBrowser,
        )
        val seed = OpenCodeBrowserSnippets.buildOpenProjectScript(workspace, origin)!!
        val loaded = CountDownLatch(1)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(cefBrowser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                if (frame?.isMain == true && frame.url.startsWith(origin)) cefBrowser?.executeJavaScript(seed, origin, 0)
            }
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true && frame.url.startsWith(origin)) loaded.countDown()
            }
        }, browser.cefBrowser)
        // Exercise the production readiness barrier before attaching the successor or closing its predecessor.
        lateinit var created: CompletableFuture<Unit>
        SwingUtilities.invokeAndWait { created = createOpenCodeBrowserBeforeReplacement(browser) }
        created.get(15, TimeUnit.SECONDS)
        beforeLoad()
        SwingUtilities.invokeAndWait {
            OpenCodeJcefTestHelper.show(browser, "Real OpenCode runtime switch", parent)
        }
        OpenCodeDocumentStartInjector(browser).installAndWait(seed, 2_000)
        OpenCodeJcefTestHelper.invokeAndWaitForLatch(loaded, "live OpenCode document") { browser.loadURL(url) }
        return browser
    }

    private fun evaluate(browser: JBCefBrowser, expression: String) = OpenCodeJcefTestHelper.evaluateString(browser, expression)
}
