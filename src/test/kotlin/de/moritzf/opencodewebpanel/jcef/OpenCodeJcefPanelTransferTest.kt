package de.moritzf.opencodewebpanel.jcef

import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.browser.OpenCodeJsQuery
import de.moritzf.opencodewebpanel.browser.createOpenCodeBrowserBeforeReplacement
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeEditorFileEditor
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeEditorVirtualFile
import de.moritzf.opencodewebpanel.toolWindow.OpenCodePanelHandle
import de.moritzf.opencodewebpanel.toolWindow.OpenCodePanelCoordinator
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Opt-in headful coverage for moving the live browser between shared-panel hosts. */
class OpenCodeJcefPanelTransferTest {
    @get:Rule
    val disposableRule = DisposableRule()

    @Before
    fun setUp() {
        OpenCodeJcefTestHelper.assumeHarnessEnabled()
    }

    @Test
    fun transfersTheSameVisibleBrowserWithoutReloadingItsSessionPage() {
        OpenCodeJcefTestServer().use { server ->
            lateinit var browser: JBCefBrowser
            lateinit var query: OpenCodeJsQuery
            var coordinator: OpenCodePanelCoordinator? = null
            lateinit var firstFrame: JFrame
            lateinit var secondFrame: JFrame
            var framesCreated = false
            val toolWindowContainer = JPanel()
            val firstEditorContainer = JPanel()
            val secondEditorContainer = JPanel()
            val editorSplit = JPanel(java.awt.GridLayout(1, 2)).apply {
                add(firstEditorContainer)
                add(secondEditorContainer)
            }
            val placeholders = mapOf(
                "tool-window" to JPanel(),
                "editor-one" to JPanel(),
                "editor-two" to JPanel(),
            )
            val callbackPayload = AtomicReference<String>()
            val callback = CountDownLatch(1)
            val transferBarrier = CountDownLatch(1)
            val transferLoadAttempts = AtomicInteger()
            val transferStarted = AtomicBoolean()

            try {
                SwingUtilities.invokeAndWait {
                    browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
                    query = OpenCodeJsQuery.create(browser)
                    assertTrue("JCEF callback channel creation", query.isAvailable)
                    query.addHandler {
                        callbackPayload.set(it)
                        when (it) {
                            "transfer-barrier" -> transferBarrier.countDown()
                            "transfer-survived" -> callback.countDown()
                        }
                        null
                    }

                    firstFrame = JFrame("OpenCode JCEF transfer source")
                    secondFrame = JFrame("OpenCode JCEF transfer target")
                    framesCreated = true
                    firstFrame.setSize(640, 480)
                    secondFrame.setSize(640, 480)
                    firstFrame.add(toolWindowContainer)
                    secondFrame.add(editorSplit)
                    firstFrame.setLocation(0, 0)
                    secondFrame.setLocation(660, 0)
                    firstFrame.isVisible = true
                    secondFrame.isVisible = true

                    val panelCoordinator = OpenCodePanelCoordinator(
                        panelComponent = browser.component,
                        disposePanel = {},
                        parkingContainer = JPanel(),
                    )
                    coordinator = panelCoordinator
                    panelCoordinator.registerPlacement("tool-window", toolWindowContainer, placeholders.getValue("tool-window"))
                    panelCoordinator.registerPlacement("editor-one", firstEditorContainer, placeholders.getValue("editor-one"))
                    panelCoordinator.registerPlacement("editor-two", secondEditorContainer, placeholders.getValue("editor-two"))
                    panelCoordinator.place("tool-window")
                }

                val route = server.origin + "/server/transfer/session/ses_transfer"
                val mainFrameLoads = AtomicInteger()
                browser.jbCefClient.addLoadHandler(
                    object : CefLoadHandlerAdapter() {
                        override fun onLoadStart(
                            cefBrowser: CefBrowser?,
                            frame: CefFrame?,
                            transitionType: CefRequest.TransitionType?,
                        ) {
                            if (frame?.isMain == true && frame.url == route) {
                                mainFrameLoads.incrementAndGet()
                                if (transferStarted.get()) transferLoadAttempts.incrementAndGet()
                            }
                        }
                    },
                    browser.cefBrowser,
                )
                browser.jbCefClient.addRequestHandler(
                    OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
                    browser.cefBrowser,
                )

                OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, route) {
                    browser.loadURL(route)
                }
                assertSame(browser.component, toolWindowContainer.getComponent(0))

                val stateBeforeTransfer = OpenCodeJcefTestHelper.evaluateString(
                    browser,
                    """
                    (() => {
                      document.body.dataset.transferMarker = 'survives';
                      const draft = document.createElement('textarea');
                      draft.id = 'transfer-draft';
                      draft.value = 'draft survives';
                      document.body.appendChild(draft);
                      draft.focus();
                      document.documentElement.style.height = '4000px';
                      document.body.style.height = '4000px';
                      window.scrollTo(0, 321);
                      return JSON.stringify({
                        marker: document.body.dataset.transferMarker,
                        draft: draft.value,
                        scroll: window.scrollY,
                        focused: document.activeElement.id,
                      });
                    })()
                    """.trimIndent(),
                )
                assertEquals("321", OpenCodeJcefTestHelper.evaluateString(browser, "String(window.scrollY)"))
                assertEquals("transfer-draft", OpenCodeJcefTestHelper.evaluateString(browser, "document.activeElement.id"))
                val urlBeforeTransfer = browser.cefBrowser.url
                val containers = linkedMapOf(
                    "tool-window" to toolWindowContainer,
                    "editor-one" to firstEditorContainer,
                    "editor-two" to secondEditorContainer,
                )
                fun assertPlacementState(activeId: String) {
                    assertEquals(
                        1,
                        containers.values.count { it.componentCount == 1 && it.getComponent(0) === browser.component },
                    )
                    containers.forEach { (id, container) ->
                        assertEquals("$id should have exactly one child", 1, container.componentCount)
                        if (id == activeId) {
                            assertSame(browser.component, container.getComponent(0))
                        } else {
                            assertSame(placeholders.getValue(id), container.getComponent(0))
                        }
                    }
                }
                assertPlacementState("tool-window")
                transferStarted.set(true)

                listOf("editor-one", "tool-window", "editor-two", "editor-one", "tool-window").forEach { target ->
                    SwingUtilities.invokeAndWait {
                        coordinator!!.place(target)
                        assertPlacementState(target)
                    }
                    assertEquals("transfer-draft", OpenCodeJcefTestHelper.evaluateString(browser, "document.activeElement.id"))
                }

                val barrierScript = checkNotNull(query.inject("'transfer-barrier'"))
                browser.cefBrowser.executeJavaScript(barrierScript, browser.cefBrowser.url, 0)
                assertTrue(transferBarrier.await(OpenCodeJcefTestHelper.WAIT_BROWSER_SECONDS, TimeUnit.SECONDS))
                assertEquals(0, transferLoadAttempts.get())
                assertEquals(1, mainFrameLoads.get())

                val callbackScript = checkNotNull(query.inject("'transfer-survived'"))
                browser.cefBrowser.executeJavaScript(callbackScript, browser.cefBrowser.url, 0)
                assertTrue(callback.await(OpenCodeJcefTestHelper.WAIT_BROWSER_SECONDS, TimeUnit.SECONDS))
                assertEquals(urlBeforeTransfer, browser.cefBrowser.url)
                assertEquals(route, browser.cefBrowser.url)
                assertEquals(1, mainFrameLoads.get())
                assertEquals(
                    stateBeforeTransfer,
                    OpenCodeJcefTestHelper.evaluateString(
                        browser,
                        "JSON.stringify({marker: document.body.dataset.transferMarker, draft: document.getElementById('transfer-draft').value, scroll: window.scrollY, focused: document.activeElement.id})",
                    ),
                )
                assertEquals("transfer-survived", callbackPayload.get())
            } finally {
                SwingUtilities.invokeAndWait {
                    coordinator?.dispose()
                    if (framesCreated) {
                        firstFrame.dispose()
                        secondFrame.dispose()
                    }
                }
            }
        }
    }

    @Test
    fun replacementAfterTransferAttachesTheReadyBrowserToTheCurrentHost() {
        OpenCodeJcefTestServer().use { server ->
            val route = server.origin + "/server/transfer/session/ses_replacement"
            val project = ProjectManager.getInstance().defaultProject
            lateinit var initial: BrowserPanel
            lateinit var successor: BrowserPanel
            val successorTransferred = CountDownLatch(1)
            var coordinator: OpenCodePanelCoordinator? = null
            lateinit var firstFrame: JFrame
            lateinit var secondFrame: JFrame
            var framesCreated = false
            var firstEditor: OpenCodeEditorFileEditor? = null
            var secondEditor: OpenCodeEditorFileEditor? = null
            lateinit var targetPlaceholder: java.awt.Component

            fun newPanel(onTransferred: () -> Unit = {}): BrowserPanel {
                val panelBrowser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
                panelBrowser.jbCefClient.addRequestHandler(
                    OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
                    panelBrowser.cefBrowser,
                )
                return BrowserPanel(panelBrowser, route, onTransferred)
            }

            try {
                SwingUtilities.invokeAndWait {
                    initial = newPanel()
                    firstFrame = JFrame("OpenCode JCEF replacement source")
                    secondFrame = JFrame("OpenCode JCEF replacement target")
                    framesCreated = true
                    firstFrame.setSize(640, 480)
                    secondFrame.setSize(640, 480)
                    firstFrame.setLocation(0, 0)
                    secondFrame.setLocation(660, 0)
                    firstFrame.isVisible = true
                    secondFrame.isVisible = true

                    val panelCoordinator = OpenCodePanelCoordinator(
                        initialPanel = initial,
                        parkingContainer = JPanel(),
                    ) {
                        newPanel { successorTransferred.countDown() }.also { successor = it }
                    }
                    coordinator = panelCoordinator
                    val file = OpenCodeEditorVirtualFile(project, "ses_replacement")
                    val source = OpenCodeEditorFileEditor(
                        project,
                        file,
                        initializePanel = false,
                        panelCoordinator = panelCoordinator,
                    )
                    val target = OpenCodeEditorFileEditor(
                        project,
                        file,
                        initializePanel = false,
                        panelCoordinator = panelCoordinator,
                    )
                    firstEditor = source
                    secondEditor = target
                    targetPlaceholder = target.component.getComponent(0)
                    firstFrame.add(source.component)
                    secondFrame.add(target.component)
                    source.selectNotify()
                }

                OpenCodeJcefTestHelper.invokeAndWaitForLoad(initial.browser, route) {
                    initial.browser.loadURL(route)
                }
                val activeCoordinator = checkNotNull(coordinator)
                val sourceEditor = checkNotNull(firstEditor)
                val targetEditor = checkNotNull(secondEditor)
                SwingUtilities.invokeAndWait {
                    targetEditor.selectNotify()
                    assertSame(initial.component, targetEditor.component.getComponent(0))
                    activeCoordinator.replacePanel()
                    sourceEditor.selectNotify()
                    assertSame(initial.component, sourceEditor.component.getComponent(0))
                }

                OpenCodeJcefTestHelper.await(successorTransferred, "waiting for replacement transfer")
                OpenCodeJcefTestHelper.await(successor.loaded, "waiting for replacement page load")
                SwingUtilities.invokeAndWait {
                    assertSame(successor.component, sourceEditor.component.getComponent(0))
                    assertEquals(1, targetEditor.component.componentCount)
                    assertSame(targetPlaceholder, targetEditor.component.getComponent(0))
                    assertTrue(initial.disposed)
                    assertTrue(activeCoordinator.isPlacementActive("editor:${System.identityHashCode(sourceEditor)}"))
                }
                assertEquals(1, initial.loadCount.get())
                assertEquals(1, successor.loadCount.get())
                assertEquals(route, successor.browser.cefBrowser.url)
            } finally {
                SwingUtilities.invokeAndWait {
                    coordinator?.dispose()
                    firstEditor?.dispose()
                    secondEditor?.dispose()
                    if (framesCreated) {
                        firstFrame.dispose()
                        secondFrame.dispose()
                    }
                }
            }
        }
    }

    private class BrowserPanel(
        val browser: JBCefBrowser,
        private val route: String,
        private val transferCallback: () -> Unit,
    ) : OpenCodePanelHandle {
        override val component: JComponent
            get() = browser.component
        val loadCount = AtomicInteger()
        val loaded = CountDownLatch(1)
        var disposed = false
            private set

        init {
            browser.jbCefClient.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadStart(
                        cefBrowser: CefBrowser?,
                        frame: CefFrame?,
                        transitionType: CefRequest.TransitionType?,
                    ) {
                        if (frame?.isMain == true && frame.url == route) loadCount.incrementAndGet()
                    }

                    override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        if (frame?.isMain == true && frame.url == route) loaded.countDown()
                    }
                },
                browser.cefBrowser,
            )
        }

        override fun prepareBrowserForReplacement() = createOpenCodeBrowserBeforeReplacement(browser)

        override fun openSession(sessionId: String?) {
            browser.loadURL(route)
        }

        override fun onPlacementTransferred() {
            transferCallback()
        }

        override fun dispose() {
            disposed = true
            Disposer.dispose(browser)
        }
    }

    companion object {
        @ClassRule
        @JvmField
        val appRule = ApplicationRule()
    }
}
