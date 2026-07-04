package de.moritzf.opencodewebpanel.features

import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState

internal class OpenCodeLocalStorageBridge(
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val syncCallback: () -> String,
) {
    fun restore(frameUrl: String?) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) return
        val snapshot = OpenCodeSettingsState.getInstance().openCodeLocalStorageSnapshot
        val script = OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(snapshot) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
    }

    fun installSync(frameUrl: String?) {
        val serverUrl = serverManager.getServerUrl() ?: return
        if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, frameUrl)) return
        val script = OpenCodeBrowserSnippets.buildSyncOpenCodeLocalStorageScript(syncCallback()) ?: return
        browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
    }

    fun sync(snapshot: String?) {
        val text = snapshot?.trim() ?: return
        val sanitized = OpenCodeSettingsState.sanitizeOpenCodeLocalStorageSnapshot(text)
        if (sanitized == "{}" && text != "{}") return
        val settings = OpenCodeSettingsState.getInstance()
        if (settings.openCodeLocalStorageSnapshot != sanitized) {
            settings.openCodeLocalStorageSnapshot = sanitized
        }
    }
}
