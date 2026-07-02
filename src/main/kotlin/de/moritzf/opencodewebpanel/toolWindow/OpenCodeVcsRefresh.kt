package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path

/**
 * Refreshes the IDE's virtual file system and VCS change detection after the OpenCode agent
 * finishes a turn, so that file modifications and commits made by the agent are reflected in
 * the project view and VCS changelists without a manual refresh.
 *
 * Uses only platform-level APIs (async VFS refresh) so no VCS plugin dependency is required.
 * In IDEs with VCS support, the refresh triggers VCS file listeners and changelist updates.
 */
internal class OpenCodeVcsRefresh(private val project: Project) {

    fun refreshProjectFiles(projectBasePath: String?) {
        if (projectBasePath.isNullOrBlank()) return
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                val basePath = Path.of(projectBasePath).toAbsolutePath().normalize()
                val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(basePath) ?: return@invokeLater
                VfsUtil.markDirtyAndRefresh(false, true, true, virtualFile)
                thisLogger().info("Refreshed project files after OpenCode agent turn")
            } catch (e: Exception) {
                thisLogger().warn("Failed to refresh project files after OpenCode agent turn", e)
            }
        }
    }
}