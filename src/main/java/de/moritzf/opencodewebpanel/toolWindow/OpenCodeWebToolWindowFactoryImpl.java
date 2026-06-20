package de.moritzf.opencodewebpanel.toolWindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class OpenCodeWebToolWindowFactoryImpl implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        OpenCodeWebToolWindowFactory.OpenCodeWebToolWindowContent toolWindowContent =
            new OpenCodeWebToolWindowFactory.OpenCodeWebToolWindowContent(toolWindow);
        ApplicationManager.getApplication().invokeLater(() -> {
            Content content = ContentFactory.getInstance().createContent(toolWindowContent.getContent(), null, false);
            content.setDisposer(toolWindowContent);
            toolWindow.getContentManager().addContent(content);
            toolWindowContent.checkAndLoadContent();
        });
    }
}
