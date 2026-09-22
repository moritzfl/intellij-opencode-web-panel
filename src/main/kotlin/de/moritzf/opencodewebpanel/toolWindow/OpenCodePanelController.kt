package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.serviceContainer.NonInjectable
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.features.OpenCodeAgentStatusState
import de.moritzf.opencodewebpanel.features.OpenCodeChatInputService
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import java.awt.Frame
import java.awt.event.HierarchyEvent
import java.util.concurrent.CompletableFuture
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal interface OpenCodePanel : Disposable {
    val component: JComponent
    val preferredFocus: JComponent
    fun prepareBrowserForReplacement(): CompletableFuture<Unit>
    fun checkAndLoadContent()
    fun dispatchChatBatch(delivery: OpenCodeChatInputService.Delivery): Boolean
    fun onHostChanged()
}

/** Owns the browser independently of its Swing host. UI mutations run on the EDT. */
@Service(Service.Level.PROJECT)
internal class OpenCodePanelController @NonInjectable internal constructor(
    val project: Project,
    private val panelFactory: (OpenCodePanelController) -> OpenCodePanel,
) : Disposable {
    constructor(project: Project) : this(project, ::OpenCodeWebToolWindowContent)

    val component = BorderLayoutPanel()
    val toolWindowComponent = BorderLayoutPanel().apply { addToCenter(component) }
    internal var editorFile: OpenCodeEditorFile? = null
        private set
    private var editorShell: BorderLayoutPanel? = null
    var isInEditor: Boolean = false
        private set
    @Volatile private var panel: OpenCodePanel? = null
    private var pendingReplacement: OpenCodePanel? = null
    private var toolWindow: ToolWindow? = null
    @Volatile private var disposed = false
    val isDisposed: Boolean get() = disposed || project.isDisposed

    init {
        component.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && component.isShowing) {
                panel?.onHostChanged()
            }
        }
        // One dispatcher for the project's lifetime. Constructing a replacement must not steal
        // delivery, and disposing its predecessor must not unregister the successor.
        OpenCodeChatInputService.getInstance(project).apply {
            setDispatcher { delivery -> panel?.dispatchChatBatch(delivery) == true }
            setActivator { activate {} }
        }
    }

    fun content(): OpenCodeWebToolWindowContent? = panel as? OpenCodeWebToolWindowContent

    fun preferredFocus(): JComponent = panel?.preferredFocus ?: component

    fun moveToEditor() {
        if (isDisposed) return
        if (isInEditor) {
            activate {}
            return
        }
        // Keep one file identity even after close: Reopen Closed Tab must borrow this same
        // panel, and FORBID_TAB_SPLIT must apply across every later open.
        val file = editorFile ?: OpenCodeEditorFile(this).also { editorFile = it }
        isInEditor = true
        try {
            val manager = FileEditorManager.getInstance(project)
            manager.openFile(file, true)
            if (manager.isFileOpen(file)) {
                toolWindow?.hide(null)
                focusPanel {}
            } else {
                moveToToolWindow()
            }
        } catch (e: ProcessCanceledException) {
            moveToToolWindow()
            throw e
        } catch (e: Throwable) {
            moveToToolWindow()
            LOG.warn("Could not open the OpenCode editor; keeping the tool window", e)
        }
    }

    fun moveToToolWindow() {
        if (isDisposed) return
        val file = editorFile
        isInEditor = false
        editorShell = null
        mountInToolWindow()
        if (file != null) FileEditorManager.getInstance(project).closeFile(file)
        activate {}
    }

    internal fun attachEditor(file: OpenCodeEditorFile, shell: BorderLayoutPanel) {
        if (isDisposed || editorFile !== file) return
        isInEditor = true
        editorShell = shell
        moveComponent(shell)
        toolWindowComponent.removeAll()
        toolWindowComponent.addToCenter(JBPanelWithEmptyText().apply {
            emptyText.appendLine("OpenCode is open in the editor.")
            emptyText.appendLine("Show in Editor", SimpleTextAttributes.LINK_ATTRIBUTES) { activate {} }
            emptyText.appendLine("Move to Tool Window", SimpleTextAttributes.LINK_ATTRIBUTES) { moveToToolWindow() }
        })
        toolWindowComponent.revalidate()
        toolWindowComponent.repaint()
    }

    internal fun editorClosed(file: OpenCodeEditorFile, shell: BorderLayoutPanel) {
        if (isDisposed || editorFile !== file || !isInEditor) return
        if (editorShell === shell) {
            editorShell = null
            mountInToolWindow()
        }
        // Moving a singleton tab closes its old wrapper before opening the new one. Only a
        // final close changes placement; neither kind of close disposes the shared browser.
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed && editorFile === file && !FileEditorManager.getInstance(project).isFileOpen(file)) {
                isInEditor = false
            }
        }
    }

    private fun mountInToolWindow() {
        if (component.parent === toolWindowComponent) return
        toolWindowComponent.removeAll()
        moveComponent(toolWindowComponent)
    }

    private fun moveComponent(target: BorderLayoutPanel) {
        if (component.parent === target) return
        val previous = component.parent
        previous?.remove(component)
        target.addToCenter(component)
        previous?.revalidate()
        previous?.repaint()
        target.revalidate()
        target.repaint()
        panel?.onHostChanged()
    }

    fun attachToolWindow(window: ToolWindow) {
        toolWindow = window
        ensurePanel()
    }

    internal fun ensurePanel() {
        if (isDisposed || panel != null || component.componentCount > 0) return
        val created = createPanel()
        if (created == null) {
            showFailure()
            return
        }
        install(created)
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed && panel === created) created.checkAndLoadContent()
        }
    }

    fun isCurrent(candidate: OpenCodePanel): Boolean = panel === candidate

    fun isPanelInView(): Boolean = !isDisposed && component.isShowing &&
        SwingUtilities.getWindowAncestor(component)?.isActive == true

    fun activate(action: () -> Unit) {
        if (isDisposed) return
        val file = editorFile.takeIf { isInEditor }
        if (file != null) {
            FileEditorManager.getInstance(project).openFile(file, true, true)
            focusPanel(action)
        } else {
            toolWindow?.activate({ focusPanel(action) }, true)
        }
    }

    private fun focusPanel(action: () -> Unit) {
        if (isDisposed) return
        val window = SwingUtilities.getWindowAncestor(component)
        if (window is Frame) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
        window?.toFront()
        action()
        panel?.preferredFocus?.requestFocusInWindow()
        panel?.onHostChanged()
    }

    fun updateHeading() {
        toolWindow?.takeUnless { it.isDisposed }?.let(::updateOpenCodeToolWindowHeading)
    }

    fun updateAgentStatus(state: String, icons: BadgeIconSupplier) {
        toolWindow?.takeUnless { it.isDisposed }?.setIcon(when (state) {
            OpenCodeAgentStatusState.ATTENTION -> icons.warningIcon
            OpenCodeAgentStatusState.BUSY -> icons.liveIndicatorIcon
            else -> icons.originalIcon
        })
    }

    /** Keep the predecessor until Chromium acknowledges the successor (especially OOP JCEF). */
    fun replacePanel() {
        if (isDisposed || pendingReplacement != null) return
        val backendId = OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId
        val replacement = createPanel() ?: return
        pendingReplacement = replacement
        val readiness = runCatching { replacement.prepareBrowserForReplacement() }
            .getOrElse { CompletableFuture.failedFuture(it) }
        readiness.whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater {
                // Disposal can already have released this browser while its acknowledgement was in flight.
                if (pendingReplacement !== replacement) return@invokeLater
                pendingReplacement = null
                if (isDisposed || error != null) {
                    if (error != null) LOG.warn("JCEF replacement did not become ready; keeping current panel", error)
                    Disposer.dispose(replacement)
                    return@invokeLater
                }
                if (OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId != backendId) {
                    Disposer.dispose(replacement)
                    replacePanel()
                    return@invokeLater
                }
                val previous = panel
                OpenCodeChatInputService.getInstance(project).requeueInFlight()
                install(replacement)
                previous?.let(Disposer::dispose)
                replacement.checkAndLoadContent()
            }
        }
    }

    fun showFailure() {
        if (isDisposed || pendingReplacement != null) return
        val previous = panel
        panel = null
        OpenCodeChatInputService.getInstance(project).requeueInFlight()
        previous?.let(Disposer::dispose)
        component.removeAll()
        component.addToCenter(OpenCodePanelFailureCard {
            OpenCodeRendererWatchdog.resetProcessRecreatesAfterStall()
            replacePanel()
        }.component)
        component.revalidate()
        component.repaint()
    }

    private fun createPanel(): OpenCodePanel? = try {
        panelFactory(this)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn("Could not create the OpenCode panel", e)
        null
    }

    private fun install(created: OpenCodePanel) {
        panel = created
        component.removeAll()
        component.addToCenter(created.component)
        component.revalidate()
        component.repaint()
        updateHeading()
    }

    override fun dispose() {
        disposed = true
        if (!project.isDisposed) editorFile?.let { project.getServiceIfCreated(FileEditorManager::class.java)?.closeFile(it) }
        editorFile = null
        isInEditor = false
        editorShell = null
        if (!project.isDisposed) OpenCodeChatInputService.getInstance(project).apply {
            setDispatcher(null)
            setActivator(null)
        }
        pendingReplacement?.let(Disposer::dispose)
        pendingReplacement = null
        panel?.let(Disposer::dispose)
        panel = null
    }

    companion object {
        private val LOG = Logger.getInstance(OpenCodePanelController::class.java)
        fun getInstance(project: Project): OpenCodePanelController = project.getService(OpenCodePanelController::class.java)
    }
}
