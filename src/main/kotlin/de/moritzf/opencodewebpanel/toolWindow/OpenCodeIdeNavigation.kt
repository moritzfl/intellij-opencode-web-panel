package de.moritzf.opencodewebpanel.toolWindow

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
            val scope = GlobalSearchScope.projectScope(project)
            resolveCodeReferenceClass(parsed, scope)
                ?: resolveCodeReferenceFileName(parsed, scope)
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

    private fun resolveCodeReferenceClass(
        parsed: OpenCodeServerProtocol.ParsedCodeReference,
        scope: GlobalSearchScope,
    ): VirtualFile? {
        if (parsed.extension != null || parsed.hasPath) return null
        val cacheClass = runCatching { Class.forName("com.intellij.psi.search.PsiShortNamesCache") }.getOrNull() ?: return null
        val cache = runCatching { cacheClass.getMethod("getInstance", Project::class.java).invoke(null, project) }.getOrNull()
            ?: return null
        val classes = runCatching {
            cacheClass.getMethod("getClassesByName", String::class.java, GlobalSearchScope::class.java)
                .invoke(cache, parsed.fileName, scope) as? Array<*>
        }.getOrNull() ?: return null
        return classes.asSequence()
            .filter { psiClass -> parsed.qualifiedName == null || psiClass.qualifiedName() == parsed.qualifiedName }
            .mapNotNull { psiClass -> psiClass.containingVirtualFile() }
            .firstOrNull()
    }

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

    private fun Any?.qualifiedName(): String? {
        return this?.javaClass?.methods
            ?.firstOrNull { it.name == "getQualifiedName" && it.parameterCount == 0 }
            ?.let { method -> runCatching { method.invoke(this) as? String }.getOrNull() }
    }

    private fun Any?.containingVirtualFile(): VirtualFile? {
        val containingFile = this?.javaClass?.methods
            ?.firstOrNull { it.name == "getContainingFile" && it.parameterCount == 0 }
            ?.let { method -> runCatching { method.invoke(this) }.getOrNull() }
            ?: return null
        return containingFile.javaClass.methods
            .firstOrNull { it.name == "getVirtualFile" && it.parameterCount == 0 }
            ?.let { method -> runCatching { method.invoke(containingFile) as? VirtualFile }.getOrNull() }
    }
}
