package de.moritzf.opencodewebpanel.jcef

import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.browser.OpenCodeJsQuery
import de.moritzf.opencodewebpanel.toolWindow.OpenCodePanelCoordinator
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
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
            val firstContainer = JPanel()
            val secondContainer = JPanel()
            val callbackPayload = AtomicReference<String>()
            val callback = CountDownLatch(1)

            try {
                SwingUtilities.invokeAndWait {
                    browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
                    query = OpenCodeJsQuery.create(browser)
                    assertTrue("JCEF callback channel creation", query.isAvailable)
                    query.addHandler {
                        callbackPayload.set(it)
                        callback.countDown()
                        null
                    }

                    firstFrame = JFrame("OpenCode JCEF transfer source")
                    secondFrame = JFrame("OpenCode JCEF transfer target")
                    framesCreated = true
                    firstFrame.setSize(640, 480)
                    secondFrame.setSize(640, 480)
                    firstFrame.add(firstContainer)
                    secondFrame.add(secondContainer)
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
                    panelCoordinator.registerPlacement("first", firstContainer, JPanel())
                    panelCoordinator.registerPlacement("second", secondContainer, JPanel())
                    panelCoordinator.place("first")
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
                assertSame(browser.component, firstContainer.getComponent(0))

                val stateBeforeTransfer = OpenCodeJcefTestHelper.evaluateString(
                    browser,
                    """
                    (() => {
                      document.body.dataset.transferMarker = 'survives';
                      const draft = document.createElement('textarea');
                      draft.id = 'transfer-draft';
                      draft.value = 'draft survives';
                      document.body.appendChild(draft);
                      document.documentElement.style.height = '4000px';
                      document.body.style.height = '4000px';
                      window.scrollTo(0, 321);
                      return JSON.stringify({
                        marker: document.body.dataset.transferMarker,
                        draft: draft.value,
                        scroll: window.scrollY,
                      });
                    })()
                    """.trimIndent(),
                )
                assertEquals("321", OpenCodeJcefTestHelper.evaluateString(browser, "String(window.scrollY)"))
                val urlBeforeTransfer = browser.cefBrowser.url

                SwingUtilities.invokeAndWait {
                    coordinator!!.place("second")
                }

                assertSame(browser.component, secondContainer.getComponent(0))
                val callbackScript = checkNotNull(query.inject("'transfer-survived'"))
                browser.cefBrowser.executeJavaScript(callbackScript, browser.cefBrowser.url, 0)
                assertTrue(callback.await(OpenCodeJcefTestHelper.WAIT_BROWSER_SECONDS, TimeUnit.SECONDS))
                val settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                OpenCodeJcefTestHelper.awaitCondition("waiting for late transfer load callbacks") {
                    System.nanoTime() >= settleDeadline
                }
                assertEquals(urlBeforeTransfer, browser.cefBrowser.url)
                assertEquals(route, browser.cefBrowser.url)
                assertEquals(1, mainFrameLoads.get())
                assertEquals(
                    stateBeforeTransfer,
                    OpenCodeJcefTestHelper.evaluateString(
                        browser,
                        "JSON.stringify({marker: document.body.dataset.transferMarker, draft: document.getElementById('transfer-draft').value, scroll: window.scrollY})",
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

    companion object {
        @ClassRule
        @JvmField
        val appRule = ApplicationRule()
    }
}
