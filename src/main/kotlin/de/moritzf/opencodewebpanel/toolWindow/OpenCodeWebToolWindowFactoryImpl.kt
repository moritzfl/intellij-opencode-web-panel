package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class OpenCodeWebToolWindowFactoryImpl : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowContent = OpenCodeWebToolWindowContent(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            val content = ContentFactory.getInstance().createContent(toolWindowContent.getContent(), null, false)
            content.setDisposer(toolWindowContent)
            toolWindow.contentManager.addContent(content)
            toolWindowContent.checkAndLoadContent()
        }
    }
}
