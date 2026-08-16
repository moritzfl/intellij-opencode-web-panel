package de.moritzf.opencodewebpanel.jcef

import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Two JBCefBrowser instances on one origin — the first+second IDE window case. */
class OpenCodeJcefTwoBrowserTest {
    @get:Rule
    val disposableRule = DisposableRule()

    @Before
    fun setUp() {
        OpenCodeJcefTestHelper.assumeHarnessEnabled()
    }

    @Test
    fun secondBrowserLoadsWhileTheFirstStaysUp() {
        OpenCodeJcefTestServer().use { server ->
            val first = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
            val second = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
            val auth = OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization)
            first.jbCefClient.addRequestHandler(auth, first.cefBrowser)
            second.jbCefClient.addRequestHandler(auth, second.cefBrowser)

            OpenCodeJcefTestHelper.invokeAndWaitForLoad(first, server.origin + "/") {
                OpenCodeJcefTestHelper.show(first, "first", disposableRule.disposable)
                first.loadURL(server.origin + "/")
            }
            assertEquals("ready", readMarker(first))

            OpenCodeJcefTestHelper.invokeAndWaitForLoad(second, server.origin + "/") {
                OpenCodeJcefTestHelper.show(second, "second", disposableRule.disposable)
                second.loadURL(server.origin + "/")
            }
            assertEquals("ready", readMarker(second))
            assertEquals("ready", readMarker(first))
        }
    }

    private fun readMarker(browser: com.intellij.ui.jcef.JBCefBrowser): String {
        return OpenCodeJcefTestHelper.evaluateString(
            browser,
            "document.getElementById('${OpenCodeJcefTestServer.MARKER_ID}')?.textContent || ''",
        )
    }

    companion object {
        @ClassRule
        @JvmField
        val appRule = ApplicationRule()
    }
}
