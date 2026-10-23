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

internal const val OPEN_CODE_EDITOR_TOOLBAR_ROW_NAME = "OpenCode.Editor.ToolbarRow"

/** IntelliJ supplies the editor/provider; this file only lends it the project's live panel. */
internal class OpenCodeEditorFile(private val controller: OpenCodePanelController) :
    UIComponentVirtualFile(
        "OpenCode Web Panel",
        IconLoader.getIcon("/icons/opencode.svg", OpenCodeEditorFile::class.java),
    ) {
    init {
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
    }

    override fun createContent(editor: UIComponentFileEditor): Content {
        val shell = BorderLayoutPanel()
        val actions = DefaultActionGroup(openCodeTitleActions())
        actions.add(
            openCodeGearActions().apply {
                isPopup = true
                templatePresentation.text = "More"
                templatePresentation.icon = com.intellij.icons.AllIcons.General.Settings
            }
        )
        val toolbar =
            ActionManager.getInstance().createActionToolbar("OpenCode.Editor", actions, true)
        toolbar.targetComponent = shell
        // The tool window keeps these actions on the right of its title bar. Right-align the
        // editor host as well so moving the panel does not move the toolbar across the page.
        val toolbarRow =
            BorderLayoutPanel().apply {
                isOpaque = false
                name = OPEN_CODE_EDITOR_TOOLBAR_ROW_NAME
                addToRight(toolbar.component)
            }
        shell.addToTop(toolbarRow)
        Disposer.register(editor) { controller.editorClosed(this, shell) }
        return object : Content {
            override fun createComponent(): JComponent {
                controller.attachEditor(this@OpenCodeEditorFile, shell)
                return shell
            }

            override fun getPreferredFocusedComponent(component: JComponent): JComponent =
                controller.preferredFocus()
        }
    }
}
