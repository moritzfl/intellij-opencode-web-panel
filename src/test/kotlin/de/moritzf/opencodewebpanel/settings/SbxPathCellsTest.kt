package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.EditorTextField
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.Action
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SbxPathCellsTest {
    companion object {
        @ClassRule @JvmField val application = ApplicationRule()
    }

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun oneClickSelectsARowAndADoubleClickOrTypingEditsIt() {
        SwingUtilities.invokeAndWait {
            val editor = SbxBrowsePathCellEditor("placeholder") { null }
            val table = JTable()
            fun click(count: Int) =
                MouseEvent(table, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, count, false)
            assertFalse(editor.isCellEditable(click(1)))
            assertTrue(editor.isCellEditable(click(2)))
            assertTrue(
                editor.isCellEditable(
                    KeyEvent(table, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_A, 'a'),
                ),
            )
            assertTrue("A new row starts editing without an event", editor.isCellEditable(null))
        }
    }

    @Test
    fun aPickedPathIsCommittedAndWrittenWithForwardSlashes() {
        SwingUtilities.invokeAndWait {
            var asked: String? = null
            val editor =
                SbxBrowsePathCellEditor("placeholder") { current ->
                    asked = current
                    "./kits/tools"
                }
            val field =
                editor.getTableCellEditorComponent(JTable(), "./old", false, 0, 0)
                    as com.intellij.openapi.ui.TextFieldWithBrowseButton
            var stopped = 0
            editor.addCellEditorListener(
                object : javax.swing.event.CellEditorListener {
                    override fun editingStopped(event: javax.swing.event.ChangeEvent) {
                        stopped++
                    }

                    override fun editingCanceled(event: javax.swing.event.ChangeEvent) = Unit
                },
            )
            field.button.doClick()
            assertEquals("The chooser starts from the cell's text", "./old", asked)
            assertEquals("./kits/tools", editor.cellEditorValue)
            assertEquals("Picking a path ends the edit", 1, stopped)
            field.text = "C:\\kits\\tools"
            assertEquals("C:/kits/tools", editor.cellEditorValue)
        }
    }

    @Test
    fun aPathTooLongForItsColumnLosesItsMiddle() {
        val width = { text: String -> text.length * 10 }
        assertEquals("~/kit", SbxPathCellRenderer.fitMiddle("~/kit", 50, width))
        val fitted = SbxPathCellRenderer.fitMiddle("/Users/ada/projects/app/kits/tools", 200, width)
        assertEquals(20, fitted.length)
        assertTrue(fitted, fitted.startsWith("/Users/ad") && fitted.endsWith("kits/tools"))
        assertTrue(fitted.contains("…"))
        assertEquals("/U…ls", SbxPathCellRenderer.fitMiddle("/Users/ada/tools", 10, width))
    }

    @Test
    fun aKitDescriptorIsReadAndSavedLikeAnEditorWould() {
        val spec = temp.newFolder("kit").toPath().resolve("spec.yaml")
        Files.writeString(spec, "schemaVersion: \"3\"\r\nkind: mixin\r\n")
        assertEquals("schemaVersion: \"3\"\nkind: mixin\n", SbxKitSpecFile.read(spec))
        SbxKitSpecFile.write(null, spec, "schemaVersion: \"3\"\nkind: mixin\n# edited\n")
        assertEquals(
            "A file with Windows line ends keeps them",
            "schemaVersion: \"3\"\r\nkind: mixin\r\n# edited\r\n",
            Files.readString(spec),
        )
        val unix = spec.resolveSibling("spec.yml")
        SbxKitSpecFile.write(null, unix, "kind: mixin\n")
        assertEquals("kind: mixin\n", Files.readString(unix))
    }

    @Test
    fun theKitDialogSavesItsTextAndCanRestoreTheTemplate() {
        val spec = temp.newFolder("network-kit").toPath().resolve("spec.yaml")
        Files.writeString(spec, "schemaVersion: \"3\"\n# - example.com\n")
        SwingUtilities.invokeAndWait {
            val dialog =
                SbxKitSpecDialog(
                    ProjectManager.getInstance().defaultProject,
                    JPanel(),
                    spec,
                    "network-kit/spec.yaml",
                    template = "schemaVersion: \"3\"\n# template\n",
                )
            val editor =
                SbxKitSpecDialog::class
                    .java
                    .getDeclaredField("editor")
                    .apply { isAccessible = true }
                    .get(dialog) as EditorTextField
            assertEquals("schemaVersion: \"3\"\n# - example.com\n", editor.text)
            assertEquals("Edit Kit: network-kit", dialog.title)
            val restore =
                SbxKitSpecDialog::class
                    .java
                    .getDeclaredMethod("createLeftSideActions")
                    .apply { isAccessible = true }
                    .invoke(dialog) as Array<*>
            (restore.single() as Action).actionPerformed(
                java.awt.event.ActionEvent(editor, java.awt.event.ActionEvent.ACTION_PERFORMED, ""),
            )
            assertEquals("schemaVersion: \"3\"\n# template\n", editor.text)
            assertEquals(
                "Nothing is written before Save",
                "schemaVersion: \"3\"\n# - example.com\n",
                Files.readString(spec),
            )
            editor.text = "schemaVersion: \"3\"\n- example.com\n"
            dialog.performOKAction()
            assertEquals("schemaVersion: \"3\"\n- example.com\n", Files.readString(spec))
            assertTrue(dialog.isOK)
        }
    }

    @Test
    fun aKitOfAnOlderSchemaIsFlaggedBecauseSbxWillNotCombineIt() {
        assertNull(SbxKitSpecFile.schemaProblem("schemaVersion: \"3\"\nkind: mixin\n"))
        assertNull(SbxKitSpecFile.schemaProblem("kind: mixin\nschemaVersion: 3 # v3\n"))
        assertNotNull(SbxKitSpecFile.schemaProblem("schemaVersion: \"2\"\nkind: mixin\n"))
        assertNotNull(SbxKitSpecFile.schemaProblem("# schemaVersion: \"3\"\nkind: mixin\n"))
    }
}
