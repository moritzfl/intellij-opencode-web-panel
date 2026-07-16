package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path

/**
 * Refreshes the IDE's virtual file system after the OpenCode agent touches the working tree, so
 * file modifications, patches, and commits made by the agent are reflected in the project view and
 * the VCS view without a manual refresh. Driven (debounced) by [OpenCodeWorkspaceRefreshCoordinator].
 *
 * The recursive refresh marks the project subtree dirty and reloads directory contents, which
 * includes `.git` — so an edit changes a working-tree file's content and a commit changes
 * `.git` (index/HEAD/refs). The IDE's VCS integration watches those VFS changes and refreshes its
 * own view automatically, so this class needs only the platform VFS API and takes no dependency
 * on any VCS plugin.
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
                // async = true: a synchronous recursive refresh of the whole project would
                // block the EDT for seconds on large projects after every agent turn.
                // recursive + reloadChildren so `.git` is refreshed too, letting the IDE's VCS
                // integration pick up commits (which change no working-tree file) on its own.
                VfsUtil.markDirtyAndRefresh(true, true, true, virtualFile)
                thisLogger().info("Scheduled project files refresh after OpenCode workspace change")
            } catch (e: Exception) {
                thisLogger().warn("Failed to refresh project files after OpenCode workspace change", e)
            }
        }
    }
}
