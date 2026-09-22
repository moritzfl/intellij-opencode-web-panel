package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.plugins.UIComponentFileEditor
import com.intellij.ide.plugins.UIComponentVirtualFile
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.JComponent

/** IntelliJ supplies the editor/provider; this file only lends it the project's live panel. */
internal class OpenCodeEditorFile(private val controller: OpenCodePanelController) : UIComponentVirtualFile(
    "OpenCode Web Panel",
    IconLoader.getIcon("/icons/opencode.svg", OpenCodeEditorFile::class.java),
) {
    init {
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
    }

    override fun createContent(editor: UIComponentFileEditor): Content {
        val shell = BorderLayoutPanel()
        val actions = DefaultActionGroup(openCodeTitleActions())
        actions.add(openCodeGearActions().apply {
            isPopup = true
            templatePresentation.text = "More"
            templatePresentation.icon = com.intellij.icons.AllIcons.General.Settings
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("OpenCode.Editor", actions, true)
        toolbar.targetComponent = shell
        shell.addToTop(toolbar.component)
        Disposer.register(editor) { controller.editorClosed(this, shell) }
        return object : Content {
            override fun createComponent(): JComponent {
                controller.attachEditor(this@OpenCodeEditorFile, shell)
                return shell
            }

            override fun getPreferredFocusedComponent(component: JComponent): JComponent = controller.preferredFocus()
        }
    }
}
