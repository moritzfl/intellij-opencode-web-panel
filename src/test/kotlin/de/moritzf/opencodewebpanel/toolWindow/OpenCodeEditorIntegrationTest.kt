package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.ui.UIUtil
import de.moritzf.opencodewebpanel.features.OpenCodeChatInputService
import java.util.concurrent.CompletableFuture
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants

class OpenCodeEditorIntegrationTest : FileEditorManagerTestCase() {
    private lateinit var controller: OpenCodePanelController
    private val panel = TestPanel()
    private var candidate = panel

    override fun setUp() {
        super.setUp()
        controller = OpenCodePanelController(project) { candidate }
        Disposer.register(testRootDisposable, controller)
        controller.ensurePanel()
        UIUtil.dispatchAllInvocationEvents()
    }

    override fun tearDown() {
        try {
            controller.moveToToolWindow()
        } finally {
            super.tearDown()
        }
    }

    fun testEditorUsesPlatformProviderAndPreservesDraftOnReturn() {
        controller.moveToEditor()
        val file = requireNotNull(controller.editorFile)
        assertEquals(true, file.getUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT))
        assertEquals(true, file.getUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB))
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.getEditors(file).single()
        // Platform test managers may create the editor lazily, just like the real UI.
        editor.component
        assertTrue(editor.javaClass.name.endsWith("UIComponentFileEditor"))
        assertTrue(UIUtil.isAncestor(editor.component, controller.component))
        assertSame(panel.focus, editor.preferredFocusedComponent)
        panel.focus.text = "keep this draft"

        controller.moveToToolWindow()

        assertFalse(manager.isFileOpen(file))
        assertSame(controller.toolWindowComponent, controller.component.parent)
        assertEquals("keep this draft", panel.focus.text)
        assertEquals(1, panel.loads)
        assertEquals(0, panel.disposals)
    }

    fun testChatActivationKeepsEditorPlacementAndInFlightDelivery() {
        controller.moveToEditor()
        val file = requireNotNull(controller.editorFile)
        FileEditorManager.getInstance(project).getEditors(file).single().component
        val chat = OpenCodeChatInputService.getInstance(project)
        assertTrue(chat.send(listOf("file:src/Main.kt")))
        assertTrue(chat.activatePanel())
        controller.moveToEditor()
        assertSame(file, controller.editorFile)
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(file))
        assertEquals(1, panel.deliveries.size)
        assertEquals(1, chat.queuedCount())
        assertTrue(chat.acknowledge(panel.deliveries.single().attemptID, true))
        assertEquals(1, panel.loads)
    }

    fun testFinalEditorCloseReturnsPanelWithoutDisposingIt() {
        controller.moveToEditor()
        val file = requireNotNull(controller.editorFile)
        val manager = FileEditorManager.getInstance(project)
        manager.getEditors(file).single().component
        manager.closeFile(file)
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(controller.isInEditor)
        assertSame(controller.toolWindowComponent, controller.component.parent)
        assertEquals(0, panel.disposals)
    }

    fun testMovingEditorWrapperAndStaleCloseCannotStealPanel() {
        controller.moveToEditor()
        val file = requireNotNull(controller.editorFile)
        val manager = FileEditorManager.getInstance(project)
        manager.getEditors(file).single().component
        val provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.single { it.editorTypeId == "ui-component-editor" }
        // A native tab move can overlap construction of its successor and old-wrapper disposal.
        val successor = provider.createEditor(project, file)
        val shell = successor.component
        manager.closeFile(file)
        assertTrue(UIUtil.isAncestor(shell, controller.component))
        assertEquals(0, panel.disposals)
        provider.disposeEditor(successor)
        UIUtil.dispatchAllInvocationEvents()
        assertSame(controller.toolWindowComponent, controller.component.parent)
    }

    fun testNativeSplitMoveKeepsOneEditorAndOneLivePanel() {
        controller.moveToEditor()
        val file = requireNotNull(controller.editorFile)
        val editorManager = requireNotNull(manager)
        val source = requireNotNull(editorManager.currentWindow)
        val code = LightVirtualFile("source.txt", "code")
        editorManager.openFile(code, source, FileEditorOpenOptions(requestFocus = true))
        val target = requireNotNull(source.split(SwingConstants.VERTICAL, true, LightVirtualFile("code.txt", "code"), true))
        editorManager.openFile(file, target, FileEditorOpenOptions(requestFocus = true))
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(source.isFileOpen(file))
        assertTrue(target.isFileOpen(file))
        assertSame(file, controller.editorFile)
        assertEquals(1, editorManager.getEditors(file).size)
        assertEquals(1, panel.loads)
        assertEquals(0, panel.disposals)
        assertTrue(UIUtil.isAncestor(editorManager.getEditors(file).single().component, controller.component))
        editorManager.openFile(code, source, FileEditorOpenOptions(requestFocus = true))
        assertTrue(OpenCodeChatInputService.getInstance(project).activatePanel())
        assertFalse(source.isFileOpen(file))
        assertTrue(target.isFileOpen(file))
    }

    fun testReopeningClosedEditorReusesFileIdentityAndLivePanel() {
        controller.moveToEditor()
        val file = requireNotNull(controller.editorFile)
        val manager = FileEditorManager.getInstance(project)
        manager.closeFile(file)
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(controller.isInEditor)
        manager.openFile(file, true)
        assertTrue(controller.isInEditor)
        assertTrue(UIUtil.isAncestor(manager.getEditors(file).single().component, controller.component))
        controller.moveToToolWindow()
        controller.moveToEditor()
        assertSame(file, controller.editorFile)
        assertEquals(1, panel.loads)
        assertEquals(0, panel.disposals)
    }

    fun testControllerDisposalClosesBorrowingEditor() {
        controller.moveToEditor()
        val file = requireNotNull(controller.editorFile)
        Disposer.dispose(controller)
        assertFalse(FileEditorManager.getInstance(project).isFileOpen(file))
        assertEquals(1, panel.disposals)
        assertFalse(OpenCodeChatInputService.getInstance(project).activatePanel())
    }

    fun testReplacementFinishesInCurrentHostAfterMoveBack() {
        controller.moveToEditor()
        val replacement = TestPanel()
        candidate = replacement
        controller.replacePanel()
        controller.moveToToolWindow()
        UIUtil.dispatchAllInvocationEvents()
        assertSame(controller.toolWindowComponent, controller.component.parent)
        assertSame(replacement.component, controller.component.getComponent(0))
        assertEquals(1, panel.disposals)
        assertEquals(1, replacement.loads)
    }

    private class TestPanel : OpenCodePanel {
        val focus = JTextField("draft")
        override val component = JPanel().apply { add(focus) }
        override val preferredFocus = focus
        val deliveries = mutableListOf<OpenCodeChatInputService.Delivery>()
        var loads = 0
        var disposals = 0
        override fun prepareBrowserForReplacement() = CompletableFuture.completedFuture(Unit)
        override fun checkAndLoadContent() { loads++ }
        override fun dispatchChatBatch(delivery: OpenCodeChatInputService.Delivery): Boolean {
            deliveries += delivery
            return true
        }
        override fun onHostChanged() = Unit
        override fun dispose() { disposals++ }
    }
}
