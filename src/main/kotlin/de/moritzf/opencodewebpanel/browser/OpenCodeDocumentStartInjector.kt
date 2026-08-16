package de.moritzf.opencodewebpanel.browser

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefDevToolsClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    private val installGeneration = AtomicLong()
    private val installedIdentifier = AtomicReference<String?>(null)

    fun install(script: String) {
        installAsync(script)
    }

    fun installAndWait(script: String, timeoutMillis: Long = 3_000): Boolean {
        return runCatching { installAsync(script).get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    fun installAsync(script: String): CompletableFuture<Boolean> {
        val source = script.trim()
        if (source.isEmpty()) return CompletableFuture.completedFuture(false)
        val devTools = client() ?: return CompletableFuture.completedFuture(false)
        val generation = installGeneration.incrementAndGet()
        val previous = installedIdentifier.getAndSet(null)
        if (!previous.isNullOrBlank()) {
            removeScript(devTools, previous)
        }
        return try {
            devTools.executeDevToolsMethod("Page.enable").thenCompose {
                devTools.executeDevToolsMethod(ADD_METHOD, sourcePayload(source))
            }.handle { response, error ->
                if (error != null) {
                    thisLogger().info("Could not register OpenCode document-start script: ${error.message}")
                    return@handle false
                }
                val identifier = parseIdentifier(response)
                if (identifier.isNullOrBlank()) return@handle false
                if (installGeneration.get() != generation) {
                    removeScript(devTools, identifier)
                    return@handle false
                }
                installedIdentifier.set(identifier)
                true
            }
        } catch (e: Exception) {
            thisLogger().info("Could not register OpenCode document-start script: ${e.message}")
            CompletableFuture.completedFuture(false)
        }
    }

    private fun removeScript(devTools: CefDevToolsClient, identifier: String) {
        try {
            devTools.executeDevToolsMethod(REMOVE_METHOD, identifierPayload(identifier))
        } catch (e: Exception) {
            thisLogger().info("Could not replace OpenCode document-start script: ${e.message}")
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
