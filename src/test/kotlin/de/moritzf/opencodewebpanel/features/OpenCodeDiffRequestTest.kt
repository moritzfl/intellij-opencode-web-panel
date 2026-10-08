package de.moritzf.opencodewebpanel.features

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.actions.impl.OpenInEditorAction
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.util.LineCol
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.NavigatableFileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.impl.ProjectImpl
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.pom.Navigatable
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.util.ui.UIUtil
import de.moritzf.opencodewebpanel.server.OpenCodeSnapshotFileDiff
import java.beans.PropertyChangeListener
import java.nio.file.Files
import javax.swing.JPanel

class OpenCodeDiffRequestTest : FileEditorManagerTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = PROJECT_DESCRIPTOR

    private val source = (1..100).joinToString("\n") { "source line $it" }
    private val patch =
        """
        @@ -9,3 +9,3 @@
         source line 9
        -old line 10
        +source line 10
         source line 11
        @@ -89,3 +89,3 @@
         source line 89
        -old line 90
        +source line 90
         source line 91
        """
            .trimIndent()

    fun testPlatformHighlightNavigationAlreadyOpensExternalTextFiles() {
        val file = externalFile()
        assertFalse(ProjectFileIndex.getInstance(project).isInContent(file))
        val content =
            DiffContentFactory.getInstance()
                .create(project, "source line 89\nsource line 90\nsource line 91", file)
        openInEditor(requireNotNull(content.getNavigatable(LineCol(1, 3))), file, 89, 3)
    }

    fun testBothSidesOpenExternalFileAtTranslatedCaret() {
        val file = externalFile()
        val contents = contents(file)
        assertEquals(
            "source line 9\nold line 10\nsource line 11\nsource line 89\nold line 90\nsource line 91",
            contents[0].document.text,
        )
        assertEquals(
            "source line 9\nsource line 10\nsource line 11\nsource line 89\nsource line 90\nsource line 91",
            contents[1].document.text,
        )
        for (content in contents) {
            openInEditor(requireNotNull(content.getNavigatable(LineCol(4, 3))), file, 89, 3)
        }
    }

    fun testBothSidesOpenProjectFileAtTranslatedCaret() {
        val file = myFixture.addFileToProject("src/source.txt", source).virtualFile
        assertTrue(ProjectFileIndex.getInstance(project).isInContent(file))
        for (content in contents(file)) {
            openInEditor(requireNotNull(content.getNavigatable(LineCol(1, 2))), file, 9, 2)
        }
    }

    fun testMissingFileKeepsSnapshotsWithoutNavigation() {
        for (content in contents(null)) {
            assertNull(content.highlightFile)
            assertNull(content.getNavigatable(LineCol(1, 0)))
        }
    }

    fun testDefaultNavigationUsesFirstPreviewLineNotCharacterOffset() {
        val file = externalFile()
        for (content in contents(file)) {
            openInEditor(requireNotNull(content.navigatable), file, 8, 0)
        }
    }

    fun testUnsavedChangesAfterNavigationQueryAreTranslated() {
        val file = externalFile()
        val navigatables = contents(file).map { requireNotNull(it.getNavigatable(LineCol(4, 3))) }
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "unsaved first\nunsaved second\n")
        }
        for (navigatable in navigatables) openInEditor(navigatable, file, 91, 3)
    }

    fun testDeletedTargetDisablesAlreadyQueriedNavigation() {
        val file = externalFile()
        val contents = contents(file)
        val navigatables = contents.map { requireNotNull(it.getNavigatable(LineCol(4, 3))) }
        WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
        for (navigatable in navigatables) {
            assertFalse(navigatable.canNavigate())
            assertFalse(navigatable.canNavigateToSource())
            navigatable.navigate(true)
        }
        for (content in contents) assertNull(content.getNavigatable(LineCol(4, 3)))
        assertEmpty(FileEditorManager.getInstance(project).openFiles)
    }

    fun testDirectoryCannotBecomeSourceNavigationTarget() {
        val directory = myFixture.tempDirFixture.findOrCreateDir("directory")
        for (content in contents(directory)) {
            assertNull(content.highlightFile)
            assertNull(content.getNavigatable(LineCol(1, 0)))
        }
    }

    fun testF4SelectsTextEditorWhenPreviewWasSelected() {
        val file = externalFile()
        val provider =
            object : FileEditorProvider, DumbAware {
                override fun accept(project: Project, candidate: VirtualFile): Boolean =
                    candidate == file

                override fun createEditor(project: Project, file: VirtualFile): FileEditor =
                    PreviewEditor(file)

                override fun getEditorTypeId(): String = "ocwp-test-preview"

                override fun getPolicy(): FileEditorPolicy =
                    FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
            }
        FileEditorProvider.EP_FILE_EDITOR_PROVIDER.point.registerExtension(
            provider,
            testRootDisposable,
        )
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFile(file, false)
        // A navigatable preview can consume generic Jump to Source without selecting source text.
        val platformContent =
            DiffContentFactory.getInstance().create(project, "source line 89\nsource line 90", file)
        assertTrue(
            OpenInEditorAction.openEditor(
                requireNotNull(platformContent.getNavigatable(LineCol(1, 3))),
                null,
            )
        )
        assertTrue(editorManager.getSelectedEditor(file) is PreviewEditor)
        for (content in contents(file)) {
            editorManager.setSelectedEditor(file, provider.editorTypeId)
            assertTrue(editorManager.getSelectedEditor(file) is PreviewEditor)
            openInEditor(
                requireNotNull(content.getNavigatable(LineCol(4, 3))),
                file,
                89,
                3,
                closeExisting = false,
            )
        }
    }

    private fun contents(file: VirtualFile?): List<DocumentContent> {
        val diff =
            OpenCodeSnapshotFileDiff(
                file?.path ?: "missing.txt",
                patch,
                2,
                2,
                "modified",
            )
        return requireNotNull(createOpenCodeDiffRequest(project, diff, file)).contents.map {
            it as DocumentContent
        }
    }

    private fun externalFile(): VirtualFile {
        val path = Files.createTempFile("ocwp-diff-", ".txt").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString())
        Disposer.register(testRootDisposable) { Files.deleteIfExists(path) }
        Files.writeString(path, source)
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun openInEditor(
        navigatable: Navigatable,
        file: VirtualFile,
        line: Int,
        column: Int,
        closeExisting: Boolean = true,
    ) {
        val editorManager = FileEditorManager.getInstance(project)
        if (closeExisting) editorManager.openFiles.forEach(editorManager::closeFile)
        assertTrue(OpenInEditorAction.openEditor(navigatable, null))
        UIUtil.dispatchAllInvocationEvents()
        val editor = (editorManager.getSelectedEditor(file) as? TextEditor)?.editor
        assertNotNull("F4 must open a text editor for ${file.path}", editor)
        assertSame(FileDocumentManager.getInstance().getDocument(file), editor!!.document)
        assertEquals(line, editor.caretModel.logicalPosition.line)
        assertEquals(column, editor.caretModel.logicalPosition.column)
    }

    private class PreviewEditor(private val source: VirtualFile) :
        UserDataHolderBase(), NavigatableFileEditor {
        private val panel = JPanel()

        override fun getComponent() = panel

        override fun getPreferredFocusedComponent() = panel

        override fun getName(): String = "Preview"

        override fun getFile(): VirtualFile = source

        override fun canNavigateTo(navigatable: Navigatable): Boolean = true

        override fun navigateTo(navigatable: Navigatable) = Unit

        override fun setState(state: FileEditorState) = Unit

        override fun isModified(): Boolean = false

        override fun isValid(): Boolean = source.isValid

        override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

        override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

        override fun dispose() = Unit
    }

    companion object {
        private val PROJECT_DESCRIPTOR =
            object : LightProjectDescriptor() {
                override fun getOpenProjectOptions(): OpenProjectTask =
                    OpenProjectTask.build()
                        .copy(
                            beforeInit = {
                                it.putUserData(FileEditorManagerKeys.ALLOW_IN_LIGHT_PROJECT, true)
                                // Installed Ultimate startup activities are unrelated to
                                // diff/editor navigation.
                                it.putUserData(ProjectImpl.RUN_START_UP_ACTIVITIES, false)
                            }
                        )
            }
    }
}
