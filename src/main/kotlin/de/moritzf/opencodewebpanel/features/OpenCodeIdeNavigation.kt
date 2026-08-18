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
import java.util.concurrent.atomic.AtomicLong
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager

internal class OpenCodeIdeNavigation(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val projectDirectory: () -> String?,
    private val coalesceKey: Any,
) {
    private val fileLinkRequestGeneration = AtomicLong()

    fun openFileLinkInIde(href: String?, basePath: String? = null) {
        val payload = OpenCodeServerProtocol.parseOpenFileLinkPayload(href)
        val targetHref = payload?.href ?: href
        val routeBasePath = OpenCodeServerProtocol.routeDirectoryFromUrl(browser.cefBrowser.url)
        val projectBasePath = projectDirectory()
        val baseCandidates = listOfNotNull(basePath, payload?.basePath, routeBasePath, projectBasePath).distinct()
        val requestGeneration = fileLinkRequestGeneration.incrementAndGet()
        // Resolution hits the filesystem and may fall back to a bounded project search, so it
        // must not run on the browser callback thread. Neither caller uses the result.
        ApplicationManager.getApplication().executeOnPooledThread {
            val target = OpenCodeServerProtocol.resolveFileLinkWithBases(targetHref, baseCandidates)
                ?: return@executeOnPooledThread
            if (requestGeneration != fileLinkRequestGeneration.get()) return@executeOnPooledThread
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path)
                ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || requestGeneration != fileLinkRequestGeneration.get()) return@invokeLater
                OpenFileDescriptor(project, virtualFile, target.line ?: -1, target.column ?: -1).navigate(true)
            }
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
        val routeBasePath = OpenCodeServerProtocol.routeDirectoryFromUrl(browser.cefBrowser.url)
        val bases = listOfNotNull(routeBasePath, projectDirectory()).distinct()
        // Path resolve hits the filesystem and may best-guess an incomplete subpath; keep it
        // off the browser JS-query callback thread.
        ApplicationManager.getApplication().executeOnPooledThread {
            val directVirtualFile = resolveCodeReferencePath(parsed, bases)
            if (directVirtualFile != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    OpenFileDescriptor(project, directVirtualFile, parsed.line ?: -1, -1).navigate(true)
                }
                return@executeOnPooledThread
            }
            ReadAction.nonBlocking<VirtualFile?> {
                resolveCodeReferenceFileName(parsed, GlobalSearchScope.projectScope(project))
            }.finishOnUiThread(ModalityState.defaultModalityState()) { virtualFile ->
                if (project.isDisposed || virtualFile == null) return@finishOnUiThread
                OpenFileDescriptor(project, virtualFile, parsed.line ?: -1, -1).navigate(true)
            }.coalesceBy(coalesceKey)
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    private fun resolveCodeReferencePath(
        parsed: OpenCodeServerProtocol.ParsedCodeReference,
        bases: List<String>,
    ): VirtualFile? {
        if (parsed.qualifiedName != null) return null
        if (!parsed.hasPath && parsed.extension == null) return null
        if (bases.isEmpty()) {
            val absolute = runCatching { Path.of(parsed.path) }.getOrNull()?.takeIf { it.isAbsolute } ?: return null
            if (!Files.isRegularFile(absolute)) return null
            return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolute)
        }
        val target = OpenCodeServerProtocol.resolveFileLinkWithBases(parsed.path, bases)
            ?: OpenCodeServerProtocol.resolveFileLinkWithBases(parsed.path.replace('\\', '/'), bases)
            ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path)
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
        val matches = fileNames.asSequence()
            .flatMap { fileName -> FilenameIndex.getVirtualFilesByName(fileName, scope).asSequence() }
            .toList()
        return matches.maxWithOrNull(
            compareBy<VirtualFile> { OpenCodeServerProtocol.scoreFilePathSuffix(it.path, parsed.path) }
                .thenByDescending { it.path.length },
        )
    }
}
