package de.moritzf.opencodewebpanel.browser

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefDevToolsClient
import org.cef.handler.CefLoadHandlerAdapter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Registers a script with Chromium's `Page.addScriptToEvaluateOnNewDocument` so it runs
 * synchronously in every new document *before* any page script. `CefBrowser.executeJavaScript`
 * from `onLoadStart` is queued to the renderer and can lose the race with the SPA bundle;
 * that race is what lets OpenCode capture the unwrapped `fetch` and freeze the composer.
 *
 * Falls back silently: callers still inject from `onLoadStart`.
 */
internal class OpenCodeDocumentStartInjector(
    private val browser: JBCefBrowser,
    private val client: () -> CefDevToolsClient? = {
        runCatching { browser.cefBrowser.devToolsClient }.getOrNull()
    },
) {
    private val lock = Any()
    private val documentReady = CompletableFuture<Unit>()
    private var bootstrapStarted = false
    private var requestedGeneration = 0L
    private var operationTail = CompletableFuture.completedFuture(Unit)
    private var installedIdentifier: String? = null
    private var installedSource: String? = null

    init {
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true && cefBrowser?.hasDocument() == true) {
                        documentReady.complete(Unit)
                    }
                }
            },
            browser.cefBrowser,
        )
        if (browser.cefBrowser.hasDocument()) documentReady.complete(Unit)
    }

    fun installAndWait(script: String, timeoutMillis: Long = 3_000): Boolean {
        return runCatching { installAsync(script).get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    fun installAsync(script: String): CompletableFuture<Boolean> {
        val source = script.trim()
        val generation = synchronized(lock) { ++requestedGeneration }
        ensureBootstrapDocument()
        return documentReady.thenCompose {
            val devTools = client() ?: return@thenCompose CompletableFuture.completedFuture(false)
            synchronized(lock) {
                val predecessor = operationTail.handle { _, _ -> }
                val operation = predecessor.thenCompose { replaceScript(devTools, source, generation) }
                operationTail = operation.handle { _, _ -> }
                operation
            }
        }
    }

    fun hasInstalledScript(): Boolean = synchronized(lock) {
        installedIdentifier != null
    }

    /** A newly-created empty JCEF browser has no renderer/DevTools target yet. */
    private fun ensureBootstrapDocument() {
        if (documentReady.isDone) return
        val shouldLoad = synchronized(lock) {
            if (documentReady.isDone || bootstrapStarted) {
                false
            } else if (browser.cefBrowser.hasDocument()) {
                documentReady.complete(Unit)
                false
            } else {
                bootstrapStarted = true
                true
            }
        }
        if (shouldLoad) browser.loadURL("about:blank")
    }

    private fun replaceScript(
        devTools: CefDevToolsClient,
        source: String,
        generation: Long,
    ): CompletableFuture<Boolean> {
        val previous = synchronized(lock) {
            if (generation != requestedGeneration) return CompletableFuture.completedFuture(false)
            if (installedIdentifier != null && installedSource == source) {
                return CompletableFuture.completedFuture(true)
            }
            installedIdentifier
        }
        val removed = if (previous.isNullOrBlank()) {
            CompletableFuture.completedFuture(true)
        } else {
            removeScript(devTools, previous)
        }
        return removed.thenCompose { removalSucceeded ->
            if (!removalSucceeded) return@thenCompose CompletableFuture.completedFuture(false)
            if (previous != null) {
                synchronized(lock) {
                    if (installedIdentifier == previous) {
                        installedIdentifier = null
                        installedSource = null
                    }
                }
            }
            if (!isCurrent(generation)) return@thenCompose CompletableFuture.completedFuture(false)
            if (source.isEmpty()) return@thenCompose CompletableFuture.completedFuture(true)
            try {
                devTools.executeDevToolsMethod("Page.enable").thenCompose {
                    devTools.executeDevToolsMethod(ADD_METHOD, sourcePayload(source))
                }.thenCompose { response ->
                    val identifier = parseIdentifier(response)
                        ?: return@thenCompose CompletableFuture.completedFuture(false)
                    val accepted = synchronized(lock) {
                        if (generation != requestedGeneration) {
                            false
                        } else {
                            installedIdentifier = identifier
                            installedSource = source
                            true
                        }
                    }
                    if (accepted) {
                        CompletableFuture.completedFuture(true)
                    } else {
                        removeScript(devTools, identifier).thenApply { removedStale ->
                            if (!removedStale) {
                                synchronized(lock) {
                                    if (installedIdentifier == null) {
                                        installedIdentifier = identifier
                                        installedSource = source
                                    }
                                }
                            }
                            false
                        }
                    }
                }.exceptionally { error ->
                    thisLogger().info("Could not register OpenCode document-start script: ${error.message}")
                    false
                }
            } catch (e: Exception) {
                thisLogger().info("Could not register OpenCode document-start script: ${e.message}")
                CompletableFuture.completedFuture(false)
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        generation == requestedGeneration
    }

    private fun removeScript(devTools: CefDevToolsClient, identifier: String): CompletableFuture<Boolean> {
        return try {
            devTools.executeDevToolsMethod(REMOVE_METHOD, identifierPayload(identifier)).handle { _, error ->
                if (error != null) {
                    thisLogger().info("Could not replace OpenCode document-start script: ${error.message}")
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            thisLogger().info("Could not replace OpenCode document-start script: ${e.message}")
            CompletableFuture.completedFuture(false)
        }
    }

    companion object {
        const val ADD_METHOD = "Page.addScriptToEvaluateOnNewDocument"
        const val REMOVE_METHOD = "Page.removeScriptToEvaluateOnNewDocument"

        fun sourcePayload(script: String): String {
            return com.google.gson.JsonObject().apply { addProperty("source", script) }.toString()
        }

        fun identifierPayload(identifier: String): String {
            return com.google.gson.JsonObject().apply { addProperty("identifier", identifier) }.toString()
        }

        fun guardForOrigin(script: String, origin: String): String {
            val source = script.trim()
            if (source.isEmpty()) return ""
            val originLiteral = JsonPrimitive(origin.trimEnd('/')).toString()
            return """
                (() => {
                  if (location.origin !== $originLiteral) return;
                  $source
                })();
            """.trimIndent()
        }

        fun parseIdentifier(response: String?): String? {
            val text = response?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val root = runCatching { JsonParser.parseString(text) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val identifier = root.get("identifier")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("identifier")?.takeIf { it.isJsonPrimitive }?.asString
            return identifier?.takeIf { it.isNotBlank() }
        }
    }
}
