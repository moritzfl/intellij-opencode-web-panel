package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.features.OPEN_CODE_TOOL_WINDOW_ID
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

private val OpenCodeEditorFileType = PlainTextFileType.INSTANCE

internal class OpenCodeEditorVirtualFile(
    val owner: Project,
    sessionId: String?,
) : LightVirtualFile("OpenCode", OpenCodeEditorFileType, "") {
    var sessionId: String? = sessionId
}

internal object OpenCodeEditorManager {
    private val fileKey = Key.create<OpenCodeEditorVirtualFile>("opencode.editor.file")
    private val toolWindowListenerKey = Key.create<MessageBusConnection>("opencode.editor.tool.window.listener")

    fun open(project: Project, sessionId: String?) {
        if (project.isDisposed) return
        installToolWindowListener(project)
        val file = fileFor(project, sessionId)
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFile(file, true)
        editorManager.getEditors(file)
            .filterIsInstance<OpenCodeEditorFileEditor>()
            .forEach { it.openSession(sessionId) }
    }

    internal fun fileFor(project: Project, sessionId: String?): OpenCodeEditorVirtualFile {
        return synchronized(project) {
            val file = project.getUserData(fileKey) ?: OpenCodeEditorVirtualFile(project, sessionId).also {
                project.putUserData(fileKey, it)
            }
            file.sessionId = sessionId
            file
        }
    }

    internal fun trackedFile(project: Project): OpenCodeEditorVirtualFile? = project.getUserData(fileKey)

    private fun installToolWindowListener(project: Project) {
        synchronized(project) {
            if (project.getUserData(toolWindowListenerKey) != null) return
            val connection = project.messageBus.connect(project)
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun toolWindowShown(toolWindow: ToolWindow) {
                        if (project.isDisposed) return
                        val trackedFile = trackedFile(project)
                        val file = editorFileToCloseOnToolWindowShown(
                            toolWindow.id,
                            toolWindow.project,
                            trackedFile,
                        ) ?: return
                        FileEditorManager.getInstance(project).closeFile(file)
                    }
                },
            )
            project.putUserData(toolWindowListenerKey, connection)
        }
    }
}

internal fun editorFileToCloseOnToolWindowShown(
    toolWindowId: String,
    toolWindowProject: Project,
    trackedFile: OpenCodeEditorVirtualFile?,
): OpenCodeEditorVirtualFile? {
    return trackedFile?.takeIf {
        toolWindowId == OPEN_CODE_TOOL_WINDOW_ID && it.owner === toolWindowProject
    }
}

internal class OpenCodeEditorFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is OpenCodeEditorVirtualFile && file.owner === project

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        require(file is OpenCodeEditorVirtualFile && file.owner === project) {
            "OpenCode editor file belongs to another project"
        }
        return OpenCodeEditorFileEditor(project, file)
    }

    override fun disposeEditor(editor: FileEditor) {
        editor.dispose()
    }

    override fun getEditorTypeId(): String = "opencode.editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class OpenCodeEditorHost(
    private val editor: OpenCodeEditorFileEditor,
) : OpenCodePanelHost {
    override val project: Project
        get() = editor.project

    override fun isDisposed(): Boolean = editor.isDisposed

    override fun isPanelInView(component: JComponent): Boolean {
        return !isDisposed() && component.isShowing &&
            WindowManager.getInstance().getFrame(project)?.isActive == true
    }

    override fun activate(component: JComponent, action: () -> Unit) {
        editor.activate(action)
    }

    override fun replacePanel() {
        editor.replacePanel()
    }

    override fun showFailure() {
        editor.showFailure()
    }
}

internal class OpenCodeEditorFileEditor(
    val project: Project,
    private val file: OpenCodeEditorVirtualFile,
    private val initializePanel: Boolean = true,
) : UserDataHolderBase(), FileEditor {
    private val propertyChangeSupport = PropertyChangeSupport(this)
    private val root = BorderLayoutPanel()
    private val host = OpenCodeEditorHost(this)
    private var panel: OpenCodeWebToolWindowContent? = null
    private var disposed = false
    private var replacementPending = false

    init {
        if (initializePanel) createPanel(file.sessionId)
    }

    private fun createPanel(sessionId: String?) {
        val created = createPanelContent(sessionId)
        panel = created
        if (created == null) {
            showFailure()
            return
        }
        root.removeAll()
        root.addToCenter(created.getContent())
        root.revalidate()
        root.repaint()
        created.checkAndLoadContent()
    }

    internal fun openSession(sessionId: String?) {
        if (disposed) return
        file.sessionId = sessionId
        panel?.openSession(sessionId)
    }

    internal fun activate(action: () -> Unit) {
        if (disposed) return
        FileEditorManager.getInstance(project).openFile(file, true)
        action()
    }

    internal fun replacePanel() {
        if (disposed || replacementPending) return
        val previous = panel ?: run {
            createPanel(file.sessionId)
            panel?.openSession(file.sessionId)
            return
        }
        val liveBackendId = OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId
        val sessionId = runCatching { previous.displayedSessionID() }
            .getOrNull()
            .takeIf { previous.backendId() == liveBackendId }
        file.sessionId = sessionId
        replacementPending = true
        val replacement = createPanelContent(sessionId)
        if (replacement == null) {
            replacementPending = false
            return
        }
        replacement.prepareBrowserForReplacement().whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater {
                replacementPending = false
                if (disposed) {
                    Disposer.dispose(replacement)
                    return@invokeLater
                }
                if (error != null) {
                    Disposer.dispose(replacement)
                    return@invokeLater
                }
                if (OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId != liveBackendId) {
                    Disposer.dispose(replacement)
                    replacePanel()
                    return@invokeLater
                }
                panel = replacement
                root.removeAll()
                root.addToCenter(replacement.getContent())
                root.revalidate()
                root.repaint()
                Disposer.dispose(previous)
                replacement.openSession(file.sessionId)
            }
        }
    }

    private fun createPanelContent(sessionId: String?): OpenCodeWebToolWindowContent? {
        return try {
            OpenCodeWebToolWindowContent(host, sessionId)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            Logger.getInstance(OpenCodeEditorFileEditor::class.java)
                .warn("Could not create the OpenCode editor panel; showing the recovery card", e)
            null
        }
    }

    internal fun showFailure() {
        if (disposed) return
        panel?.let(Disposer::dispose)
        panel = null
        root.removeAll()
        root.addToCenter(OpenCodePanelFailureCard(::replacePanel).component)
        root.revalidate()
        root.repaint()
    }

    override fun getComponent(): JComponent = root

    override fun getFile(): VirtualFile = file

    override fun getPreferredFocusedComponent(): JComponent = panel?.getContent() ?: root

    override fun getName(): String = "OpenCode"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed && file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        if (disposed) return
        disposed = true
        panel?.let(Disposer::dispose)
        panel = null
    }

    internal val isDisposed: Boolean
        get() = disposed || project.isDisposed
}
