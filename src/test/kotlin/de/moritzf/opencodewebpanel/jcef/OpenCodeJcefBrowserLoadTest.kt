package de.moritzf.opencodewebpanel.jcef

import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.browser.OpenCodeDocumentStartInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

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
                OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
                browser.cefBrowser,
            )
            OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, server.origin + "/") {
                OpenCodeJcefTestHelper.show(browser, javaClass.simpleName, disposableRule.disposable)
                browser.loadURL(server.origin + "/")
            }
            val marker = OpenCodeJcefTestHelper.evaluateString(
                browser,
                "document.getElementById('${OpenCodeJcefTestServer.MARKER_ID}')?.textContent || ''",
            )
            assertEquals("ready", marker)
            assertTrue(server.requestPaths.contains("/assets/app.js"))
            assertEquals(0, server.unauthorizedCount.get())
        }
    }

    @Test
    fun documentStartWatchdogIsInstalledBeforePageScript() {
        OpenCodeJcefTestServer().use { server ->
            val browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
            browser.jbCefClient.addRequestHandler(
                OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
                browser.cefBrowser,
            )
            val injector = OpenCodeDocumentStartInjector(browser)
            OpenCodeJcefTestHelper.showAndWaitForBrowser(
                browser,
                javaClass.simpleName,
                disposableRule.disposable,
            )
            val registered = injector.installAndWait(
                OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!,
                timeoutMillis = OpenCodeJcefTestHelper.WAIT_BROWSER_SECONDS * 1_000,
            )
            assertTrue("Page.addScriptToEvaluateOnNewDocument was not accepted", registered)

            OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, server.origin + "/") {
                browser.loadURL(server.origin + "/")
            }

            val installed = OpenCodeJcefTestHelper.evaluateString(
                browser,
                "window.__opencodeJcefWatchdog ? '1' : '0'",
            )
            assertEquals("1", installed)
        }
    }

    @Test
    fun documentStartScriptDoesNotRunOnAnotherOrigin() {
        OpenCodeJcefTestServer().use { server ->
            OpenCodeJcefTestServer(requireAuth = false).use { foreignServer ->
                val browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
                browser.jbCefClient.addRequestHandler(
                    OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
                    browser.cefBrowser,
                )
                val injector = OpenCodeDocumentStartInjector(browser)
                OpenCodeJcefTestHelper.showAndWaitForBrowser(
                    browser,
                    javaClass.simpleName,
                    disposableRule.disposable,
                )
                assertTrue(
                    injector.installAndWait(
                        OpenCodeDocumentStartInjector.guardForOrigin(
                            "window.__opencodeGuarded = true;",
                            server.origin,
                        ),
                        timeoutMillis = OpenCodeJcefTestHelper.WAIT_BROWSER_SECONDS * 1_000,
                    ),
                )

                OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, foreignServer.origin + "/") {
                    browser.loadURL(foreignServer.origin + "/")
                }
                assertEquals("ready", readMarker(browser))
                assertEquals(
                    "0",
                    OpenCodeJcefTestHelper.evaluateString(
                        browser,
                        "window.__opencodeGuarded ? '1' : '0'",
                    ),
                )

                OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, server.origin + "/") {
                    browser.loadURL(server.origin + "/")
                }
                assertEquals(
                    "1",
                    OpenCodeJcefTestHelper.evaluateString(
                        browser,
                        "window.__opencodeGuarded ? '1' : '0'",
                    ),
                )
            }
        }
    }

    @Test
    fun newestDocumentStartScriptReplacesOlderRegistration() {
        OpenCodeJcefTestServer().use { server ->
            val browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
            browser.jbCefClient.addRequestHandler(
                OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
                browser.cefBrowser,
            )
            val injector = OpenCodeDocumentStartInjector(browser)
            OpenCodeJcefTestHelper.showAndWaitForBrowser(
                browser,
                javaClass.simpleName,
                disposableRule.disposable,
            )
            assertTrue(injector.installAndWait("(window.__documentStartRuns ||= []).push('old');", 10_000))
            injector.installAsync("(window.__documentStartRuns ||= []).push('superseded');")
            assertTrue(injector.installAndWait("(window.__documentStartRuns ||= []).push('latest');", 10_000))

            OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, server.origin + "/") {
                browser.loadURL(server.origin + "/")
            }
            assertEquals(
                "[\"latest\"]",
                OpenCodeJcefTestHelper.evaluateString(browser, "JSON.stringify(window.__documentStartRuns || [])"),
            )
        }
    }

    @Test
    fun eventStreamStalledBeforeHeadersIsAbortedAndReconnected() {
        assertStalledEventStreamReconnects(stallBeforeHeaders = true)
    }

    @Test
    fun eventStreamStalledBetweenBodyBytesIsAbortedAndReconnected() {
        assertStalledEventStreamReconnects(stallBeforeHeaders = false)
    }

    @Test
    fun healthyEventStreamStaysConnectedAcrossTheSilenceBudget() {
        OpenCodeJcefTestServer().use { server ->
            val browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
            browser.jbCefClient.addRequestHandler(
                OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
                browser.cefBrowser,
            )
            val injector = OpenCodeDocumentStartInjector(browser)
            OpenCodeJcefTestHelper.showAndWaitForBrowser(
                browser,
                javaClass.simpleName,
                disposableRule.disposable,
            )
            assertTrue(
                injector.installAndWait(
                    OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(
                        enabled = true,
                        stallTimeoutMillis = OpenCodeBrowserSnippets.MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS,
                    )!!,
                    timeoutMillis = OpenCodeJcefTestHelper.WAIT_BROWSER_SECONDS * 1_000,
                ),
            )

            val targetUrl = server.origin + "/?watchdog=1"
            OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, targetUrl) {
                browser.loadURL(targetUrl)
            }
            Thread.sleep(OpenCodeBrowserSnippets.MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS.toLong() + 3_000)
            assertEquals(1, server.eventStreamCount.get())
        }
    }

    private fun assertStalledEventStreamReconnects(stallBeforeHeaders: Boolean) {
        OpenCodeJcefTestServer(
            stallEventStream = true,
            stallBeforeEventStreamHeaders = stallBeforeHeaders,
        ).use { server ->
            val browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
            browser.jbCefClient.addRequestHandler(
                OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
                browser.cefBrowser,
            )
            val injector = OpenCodeDocumentStartInjector(browser)
            OpenCodeJcefTestHelper.showAndWaitForBrowser(
                browser,
                javaClass.simpleName,
                disposableRule.disposable,
            )
            assertTrue(
                injector.installAndWait(
                    OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(
                        enabled = true,
                        stallTimeoutMillis = OpenCodeBrowserSnippets.MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS,
                    )!!,
                    timeoutMillis = OpenCodeJcefTestHelper.WAIT_BROWSER_SECONDS * 1_000,
                ),
            )

            val targetUrl = server.origin + "/?watchdog=1"
            OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, targetUrl) {
                browser.loadURL(server.origin + "/?watchdog=1")
            }
            OpenCodeJcefTestHelper.awaitCondition(
                "waiting for the stalled event stream to reconnect",
                timeoutSeconds = 30,
            ) {
                server.eventStreamCount.get() >= 2
            }
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
