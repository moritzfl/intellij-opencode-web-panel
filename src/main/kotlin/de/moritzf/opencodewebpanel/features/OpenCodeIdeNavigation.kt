package de.moritzf.opencodewebpanel.features

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
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
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeUnifiedDiff
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import de.moritzf.opencodewebpanel.server.SbxLaunchSpecInspection

internal class OpenCodeIdeNavigation(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val serverManager: OpenCodeServerBackend,
    private val projectDirectory: () -> String?,
    private val coalesceKey: Any,
) {
    private val fileLinkRequestGeneration = AtomicLong()

    fun openFileLinkInIde(href: String?, basePath: String? = null) {
        val payload = OpenCodeServerProtocol.parseOpenFileLinkPayload(href)
        val targetHref = payload?.href ?: href
        val partID = payload?.partID
        val routeBasePath = OpenCodeServerProtocol.routeDirectoryFromUrl(browser.cefBrowser.url)
        val projectBasePath = projectDirectory()
        val baseCandidates = listOfNotNull(basePath, payload?.basePath, routeBasePath, projectBasePath).distinct()
        val requestGeneration = fileLinkRequestGeneration.incrementAndGet()
        // Resolution hits the filesystem and may fall back to a bounded project search, so it
        // must not run on the browser callback thread. Neither caller uses the result.
        ApplicationManager.getApplication().executeOnPooledThread {
            val target = OpenCodeServerProtocol.resolveFileLinkWithBases(
                targetHref,
                baseCandidates,
                guestToHostPrefixes = guestToHostPrefixes(),
                home = pathHome(),
            ) ?: return@executeOnPooledThread
            if (requestGeneration != fileLinkRequestGeneration.get()) return@executeOnPooledThread
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path)
                ?: return@executeOnPooledThread
            val hintedLine = target.line
            ApplicationManager.getApplication().invokeLater {
                if (requestGeneration != fileLinkRequestGeneration.get()) return@invokeLater
                navigateToEditor(virtualFile, hintedLine, target.column)
            }
            if (hintedLine != null || partID.isNullOrBlank()) return@executeOnPooledThread
            val line = runCatching { firstChangeLineFromPart(partID, targetHref) }.getOrNull()
                ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (requestGeneration != fileLinkRequestGeneration.get()) return@invokeLater
                navigateToEditor(virtualFile, line, target.column)
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
                val line = parsed.line ?: memberLine(directVirtualFile, parsed.memberName)
                ApplicationManager.getApplication().invokeLater {
                    navigateToEditor(directVirtualFile, line, parsed.column)
                }
                return@executeOnPooledThread
            }
            ReadAction.nonBlocking<Pair<VirtualFile, Int?>?> {
                val virtualFile = resolveCodeReferenceFileName(parsed, GlobalSearchScope.projectScope(project))
                    ?: return@nonBlocking null
                virtualFile to (parsed.line ?: memberLine(virtualFile, parsed.memberName))
            }.finishOnUiThread(ModalityState.defaultModalityState()) { target ->
                if (target == null) return@finishOnUiThread
                navigateToEditor(target.first, target.second, parsed.column)
            }.coalesceBy(coalesceKey)
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    private fun firstChangeLineFromPart(partID: String?, fileHint: String?): Int? {
        if (partID.isNullOrBlank()) return null
        val serverUrl = serverManager.getServerUrl() ?: return null
        val password = serverManager.getServerPassword() ?: return null
        val sessionID = OpenCodeServerProtocol.sessionIdFromUrl(browser.cefBrowser.url) ?: return null
        val directory = projectDirectory()?.takeIf { it.isNotBlank() } ?: return null
        val result = OpenCodeServerProtocol.fetchToolPartChange(
            serverUrl,
            OpenCodeServerProtocol.buildBasicAuthHeader(password),
            directory,
            sessionID,
            partID,
        )
        val change = (result as? OpenCodeProtocolResult.Success)?.value ?: return null
        val diffs = OpenCodeDiffNavigation.resolvePartDiffs(change.diffs, fileHint)
        return diffs.firstNotNullOfOrNull { OpenCodeUnifiedDiff.firstChangedLineIndex(it.patch) }
    }

    private fun navigateToEditor(virtualFile: VirtualFile, line: Int?, column: Int?) {
        if (project.isDisposed) return
        try {
            OpenFileDescriptor(project, virtualFile, line ?: -1, column ?: -1).navigate(true)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: ClassCastException) {
            thisLogger().warn("Could not open ${virtualFile.path} in the IDE", e)
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
        val prefixes = guestToHostPrefixes()
        val home = pathHome()
        val target = OpenCodeServerProtocol.resolveFileLinkWithBases(
            parsed.path,
            bases,
            guestToHostPrefixes = prefixes,
            home = home,
        ) ?: OpenCodeServerProtocol.resolveFileLinkWithBases(
            parsed.path.replace('\\', '/'),
            bases,
            guestToHostPrefixes = prefixes,
            home = home,
        ) ?: return null
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
        val matches = OpenCodeServerProtocol.codeReferenceFileNames(parsed).asSequence()
            .flatMap { fileName -> FilenameIndex.getVirtualFilesByName(fileName, scope).asSequence() }
            .distinct()
            .toList()
        val picked = OpenCodeServerProtocol.pickDistinctPath(matches.map { it.path }, parsed.path)
            ?: return null
        return matches.firstOrNull { it.path.replace('\\', '/') == picked.replace('\\', '/') }
    }

    private fun memberLine(virtualFile: VirtualFile, memberName: String?): Int? {
        val member = memberName?.trim()?.ifBlank { null } ?: return null
        val text = runCatching { Files.readString(virtualFile.toNioPath()) }.getOrNull() ?: return null
        return OpenCodeServerProtocol.findMemberLineIndex(text, member)
    }

    private fun pathHome(): String? {
        return if (OpenCodeServerBackend.isNative(serverManager.backendId)) {
            System.getProperty("user.home")
        } else {
            SbxCli.SANDBOX_HOME
        }
    }

    private fun guestToHostPrefixes(): List<Pair<String, String>> {
        val dir = projectDirectory() ?: return emptyList()
        if (OpenCodeServerBackend.isNative(serverManager.backendId)) return emptyList()
        val spec = (SbxLaunchSpec.inspect(dir) as? SbxLaunchSpecInspection.Valid)?.spec
        val extra = spec?.let { SbxCli.resolveExtraMounts(it.extraMounts, dir) }.orEmpty()
        val persist = spec?.takeIf { it.persistSandboxSessions }?.let { SbxCli.sandboxPersistDataHome(it.name) }
        return SbxCli.guestToHostPathMappings(dir, extra, persist)
    }
}
