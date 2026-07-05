package de.moritzf.opencodewebpanel.features

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager

internal class OpenCodeIdeNavigation(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val projectDirectory: () -> String?,
    private val coalesceKey: Any,
) {
    fun openFileLinkInIde(href: String?, basePath: String? = null) {
        val payload = OpenCodeServerProtocol.parseOpenFileLinkPayload(href)
        val targetHref = payload?.href ?: href
        val routeBasePath = OpenCodeServerProtocol.routeDirectoryFromUrl(browser.cefBrowser.url)
        val projectBasePath = projectDirectory()
        val target = listOfNotNull(basePath, payload?.basePath, routeBasePath)
            .distinct()
            .asSequence()
            .mapNotNull { OpenCodeServerProtocol.resolveFileLink(targetHref, projectBasePath, it) }
            .firstOrNull()
            ?: OpenCodeServerProtocol.resolveFileLink(targetHref, projectBasePath)
            ?: return
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            OpenFileDescriptor(project, virtualFile, target.line ?: -1, target.column ?: -1).navigate(true)
        }
    }

    fun openExternalLinkInBrowser(href: String?) {
        val serverUrl = serverManager.getServerUrl() ?: return
        val target = OpenCodeServerProtocol.externalHttpUrl(href, serverUrl) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            BrowserUtil.browse(target)
        }
    }

    fun openCodeReferenceInIde(ref: String?) {
        val text = ref?.trim()?.ifBlank { null } ?: return
        val parsed = OpenCodeServerProtocol.parseCodeReference(text) ?: return
        val directVirtualFile = resolveCodeReferencePath(parsed)
        if (directVirtualFile != null) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                OpenFileDescriptor(project, directVirtualFile, parsed.line ?: -1, -1).navigate(true)
            }
            return
        }
        ReadAction.nonBlocking<VirtualFile?> {
            resolveCodeReferenceFileName(parsed, GlobalSearchScope.projectScope(project))
        }.finishOnUiThread(ModalityState.defaultModalityState()) { virtualFile ->
            if (project.isDisposed || virtualFile == null) return@finishOnUiThread
            OpenFileDescriptor(project, virtualFile, parsed.line ?: -1, -1).navigate(true)
        }.coalesceBy(coalesceKey)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun resolveCodeReferencePath(parsed: OpenCodeServerProtocol.ParsedCodeReference): VirtualFile? {
        if (!parsed.hasPath) return null
        val projectBasePath = projectDirectory()?.takeIf { it.isNotBlank() }
        val candidates = buildList {
            runCatching { Path.of(parsed.path) }.getOrNull()?.let { path ->
                if (path.isAbsolute) add(path)
            }
            if (projectBasePath != null) {
                runCatching { Path.of(projectBasePath).resolve(parsed.path).normalize() }.getOrNull()?.let(::add)
            }
        }
        val path = candidates.firstOrNull { Files.isRegularFile(it) } ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    // Class references resolve through the filename index only (Foo -> Foo.kt/.java/...).
    // A PsiShortNamesCache lookup would also find classes in unrelated file names, but that
    // requires the com.intellij.java plugin; a best-effort click-to-navigate feature does
    // not justify that dependency.
    private fun resolveCodeReferenceFileName(
        parsed: OpenCodeServerProtocol.ParsedCodeReference,
        scope: GlobalSearchScope,
    ): VirtualFile? {
        val fileNames = if (parsed.extension == null && !parsed.hasPath) {
            listOf(
                parsed.fileName,
                "${parsed.fileName}.kt",
                "${parsed.fileName}.kts",
                "${parsed.fileName}.java",
                "${parsed.fileName}.ts",
                "${parsed.fileName}.tsx",
                "${parsed.fileName}.js",
                "${parsed.fileName}.jsx",
            )
        } else {
            listOf(parsed.fileName)
        }
        return fileNames.asSequence()
            .flatMap { fileName -> FilenameIndex.getVirtualFilesByName(fileName, scope).asSequence() }
            .firstOrNull()
    }
}
