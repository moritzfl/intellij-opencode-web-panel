package de.moritzf.opencodewebpanel.browser

import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A constructed JBCefBrowser wrapper does not yet own a remote Chromium browser. */
internal fun createOpenCodeBrowserBeforeReplacement(browser: JBCefBrowser): CompletableFuture<Unit> {
    val created = CompletableFuture<Unit>()
    val handler = object : CefLifeSpanHandlerAdapter() {
        override fun onAfterCreated(cefBrowser: CefBrowser?) {
            created.complete(Unit)
        }
    }
    try {
        browser.jbCefClient.addLifeSpanHandler(handler, browser.cefBrowser)
        created.orTimeout(10, TimeUnit.SECONDS).whenComplete { _, _ ->
            browser.jbCefClient.removeLifeSpanHandler(handler, browser.cefBrowser)
        }
        browser.createImmediately()
    } catch (error: Exception) {
        created.completeExceptionally(error)
    }
    return created
}
