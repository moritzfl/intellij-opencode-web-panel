package de.moritzf.opencodewebpanel.jcef

import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.browser.OpenCodeDocumentStartInjector
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import javax.swing.JFrame

/**
 * Real JCEF (same stack as the tool window), against a local Basic-auth server.
 * Mirrors JetBrains `JBCefLoadHtmlTest`: ApplicationRule + show frame + wait onLoadEnd.
 */
class OpenCodeJcefBrowserLoadTest {
    @get:Rule
    val disposableRule = DisposableRule()

    @Before
    fun setUp() {
        OpenCodeJcefTestHelper.assumeHarnessEnabled()
    }

    @Test
    fun loadsAnAuthenticatedDocument() {
        OpenCodeJcefTestServer().use { server ->
            val browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
            browser.jbCefClient.addRequestHandler(
                OpenCodeJcefAuthHandler(server.expectedAuthorization),
                browser.cefBrowser,
            )
            OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser) {
                show(browser)
                browser.loadURL(server.origin + "/")
            }
            val marker = OpenCodeJcefTestHelper.evaluateString(
                browser,
                "document.getElementById('${OpenCodeJcefTestServer.MARKER_ID}')?.textContent || ''",
            )
            assertEquals("ready", marker)
        }
    }

    @Test
    fun documentStartWatchdogIsInstalledBeforePageScript() {
        OpenCodeJcefTestServer().use { server ->
            val browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
            browser.jbCefClient.addRequestHandler(
                OpenCodeJcefAuthHandler(server.expectedAuthorization),
                browser.cefBrowser,
            )
            show(browser)
            browser.createImmediately()
            val injector = OpenCodeDocumentStartInjector(browser)
            val registered = injector.installAndWait(
                OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!,
            )
            org.junit.Assume.assumeTrue("Page.addScriptToEvaluateOnNewDocument was not accepted", registered)

            OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser) {
                browser.loadURL(server.origin + "/")
            }

            val installed = OpenCodeJcefTestHelper.evaluateString(
                browser,
                "window.__opencodeIntellijEventWatchdogInstalled ? '1' : '0'",
            )
            assertEquals("1", installed)
        }
    }

    private fun show(browser: com.intellij.ui.jcef.JBCefBrowser) {
        val frame = JFrame(javaClass.simpleName)
        frame.setSize(640, 480)
        frame.add(browser.component)
        frame.isVisible = true
    }

    companion object {
        @ClassRule
        @JvmField
        val appRule = ApplicationRule()
    }
}
