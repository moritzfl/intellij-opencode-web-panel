package de.moritzf.opencodewebpanel.jcef

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.util.ui.UIUtil
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.junit.Assert
import org.junit.Assume
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame

/**
 * Headful JCEF helpers, aligned with JetBrains' `JBCefTestHelper`
 * (`platform/platform-tests/.../JBCefTestHelper.java`).
 *
 * Official tests wait up to 60s for first CEF init (TeamCity overlap with downloads).
 * Opt in with `./gradlew test -Pjcef` so `check` stays headless.
 */
internal object OpenCodeJcefTestHelper {
    const val WAIT_BROWSER_SECONDS = 60L

    fun assumeHarnessEnabled() {
        val enabled = System.getProperty("openCode.jcefTests") == "true"
        Assume.assumeTrue("JCEF harness is opt-in: rtk ./gradlew test -Pjcef", enabled)
        Assert.assertFalse("JCEF needs a display", GraphicsEnvironment.isHeadless())
        Assert.assertTrue("JCEF is not supported in this runtime", JBCefApp.isSupported())
    }

    fun showAndWaitForBrowser(browser: JBCefBrowserBase, frameTitle: String, parent: Disposable) {
        val latch = CountDownLatch(1)
        val handler = object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(cefBrowser: CefBrowser?) {
                latch.countDown()
            }
        }
        browser.jbCefClient.addLifeSpanHandler(handler, browser.cefBrowser)
        try {
            invokeAndWaitForLatch(latch, "waiting for native browser creation") {
                show(browser, frameTitle, parent)
                browser.cefBrowser.createImmediately()
            }
        } finally {
            browser.jbCefClient.removeLifeSpanHandler(handler, browser.cefBrowser)
        }
    }

    fun invokeAndWaitForLoad(browser: JBCefBrowserBase, expectedUrl: String, runnable: Runnable) {
        val latch = CountDownLatch(1)
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain != true || cefBrowser == null) return
                    if (frame.url != expectedUrl) return
                    browser.jbCefClient.removeLoadHandler(this, cefBrowser)
                    latch.countDown()
                }
            },
            browser.cefBrowser,
        )
        invokeAndWaitForLatch(latch, "waiting onLoadEnd", runnable)
    }

    fun invokeAndWaitForLatch(latch: CountDownLatch, description: String, runnable: Runnable) {
        UIUtil.invokeLaterIfNeeded(runnable)
        await(latch, description)
    }

    fun await(latch: CountDownLatch, description: String) {
        if (!latch.await(WAIT_BROWSER_SECONDS, TimeUnit.SECONDS)) {
            Assert.fail("$description timed out after ${WAIT_BROWSER_SECONDS}s")
        }
    }

    fun awaitCondition(description: String, timeoutSeconds: Long = WAIT_BROWSER_SECONDS, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            if (System.nanoTime() >= deadline) Assert.fail("$description timed out after ${timeoutSeconds}s")
            Thread.sleep(100)
        }
    }

    fun show(browser: JBCefBrowserBase, frameTitle: String, parent: Disposable) {
        val frame = JFrame(frameTitle)
        frame.setSize(640, 480)
        frame.setLocationRelativeTo(null)
        frame.add(browser.component, BorderLayout.CENTER)
        frame.isVisible = true
        Disposer.register(parent) { UIUtil.invokeLaterIfNeeded { frame.dispose() } }
    }

    fun createBrowser(parent: Disposable): JBCefBrowser {
        val browser = JBCefBrowser()
        Disposer.register(parent, browser)
        return browser
    }

    /**
     * Evaluate a JS expression via `console.log`. JBCefJSQuery's `cefQuery_*` is not always
     * bound in time for a just-loaded document (official tests inject the query *into* the
     * HTML they load). Console messages are always available.
     */
    fun evaluateString(browser: JBCefBrowserBase, expression: String): String {
        val prefix = "OPENCODE_JCEF_EVAL:"
        val latch = CountDownLatch(1)
        val value = AtomicReference("")
        val handler = object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                cefBrowser: CefBrowser?,
                level: CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int,
            ): Boolean {
                val text = message ?: return false
                if (!text.startsWith(prefix)) return false
                value.set(text.removePrefix(prefix))
                latch.countDown()
                return true
            }
        }
        browser.jbCefClient.addDisplayHandler(handler, browser.cefBrowser)
        try {
            browser.cefBrowser.executeJavaScript(
                "console.log('$prefix' + String($expression))",
                browser.cefBrowser.url,
                0,
            )
            await(latch, "js eval: $expression")
            return value.get()
        } finally {
            browser.jbCefClient.removeDisplayHandler(handler, browser.cefBrowser)
        }
    }
}
