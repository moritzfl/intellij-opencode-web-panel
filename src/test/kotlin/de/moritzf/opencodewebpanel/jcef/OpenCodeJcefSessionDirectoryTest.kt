package de.moritzf.opencodewebpanel.jcef

import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import com.intellij.openapi.util.Disposer
import org.junit.Test
import java.nio.file.Files

/**
 * Reproduces the SPA submit toast: `sync().session.get(id)` returns the session only when
 * `session.directory === sdk().directory`. Seeding lastProjectSession with the IDE path
 * (symlink / `/var`) while OpenCode stored the realpath makes every send fail with
 * "Unable to retrieve session" after a fresh IDE start.
 */
class OpenCodeJcefSessionDirectoryTest {
    @get:Rule
    val disposableRule = DisposableRule()

    @Before
    fun setUp() {
        OpenCodeJcefTestHelper.assumeHarnessEnabled()
    }

    @Test
    fun spaSessionGetFailsWhenSeededDirectoryDiffersFromSessionDirectory() {
        val paths = symlinkProject() ?: return
        OpenCodeJcefTestServer().use { server ->
            val browser = openAuthedPage(server)
            seedLastProjectSession(browser, server.origin, paths.link, sessionDirectory = null)
            assertEquals(
                "0",
                evaluateSessionGet(browser, paths.target),
            )
        }
    }

    @Test
    fun spaSessionGetSucceedsWhenPointerUsesTheSessionsOwnDirectory() {
        val paths = symlinkProject() ?: return
        OpenCodeJcefTestServer().use { server ->
            val browser = openAuthedPage(server)
            seedLastProjectSession(browser, server.origin, paths.link, sessionDirectory = paths.target)
            assertEquals(
                "1",
                evaluateSessionGet(browser, paths.target),
            )
            assertEquals(
                paths.target,
                OpenCodeJcefTestHelper.evaluateString(
                    browser,
                    "JSON.parse(localStorage.getItem('opencode.global.dat:layout.page')" +
                        ").lastProjectSession[Object.keys(JSON.parse(localStorage.getItem(" +
                        "'opencode.global.dat:layout.page')).lastProjectSession)[0]].directory",
                ),
            )
        }
    }

    private fun openAuthedPage(server: OpenCodeJcefTestServer): com.intellij.ui.jcef.JBCefBrowser {
        val browser = OpenCodeJcefTestHelper.createBrowser(disposableRule.disposable)
        browser.jbCefClient.addRequestHandler(
            OpenCodeJcefAuthHandler(server.origin, server.expectedAuthorization),
            browser.cefBrowser,
        )
        OpenCodeJcefTestHelper.invokeAndWaitForLoad(browser, server.origin + "/") {
            OpenCodeJcefTestHelper.show(browser, javaClass.simpleName, disposableRule.disposable)
            browser.loadURL(server.origin + "/")
        }
        return browser
    }

    private fun seedLastProjectSession(
        browser: com.intellij.ui.jcef.JBCefBrowser,
        origin: String,
        projectDirectory: String,
        sessionDirectory: String?,
    ) {
        val script = OpenCodeBrowserSnippets.buildOpenProjectScript(
            projectDirectory,
            "$origin/",
            openMostRecentConversation = true,
            mostRecentSessionId = SESSION_ID,
            navigate = false,
            mostRecentSessionDirectory = sessionDirectory,
        )!!
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        OpenCodeJcefTestHelper.awaitCondition("open-project seed wrote lastProjectSession") {
            OpenCodeJcefTestHelper.evaluateString(
                browser,
                "localStorage.getItem('opencode.global.dat:layout.page') ? '1' : '0'",
            ) == "1"
        }
    }

    private fun evaluateSessionGet(browser: com.intellij.ui.jcef.JBCefBrowser, sessionDirectory: String): String {
        val escaped = sessionDirectory.replace("\\", "\\\\").replace("'", "\\'")
        return OpenCodeJcefTestHelper.evaluateString(
            browser,
            """
            (function() {
              var page = JSON.parse(localStorage.getItem('opencode.global.dat:layout.page') || '{}');
              var map = page.lastProjectSession || {};
              var key = Object.keys(map)[0];
              var pointer = key ? map[key] : null;
              var session = { id: '$SESSION_ID', directory: '$escaped' };
              return pointer && session.directory === pointer.directory ? '1' : '0';
            })()
            """.trimIndent().replace('\n', ' '),
        )
    }

    private fun symlinkProject(): Paths? {
        val root = Files.createTempDirectory("opencode-jcef-session-dir")
        Disposer.register(disposableRule.disposable) { root.toFile().deleteRecursively() }
        val target = Files.createDirectory(root.resolve("target"))
        val link = root.resolve("link")
        val created = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        Assume.assumeTrue("symlink creation is required for this JCEF contract test", created)
        val targetPath = target.toRealPath().toString()
        val linkPath = link.toString()
        Assume.assumeTrue("symlink and target must be different spellings", linkPath != targetPath)
        return Paths(link = linkPath, target = targetPath)
    }

    private data class Paths(val link: String, val target: String)

    companion object {
        private const val SESSION_ID = "ses_abc123"

        @ClassRule
        @JvmField
        val appRule = ApplicationRule()
    }
}
