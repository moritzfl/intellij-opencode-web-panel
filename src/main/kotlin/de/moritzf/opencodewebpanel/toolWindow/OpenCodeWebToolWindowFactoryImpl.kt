package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import javax.swing.JComponent
import kotlin.jvm.JvmDefaultWithoutCompatibility

internal fun openCodeToolWindowHeading(project: Project?): String {
    val native = OpenCodeServerBackend.isNative(
        OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId,
    )
    return if (native) "OpenCode (native CLI)" else "OpenCode (sbx)"
}

internal fun updateOpenCodeToolWindowHeading(toolWindow: ToolWindow) {
    if (toolWindow.isDisposed) return
    toolWindow.stripeTitle = openCodeToolWindowHeading(toolWindow.project)
}

@JvmDefaultWithoutCompatibility
class OpenCodeWebToolWindowFactoryImpl : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Build content + disposer on this call (typically EDT) so a fast project close cannot
        // orphan JCEF before an invokeLater runs. Only the initial server/page load is deferred.
        val toolWindowContent = installOpenCodeToolWindowContent(toolWindow)
        updateOpenCodeToolWindowHeading(toolWindow)
        installTitleActions(toolWindow)
        if (toolWindowContent == null) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || toolWindow.isDisposed || project != toolWindow.project) {
                return@invokeLater
            }
            toolWindowContent.checkAndLoadContent()
        }
    }

    private fun installTitleActions(toolWindow: ToolWindow) {
        // Icon-only actions in the existing title bar; IntelliJ clips them on narrow panels,
        // so the gear menu below duplicates everything.
        toolWindow.setTitleActions(
            listOf(
                OpenCodeZoomOutAction(),
                OpenCodeZoomInAction(),
                OpenCodeReloadPageAction(),
                OpenCodeRestartServerAction(),
            ),
        )
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup().apply {
                add(OpenCodeNewSessionAction())
                addSeparator()
                add(OpenCodeZoomOutAction())
                add(OpenCodeZoomInAction())
                add(OpenCodeResetZoomAction())
                addSeparator()
                add(OpenCodeAutoAcceptPermissionsAction())
                addSeparator()
                add(OpenCodeReloadPageAction())
                add(OpenCodeRestartServerAction())
                add(OpenCodeStopServerAction())
                addSeparator()
                add(OpenCodeResetWebStateAction())
                add(OpenCodeResetSandboxAction())
                add(OpenCodeUpgradeSandboxBinaryAction())
                add(OpenCodeUpdateSandboxImageAction())
                add(OpenCodeOpenDevToolsAction())
                add(OpenCodeViewServerLogAction())
                addSeparator()
                add(OpenCodeOpenProjectSettingsAction())
                add(OpenCodeOpenSettingsAction())
                add(OpenCodeOpenKeymapAction())
            },
        )
    }
}

/**
 * Installs a fresh panel, or the failure card when JCEF refuses to create one. Never throws:
 * an exception here would leave the tool window without content until the IDE restarts, because
 * [ToolWindowFactory.createToolWindowContent] runs once per tool window.
 */
internal fun installOpenCodeToolWindowContent(
    toolWindow: ToolWindow,
    sessionIdToRestore: String? = null,
): OpenCodeWebToolWindowContent? {
    val toolWindowContent = createOpenCodeToolWindowContent(toolWindow, sessionIdToRestore)
    if (toolWindowContent == null) {
        installOpenCodePanelFailureCard(toolWindow)
        return null
    }
    addOpenCodeToolWindowContent(toolWindow, toolWindowContent.getContent(), toolWindowContent)
    return toolWindowContent
}

/**
 * Recovery hammer for a stuck or crashed JCEF panel: install a fresh browser and dispose the
 * current one. Keeps the session id from the previous URL when it is still readable.
 * Stop→Start must not use this — that path keeps the existing document (Windows).
 *
 * The replacement's remote browser is created *before* the current content is dropped. Disposing first tears the
 * old browser down inside the CEF server while the new browser and its message routers are being
 * created, which is exactly when out-of-process JCEF answers with no remote object
 * (`RemoteMessageRouterImpl` NPE) — and a failure at that point used to leave the tool window
 * permanently empty.
 */
internal fun replaceOpenCodeToolWindowContent(toolWindow: ToolWindow) {
    if (toolWindow.isDisposed || toolWindow.project.isDisposed) return
    val application = ApplicationManager.getApplication()
    if (application == null || application.isDisposed) return
    if (toolWindow.component.getClientProperty(OPEN_CODE_REPLACEMENT_PENDING_KEY) == true) return
    val manager = toolWindow.contentManager
    val previous = manager.contents.firstNotNullOfOrNull { it.disposer as? OpenCodeWebToolWindowContent }
    val liveBackendId = OpenCodeServerBackendRegistry.getInstance().backendFor(toolWindow.project).backendId
    val sessionId = previous
        ?.takeIf { it.backendId() == liveBackendId }
        ?.let { runCatching { it.displayedSessionID() }.getOrNull() }
    Logger.getInstance(OpenCodeWebToolWindowContent::class.java)
        .info("jcef replace previous=${previous != null} backend=$liveBackendId")
    val replacement = createOpenCodeToolWindowContent(toolWindow, sessionId)
    if (replacement == null) {
        Logger.getInstance(OpenCodeWebToolWindowContent::class.java)
            .warn("jcef replace constructor failed")
        // Keep whatever is on screen: a working panel is better than a failure card, and a
        // failure card that is already installed still offers Retry.
        val hasPanel = manager.contents.any { it.disposer is OpenCodeWebToolWindowContent }
        if (!hasPanel && !hasOpenCodePanelFailureCard(manager)) installOpenCodePanelFailureCard(toolWindow)
        return
    }
    // Keep the old remote browser alive until Chromium acknowledges its successor.
    toolWindow.component.putClientProperty(OPEN_CODE_REPLACEMENT_PENDING_KEY, true)
    replacement.prepareBrowserForReplacement().whenComplete { _, error ->
        application.invokeLater {
            toolWindow.component.putClientProperty(OPEN_CODE_REPLACEMENT_PENDING_KEY, null)
            if (toolWindow.isDisposed || toolWindow.project.isDisposed) {
                Disposer.dispose(replacement)
                return@invokeLater
            }
            if (error != null) {
                Logger.getInstance(OpenCodeWebToolWindowContent::class.java)
                    .warn("jcef replace renderer wait failed; keeping current panel", error)
                Disposer.dispose(replacement)
                return@invokeLater
            }
            if (OpenCodeServerBackendRegistry.getInstance().backendFor(toolWindow.project).backendId != liveBackendId) {
                Disposer.dispose(replacement)
                replaceOpenCodeToolWindowContent(toolWindow)
                return@invokeLater
            }
            manager.removeAllContents(true)
            addOpenCodeToolWindowContent(toolWindow, replacement.getContent(), replacement)
            replacement.checkAndLoadContent()
        }
    }
}

private fun createOpenCodeToolWindowContent(
    toolWindow: ToolWindow,
    sessionIdToRestore: String?,
): OpenCodeWebToolWindowContent? {
    return try {
        OpenCodeWebToolWindowContent(toolWindow, sessionIdToRestore)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        // Logged as a warning on purpose: JCEF failures here are recoverable through the card's
        // Retry, and reporting them as IDE errors only hides the recovery behind a crash dialog.
        Logger.getInstance(OpenCodeWebToolWindowContent::class.java)
            .warn("Could not create the OpenCode panel; showing the recovery card", e)
        null
    }
}

internal fun installOpenCodePanelFailureCard(toolWindow: ToolWindow) {
    if (toolWindow.isDisposed || toolWindow.project.isDisposed) return
    val manager = toolWindow.contentManager
    manager.removeAllContents(true)
    val card = OpenCodePanelFailureCard {
        OpenCodeRendererWatchdog.resetProcessRecreatesAfterStall()
        replaceOpenCodeToolWindowContent(toolWindow)
    }
    val content = addOpenCodeToolWindowContent(toolWindow, card.component, disposer = null)
    content.putUserData(OPEN_CODE_PANEL_FAILURE_CARD_KEY, true)
}

private fun hasOpenCodePanelFailureCard(manager: ContentManager): Boolean {
    return manager.contents.any { it.getUserData(OPEN_CODE_PANEL_FAILURE_CARD_KEY) == true }
}

private fun addOpenCodeToolWindowContent(
    toolWindow: ToolWindow,
    component: JComponent,
    disposer: Disposable?,
): Content {
    val content = ContentFactory.getInstance().createContent(component, null, false)
    disposer?.let(content::setDisposer)
    toolWindow.contentManager.addContent(content)
    updateOpenCodeToolWindowHeading(toolWindow)
    return content
}

private val OPEN_CODE_PANEL_FAILURE_CARD_KEY = Key.create<Boolean>("opencode.panel.failure.card")
private val OPEN_CODE_REPLACEMENT_PENDING_KEY = Key.create<Boolean>("opencode.panel.replacement.pending")
