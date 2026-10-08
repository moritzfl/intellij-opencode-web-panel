package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.ui.LafManager
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptScheduler
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.browser.OpenCodeJsQuery
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeWireProtocol
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeUiSetting

/**
 * Per-document injection configuration, gates and scheduling. Browser ownership stays in the panel.
 */
internal class OpenCodePanelInjections(
    private val browser: JBCefBrowser,
    private val serverManager: OpenCodeServerBackend,
    private val scriptScheduler: OpenCodeBrowserScriptScheduler,
    private val openCodeServerDirectory: () -> String?,
    private val query: (OpenCodeUiSetting) -> OpenCodeJsQuery,
    private val resetMirroredBrowserCursor: () -> Unit,
    private val reloadPage: () -> Unit,
) {
    /**
     * A UI-behavior enhancement injected into the OpenCode page as JavaScript. Instances bundle the
     * setting gate, the script builder, and the per-page-load "already scheduled" flag so
     * scheduling and setting toggles can be handled generically for every feature.
     */
    class InjectedFeature(
        val enabledInSettings: () -> Boolean,
        val buildScript: () -> String?,
        /** Extra cleanup before the page reload that removes a disabled feature. */
        val onDisable: () -> Unit = {},
    ) {
        var scheduled = false
    }

    /**
     * A UI-behavior enhancement that must run before the SPA bundle executes. Registered with
     * Chromium document-start before navigation, then injected again from `onLoadStart` (and
     * retried on the early delay series) in case document-start was unavailable. Builders are
     * re-invoked on every attempt so scripts can embed current state (e.g. the IDE theme); they
     * must be idempotent in-page. Instances share one scheduling routine ([injectEarlyFeature]) and
     * one reset point so per-feature flag drift is impossible.
     */
    class EarlyInjectedFeature(
        val enabledInSettings: () -> Boolean = { true },
        val buildScript: (serverUrl: String) -> String?,
    ) {
        var scheduled = false
    }

    private val openProjectSeedFeature =
        EarlyInjectedFeature(
            buildScript = { serverUrl ->
                openCodeServerDirectory()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { projectDirectory ->
                        // Bind this panel's directory before the SPA reads shared localStorage.
                        // OpenCode owns session selection; this seed never navigates or overwrites
                        // its lastProjectSession pointer.
                        OpenCodeBrowserSnippets.buildOpenProjectScript(
                            projectDirectory,
                            serverUrl,
                        )
                    }
            }
        )
    val matchMediaPatchFeature =
        EarlyInjectedFeature(
            enabledInSettings = {
                val settings = OpenCodeSettingsState.getInstance()
                settings.syncThemeWithIde || settings.forceCompactLayout
            },
            buildScript = {
                val settings = OpenCodeSettingsState.getInstance()
                OpenCodeBrowserSnippets.buildMatchMediaPatchScript(
                    compact = settings.forceCompactLayout,
                    theme = settings.syncThemeWithIde,
                    dark = isIdeDarkTheme(),
                )
            },
        )
    private val compactHomeLayoutFeature =
        EarlyInjectedFeature(
            enabledInSettings = { OpenCodeSettingsState.getInstance().forceCompactLayout },
            buildScript = { OpenCodeBrowserSnippets.buildCompactHomeLayoutScript(enabled = true) },
        )
    val hideWebsiteButtonFeature =
        EarlyInjectedFeature(
            enabledInSettings = { OpenCodeSettingsState.getInstance().hideWebsiteButton },
            buildScript = { OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = true) },
        )
    val pathHoverPreviewFeature =
        EarlyInjectedFeature(
            enabledInSettings = { OpenCodeSettingsState.getInstance().fasterPathHoverPreview },
            buildScript = { OpenCodeBrowserSnippets.buildPathHoverPreviewScript(enabled = true) },
        )
    val eventStreamWatchdogFeature =
        EarlyInjectedFeature(
            enabledInSettings = {
                OpenCodeSettingsState.getInstance().recoverStalledEventStream &&
                    serverManager.getWireProtocol() != OpenCodeWireProtocol.V2_CLI
            },
            buildScript = {
                OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(
                    enabled = true,
                    wireProtocol = serverManager.getWireProtocol(),
                )
            },
        )
    val chunkLoadRecoveryFeature =
        EarlyInjectedFeature(
            enabledInSettings = { OpenCodeSettingsState.getInstance().recoverFailedChunkLoads },
            buildScript = {
                // Signal-only callback: the message it carries is diagnostic, the JVM side just
                // marks that this document raised a chunk failure and decides on reload.
                OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(
                    enabled = true,
                    fatalCallback = query(OpenCodeUiSetting.CHUNK_LOAD_RECOVERY).inject("message"),
                )
            },
        )

    /** Injection order matters: the project seed must precede everything else. */
    val earlyInjectedFeatures =
        listOf(
            openProjectSeedFeature,
            matchMediaPatchFeature,
            compactHomeLayoutFeature,
            hideWebsiteButtonFeature,
            pathHoverPreviewFeature,
            eventStreamWatchdogFeature,
            chunkLoadRecoveryFeature,
        )

    val fileLinkFeature =
        InjectedFeature(
            enabledInSettings = { OpenCodeSettingsState.getInstance().openFileLinksInIde },
            buildScript = {
                OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
                    openCodeServerDirectory(),
                    enabled = true,
                    openFileCallback =
                        query(OpenCodeUiSetting.FILE_LINK_NAVIGATION)
                            .inject("rawHref + '\\n' + directory + '\\n' + partID"),
                )
            },
        )
    val externalLinkFeature =
        InjectedFeature(
            enabledInSettings = { OpenCodeSettingsState.getInstance().openExternalLinksInBrowser },
            buildScript = {
                OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(
                    enabled = true,
                    openExternalCallback =
                        query(OpenCodeUiSetting.EXTERNAL_LINK_NAVIGATION).inject("href"),
                )
            },
        )
    val codeNavigationFeature =
        InjectedFeature(
            enabledInSettings = {
                OpenCodeSettingsState.getInstance().effectiveCodeNavigationEnabled()
            },
            buildScript = {
                OpenCodeBrowserSnippets.buildCodeNavigationScript(
                    enabled = true,
                    openCodeCallback = query(OpenCodeUiSetting.CODE_NAVIGATION).inject("ref"),
                )
            },
        )
    val diffNavigationFeature =
        InjectedFeature(
            enabledInSettings = { OpenCodeSettingsState.getInstance().openDiffsInIde },
            buildScript = {
                OpenCodeBrowserSnippets.buildDiffNavigationScript(
                    enabled = true,
                    openDiffCallback =
                        query(OpenCodeUiSetting.DIFF_NAVIGATION)
                            .inject(
                                "messageID + '\\n' + filePath + '\\n' + partID + '\\n' + (typeof vcsMode === 'string' ? vcsMode : '')"
                            ),
                )
            },
        )
    val projectSwitchPromptSuppressionFeature =
        InjectedFeature(
            enabledInSettings = {
                OpenCodeSettingsState.getInstance().suppressProjectSwitchPrompts
            },
            buildScript = {
                OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = true)
            },
        )
    val cursorMirrorFeature =
        InjectedFeature(
            enabledInSettings = { OpenCodeSettingsState.getInstance().mirrorBrowserCursor },
            buildScript = {
                OpenCodeBrowserSnippets.buildCursorMirrorScript(
                    enabled = true,
                    cursorCallback =
                        query(OpenCodeUiSetting.BROWSER_CURSOR_MIRROR).inject("payload"),
                )
            },
            onDisable = { resetMirroredBrowserCursor() },
        )
    val rendererHeartbeatFeature =
        InjectedFeature(
            enabledInSettings = {
                OpenCodeSettingsState.getInstance().recoverStalledRenderer &&
                    query(OpenCodeUiSetting.RENDERER_WATCHDOG).isAvailable
            },
            buildScript = {
                OpenCodeBrowserSnippets.buildRendererHeartbeatScript(
                    enabled = true,
                    heartbeatCallback =
                        query(OpenCodeUiSetting.RENDERER_WATCHDOG).inject("visibility"),
                )
            },
        )
    val injectedFeatures =
        listOf(
            diffNavigationFeature,
            fileLinkFeature,
            externalLinkFeature,
            codeNavigationFeature,
            projectSwitchPromptSuppressionFeature,
            cursorMirrorFeature,
            rendererHeartbeatFeature,
        )

    /**
     * Injects [feature] from `onLoadStart`: executes the script immediately (before the SPA bundle
     * runs) and retries on the early delay series in case the first attempt ran before the new
     * document's V8 context was ready. The builder is re-invoked per attempt so it always reflects
     * current IDE state. The open-project seed must be first: post-load injects alone race the
     * SPA's first read of the shared browser profile and can leave the panel bound to another IDE
     * project's workspace.
     */
    fun injectEarlyFeature(feature: EarlyInjectedFeature) {
        if (feature.scheduled) return
        if (!feature.enabledInSettings()) return
        val serverUrl = serverManager.getServerUrl() ?: return
        val script = feature.buildScript(serverUrl) ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        feature.scheduled = true
        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        scriptScheduler.scheduleAction(
            early = true,
            shouldRun = { feature.enabledInSettings() && isBrowserOnOpenCodeServerPage(serverUrl) },
        ) {
            feature.buildScript(serverUrl)?.let {
                browser.cefBrowser.executeJavaScript(it, rootUrl, 0)
            }
        }
    }

    /** Schedules [feature]'s script for retried injection into the current page load. */
    fun scheduleFeatureScript(feature: InjectedFeature) {
        if (feature.scheduled) return
        if (!feature.enabledInSettings()) return

        val serverUrl = serverManager.getServerUrl() ?: return
        val script = feature.buildScript() ?: return
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        feature.scheduled = true

        // These scripts install document-level listeners and carry their own idempotence guards;
        // no DOM target needs to exist first. Install immediately so a fast first click/paste after
        // load cannot slip through, then retain retries for SPA/browser timing resilience.
        browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        scriptScheduler.schedule(script, rootUrl) {
            feature.enabledInSettings() && isBrowserOnOpenCodeServerPage(serverUrl)
        }
    }

    /**
     * Applies a runtime toggle of [feature]: injects the script when enabled, or reloads the page
     * when disabled so previously installed listeners and patches are fully removed (per the
     * safeguard contract, never a "disable" script).
     */
    fun applyFeature(feature: InjectedFeature, enabled: Boolean) {
        val serverUrl = serverManager.getServerUrl() ?: return
        val decision =
            OpenCodeInjectedFeaturePolicy.decide(
                enabled = enabled,
                enabledInSettings = feature.enabledInSettings(),
                onOpenCodePage = isBrowserOnOpenCodeServerPage(serverUrl),
                script =
                    if (enabled && feature.enabledInSettings()) feature.buildScript() else null,
            )
        if (decision.clearScheduled) feature.scheduled = false
        when (decision.action) {
            OpenCodeInjectedFeaturePolicy.Action.NONE -> return
            OpenCodeInjectedFeaturePolicy.Action.RELOAD -> {
                feature.onDisable()
                reloadPage()
            }
            OpenCodeInjectedFeaturePolicy.Action.INJECT -> {
                val script = decision.script ?: return
                browser.cefBrowser.executeJavaScript(
                    script,
                    OpenCodeServerProtocol.buildServerRootUrl(serverUrl),
                    0,
                )
                if (decision.markScheduled) feature.scheduled = true
            }
        }
    }

    fun scheduleIdeThemeSyncScript() {
        if (!matchMediaPatchFeature.enabledInSettings()) return
        val serverUrl = serverManager.getServerUrl() ?: return
        scriptScheduler.scheduleAction(
            shouldRun = {
                matchMediaPatchFeature.enabledInSettings() &&
                    isBrowserOnOpenCodeServerPage(serverUrl)
            },
            action = { executeIdeThemeSyncScript(serverUrl) },
        )
    }

    fun executeIdeThemeSyncScript(serverUrl: String): Boolean {
        val settings = OpenCodeSettingsState.getInstance()
        val script =
            OpenCodeBrowserSnippets.buildMatchMediaPatchScript(
                compact = settings.forceCompactLayout,
                theme = settings.syncThemeWithIde,
                dark = isIdeDarkTheme(),
            ) ?: return false
        browser.cefBrowser.executeJavaScript(
            script,
            OpenCodeServerProtocol.buildServerRootUrl(serverUrl),
            0,
        )
        return true
    }

    private fun isIdeDarkTheme(): Boolean {
        return LafManager.getInstance().currentUIThemeLookAndFeel?.isDark == true
    }

    fun documentStartScript(serverUrl: String): String = buildString {
        earlyInjectedFeatures.forEach { feature ->
            if (feature.enabledInSettings()) {
                feature
                    .buildScript(serverUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(it)
                        append('\n')
                    }
            }
        }
    }

    fun resetScheduled() {
        injectedFeatures.forEach { it.scheduled = false }
        earlyInjectedFeatures.forEach { it.scheduled = false }
    }

    private fun isBrowserOnOpenCodeServerPage(serverUrl: String): Boolean =
        OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, browser.cefBrowser.url)
}
