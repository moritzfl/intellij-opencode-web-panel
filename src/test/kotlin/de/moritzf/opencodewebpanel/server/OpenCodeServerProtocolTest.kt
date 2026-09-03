package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.atomic.AtomicInteger

class OpenCodeServerProtocolTest {

    class FakeMcpService(
        private val running: Boolean,
        private val url: String? = null,
    ) {
        fun isRunning(): Boolean = running

        fun getServerSseUrl(): String? = url
    }

    @Test
    fun buildOpenCodeCommandUsesLoopbackDynamicPortAndLogs() {
        assertEquals(
            listOf("opencode", "serve", "--hostname", "127.0.0.1", "--port", "0", "--print-logs"),
            OpenCodeServerProtocol.buildOpenCodeCommand(),
        )
    }

    @Test
    fun buildOpenCodeCommandUsesConfiguredFixedPort() {
        assertEquals(
            listOf("opencode", "serve", "--hostname", "127.0.0.1", "--port", "4096", "--print-logs"),
            OpenCodeServerProtocol.buildOpenCodeCommand("4096"),
        )
    }

    @Test
    fun buildOpenCodeCommandUsesConfiguredExecutable() {
        assertEquals(
            listOf("/custom/bin/opencode", "serve", "--hostname", "127.0.0.1", "--port", "4096", "--print-logs"),
            OpenCodeServerProtocol.buildOpenCodeCommand("4096", "/custom/bin/opencode"),
        )
    }

    @Test
    fun createProcessBuilderSetsProjectDirectoryAndServerPassword() {
        val projectDirectory = Files.createTempDirectory("opencode-project")
        try {
            val processBuilder = OpenCodeServerProtocol.createProcessBuilder(
                projectBasePath = projectDirectory.toString(),
                password = "secret-password",
                port = "4096",
                executable = "/custom/bin/opencode",
                path = "test-path",
            )

            assertEquals(OpenCodeServerProtocol.buildOpenCodeCommand("4096", "/custom/bin/opencode"), processBuilder.command())
            assertEquals(projectDirectory.toFile(), processBuilder.directory())
            assertTrue(processBuilder.redirectErrorStream())
            assertEquals("test-path", processBuilder.environment()["PATH"])
            assertEquals("secret-password", processBuilder.environment()["OPENCODE_SERVER_PASSWORD"])
        } finally {
            projectDirectory.toFile().delete()
        }
    }

    @Test
    fun createProcessBuilderForwardsIdeHttpProxy() {
        val processBuilder = OpenCodeServerProtocol.createProcessBuilder(
            projectBasePath = null,
            password = "secret-password",
            path = "test-path",
            httpProxy = IdeHttpProxy(
                protocol = IdeHttpProxy.Protocol.HTTP,
                host = "127.0.0.1",
                port = 7897,
                username = "admin",
                password = "secret",
            ),
        )

        assertEquals("http://admin:secret@127.0.0.1:7897", processBuilder.environment()["HTTP_PROXY"])
        assertEquals("http://admin:secret@127.0.0.1:7897", processBuilder.environment()["HTTPS_PROXY"])
        assertTrue(processBuilder.environment()["NO_PROXY"].orEmpty().contains("127.0.0.1"))
    }

    @Test
    fun createProcessBuilderCanStripInheritedProxy() {
        val processBuilder = OpenCodeServerProtocol.createProcessBuilder(
            projectBasePath = null,
            password = "secret-password",
            path = "test-path",
            httpProxy = IdeHttpProxy(IdeHttpProxy.Protocol.HTTP, "127.0.0.1", 7897),
            stripInheritedProxy = true,
        )

        assertNull(processBuilder.environment()["HTTP_PROXY"])
        assertNull(processBuilder.environment()["HTTPS_PROXY"])
    }

    @Test
    fun createProcessBuilderUsesDetectedExecutablePathForLaunch() {
        val projectDirectory = Files.createTempDirectory("opencode-project")
        val executableDirectory = Files.createTempDirectory("opencode-bin")
        val executable = executableDirectory.resolve("opencode").toFile()
        try {
            executable.writeText("")
            executable.setExecutable(true)

            val processBuilder = OpenCodeServerProtocol.createProcessBuilder(
                projectBasePath = projectDirectory.toString(),
                password = "secret-password",
                path = executableDirectory.toString(),
            )

            assertEquals(executable.absolutePath, processBuilder.command().first())
        } finally {
            executable.delete()
            executableDirectory.toFile().delete()
            projectDirectory.toFile().delete()
        }
    }

    @Test
    fun createProcessBuilderKeepsDefaultDirectoryWhenProjectPathIsMissing() {
        val processBuilder = OpenCodeServerProtocol.createProcessBuilder(
            projectBasePath = null,
            password = "secret-password",
            path = "test-path",
        )

        assertNull(processBuilder.directory())
    }

    @Test
    fun resolvePathPreservesCurrentEntriesAndAddsCommonEntriesWithoutDuplicates() {
        val currentPath = listOf("/custom/bin", "/usr/bin").joinToString(File.pathSeparator)

        assertEquals(
            listOf("/custom/bin", "/usr/bin", "/bin").joinToString(File.pathSeparator),
            OpenCodeServerProtocol.resolvePath(currentPath, listOf("/usr/bin", "/bin")),
        )
    }

    @Test
    fun resolvePathAddsExpandedWindowsPackageManagerLocations() {
        val path = OpenCodeServerProtocol.resolvePath(
            currentPath = "",
            environment = mapOf(
                "APPDATA" to "C:\\Users\\Alice\\AppData\\Roaming",
                "LOCALAPPDATA" to "C:\\Users\\Alice\\AppData\\Local",
                "USERPROFILE" to "C:\\Users\\Alice",
                "PROGRAMDATA" to "C:\\ProgramData",
                "NVM_HOME" to "C:\\Users\\Alice\\AppData\\Roaming\\nvm",
            ),
        )

        assertTrue(path.contains("C:\\Users\\Alice\\AppData\\Roaming\\npm"))
        assertTrue(path.contains("C:\\Users\\Alice\\AppData\\Local\\pnpm"))
        assertTrue(path.contains("C:\\Users\\Alice\\scoop\\shims"))
        assertTrue(path.contains("C:\\ProgramData\\chocolatey\\bin"))
        assertFalse(path.contains("%APPDATA%"))
        assertFalse(path.contains("%USERNAME%"))
    }

    @Test
    fun resolvePathAddsHomeInstallLocations() {
        val path = OpenCodeServerProtocol.resolvePath(
            currentPath = "",
            environment = mapOf("HOME" to "/Users/alice"),
        )

        assertTrue(path.contains("/Users/alice/.opencode/bin"))
        assertTrue(path.contains("/Users/alice/.local/bin"))
    }

    @Test
    fun detectExecutablePathFindsExecutableOnPath() {
        val executableDirectory = Files.createTempDirectory("opencode-bin")
        val executable = executableDirectory.resolve("opencode").toFile()
        try {
            executable.writeText("")
            executable.setExecutable(true)

            assertEquals(
                executable.absolutePath,
                OpenCodeServerProtocol.detectExecutablePath(path = executableDirectory.toString()),
            )
        } finally {
            executable.delete()
            executableDirectory.toFile().delete()
        }
    }

    @Test
    fun detectExecutablePathFindsWindowsCommandShimOnPath() {
        val executableDirectory = Files.createTempDirectory("opencode-bin")
        val executable = executableDirectory.resolve("opencode.cmd").toFile()
        try {
            executable.writeText("")

            assertEquals(
                executable.absolutePath,
                OpenCodeServerProtocol.detectExecutablePath(path = executableDirectory.toString()),
            )
        } finally {
            executable.delete()
            executableDirectory.toFile().delete()
        }
    }

    @Test
    fun detectExecutablePathPrefersWindowsCommandShimOverExtensionlessNpmShellShim() {
        val executableDirectory = Files.createTempDirectory("opencode-bin")
        val shellShim = executableDirectory.resolve("opencode").toFile()
        val commandShim = executableDirectory.resolve("opencode.cmd").toFile()
        try {
            shellShim.writeText("#!/bin/sh\n")
            shellShim.setExecutable(true)
            commandShim.writeText("")

            assertEquals(
                commandShim.absolutePath,
                OpenCodeServerProtocol.detectExecutablePath(path = executableDirectory.toString(), osName = "Windows 11"),
            )
        } finally {
            shellShim.delete()
            commandShim.delete()
            executableDirectory.toFile().delete()
        }
    }

    @Test
    fun detectExecutablePathReturnsNullWhenExecutableIsMissing() {
        val executableDirectory = Files.createTempDirectory("opencode-bin")
        try {
            assertNull(OpenCodeServerProtocol.detectExecutablePath(path = executableDirectory.toString()))
        } finally {
            executableDirectory.toFile().delete()
        }
    }

    @Test
    fun resolveExecutableForLaunchFallsBackToCommandNameWhenExecutableIsMissing() {
        val executableDirectory = Files.createTempDirectory("opencode-bin")
        try {
            assertEquals("opencode", OpenCodeServerProtocol.resolveExecutableForLaunch(path = executableDirectory.toString()))
        } finally {
            executableDirectory.toFile().delete()
        }
    }

    @Test
    fun toCefZoomLevelConvertsPercentageScale() {
        assertEquals(0.0, OpenCodeServerProtocol.toCefZoomLevel(100), 0.0001)
        assertEquals(1.0, OpenCodeServerProtocol.toCefZoomLevel(120), 0.0001)
        assertTrue(OpenCodeServerProtocol.toCefZoomLevel(80) < 0.0)
    }

    @Test
    fun parseServerUrlAcceptsOpenCodeOutputAndTrimsTrailingSlash() {
        assertEquals(
            "http://127.0.0.1:60482",
            OpenCodeServerProtocol.parseServerUrl("OpenCode server listening on http://127.0.0.1:60482/"),
        )
        assertEquals(
            "http://localhost:4096",
            OpenCodeServerProtocol.parseServerUrl("opencode server listening on http://localhost:4096"),
        )
        assertNull(OpenCodeServerProtocol.parseServerUrl("starting server"))
    }

    @Test
    fun parseServerUrlRejectsNonLoopbackHosts() {
        assertNull(
            OpenCodeServerProtocol.parseServerUrl("OpenCode server listening on http://192.168.1.10:60482"),
        )
        assertNull(
            OpenCodeServerProtocol.parseServerUrl("OpenCode server listening on http://example.com:60482"),
        )
        assertFalse(OpenCodeServerProtocol.isLoopbackServerUrl("http://10.0.0.1:4096"))
        assertTrue(OpenCodeServerProtocol.isLoopbackServerUrl("http://127.0.0.1:4096"))
        assertTrue(OpenCodeServerProtocol.isLoopbackServerUrl("http://localhost:4096"))
    }

    @Test
    fun buildServerRootUrlTrimsTrailingSlash() {
        assertEquals(
            "http://127.0.0.1:60482",
            OpenCodeServerProtocol.buildServerRootUrl("http://127.0.0.1:60482/"),
        )
    }

    @Test
    fun buildProjectUrlLoadsServerRootWithoutProjectPath() {
        assertEquals(
            "http://127.0.0.1:60482",
            OpenCodeServerProtocol.buildProjectUrl("http://127.0.0.1:60482/"),
        )
    }

    @Test
    fun buildProjectUrlLoadsCurrentProjectSessionPage() {
        assertEquals(
            "http://127.0.0.1:60482/L1VzZXJzL21vcml0ei9NeSBQcm9qZWN0/session",
            OpenCodeServerProtocol.buildProjectUrl("http://127.0.0.1:60482/", "/Users/moritz/My Project"),
        )
    }

    @Test
    fun buildServerSessionUrlBuildsTheNativeServerRoute() {
        // serverKey is base64url (no padding) of the origin - the same encoding the SPA uses.
        assertEquals(
            "http://127.0.0.1:60482/server/aHR0cDovLzEyNy4wLjAuMTo2MDQ4Mg/session",
            OpenCodeServerProtocol.buildServerSessionUrl("http://127.0.0.1:60482/"),
        )
        assertEquals(
            "http://127.0.0.1:60482/server/aHR0cDovLzEyNy4wLjAuMTo2MDQ4Mg/session/ses_abc123",
            OpenCodeServerProtocol.buildServerSessionUrl("http://127.0.0.1:60482/", "ses_abc123"),
        )
    }

    @Test
    fun unsupportedOpenCodeVersionsAreDetectedAgainstTheCentralMinimum() {
        assertEquals("1.18.0", OpenCodeServerProtocol.MINIMUM_SUPPORTED_OPENCODE_VERSION)
        assertTrue(OpenCodeServerProtocol.isOpenCodeVersionUnsupported("1.17.9"))
        assertTrue(OpenCodeServerProtocol.isOpenCodeVersionUnsupported("v1.17.9"))
        assertTrue(OpenCodeServerProtocol.isOpenCodeVersionUnsupported("1.18.0-rc.1"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeVersionUnsupported("1.18.0"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeVersionUnsupported("1.18.1"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeVersionUnsupported("2.0.0"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeVersionUnsupported("development"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeVersionUnsupported(null))
    }

    @Test
    fun buildOpenProjectScriptSeedsProjectState() {
        val script = OpenCodeBrowserSnippets.buildOpenProjectScript("/tmp/my 'project'", "http://127.0.0.1:60482/")!!

        assertTrue(script.contains("if (window.location.origin !== 'http://127.0.0.1:60482') return;"))
        assertTrue(script.contains("opencode.global.dat:server"))
        assertTrue(script.contains("state.projects[scope]"))
        assertTrue(script.contains("state.lastProject[scope] = directory"))
        assertTrue(script.contains("!Array.isArray(state.projects)"))
        assertTrue(script.contains("!Array.isArray(state.lastProject)"))
        assertTrue(script.contains("worktree: directory, expanded: true"))
        // Existing entries preserve position, collapse state, and unknown fields across re-seeds.
        assertTrue(script.contains("Object.assign"))
        assertTrue(script.contains("typeof project.expanded === 'boolean' ? project.expanded : true"))
        assertTrue(script.contains("if (!found) nextProjects.unshift"))
        assertTrue(script.contains("if (nextRaw !== raw)"))
        // Foreign-schema guard: a root that parses but is not a plain object is treated as a
        // newer OpenCode schema and skipped (fail soft) instead of being replaced wholesale.
        assertTrue(script.contains("if (!parseFailed && parsed !== null && !isPlainObject)"))
        assertTrue(script.contains("Skipping OpenCode project seed: unrecognized project-state schema"))
        assertFalse(script.contains("state.list ="))
        assertTrue(script.contains("const sameWorktree = (left, right) =>"))
        assertTrue(script.contains("!sameWorktree(project.worktree, directory)"))
        assertTrue(script.contains("next.startsWith('//')) next = next.toLowerCase()"))
        assertTrue(script.contains("const directory = '/tmp/my \\'project\\''"))
        assertFalse(script.contains("window.location.assign"))
        assertFalse(script.contains("lastProjectSession"))
        assertFalse(script.contains("window.location.reload()"))
        assertFalse(script.contains("projectPath"))
        assertFalse(script.contains("findLastProjectSession"))
        assertFalse(script.contains("directorylessProject"))
        assertFalse(script.contains("shouldKeepWaitingForRecentSession"))
        assertFalse(script.contains("onSameProjectRoute"))
    }

    @Test
    fun buildOpenProjectScriptIsMissingWithoutProjectPath() {
        assertNull(OpenCodeBrowserSnippets.buildOpenProjectScript(null))
        assertNull(OpenCodeBrowserSnippets.buildOpenProjectScript(""))
    }

    @Test
    fun buildFileLinkHandlerScriptInterceptsLocalFileLinks() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijFileLinksInstalled"))
        assertTrue(script.contains("target.closest('a')"))
        assertTrue(script.contains("inferredFileLink(link)"))
        assertTrue(script.contains("changedFileButtonLink(target)"))
        assertTrue(script.contains("resolveFileOpenTarget(event.target, changedButtonOnly)"))
        assertTrue(script.contains("session-review-view-button"))
        assertTrue(script.contains("session-review-accordion-item"))
        assertTrue(script.contains("getAttribute('data-file')"))
        // Locale-specific aria/title labels are no longer used as fallbacks.
        assertFalse(script.contains("button[aria-label=\"Open file\"]"))
        assertFalse(script.contains("Datei öffnen"))
        assertTrue(script.contains("session-review-file-info"))
        assertTrue(script.contains("session-review-directory"))
        assertTrue(script.contains("session-review-filename"))
        assertTrue(script.contains("document.addEventListener('pointerdown'"))
        assertTrue(script.contains("document.addEventListener('mousedown'"))
        assertTrue(script.contains("now - lastOpenedAt < 750"))
        assertTrue(script.contains("data-opencode-intellij-pointer"))
        assertTrue(script.contains("opencode-intellij-pointer-cursor"))
        assertTrue(script.contains("if (!style.isConnected) parent.appendChild(style)"))
        assertTrue(script.contains("cursor: pointer !important"))
        assertTrue(script.contains("document.addEventListener('mouseover'"))
        assertTrue(script.contains("document.addEventListener('mouseout'"))
        assertTrue(script.contains("supportedFileProtocol"))
        assertTrue(script.contains("link.closest('[data-component=\"markdown\"]')"))
        assertTrue(script.contains("link.target !== '_blank'"))
        assertTrue(script.contains("decodeRouteDirectory"))
        assertTrue(script.contains("isOpenCodeAppRoute(href)"))
        // Subagent/task cards link to /server/<key>/session/<id>; that must not be treated as a file path.
        assertTrue(script.contains("/server/"))
        assertTrue(script.contains("new-session"))
        assertTrue(script.contains("lastSegmentLooksLikeFile(href)"))
        assertTrue(script.contains("href.startsWith('/') && !href.startsWith('//')"))
        assertTrue(script.indexOf("lastSegmentLooksLikeFile") < script.indexOf("explicitProtocol.test(href)"))
        assertTrue(script.contains("!href.includes('://')"))
        assertTrue(script.contains("${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}"))
    }

    @Test
    fun buildFileLinkHandlerScriptSupportsRedesignedReviewPanelPreviewHeader() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!

        // The redesigned (v2) review panel — shown on desktop when forceCompactLayout is off —
        // exposes the changed file only through its preview header spans, so the "open in IDE"
        // gesture must resolve the path from there.
        assertTrue(script.contains("reviewV2FileLink(target)"))
        assertTrue(script.contains("session-review-v2-file-title"))
        assertTrue(script.contains("session-review-v2-file-name"))
        assertTrue(script.contains("session-review-v2-file-path"))
        assertTrue(script.contains("session-review-v2-file-header"))
        // The sidebar tree rows are the SPA's own preview navigation; hijacking them would break
        // in-app review, so they must never be an intercept target.
        assertFalse(script.contains("session-review-v2-sidebar-tree"))
    }

    @Test
    fun buildFileLinkHandlerScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = false))
    }

    @Test
    fun buildFileLinkHandlerScriptCanUseDirectCallback() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
            "/tmp/project",
            enabled = true,
            openFileCallback = "window.intellijOpenFile(rawHref + '\\n' + directory)",
        )!!

        assertTrue(script.contains("window.intellijOpenFile(rawHref + '\\n' + directory)"))
        assertTrue(script.contains("typeof window.cefQuery === 'function'"))
        assertTrue(script.contains("Failed to forward file link to IntelliJ"))
        assertTrue(script.contains("${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}"))
        assertFalse(script.contains("window.location.assign(target)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptInterceptsOnlyExternalHttpLinks() {
        val script = OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(
            enabled = true,
            openExternalCallback = "window.intellijOpenExternal(href)",
        )!!

        assertTrue(script.contains("window.__opencodeIntellijExternalLinksInstalled"))
        assertTrue(script.contains("event.target.closest('a')"))
        assertTrue(script.contains("url.protocol !== 'http:' && url.protocol !== 'https:'"))
        assertTrue(script.contains("url.origin === window.location.origin"))
        assertTrue(script.contains("window.__opencodeIntellijNativeWindowOpen"))
        assertTrue(script.contains("window.open = function(url, target, features)"))
        assertTrue(script.contains("event.preventDefault()"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
        assertTrue(script.contains("window.intellijOpenExternal(href)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(enabled = false, openExternalCallback = "callback(href)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(enabled = true, openExternalCallback = null))
    }

    @Test
    fun externalHttpUrlAllowsOnlyHttpLinksOutsideOpenCodeOrigin() {
        val serverUrl = "http://127.0.0.1:4096"

        assertEquals("https://example.com/docs", OpenCodeServerProtocol.externalHttpUrl("https://example.com/docs", serverUrl))
        assertEquals("http://example.com/docs", OpenCodeServerProtocol.externalHttpUrl(" http://example.com/docs ", serverUrl))
        assertNull(OpenCodeServerProtocol.externalHttpUrl("http://127.0.0.1:4096/docs", serverUrl))
        assertNull(OpenCodeServerProtocol.externalHttpUrl("/docs", serverUrl))
        assertNull(OpenCodeServerProtocol.externalHttpUrl("mailto:test@example.com", serverUrl))
    }

    @Test
    fun buildRestoreOpenCodeLocalStorageScriptIsMissingWithoutSnapshot() {
        assertNull(OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(null))
        assertNull(OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript("{}"))
    }

    @Test
    fun buildRestoreOpenCodeLocalStorageScriptRestoresOpenCodeKeysOnlyWhenMissing() {
        val script = OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(
            "{\"opencode.global.dat:language\":\"{\\\"locale\\\":\\\"de\\\"}\"}",
        )!!

        assertTrue(script.contains("opencode.global.dat:language"))
        assertTrue(script.contains(OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY))
        assertTrue(script.contains("opencode-color-scheme"))
        assertTrue(script.contains("opencode\\.global\\.dat"))
        assertTrue(script.contains("opencode\\.workspace\\."))
        assertTrue(script.contains("opencode\\.window\\.browser\\.dat:tabs"))
        assertTrue(script.contains("'settings.v3'"))
        assertTrue(script.contains("home\\.servers"))
        assertTrue(script.contains("review-panel-v2"))
        assertTrue(script.contains("new-session\\.provider-tip"))
        assertTrue(script.contains("recent|info|closed"))
        assertFalse(script.contains("opencode.settings.dat:defaultServerUrl"))
        assertTrue(script.contains("window.localStorage.getItem(key) === null"))
        assertTrue(script.contains("rewriteLoopbackServerRefs(value)"))
        assertTrue(script.contains("rewriteLoopbackServerRefs(current)"))
        assertTrue(script.contains("LOOPBACK_ORIGIN_RE"))
        // Still only-if-absent for snapshot inject; rewrite pass covers already-present keys.
        assertTrue(script.contains("window.localStorage.setItem(key, rewriteLoopbackServerRefs(value))"))
    }

    @Test
    fun buildSyncOpenCodeLocalStorageScriptMirrorsOpenCodeKeys() {
        val script = OpenCodeBrowserSnippets.buildSyncOpenCodeLocalStorageScript("window.intellijStore(payload)")!!

        assertTrue(script.contains("window.__opencodeIntellijLocalStorageSyncInstalled"))
        assertTrue(script.contains("Storage.prototype.setItem"))
        assertTrue(script.contains("Storage.prototype.removeItem"))
        assertTrue(script.contains("Storage.prototype.clear"))
        assertTrue(script.contains(OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY))
        assertTrue(script.contains("opencode-color-scheme"))
        assertTrue(script.contains("opencode\\.global\\.dat"))
        assertTrue(script.contains("opencode\\.workspace\\."))
        assertTrue(script.contains("opencode\\.window\\.browser\\.dat:tabs"))
        assertTrue(script.contains("'settings.v3'"))
        assertTrue(script.contains("home\\.servers"))
        assertTrue(script.contains("review-panel-v2"))
        assertTrue(script.contains("new-session\\.provider-tip"))
        assertTrue(script.contains("recent|info|closed"))
        assertTrue(script.contains("MAX_VALUE_CHARS"))
        assertFalse(script.contains("opencode.settings.dat:defaultServerUrl"))
        assertTrue(script.contains("window.intellijStore(payload)"))
        // Auto-port relaunches: normalize loopback server keys before the IDE snapshot is saved.
        assertTrue(script.contains("rewriteLoopbackServerRefs(value)"))
        assertTrue(script.contains("LOOPBACK_ORIGIN_RE"))
        // Containment contract for the only page-wide API patch: the original method runs
        // first, and the mirror tail is try-caught so a bug in it can never break the SPA's
        // own storage operations.
        assertTrue(script.contains("const result = originalSetItem.apply(this, arguments);"))
        assertEquals(3, Regex("""const result = original\w+\.apply\(this, arguments\);\s*\n\s*try \{""").findAll(script).count())
    }

    @Test
    fun buildClearOpenCodeWebStateScriptWipesBrowserStorage() {
        val script = OpenCodeBrowserSnippets.buildClearOpenCodeWebStateScript()

        assertTrue(script.contains("window.localStorage.clear()"))
        assertTrue(script.contains("window.sessionStorage.clear()"))
        // Each clear is individually guarded so a storage-access failure cannot abort the other.
        assertEquals(2, Regex("""try \{ window\.\w+Storage\.clear\(\); \} catch \(_\) \{\}""").findAll(script).count())
    }

    @Test
    fun logIndicatesPortConflictMatchesKnownFailurePatterns() {
        assertTrue(OpenCodeServerProtocol.logIndicatesPortConflict(listOf("Error: listen EADDRINUSE: address already in use 127.0.0.1:4096")))
        assertTrue(OpenCodeServerProtocol.logIndicatesPortConflict(listOf("error: Failed to start server. Is port 4096 in use?")))
        assertTrue(OpenCodeServerProtocol.logIndicatesPortConflict(listOf("Error: Unexpected error", "ServeError")))
        assertFalse(OpenCodeServerProtocol.logIndicatesPortConflict(listOf("opencode server listening on http://127.0.0.1:4096")))
        assertFalse(OpenCodeServerProtocol.logIndicatesPortConflict(emptyList()))
    }

    @Test
    fun lifecycleStatusTextUsesCircleStatusStyle() {
        val html = formatOpenCodeServerLifecycleStatusText(OpenCodeServerLifecycleState.STARTING)

        assertTrue(html.contains("&#9679;"))
        assertTrue(html.contains("#FFC107"))
        assertTrue(html.contains("OpenCode server: Starting"))
    }

    @Test
    fun lifecycleStatusIsHiddenWhenServerIsRunning() {
        assertFalse(isOpenCodeServerLifecycleStatusVisible(OpenCodeServerLifecycleState.RUNNING))
        assertTrue(isOpenCodeServerLifecycleStatusVisible(OpenCodeServerLifecycleState.STARTING))
        assertTrue(isOpenCodeServerLifecycleStatusVisible(OpenCodeServerLifecycleState.FAILED))
        assertTrue(isOpenCodeServerLifecycleStatusVisible(OpenCodeServerLifecycleState.RESTARTING))
        assertTrue(isOpenCodeServerLifecycleStatusVisible(OpenCodeServerLifecycleState.STOPPED))
    }

    @Test
    fun intellijMcpServerStatusUsesRuntimeState() {
        val runningStatus = IntellijMcpServerStartup.statusForRuntimeState(
            enabled = true,
            service = FakeMcpService(true, "http://127.0.0.1:64342/sse"),
        )
        val stoppedStatus = IntellijMcpServerStartup.statusForRuntimeState(
            enabled = true,
            service = FakeMcpService(false),
        )
        val disabledStatus = IntellijMcpServerStartup.statusForRuntimeState(enabled = false, service = null)
        val unavailableStatus = IntellijMcpServerStartup.statusForRuntimeState(enabled = null, service = null)

        assertEquals(IntellijMcpServerStartupState.ENABLED, runningStatus.state)
        assertEquals("IntelliJ MCP server is running at http://127.0.0.1:64342/sse", runningStatus.message)
        assertEquals(IntellijMcpServerStartupState.ENABLED_NOT_RUNNING, stoppedStatus.state)
        assertEquals(IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED, disabledStatus.state)
        assertEquals(IntellijMcpServerStartupState.UNAVAILABLE, unavailableStatus.state)
    }

    @Test
    fun intellijMcpServerWaitsOnlyWhenEnabledButNotRunning() {
        assertTrue(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                    "not running",
                ),
            ),
        )
        assertFalse(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED,
                    "running",
                ),
            ),
        )
        assertFalse(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.NOT_CONFIGURED_OR_DISABLED,
                    "disabled",
                ),
            ),
        )
        assertFalse(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.UNAVAILABLE,
                    "unavailable",
                ),
            ),
        )
        assertFalse(
            IntellijMcpServerStartup.shouldWaitFor(
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                    "not running",
                ),
                enabled = false,
            ),
        )
    }

    @Test
    fun intellijMcpServerWaitStopsWhenServerStarts() {
        var now = 0L
        var checks = 0
        val sleeps = mutableListOf<Long>()

        val result = IntellijMcpServerStartup.waitUntilReady(
            stillWaiting = {
                checks += 1
                // Initial check plus one polled check still waiting; the server is up on the third.
                checks <= 2
            },
            nowMillis = { now },
            sleepMillis = { millis ->
                sleeps += millis
                now += millis
            },
            timeoutMillis = 2_000L,
            pollIntervalMillis = 500L,
        )

        assertEquals(IntellijMcpServerWaitResult.READY, result)
        assertEquals(listOf(500L, 500L), sleeps)
    }

    @Test
    fun intellijMcpServerWaitTimesOut() {
        var now = 0L
        val sleeps = mutableListOf<Long>()

        val result = IntellijMcpServerStartup.waitUntilReady(
            stillWaiting = { true },
            nowMillis = { now },
            sleepMillis = { millis ->
                sleeps += millis
                now += millis
            },
            timeoutMillis = 1_000L,
            pollIntervalMillis = 400L,
        )

        assertEquals(IntellijMcpServerWaitResult.TIMED_OUT, result)
        assertEquals(listOf(400L, 400L, 200L), sleeps)
    }

    @Test
    fun intellijMcpServerWaitStopsWhenSettingIsDisabled() {
        var now = 0L
        var enabled = true
        val sleeps = mutableListOf<Long>()

        val result = IntellijMcpServerStartup.waitUntilReady(
            stillWaiting = {
                IntellijMcpServerStartup.shouldWaitFor(
                    IntellijMcpServerStartupStatus(IntellijMcpServerStartupState.ENABLED_NOT_RUNNING, "not running"),
                    enabled,
                )
            },
            nowMillis = { now },
            sleepMillis = { millis ->
                sleeps += millis
                now += millis
                enabled = false
            },
            timeoutMillis = 2_000L,
            pollIntervalMillis = 500L,
        )

        assertEquals(IntellijMcpServerWaitResult.READY, result)
        assertEquals(listOf(500L), sleeps)
    }

    @Test
    fun buildDispatchDroppedFilesScriptCreatesBrowserDropEvent() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            listOf(
                OpenCodeServerProtocol.DroppedFilePayload(
                    name = "hello 'world'.txt",
                    mime = "text/plain",
                    lastModified = 123,
                    base64 = "aGVsbG8=",
                ),
            ),
        )!!

        assertTrue(script.contains("new DataTransfer()"))
        assertTrue(script.contains("new File([decode(entry.base64)], entry.name"))
        assertTrue(script.contains("[data-component=\"prompt-input\"][contenteditable=\"true\"]"))
        assertTrue(script.contains("const event = new DragEvent('drop'"))
        assertTrue(script.contains("return event.defaultPrevented"))
        assertTrue(script.contains("hello \\'world\\'.txt"))
        assertTrue(script.contains("aGVsbG8="))
        assertTrue(script.contains("const focusPrompt = false"))
        assertTrue(script.contains("if (focusPrompt)"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptFocusesPromptOnlyWhenRequested() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("file:src/main/App.kt"),
            enabled = true,
            focusPrompt = true,
        )!!

        assertTrue(script.contains("const focusPrompt = true"))
        assertTrue(script.contains("if (focusPrompt)"))
        assertTrue(script.contains("target.focus()"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptEscapesUnsafeCharactersInFileNames() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            listOf(
                OpenCodeServerProtocol.DroppedFilePayload(
                    name = "a<b\u2028c\u2029d\u0000e",
                    mime = "text/plain",
                    lastModified = 1,
                    base64 = "aGVsbG8=",
                ),
            ),
        )!!

        assertTrue(script.contains("a\\u003Cb\\u2028c\\u2029d\\u0000e"))
        assertFalse(script.contains("a<b"))
        assertFalse(script.contains("\u2028"))
        assertFalse(script.contains("\u0000"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptCanForwardTextPlainDropData() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("file:src/main/App.kt"),
            enabled = true,
        )!!

        assertTrue(script.contains("transfer.setData('text/plain', 'file:src/main/App.kt')"))
        assertTrue(script.contains("const event = new DragEvent('drop'"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptDispatchesTextPlainDropsSeparately() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("file:CHANGELOG.md", "file:gradle.properties"),
            enabled = true,
        )!!

        assertTrue(script.contains("results.push(dispatchDrop((transfer) => transfer.setData('text/plain', 'file:CHANGELOG.md')))"))
        assertTrue(script.contains("results.push(dispatchDrop((transfer) => transfer.setData('text/plain', 'file:gradle.properties')))"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptPastesGenericTextAndReportsAcceptance() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("selected code"),
            batchId = "chat-1",
            resultCallback = "window.intellijResult(batchId, accepted)",
        )!!

        assertTrue(script.contains("new ClipboardEvent('paste'"))
        assertTrue(script.contains("results.push(dispatchPaste('selected code'))"))
        assertTrue(script.contains("const batchId = 'chat-1'"))
        assertTrue(script.contains("window.intellijResult(batchId, accepted)"))
        assertTrue(script.contains("results.every(Boolean)"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptPastesMultilineSelectionBeginningWithFileReference() {
        val selection = """file:src/main/App.kt
            |src/main/App.kt lines 1-2:
            |```kotlin
            |fun main() = Unit
            |```""".trimMargin()

        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf(selection),
        )!!

        assertTrue(script.contains("results.push(dispatchPaste('file:src/main/App.kt\\n"))
        assertFalse(script.contains("transfer.setData('text/plain', 'file:src/main/App.kt\\n"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWithoutFiles() {
        assertNull(OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(emptyList()))
        assertNull(OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(emptyList(), textPlain = emptyList(), enabled = true))
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                listOf(
                    OpenCodeServerProtocol.DroppedFilePayload(
                        name = "hello.txt",
                        mime = "text/plain",
                        lastModified = 123,
                        base64 = "aGVsbG8=",
                    ),
                ),
                enabled = false,
            ),
        )
    }

    @Test
    fun localFileDropTextUsesOpenCodeProjectRelativeConvention() {
        val projectRoot = Files.createTempDirectory("opencode-project")
        try {
            val file = projectRoot.resolve("src/main/App.kt")
            Files.createDirectories(file.parent)
            Files.writeString(file, "fun main() {}")

            assertEquals(
                "file:src/main/App.kt",
                OpenCodeServerProtocol.localFileDropText(file.toFile(), projectRoot.toString()),
            )
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun localFileDropTextRejectsFilesOutsideProject() {
        val projectRoot = Files.createTempDirectory("opencode-project")
        val outsideRoot = Files.createTempDirectory("opencode-outside")
        try {
            val file = outsideRoot.resolve("outside.txt")
            Files.writeString(file, "outside")

            assertNull(OpenCodeServerProtocol.localFileDropText(file.toFile(), projectRoot.toString()))
        } finally {
            projectRoot.toFile().deleteRecursively()
            outsideRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun buildMatchMediaPatchScriptIsMissingWhenBothDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildMatchMediaPatchScript(compact = false, theme = false, dark = true))
    }

    @Test
    fun buildMatchMediaPatchScriptCombinesCompactAndThemeInOneWrapper() {
        val script = OpenCodeBrowserSnippets.buildMatchMediaPatchScript(compact = true, theme = true, dark = true)!!

        assertEquals(1, Regex("window\\.matchMedia = ").findAll(script).count())
        assertTrue(script.contains("(min-width:768px)"))
        assertTrue(script.contains("THEME_KEY"))
        assertTrue(script.contains("window.__opencodeIntellijOrigMatchMedia"))
    }

    @Test
    fun buildCompactLayoutScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = false))
    }

    @Test
    fun buildCompactLayoutScriptPatchesMatchMediaOnly() {
        val script = OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijCompactInstalled"))
        assertTrue(script.contains("window.matchMedia = "))
        assertTrue(script.contains("(min-width:768px)"))
        assertTrue(script.contains("(max-width:767px)"))
        // Whitespace-tolerant match so minifier/formatter changes do not disable the stub.
        assertTrue(script.contains("const keyOf = (q) =>"))
        assertTrue(script.contains("replace(/\\s+/g, '')"))
        assertTrue(script.contains("stub(q, false)"))
        assertTrue(script.contains("stub(q, true)"))
        // No Tailwind class overrides — layout is driven only by the media-query stub.
        assertFalse(script.contains("opencode-intellij-compact-layout"))
        assertFalse(script.contains("md\\\\:flex-row"))
        assertFalse(script.contains("createElement('style')"))
    }

    @Test
    fun buildHideWebsiteButtonScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = false))
    }

    @Test
    fun buildHideWebsiteButtonScriptUsesDurableHrefSelectors() {
        val script = OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijHideWebsiteButtonInstalled"))
        assertTrue(script.contains("href^=\"https://opencode.ai\""))
        assertTrue(script.contains("data-component*=\"icon-button\""))
        // Locale-specific labels and Tailwind layout utilities are not matchers.
        assertFalse(script.contains(".fixed"))
        assertFalse(script.contains("Open the OpenCode website"))
        assertFalse(script.contains("bottom-5"))
        assertFalse(script.contains("right-5"))
    }

    @Test
    fun buildEventStreamWatchdogScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = false))
    }

    @Test
    fun buildEventStreamWatchdogScriptWatchesEveryEventRouteGeneration() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijEventWatchdogInstalled"))
        // All event route generations the SPA may pick, matched by pathname (not full URL) so
        // origin rewrites and directory/workspace query parameters cannot bypass the watchdog.
        assertTrue(script.contains("'/global/event'"))
        assertTrue(script.contains("'/event'"))
        assertTrue(script.contains("'/api/event'"))
        assertTrue(script.contains("new URL(requestUrl(input), location.href).pathname"))
        assertTrue(script.contains("typeof input.url === 'string'"))
        assertTrue(script.contains("typeof input.href === 'string'"))
        // Recovery works by aborting so OpenCode's own reconnect loop sees a stream error;
        // the plugin must never reimplement the stream or reload the page here.
        assertTrue(script.contains("controller.abort()"))
        assertFalse(script.contains("location.reload"))
    }

    @Test
    fun buildEventStreamWatchdogScriptDoesNotClaimInstallBeforeWrap() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        val installedAt = script.indexOf("window.__opencodeIntellijEventWatchdogInstalled = true")
        val fetchGuardAt = script.indexOf("typeof realFetch !== 'function'")
        assertTrue(installedAt > 0)
        assertTrue(fetchGuardAt > 0)
        assertTrue(fetchGuardAt < installedAt)
    }

    @Test
    fun buildEventStreamWatchdogScriptRewrapsRequestInputs() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        assertTrue(script.contains("input instanceof Request"))
        assertTrue(script.contains("new Request(input, options)"))
        assertTrue(script.contains("globalThis.fetch = watchedFetch"))
    }

    @Test
    fun buildEventStreamWatchdogScriptChainsTheCallerAbortSignal() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        // The SPA cancels its own stream on stop()/cleanup; dropping that signal would leak
        // the previous connection on every reconnect.
        assertTrue(script.contains("outer.addEventListener('abort'"))
        assertTrue(script.contains("outer.removeEventListener('abort'"))
        assertTrue(script.contains("if (outer.aborted) controller.abort();"))
    }

    @Test
    fun buildEventStreamWatchdogScriptTimesOutBeforeResponseHeaders() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        val armAt = script.indexOf("arm();")
        val fetchAt = script.indexOf("return realFetch.call")
        assertTrue(armAt > 0)
        assertTrue(fetchAt > armAt)
    }

    @Test
    fun buildEventStreamWatchdogScriptKeepsTimeoutAboveTheHeartbeatInterval() {
        // Heartbeats arrive every 10s; a timeout at or below that would reconnect endlessly on
        // a perfectly healthy stream.
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        assertTrue(script.contains("const STALL_MS = ${OpenCodeBrowserSnippets.EVENT_STREAM_STALL_TIMEOUT_MILLIS};"))

        val clamped = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true, stallTimeoutMillis = 1_000)!!
        assertTrue(clamped.contains("const STALL_MS = ${OpenCodeBrowserSnippets.MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS};"))
    }

    @Test
    fun buildForceEventReconnectScriptIsANoOpWithoutTheWatchdog() {
        val script = OpenCodeBrowserSnippets.buildForceEventReconnectScript()

        // Fired unconditionally on resume, including when the safeguard is off and the hook
        // was never installed, so it must guard before calling.
        assertTrue(script.contains("typeof force === 'function'"))
        assertTrue(script.contains("window.__opencodeIntellijForceEventReconnect"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = false, fatalCallback = "report();"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptIsIdempotent() {
        val script = OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = true, fatalCallback = "report();")!!

        assertTrue(script.contains("window.__opencodeIntellijChunkRecoveryInstalled"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptCoversScriptSrcAndImportFailures() {
        val script = OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = true, fatalCallback = "report();")!!

        // Module-script src failures surface as resource error events (event.target.src);
        // import() failures surface as the "dynamically imported module" message the boundary shows.
        // Solid's error boundary often catches the rejected lazy() promise, so the same engine
        // text is scanned from the error-page details field (textarea/input value).
        assertTrue(script.contains("addEventListener('error'"))
        assertTrue(script.contains("addEventListener('unhandledrejection'"))
        assertTrue(script.contains("target.tagName === 'SCRIPT'"))
        assertTrue(script.contains("failed to fetch dynamically imported module"))
        assertTrue(script.contains("querySelectorAll('textarea, input')"))
        assertTrue(script.contains("assets"))
        assertFalse(script.contains("location.reload"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptSignalsAtMostOncePerPage() {
        val script = OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = true, fatalCallback = "report();")!!

        // The boundary is terminal: after the first report the reload fixes the renderer, so a
        // second signal would only race another reload into the load the first one started.
        assertTrue(script.contains("let notified = false;"))
        assertTrue(script.contains("notified = true;"))
        assertTrue(script.contains("observer.disconnect()"))
    }

    @Test
    fun buildViewportRasterNudgeScriptTogglesTranslateWithoutHostResize() {
        val script = OpenCodeBrowserSnippets.buildViewportRasterNudgeScript()

        assertTrue(script.contains("document.documentElement"))
        assertTrue(script.contains("setProperty('transform', 'translate(1px, 0)')"))
        assertTrue(script.contains("removeProperty('transform')"))
        assertTrue(script.contains("requestAnimationFrame"))
        assertTrue(script.contains("dispatchEvent(new Event('resize'))"))
        assertFalse(script.contains("data-component"))
        assertFalse(script.contains("setBounds"))
        assertFalse(script.contains("1.001"))
    }

    @Test
    fun isInPlaceDialogRepaintEventCoversAskAndDismiss() {
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("permission.asked"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("permission.replied"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.asked"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.replied"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.rejected"))
        assertFalse(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("session.status"))
        assertFalse(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("session.created"))
    }

    @Test
    fun buildEventStreamWatchdogScriptExposesTheForceReconnectHook() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijForceEventReconnect = () =>"))
        // Finished streams must leave the tracking set or the hook would abort dead controllers
        // and leak them for the lifetime of the page.
        assertTrue(script.contains("active.delete(controller)"))
    }

    @Test
    fun buildCompactLayoutScriptIsIdempotent() {
        val script = OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijMatchMediaInstalled"))
    }

    @Test
    fun buildIdeThemeSyncScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = false, dark = true))
    }

    @Test
    fun buildIdeThemeSyncScriptPatchesMatchMediaForPrefersColorScheme() {
        val darkScript = OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = true)!!
        val lightScript = OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = false)!!

        assertTrue(darkScript.contains("(prefers-color-scheme: dark)"))
        assertTrue(darkScript.contains("const dark = true"))
        assertTrue(lightScript.contains("const dark = false"))
        assertTrue(darkScript.contains("window.__opencodeIntellijMatchMediaInstalled"))
        assertTrue(darkScript.contains("const THEME_KEY = '(prefers-color-scheme:dark)'"))
        assertTrue(darkScript.contains("replace(/\\s+/g, '').toLowerCase()"))
        assertTrue(darkScript.contains("theme && key === THEME_KEY"))
        assertTrue(darkScript.contains("matches: dark"))
        assertFalse(darkScript.contains("window.localStorage.setItem"))
        assertFalse(darkScript.contains("StorageEvent"))
    }

    @Test
    fun buildIdeThemeSyncScriptDispatchesChangeEventOnUpdate() {
        val script = OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = true)!!

        assertTrue(script.contains("MediaQueryListEvent('change'"))
        assertTrue(script.contains("window.__opencodeIntellijThemeMql"))
        assertTrue(script.contains("window.__opencodeIntellijThemeDark !== dark"))
    }

    @Test
    fun buildProjectSwitchPromptSuppressionScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = false))
    }

    @Test
    fun buildProjectSwitchPromptSuppressionScriptDismissesGoToSessionNotifications() {
        val script = OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijProjectSwitchPromptSuppressionInstalled"))
        assertTrue(script.contains("[data-component=\"toast\"], [data-component=\"toast-v2\"]"))
        // Locale-independent structural match: sprite icon names, not translated labels.
        // Both v1 and v2 sprite prefixes are covered so an icon-system migration stays matched.
        assertTrue(script.contains("use[href=\"#opencode-icon-checklist\"]"))
        assertTrue(script.contains("use[href=\"#opencode-icon-bubble-5\"]"))
        assertTrue(script.contains("use[href=\"#opencode-v2-icon-checklist\"]"))
        assertTrue(script.contains("use[href=\"#opencode-v2-icon-bubble-5\"]"))
        assertTrue(script.contains("[data-slot=\"toast-icon\"], [data-slot=\"toast-v2-icon\"]"))
        assertFalse(script.contains("Permission required"))
        assertFalse(script.contains("Go to session"))
        assertTrue(script.contains("[data-slot=\"toast-close-button\"], [data-slot=\"toast-v2-close-button\"]"))
        assertTrue(script.contains("new MutationObserver"))
        // Bounded blast radius: auto-dismissals are capped per page load, and hitting the cap
        // disconnects the observer (suppression off for this load) with a single warning.
        assertTrue(script.contains("const MAX_DISMISSALS = 20;"))
        assertTrue(script.contains("if (dismissals >= MAX_DISMISSALS)"))
        assertTrue(script.contains("observer.disconnect()"))
        assertTrue(script.contains("OpenCode toast suppression cap reached"))
    }

    @Test
    fun buildCursorMirrorScriptIsMissingWhenDisabledOrIncomplete() {
        assertNull(OpenCodeBrowserSnippets.buildCursorMirrorScript(enabled = false, cursorCallback = "cb(payload)"))
        assertNull(OpenCodeBrowserSnippets.buildCursorMirrorScript(enabled = true, cursorCallback = null))
    }

    @Test
    fun buildCursorMirrorScriptTracksHoveredElementCursor() {
        val script = OpenCodeBrowserSnippets.buildCursorMirrorScript(
            enabled = true,
            cursorCallback = "window.intellijCursor(payload)",
        )!!

        assertTrue(script.contains("window.__opencodeIntellijCursorMirrorInstalled"))
        assertTrue(script.contains("getComputedStyle(el).cursor"))
        assertTrue(script.contains("caretPositionFromPoint"))
        assertTrue(script.contains("caretRangeFromPoint"))
        assertTrue(script.contains("addEventListener('pointermove'"))
        assertTrue(script.contains("addEventListener('pointerdown'"))
        assertTrue(script.contains("addEventListener('pointerup'"))
        assertTrue(script.contains("addEventListener('scroll'"))
        assertTrue(script.contains("addEventListener('mouseout'"))
        assertTrue(script.contains("send('default')"))
        assertTrue(script.contains("event.buttons !== 0"))
        assertTrue(script.contains("window.intellijCursor(payload)"))
    }

    @Test
    fun awtCursorTypeCoversCommonCssCursors() {
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss(null))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("default"))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("auto"))
        assertEquals(java.awt.Cursor.HAND_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("pointer"))
        assertEquals(java.awt.Cursor.TEXT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("text"))
        assertEquals(java.awt.Cursor.WAIT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("progress"))
        assertEquals(java.awt.Cursor.S_RESIZE_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("row-resize"))
        assertEquals(java.awt.Cursor.S_RESIZE_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("ns-resize"))
        assertEquals(java.awt.Cursor.W_RESIZE_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("col-resize"))
        assertEquals(java.awt.Cursor.MOVE_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("grabbing"))
        // Unknown keywords resolve to the default arrow; custom cursors use their keyword fallback.
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("zoom-in"))
        assertEquals(java.awt.Cursor.HAND_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("url(\"custom.png\") 4 4, pointer"))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("URL(x.cur)"))
    }

    @Test
    fun buildFilePasteSuppressionScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildFilePasteSuppressionScript(enabled = false))
    }

    @Test
    fun buildFilePasteSuppressionScriptCancelsFilePasteEvents() {
        val script = OpenCodeBrowserSnippets.buildFilePasteSuppressionScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijFilePasteSuppressionInstalled"))
        assertTrue(script.contains("document.addEventListener('paste'"))
        assertFalse(script.contains("__opencodeIntellijSuppressNativeFilePasteUntil"))
        assertTrue(script.contains("item.kind === 'file'"))
        assertTrue(script.contains("includes('Files')"))
        assertTrue(script.contains("event.preventDefault()"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
    }

    @Test
    fun buildCodeNavigationScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildCodeNavigationScript(enabled = false, openCodeCallback = "callback(ref)"))
    }

    @Test
    fun buildCodeNavigationScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeBrowserSnippets.buildCodeNavigationScript(enabled = true, openCodeCallback = null))
    }

    @Test
    fun buildCodeNavigationScriptInstallsClickListenerOnCodeElements() {
        val script = OpenCodeBrowserSnippets.buildCodeNavigationScript(enabled = true, openCodeCallback = "window.intellijOpenCodeRef(ref)")!!

        assertTrue(script.contains("window.__opencodeIntellijCodeNavInstalled"))
        assertTrue(script.contains("event.target.closest('code')"))
        assertTrue(script.contains("codeEl.closest('[data-component=\"markdown\"]')"))
        assertTrue(script.contains("codeEl.closest('pre')"))
        assertTrue(script.contains("codeEl.closest('a')"))
        assertTrue(script.contains("data-inline-code-kind"))
        assertTrue(script.contains("kind === 'url'"))
        assertTrue(script.contains("kind === 'path'"))
        assertTrue(script.contains("isSnakeCase"))
        assertTrue(script.contains("data-opencode-intellij-pointer"))
        assertTrue(script.contains("opencode-intellij-pointer-cursor"))
        assertTrue(script.contains("cursor: pointer !important"))
        assertTrue(script.contains("document.addEventListener('mouseover'"))
        assertTrue(script.contains("document.addEventListener('mouseout'"))
        assertTrue(script.contains("hasExtension"))
        assertTrue(script.contains("hasPathLocator"))
        assertTrue(script.contains("fileLocAtEvent"))
        assertTrue(script.contains("bash-pre"))
        assertTrue(script.contains("tool-output"))
        assertTrue(script.contains("File"))
        assertTrue(script.contains("isUrl"))
        assertTrue(script.contains("isQualifiedClass"))
        assertTrue(script.contains("isPascalCase"))
        assertTrue(script.contains("isTypeMember"))
        assertTrue(script.contains("adjacentLocator"))
        assertTrue(script.contains("withAdjacentLocator"))
        assertTrue(script.contains("codeBesideLocator"))
        assertTrue(script.contains("(L?"))
        assertTrue(script.contains("if (event.defaultPrevented) return"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
        assertTrue(script.contains("window.intellijOpenCodeRef(ref)"))
    }

    @Test
    fun buildFileLinkHandlerScriptStopsAlreadyHandledClicks() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
            "/tmp/project",
            enabled = true,
            openFileCallback = "window.intellijOpenFile(rawHref)",
        )!!

        assertTrue(script.contains("if (event.defaultPrevented) return"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
    }

    @Test
    fun parseSessionInfoReadsBareAndEnvelopedSessions() {
        val bare = OpenCodeServerProtocol.parseSessionInfo("""{"id":"ses_1","title":"Fix the build"}""")!!
        assertEquals("ses_1", bare.id)
        assertEquals("Fix the build", bare.title)
        assertNull(bare.parentID)

        val enveloped = OpenCodeServerProtocol.parseSessionInfo(
            """{"data":{"id":"ses_2","title":"Subtask","parentID":"ses_1"}}""",
        )!!
        assertEquals("Subtask", enveloped.title)
        assertEquals("ses_1", enveloped.parentID)

        val untitled = OpenCodeServerProtocol.parseSessionInfo("""{"id":"ses_3"}""")!!
        assertEquals("", untitled.title)
    }

    @Test
    fun parseSessionInfoRejectsMalformedResponses() {
        assertNull(OpenCodeServerProtocol.parseSessionInfo(""))
        assertNull(OpenCodeServerProtocol.parseSessionInfo("not json"))
        assertNull(OpenCodeServerProtocol.parseSessionInfo("[]"))
        assertNull(OpenCodeServerProtocol.parseSessionInfo("{}"))
        assertNull(OpenCodeServerProtocol.parseSessionInfo("""{"title":"Missing id"}"""))
    }

    @Test
    fun parseSessionChildrenReadsBareAndEnvelopedArrays() {
        val bare = OpenCodeServerProtocol.parseSessionChildren(
            """[{"id":"ses_child","title":"Subtask","parentID":"ses_1"}]""",
        )
        assertEquals(listOf("ses_child"), bare.map { it.id })
        assertEquals(listOf("ses_1"), bare.map { it.parentID })

        val enveloped = OpenCodeServerProtocol.parseSessionChildren(
            """{"data":[{"id":"ses_2","parentID":"ses_1"},{"id":"ses_3","parentID":"ses_1"}]}""",
        )
        assertEquals(listOf("ses_2", "ses_3"), enveloped.map { it.id })
    }

    @Test
    fun parseSessionChildrenRejectsMalformedResponses() {
        assertEquals(emptyList<OpenCodeServerProtocol.SessionInfo>(), OpenCodeServerProtocol.parseSessionChildren(""))
        assertEquals(emptyList<OpenCodeServerProtocol.SessionInfo>(), OpenCodeServerProtocol.parseSessionChildren("not json"))
        assertEquals(emptyList<OpenCodeServerProtocol.SessionInfo>(), OpenCodeServerProtocol.parseSessionChildren("{}"))
        assertEquals(emptyList<OpenCodeServerProtocol.SessionInfo>(), OpenCodeServerProtocol.parseSessionChildren("""{"data":{}}"""))
        assertEquals(
            emptyList<OpenCodeServerProtocol.SessionInfo>(),
            OpenCodeServerProtocol.parseSessionChildren("""[{"title":"Missing id"}]"""),
        )
    }

    @Test
    fun buildSessionRouteEncodesDirectoryAndSession() {
        val root = OpenCodeServerProtocol.buildSessionRoute("/tmp/project", null)
        assertEquals("/" + OpenCodeServerProtocol.encodeDirectory("/tmp/project"), root)
        assertEquals(root, OpenCodeServerProtocol.buildSessionRoute("/tmp/project", ""))
        assertEquals(
            "$root/session/ses_1",
            OpenCodeServerProtocol.buildSessionRoute("/tmp/project", "ses_1"),
        )
        // With a server URL, prefer the 1.18 directoryless route the SPA actually uses.
        val serverRoute = OpenCodeServerProtocol.buildSessionRoute(
            "http://127.0.0.1:60482",
            "/tmp/project",
            "ses_1",
        )
        assertEquals(
            "/server/${OpenCodeServerProtocol.encodeDirectory("http://127.0.0.1:60482")}/session/ses_1",
            serverRoute,
        )
    }

    @Test
    fun sessionIdFromUrlReadsClassicAndServerRoutes() {
        assertEquals(
            "ses_abc123",
            OpenCodeServerProtocol.sessionIdFromUrl("http://127.0.0.1:1234/L3RtcC9wcm9qZWN0/session/ses_abc123"),
        )
        assertEquals(
            "ses_abc123",
            OpenCodeServerProtocol.sessionIdFromUrl("http://127.0.0.1:1234/server/key/session/ses_abc123?x=1#frag"),
        )
        assertNull(OpenCodeServerProtocol.sessionIdFromUrl(null))
        assertNull(OpenCodeServerProtocol.sessionIdFromUrl("http://127.0.0.1:1234/L3RtcA/session"))
        assertNull(OpenCodeServerProtocol.sessionIdFromUrl("http://127.0.0.1:1234/new-session"))
        assertNull(OpenCodeServerProtocol.sessionIdFromUrl("http://127.0.0.1:1234/x/session/ses%2F..%2Fevil"))
    }

    @Test
    fun projectDisplayNameUsesLastPathSegment() {
        assertEquals("project", OpenCodeServerProtocol.projectDisplayName("/tmp/project"))
        assertEquals("project", OpenCodeServerProtocol.projectDisplayName("/tmp/project/"))
        assertEquals("project", OpenCodeServerProtocol.projectDisplayName("C:\\code\\project\\"))
        assertEquals("/", OpenCodeServerProtocol.projectDisplayName("/"))
    }

    @Test
    fun parseBusySessionIdsKeepsBusyAndRetrySessions() {
        val json = """
            {
              "ses_busy": {"type": "busy"},
              "ses_retry": {"type": "retry", "attempt": 2},
              "ses_idle": {"type": "idle"},
              "ses_broken": "busy"
            }
        """.trimIndent()

        assertEquals(setOf("ses_busy", "ses_retry"), OpenCodeServerProtocol.parseBusySessionIds(json))
    }

    @Test
    fun parseBusySessionIdsToleratesMalformedResponses() {
        assertTrue(OpenCodeServerProtocol.parseBusySessionIds("").isEmpty())
        assertTrue(OpenCodeServerProtocol.parseBusySessionIds("not json").isEmpty())
        assertTrue(OpenCodeServerProtocol.parseBusySessionIds("[]").isEmpty())
        assertTrue(OpenCodeServerProtocol.parseBusySessionIds("{}").isEmpty())
    }

    @Test
    fun parsePendingRequestIdsReadsRequestIds() {
        val json = """
            [
              {"id": "per_1", "sessionID": "ses_1"},
              {"id": "que_2"},
              {"sessionID": "ses_2"},
              {"id": ""},
              "per_3"
            ]
        """.trimIndent()

        assertEquals(listOf("per_1", "que_2"), OpenCodeServerProtocol.parsePendingRequestIds(json))
    }

    @Test
    fun parsePendingRequestIdsToleratesMalformedResponses() {
        assertTrue(OpenCodeServerProtocol.parsePendingRequestIds("").isEmpty())
        assertTrue(OpenCodeServerProtocol.parsePendingRequestIds("not json").isEmpty())
        assertTrue(OpenCodeServerProtocol.parsePendingRequestIds("{}").isEmpty())
    }

    @Test
    fun parsePendingRequestsRequiresSafeRequestAndSessionIds() {
        val requests = OpenCodeServerProtocol.parsePendingRequests(
            """[
                {"id":"per_1","sessionID":"ses_1"},
                {"id":"que_2","sessionID":"ses_2"},
                {"id":"per_1","sessionID":"ses_duplicate"},
                {"id":"per/unsafe","sessionID":"ses_3"},
                {"id":"per_4"},
                {"id":"per_5","sessionID":"not-a-session"}
            ]""".trimIndent(),
        )

        assertEquals(
            listOf(
                OpenCodeServerProtocol.PendingRequestSummary("per_1", "ses_1"),
                OpenCodeServerProtocol.PendingRequestSummary("que_2", "ses_2"),
            ),
            requests,
        )
        assertTrue(OpenCodeServerProtocol.parsePendingRequests("{}").isEmpty())
        assertTrue(
            OpenCodeServerProtocol.parsePendingRequestsResult("""[{"id":"per_1"}]""") is
                OpenCodeProtocolResult.Failure,
        )
        assertEquals(
            OpenCodeProtocolResult.Success(
                listOf(OpenCodeServerProtocol.PendingRequestSummary("per_1", "ses_1")),
            ),
            OpenCodeServerProtocol.parsePendingRequestsResult(
                """[{"id":"per_1","sessionID":"ses_1"}]""",
            ),
        )
    }

    @Test
    fun permissionNotificationRequiresSafeRecordIds() {
        val base = OpenCodeServerProtocol.SystemNotificationPayload(
            id = "id", directory = "/tmp", route = "/r", title = "t", body = "b",
            kind = "permission", sessionID = "ses_1", requestID = "per_1",
        )
        assertTrue(OpenCodeServerProtocol.isPermissionNotification(base))
        assertFalse(OpenCodeServerProtocol.isPermissionNotification(base.copy(kind = "session")))
        assertFalse(OpenCodeServerProtocol.isPermissionNotification(base.copy(requestID = "")))
        assertFalse(OpenCodeServerProtocol.isPermissionNotification(base.copy(sessionID = "ses/../evil")))
    }

    @Test
    fun permissionAndQuestionNotificationsDismissByRequest() {
        val permission = OpenCodeServerProtocol.SystemNotificationPayload(
            id = "id", directory = "/tmp", route = "/r", title = "t", body = "b",
            kind = "permission", sessionID = "ses_1", requestID = "per_1",
        )
        assertEquals(listOf("request:per_1"), OpenCodeServerProtocol.notificationDismissKeys(permission))
        assertEquals(
            listOf("request:que_1"),
            OpenCodeServerProtocol.notificationDismissKeys(permission.copy(kind = "question", requestID = "que_1")),
        )
        assertTrue(OpenCodeServerProtocol.notificationDismissKeys(permission.copy(requestID = "")).isEmpty())
    }

    @Test
    fun sessionNotificationsDismissBySession() {
        val session = OpenCodeServerProtocol.SystemNotificationPayload(
            id = "id", directory = "/tmp", route = "/r", title = "t", body = "b",
            kind = "session", sessionID = "ses_1", requestID = "",
        )
        assertEquals(listOf("session:ses_1"), OpenCodeServerProtocol.notificationDismissKeys(session))
        assertTrue(OpenCodeServerProtocol.notificationDismissKeys(session.copy(sessionID = "")).isEmpty())
        assertTrue(OpenCodeServerProtocol.notificationDismissKeys(session.copy(sessionID = "ses/../evil")).isEmpty())
    }

    @Test
    fun parseCodeReferenceExtractsFileNameAndExtension() {
        val ref = OpenCodeServerProtocol.parseCodeReference("Main.kt")!!

        assertEquals("Main.kt", ref.fileName)
        assertEquals("Main.kt", ref.path)
        assertNull(ref.qualifiedName)
        assertEquals("kt", ref.extension)
        assertNull(ref.line)
        assertFalse(ref.hasPath)
    }

    @Test
    fun parseCodeReferenceExtractsLineFromPathWithLine() {
        val ref = OpenCodeServerProtocol.parseCodeReference("src/Main.kt:42")!!

        assertEquals("Main.kt", ref.fileName)
        assertEquals("src/Main.kt", ref.path)
        assertNull(ref.qualifiedName)
        assertEquals("kt", ref.extension)
        assertEquals(41, ref.line)
        assertTrue(ref.hasPath)
    }

    @Test
    fun parseCodeReferenceHandlesPascalCaseClassName() {
        val ref = OpenCodeServerProtocol.parseCodeReference("OpenCodeServerProtocol")!!

        assertEquals("OpenCodeServerProtocol", ref.fileName)
        assertEquals("OpenCodeServerProtocol", ref.path)
        assertNull(ref.qualifiedName)
        assertNull(ref.extension)
        assertNull(ref.line)
    }

    @Test
    fun parseCodeReferenceHandlesQualifiedClassName() {
        val ref = OpenCodeServerProtocol.parseCodeReference("de.moritzf.opencodewebpanel.toolWindow.OpenCodeServerProtocol")!!

        assertEquals("OpenCodeServerProtocol", ref.fileName)
        assertEquals("de.moritzf.opencodewebpanel.toolWindow.OpenCodeServerProtocol", ref.path)
        assertEquals("de.moritzf.opencodewebpanel.toolWindow.OpenCodeServerProtocol", ref.qualifiedName)
        assertNull(ref.extension)
        assertNull(ref.line)
        assertFalse(ref.hasPath)
    }

    @Test
    fun parseCodeReferenceHandlesAbsolutePathWithLine() {
        val ref = OpenCodeServerProtocol.parseCodeReference("/tmp/project/src/Main.kt:42")!!

        assertEquals("Main.kt", ref.fileName)
        assertEquals("/tmp/project/src/Main.kt", ref.path)
        assertNull(ref.qualifiedName)
        assertEquals("kt", ref.extension)
        assertEquals(41, ref.line)
        assertTrue(ref.hasPath)
    }

    @Test
    fun parseCodeReferenceUnderstandsCommonLocators() {
        fun loc(text: String) = OpenCodeServerProtocol.parseCodeReference(text)!!

        val colon = loc("src/main.ts:42")
        assertEquals("src/main.ts", colon.path)
        assertEquals(41, colon.line)
        assertNull(colon.column)

        val colonCol = loc("src/main.ts:42:13")
        assertEquals("src/main.ts", colonCol.path)
        assertEquals(41, colonCol.line)
        assertEquals(12, colonCol.column)

        val github = loc("src/main.ts#L42")
        assertEquals("src/main.ts", github.path)
        assertEquals(41, github.line)

        val githubRange = loc("src/main.ts#L42-L57")
        assertEquals("src/main.ts", githubRange.path)
        assertEquals(41, githubRange.line)

        val lRange = loc("Foo.java:L123-1234")
        assertEquals("Foo.java", lRange.path)
        assertEquals("java", lRange.extension)
        assertEquals(122, lRange.line)

        val vs = loc("src/main.ts(42)")
        assertEquals("src/main.ts", vs.path)
        assertEquals(41, vs.line)
        assertNull(vs.column)

        val msvc = loc("src/main.ts(42,13)")
        assertEquals("src/main.ts", msvc.path)
        assertEquals(41, msvc.line)
        assertEquals(12, msvc.column)

        val windows = loc("""C:\proj\Foo.java:L10""")
        assertEquals("""C:\proj\Foo.java""", windows.path)
        assertEquals(9, windows.line)

        val parenL = loc("Foo.java(L98)")
        assertEquals("Foo.java", parenL.path)
        assertEquals(97, parenL.line)

        val colonNoL = loc("production.xsd:16488")
        assertEquals("production.xsd", colonNoL.path)
        assertEquals(16487, colonNoL.line)
    }

    @Test
    fun parseCodeReferenceStripsMethodCallToType() {
        val method = OpenCodeServerProtocol.parseCodeReference("PackagingMailingBarcodeDefinition.isWithScanRule()")!!
        assertEquals("PackagingMailingBarcodeDefinition", method.fileName)
        assertEquals("PackagingMailingBarcodeDefinition", method.path)
        assertNull(method.qualifiedName)
        assertNull(method.extension)
        assertNull(method.line)

        val withLine = OpenCodeServerProtocol.parseCodeReference("PackagingMailingBarcodeDefinition.isWithScanRule()(L98)")!!
        assertEquals("PackagingMailingBarcodeDefinition", withLine.fileName)
        assertEquals(97, withLine.line)

        val qualified = OpenCodeServerProtocol.parseCodeReference("java.util.Optional.of(Boolean.TRUE)")!!
        assertEquals("Optional", qualified.fileName)
        assertEquals("java.util.Optional", qualified.path)
        assertEquals("java.util.Optional", qualified.qualifiedName)
        assertNull(qualified.extension)
    }

    @Test
    fun parseCodeReferenceKeepsLowercaseCallUnchanged() {
        val ref = OpenCodeServerProtocol.parseCodeReference("handle(CurrentSheetField)")!!
        assertEquals("handle(CurrentSheetField)", ref.fileName)
        assertEquals("handle(CurrentSheetField)", ref.path)
        assertNull(ref.extension)
    }

    @Test
    fun parseCodeReferenceAllowsSpacesWhenExtensionPresent() {
        val ref = OpenCodeServerProtocol.parseCodeReference("Manuelle Verpackung und PM-Mobile.xml:L1063")!!
        assertEquals("Manuelle Verpackung und PM-Mobile.xml", ref.path)
        assertEquals("xml", ref.extension)
        assertEquals(1062, ref.line)
    }

    @Test
    fun pickDistinctPathRequiresAUniqueWinner() {
        assertEquals("/a/src/Main.kt", OpenCodeServerProtocol.pickDistinctPath(listOf("/a/src/Main.kt"), "Main.kt"))
        assertNull(
            OpenCodeServerProtocol.pickDistinctPath(
                listOf("/a/src/Main.kt", "/b/src/Main.kt"),
                "Main.kt",
            ),
        )
        assertEquals(
            "/repo/packages/app/src/Main.kt",
            OpenCodeServerProtocol.pickDistinctPath(
                listOf("/repo/packages/app/src/Main.kt", "/repo/other/Main.kt"),
                "src/Main.kt",
            ),
        )
    }

    @Test
    fun parseCodeReferenceReturnsNullForBlankInput() {
        assertNull(OpenCodeServerProtocol.parseCodeReference(""))
        assertNull(OpenCodeServerProtocol.parseCodeReference("   "))
    }

    @Test
    fun openFileLinkRequestParsesHref() {
        val url =
            "${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}?href=src%2FMain.kt&base=%2Ftmp%2Fproject"

        assertTrue(OpenCodeServerProtocol.isOpenFileLinkRequest(url))
        assertEquals("src/Main.kt", OpenCodeServerProtocol.openFileLinkHref(url))
        assertEquals("/tmp/project", OpenCodeServerProtocol.openFileLinkBase(url))
        assertFalse(OpenCodeServerProtocol.isOpenFileLinkRequest("https://example.com/src/Main.kt"))
    }

    @Test
    fun openFileLinkPayloadCarriesHrefAndBasePath() {
        val payload = OpenCodeServerProtocol.parseOpenFileLinkPayload("src/Main.kt\n/tmp/project")

        assertNotNull(payload)
        assertEquals("src/Main.kt", payload!!.href)
        assertEquals("/tmp/project", payload.basePath)
        assertNull(OpenCodeServerProtocol.parseOpenFileLinkPayload(""))
    }

    @Test
    fun routeDirectoryFromUrlDecodesOpenCodeProjectRoute() {
        val directory = "/Users/moritz/Desktop/git/benjamin"
        val url = "http://127.0.0.1:57099/${OpenCodeServerProtocol.encodeDirectory(directory)}/session/license.md"

        assertEquals(directory, OpenCodeServerProtocol.routeDirectoryFromUrl(url))
        // Directoryless 1.18 routes must not invent a project directory from the "server" segment.
        assertNull(
            OpenCodeServerProtocol.routeDirectoryFromUrl(
                "http://127.0.0.1:57099/server/aHR0cDovLzEyNy4wLjAuMTo1NzA5OQ/session/ses_1",
            ),
        )
        assertNull(OpenCodeServerProtocol.routeDirectoryFromUrl("http://127.0.0.1:57099/new-session"))

        val uncDirectory = "\\\\server\\share\\project"
        val uncUrl = "http://127.0.0.1:57099/${OpenCodeServerProtocol.encodeDirectory(uncDirectory)}/session/ses_1"
        assertEquals(uncDirectory, OpenCodeServerProtocol.routeDirectoryFromUrl(uncUrl))
    }

    @Test
    fun isSameFilesystemPathTreatsWindowsSeparatorsAndDriveCaseAsEquivalent() {
        assertTrue(OpenCodeServerProtocol.isSameFilesystemPath("C:\\Source\\Project", "c:/source/project/"))
        assertTrue(OpenCodeServerProtocol.isSameFilesystemPath("\\\\SERVER\\Share\\Project", "//server/share/project/"))
        assertFalse(OpenCodeServerProtocol.isSameFilesystemPath("C:\\Source\\Project", "C:/Source/Other"))
    }

    @Test
    fun isSameFilesystemPathResolvesSymlinkAliasesWhenAvailable() {
        val root = Files.createTempDirectory("opencode-path-alias")
        try {
            val target = Files.createDirectory(root.resolve("target"))
            val link = root.resolve("link")
            if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) {
                assertTrue(OpenCodeServerProtocol.isSameFilesystemPath(link.toString(), target.toString()))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun canonicalOpenCodeDirectoryResolvesSymlinkAliases() {
        val root = Files.createTempDirectory("opencode-canonical-dir")
        try {
            val target = Files.createDirectory(root.resolve("target"))
            val link = root.resolve("link")
            if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) {
                assertEquals(
                    target.toRealPath().toString(),
                    OpenCodeServerProtocol.canonicalOpenCodeDirectory(link.toString()),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun adoptOpenCodeDirectoryPrefersTheServerSpellingOfTheSameFolder() {
        val root = Files.createTempDirectory("opencode-adopt-dir")
        try {
            val target = Files.createDirectory(root.resolve("target"))
            val link = root.resolve("link")
            if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) {
                assertEquals(
                    target.toRealPath().toString(),
                    OpenCodeServerProtocol.adoptOpenCodeDirectory(link.toString(), target.toRealPath().toString()),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun adoptOpenCodeDirectoryKeepsTheProjectRootWhenTheServerPathIsADifferentFolder() {
        assertEquals(
            OpenCodeServerProtocol.canonicalOpenCodeDirectory("/tmp/project"),
            OpenCodeServerProtocol.adoptOpenCodeDirectory("/tmp/project", "/tmp/project-worktree"),
        )
    }

    @Test
    fun resolveFileLinkPrefersOpenCodeRouteDirectoryForRelativeLinks() {
        val ideProjectDir = Files.createTempDirectory("opencode-ide-project")
        val openCodeDir = Files.createTempDirectory("opencode-route-project")
        val file = Files.writeString(openCodeDir.resolve("license.md"), "license")

        val target = OpenCodeServerProtocol.resolveFileLink("license.md", ideProjectDir.toString(), openCodeDir.toString())

        assertNotNull(target)
        assertEquals(file.normalize(), target!!.path)
    }

    @Test
    fun resolveFileLinkChecksEveryExactBaseBeforeGuessing() {
        val guessBase = Files.createTempDirectory("opencode-guess-base")
        val exactBase = Files.createTempDirectory("opencode-exact-base")
        Files.createDirectories(guessBase.resolve("nested/src"))
        Files.writeString(guessBase.resolve("nested/src/Main.kt"), "guess")
        Files.createDirectories(exactBase.resolve("src"))
        val exact = Files.writeString(exactBase.resolve("src/Main.kt"), "exact")

        val target = OpenCodeServerProtocol.resolveFileLinkWithBases(
            "src/Main.kt",
            listOf(guessBase.toString(), exactBase.toString()),
        )

        assertEquals(exact.normalize(), target?.path)
    }

    @Test
    fun resolveFileLinkGuessUsesCaseInsensitiveFilesystemSemanticsWhenRequested() {
        val base = Files.createTempDirectory("opencode-case-guess")
        Files.createDirectories(base.resolve("nested/src"))
        val actual = Files.writeString(base.resolve("nested/src/Main.kt"), "x")

        val target = OpenCodeServerProtocol.resolveFileLinkWithBases(
            "SRC/main.kt",
            listOf(base.toString()),
            caseSensitive = false,
        )

        assertEquals(actual.normalize(), target?.path)
    }

    @Test
    fun resolveFileLinkDoesNotPruneCaseDistinctSourceDirectories() {
        val base = Files.createTempDirectory("opencode-case-prune")
        Files.createDirectories(base.resolve("Build/src"))
        val actual = Files.writeString(base.resolve("Build/src/Main.kt"), "x")

        val target = OpenCodeServerProtocol.resolveFileLinkWithBases(
            "src/Main.kt",
            listOf(base.toString()),
            caseSensitive = true,
        )

        assertEquals(actual.normalize(), target?.path)
    }

    @Test
    fun resolveFileLinkDecodesPercentEncodedRelativeLinks() {
        // OpenCode renders markdown links through marked, which runs encodeURI on every href, so
        // any relative path with a space or non-ASCII character arrives percent-encoded. Plain
        // ASCII paths are untouched, which is why only *some* relative links used to fail.
        val base = Files.createTempDirectory("opencode-encoded")
        Files.createDirectories(base.resolve("docs"))
        val spaced = Files.writeString(base.resolve("docs/My File.md"), "x")
        val umlaut = Files.writeString(base.resolve("docs/Ümlaut.md"), "x")
        val plus = Files.writeString(base.resolve("docs/a+b.txt"), "x")

        assertEquals(spaced.normalize(), OpenCodeServerProtocol.resolveFileLink("docs/My%20File.md", base.toString(), null)?.path)
        assertEquals(umlaut.normalize(), OpenCodeServerProtocol.resolveFileLink("docs/%C3%9Cmlaut.md", base.toString(), null)?.path)
        // `+` is a literal in a path segment: both spellings must land on the same file.
        assertEquals(plus.normalize(), OpenCodeServerProtocol.resolveFileLink("docs/a+b.txt", base.toString(), null)?.path)
        assertEquals(plus.normalize(), OpenCodeServerProtocol.resolveFileLink("docs/a%2Bb.txt", base.toString(), null)?.path)
        // The raw spelling still wins for a name that really contains a percent escape.
        val literal = Files.writeString(base.resolve("docs/100%25.md"), "x")
        assertEquals(literal.normalize(), OpenCodeServerProtocol.resolveFileLink("docs/100%25.md", base.toString(), null)?.path)
    }

    @Test
    fun resolveFileLinkReadsRelativeFileUrlsWithoutAuthority() {
        // `file:<relative>` is the exact spelling the panel writes for dropped files.
        val base = Files.createTempDirectory("opencode-file-scheme")
        Files.createDirectories(base.resolve("src"))
        val file = Files.writeString(base.resolve("src/Main.kt"), "x")

        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink("file:src/Main.kt", base.toString(), null)?.path)
        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink("file://src/Main.kt", base.toString(), null)?.path)
    }

    @Test
    fun resolveFileLinkFallsBackToProjectRootForRootRelativeLinks() {
        // `/src/Main.kt` is absolute on Unix but is just as often meant project-relative, and on
        // Windows it is not absolute at all (resolve would drop the drive).
        val base = Files.createTempDirectory("opencode-root-relative")
        Files.createDirectories(base.resolve("src"))
        val file = Files.writeString(base.resolve("src/Main.kt"), "x")

        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink("/src/Main.kt", base.toString(), null)?.path)
        // A real absolute path still wins over the project-relative reading.
        val outside = Files.createTempDirectory("opencode-outside")
        val outsideFile = Files.writeString(outside.resolve("Other.kt"), "x")
        assertEquals(
            outsideFile.normalize(),
            OpenCodeServerProtocol.resolveFileLink(outsideFile.toString(), base.toString(), null)?.path,
        )
    }

    @Test
    fun resolveFileLinkTrimsDecoratedHrefs() {
        val base = Files.createTempDirectory("opencode-decorated")
        Files.createDirectories(base.resolve("src"))
        val file = Files.writeString(base.resolve("src/Main.kt"), "x")

        for (href in listOf(" src/Main.kt", "src/Main.kt ", "\u202Asrc/Main.kt\u202C")) {
            assertEquals(
                "href $href",
                file.normalize(),
                OpenCodeServerProtocol.resolveFileLink(href, base.toString(), null)?.path,
            )
        }
    }

    @Test
    fun resolveFileLinkComposesRootRelativeLinksWithTrailingLineNumbers() {
        val base = Files.createTempDirectory("opencode-root-line")
        Files.createDirectories(base.resolve("src"))
        val file = Files.writeString(base.resolve("src/Main.kt"), "x")

        val target = OpenCodeServerProtocol.resolveFileLink("/src/Main.kt:42", base.toString(), null)
        assertEquals(file.normalize(), target?.path)
        assertEquals(41, target?.line)
    }

    @Test
    fun resolveFileLinkKeepsTrailingColonWhenItIsPartOfTheName() {
        val base = Files.createTempDirectory("opencode-colon")
        Files.createDirectories(base.resolve("src"))
        val literal = Files.writeString(base.resolve("src/Main.kt:42"), "x")

        // The whole spelling exists, so it wins and no line number leaks onto it.
        val target = OpenCodeServerProtocol.resolveFileLink("src/Main.kt:42", base.toString(), null)
        assertEquals(literal.normalize(), target?.path)
        assertNull(target?.line)

        // With the stripped file present too, the line reading wins — the common case.
        val stripped = Files.writeString(base.resolve("src/Main.kt"), "x")
        val lineTarget = OpenCodeServerProtocol.resolveFileLink("src/Main.kt:42", base.toString(), null)
        assertEquals(stripped.normalize(), lineTarget?.path)
        assertEquals(41, lineTarget?.line)
    }

    @Test
    fun resolveFileLinkCarriesPositionsThroughEncodedSpellings() {
        val base = Files.createTempDirectory("opencode-encoded-line")
        Files.createDirectories(base.resolve("docs"))
        val file = Files.writeString(base.resolve("docs/My File.md"), "x")

        val fragment = OpenCodeServerProtocol.resolveFileLink("docs/My%20File.md#L12:5", base.toString(), null)
        assertEquals(file.normalize(), fragment?.path)
        assertEquals(11, fragment?.line)
        assertEquals(4, fragment?.column)

        val query = OpenCodeServerProtocol.resolveFileLink("docs/My%20File.md?start=10", base.toString(), null)
        assertEquals(file.normalize(), query?.path)
        assertEquals(9, query?.line)

        val trailing = OpenCodeServerProtocol.resolveFileLink("docs/My%20File.md:8", base.toString(), null)
        assertEquals(file.normalize(), trailing?.path)
        assertEquals(7, trailing?.line)
    }

    @Test
    fun resolveFileLinkSurvivesPathCharactersThatCannotBeParsed() {
        val base = Files.createTempDirectory("opencode-illegal")
        // Percent-decoding can produce characters that are illegal in a path (NUL everywhere,
        // `<>?*|"` on Windows). Those must fail closed, not throw out of the click handler.
        for (href in listOf("docs/a%00b.md", "docs/a%00b.md:12", "src/%3Cname%3E.kt:10", "docs/a%zz.md", "docs/trailing%")) {
            assertNull("href $href", OpenCodeServerProtocol.resolveFileLink(href, base.toString(), null))
        }
    }

    @Test
    fun resolveFileLinkGuessesIncompleteSubPaths() {
        val base = Files.createTempDirectory("opencode-guess")
        Files.createDirectories(base.resolve("packages/app/src"))
        val nested = Files.writeString(base.resolve("packages/app/src/Main.kt"), "x")

        // Reference is missing the leading segments the project actually has.
        assertEquals(nested.normalize(), OpenCodeServerProtocol.resolveFileLink("src/Main.kt", base.toString(), null)?.path)
        assertEquals(nested.normalize(), OpenCodeServerProtocol.resolveFileLink("Main.kt", base.toString(), null)?.path)
        // …and keeps the line number from the reference.
        val withLine = OpenCodeServerProtocol.resolveFileLink("src/Main.kt:7", base.toString(), null)
        assertEquals(nested.normalize(), withLine?.path)
        assertEquals(6, withLine?.line)

        // Reference is anchored above the panel's directory: the base already sits inside it.
        val inner = base.resolve("packages/app").toString()
        assertEquals(nested.normalize(), OpenCodeServerProtocol.resolveFileLink("packages/app/src/Main.kt", inner, null)?.path)

        // An exact match always wins over a guess.
        Files.createDirectories(base.resolve("src"))
        val exact = Files.writeString(base.resolve("src/Main.kt"), "x")
        assertEquals(exact.normalize(), OpenCodeServerProtocol.resolveFileLink("src/Main.kt", base.toString(), null)?.path)
    }

    @Test
    fun resolveFileLinkGuessPrefersTheMostSpecificMatch() {
        val base = Files.createTempDirectory("opencode-guess-rank")
        Files.createDirectories(base.resolve("a/src"))
        Files.createDirectories(base.resolve("b/other"))
        val wanted = Files.writeString(base.resolve("a/src/Main.kt"), "x")
        Files.writeString(base.resolve("b/other/Main.kt"), "x")

        // Both files share the name; only one also matches the `src` segment.
        assertEquals(wanted.normalize(), OpenCodeServerProtocol.resolveFileLink("src/Main.kt", base.toString(), null)?.path)
    }

    @Test
    fun resolveFileLinkGuessSkipsBuildAndVcsDirectories() {
        val base = Files.createTempDirectory("opencode-guess-prune")
        Files.createDirectories(base.resolve("node_modules/pkg/src"))
        Files.createDirectories(base.resolve("build/generated"))
        Files.writeString(base.resolve("node_modules/pkg/src/Only.kt"), "x")
        Files.writeString(base.resolve("build/generated/Only.kt"), "x")

        assertNull(OpenCodeServerProtocol.resolveFileLink("Only.kt", base.toString(), null))
    }

    @Test
    fun scoreFilePathSuffixPrefersTheLongerTrailingMatch() {
        assertTrue(
            OpenCodeServerProtocol.scoreFilePathSuffix("/repo/packages/app/src/Main.kt", "src/Main.kt") >
                OpenCodeServerProtocol.scoreFilePathSuffix("/repo/other/Main.kt", "src/Main.kt"),
        )
        assertEquals(0, OpenCodeServerProtocol.scoreFilePathSuffix("/repo/other/Util.kt", "src/Main.kt"))
    }

    @Test
    fun resolveFileLinkDoesNotGuessForMissingFiles() {
        val base = Files.createTempDirectory("opencode-guess-miss")
        Files.createDirectories(base.resolve("src"))
        Files.writeString(base.resolve("src/Main.kt"), "x")

        assertNull(OpenCodeServerProtocol.resolveFileLink("src/Missing.kt", base.toString(), null))
        assertNull(OpenCodeServerProtocol.resolveFileLink("Missing.kt", base.toString(), null))
    }

    @Test
    fun resolveFileLinkIgnoresRootRelativeHrefWithoutAFileSuffix() {
        val base = Files.createTempDirectory("opencode-root-rel")
        Files.createDirectories(base.resolve("src"))
        Files.writeString(base.resolve("src/Main.kt"), "x")

        assertNull(OpenCodeServerProtocol.resolveFileLink("/future-spa-page", base.toString(), null))
        assertEquals(
            base.resolve("src/Main.kt").normalize(),
            OpenCodeServerProtocol.resolveFileLink("/src/Main.kt", base.toString(), null)?.path,
        )
        assertTrue(OpenCodeServerProtocol.lastPathSegmentLooksLikeFile("/src/Main.kt"))
        assertFalse(OpenCodeServerProtocol.lastPathSegmentLooksLikeFile("/server/abc/session/ses_123"))
    }

    @Test
    fun isOpenCodeSessionRouteHrefDetectsOpenCodeAppSessionRoutes() {
        val projectRoute = "/${OpenCodeServerProtocol.encodeDirectory("/tmp/project")}/session/session-id"
        val serverRoute = "/server/aHR0cDovLzEyNy4wLjAuMTo2MDQ4Mg/session/ses_child"

        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref(projectRoute))
        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("http://127.0.0.1:60482$projectRoute"))
        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref(serverRoute))
        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("http://127.0.0.1:60482$serverRoute"))
        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("/new-session"))
        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("/new-session?draftId=abc"))
        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("/"))
        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("/server/abc"))
        assertTrue(
            OpenCodeServerProtocol.isOpenCodeSessionRouteHref(
                "/${OpenCodeServerProtocol.encodeDirectory("/tmp/project")}",
            ),
        )
        assertTrue(
            OpenCodeServerProtocol.isOpenCodeSessionRouteHref(
                "/${OpenCodeServerProtocol.encodeDirectory("\\\\server\\share\\project")}/session/ses_unc",
            ),
        )
        assertFalse(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("/tmp/session/readme.md"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("license.md"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("/src/Main.kt"))
    }

    @Test
    fun resolveFileLinkIgnoresOpenCodeAppSessionRoutes() {
        val projectDir = Files.createTempDirectory("opencode-file-link-test")
        val route = "/${OpenCodeServerProtocol.encodeDirectory(projectDir.toString())}/session/session-id"
        val serverRoute = "/server/aHR0cDovLzEyNy4wLjAuMTo2MDQ4Mg/session/ses_child"

        assertNull(OpenCodeServerProtocol.resolveFileLink(route, projectDir.toString()))
        assertNull(OpenCodeServerProtocol.resolveFileLink(serverRoute, projectDir.toString()))
    }

    @Test
    fun resolveFileLinkUsesOpenCodeFileUrlStartQueryAsLine() {
        val projectDir = Files.createTempDirectory("opencode-file-link-test")
        val file = Files.writeString(projectDir.resolve("Main.kt"), "fun main() {}")

        val target = OpenCodeServerProtocol.resolveFileLink("${file.toUri()}?start=10&end=20", projectDir.toString())

        assertNotNull(target)
        assertEquals(file.normalize(), target!!.path)
        assertEquals(9, target.line)
        assertNull(target.column)
    }

    @Test
    fun resolveFileLinkSupportsOpenCodeRelativeFileUrlTabs() {
        val projectDir = Files.createTempDirectory("opencode-file-link-test")
        val file = Files.createDirectories(projectDir.resolve("src")).resolve("Main.kt")
        Files.writeString(file, "fun main() {}")

        val target = OpenCodeServerProtocol.resolveFileLink("file://src/Main.kt?start=3&end=4", projectDir.toString())

        assertNotNull(target)
        assertEquals(file.normalize(), target!!.path)
        assertEquals(2, target.line)
        assertNull(target.column)
    }

    @Test
    fun resolveFileLinkSupportsSandboxLinksByProjectFileName() {
        val projectDir = Files.createTempDirectory("opencode-file-link-test")
        val file = Files.writeString(projectDir.resolve("bcoca-reference-11.pdf"), "pdf")

        val target = OpenCodeServerProtocol.resolveFileLink(
            "sandbox:/mnt/data/bcoca-reference-11.pdf",
            projectDir.toString(),
        )

        assertNotNull(target)
        assertEquals(file.normalize(), target!!.path)
        assertNull(target.line)
        assertNull(target.column)
    }


    @Test
    fun buildBasicAuthHeaderUsesOpenCodeUsername() {
        assertEquals(
            "Basic b3BlbmNvZGU6c2VjcmV0LXBhc3N3b3Jk",
            OpenCodeServerProtocol.buildBasicAuthHeader("secret-password"),
        )
    }

    @Test
    fun buildAuthTokenUsesOpenCodeUsername() {
        assertEquals("b3BlbmNvZGU6c2VjcmV0LXBhc3N3b3Jk", OpenCodeServerProtocol.buildAuthToken("secret-password"))
    }

    @Test
    fun shouldSendBasicAuthHeaderOnlyForOpenCodeServerRequests() {
        val serverUrl = "http://127.0.0.1:60482"

        assertTrue(OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverUrl, "http://127.0.0.1:60482/"))
        assertTrue(OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverUrl, "http://127.0.0.1:60482/assets/index.js"))
        assertTrue(OpenCodeServerProtocol.shouldSendBasicAuthHeader("http://127.0.0.1", "http://127.0.0.1/global/config"))
        assertFalse(OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverUrl, "http://127.0.0.1:60483/"))
        assertFalse(OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverUrl, "http://localhost:60482/"))
        assertFalse(OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverUrl, "https://127.0.0.1:60482/"))
        assertFalse(OpenCodeServerProtocol.shouldSendBasicAuthHeader(null, "http://127.0.0.1:60482/"))
        assertFalse(OpenCodeServerProtocol.shouldSendBasicAuthHeader(serverUrl, null))
    }

    @Test
    fun isOpenCodeServerPageOnlyMatchesCurrentServer() {
        assertTrue(OpenCodeServerProtocol.isOpenCodeServerPage("http://127.0.0.1:60482", "http://127.0.0.1:60482/"))
        assertTrue(OpenCodeServerProtocol.isOpenCodeServerPage("http://127.0.0.1:60482", "http://127.0.0.1:60482/foo/session"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeServerPage("http://127.0.0.1:60482", "http://127.0.0.1:60483/foo/session"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeServerPage(null, "http://127.0.0.1:60482/foo/session"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeServerPage("http://127.0.0.1:60482", null))
    }

    @Test
    fun isOpenCodeRouteAlreadyOpenMatchesCurrentRoute() {
        assertTrue(
            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                "http://127.0.0.1:60482",
                "http://127.0.0.1:60482/L3RtcC9wcm9qZWN0/session/ses_123",
                "/L3RtcC9wcm9qZWN0/session/ses_123",
            ),
        )
        // Same session under the 1.18 server route vs a legacy directory route is already open.
        assertTrue(
            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                "http://127.0.0.1:60482",
                "http://127.0.0.1:60482/server/abc/session/ses_123",
                "/L3RtcC9wcm9qZWN0/session/ses_123",
            ),
        )
        assertTrue(
            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                "http://127.0.0.1:60482",
                "http://127.0.0.1:60482/server/abc/session/ses_123",
                OpenCodeServerProtocol.buildServerSessionUrl("http://127.0.0.1:60482", "ses_123"),
            ),
        )
    }

    @Test
    fun isOpenCodeRouteAlreadyOpenRejectsDifferentRouteOrServer() {
        assertFalse(
            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                "http://127.0.0.1:60482",
                "http://127.0.0.1:60482/L3RtcC9wcm9qZWN0/session/ses_123",
                "/L3RtcC9wcm9qZWN0/session/ses_456",
            ),
        )
        assertFalse(
            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                "http://127.0.0.1:60482",
                "http://127.0.0.1:60483/L3RtcC9wcm9qZWN0/session/ses_123",
                "/L3RtcC9wcm9qZWN0/session/ses_123",
            ),
        )
    }

    @Test
    fun isOpenCodeRouteAlreadyOpenIncludesQueryButIgnoresTrailingPathSlash() {
        assertTrue(
            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                "http://127.0.0.1:60482",
                "http://127.0.0.1:60482/server/abc/session/ses_123/?tab=ask",
                "/server/abc/session/ses_123?tab=ask",
            ),
        )
        assertFalse(
            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                "http://127.0.0.1:60482",
                "http://127.0.0.1:60482/server/abc/session/ses_123?tab=ask",
                "/server/abc/session/ses_123?tab=review",
            ),
        )
        // A trailing slash inside a query value is significant, not path noise.
        assertFalse(
            OpenCodeServerProtocol.isOpenCodeRouteAlreadyOpen(
                "http://127.0.0.1:60482",
                "http://127.0.0.1:60482/server/abc/session/ses_123?tab=ask/",
                "/server/abc/session/ses_123?tab=ask",
            ),
        )
    }

    @Test
    fun encodeDirectoryMatchesOpenCodeUrlSafeBase64Format() {
        assertEquals("L3RtcC9wcm9qZWN0", OpenCodeServerProtocol.encodeDirectory("/tmp/project"))
        assertFalse(OpenCodeServerProtocol.encodeDirectory("/tmp/project").contains("="))
    }

    @Test
    fun generateServerPasswordReturnsUrlSafeSecret() {
        val password = OpenCodeServerProtocol.generateServerPassword()

        assertTrue(password.length >= 40)
        assertTrue(password.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun shouldHandleBasicAuthChallengeOnlyForOpenCodeServer() {
        val serverUrl = "http://127.0.0.1:60482"

        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, false, "127.0.0.1", 60482))
        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge("http://127.0.0.1", false, "127.0.0.1", 80))
        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge("https://127.0.0.1", false, "127.0.0.1", 443))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, true, "127.0.0.1", 60482))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, false, "localhost", 60482))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, false, "127.0.0.1", 60483))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(null, false, "127.0.0.1", 60482))
    }

    @Test
    fun checkServerRespondingReturnsTrueForOpenCodeHealthResponse() {
        withSingleRequestHttpServer(body = "{\"healthy\":true}") { url ->
            assertTrue(
                OpenCodeServerProtocol.checkServerResponding(
                    url,
                    basicAuthHeader = "Basic test-token",
                    connectTimeoutMillis = 1000,
                    readTimeoutMillis = 1000,
                ),
            )
        }
    }

    @Test
    fun checkServerRespondingRejectsNonOpenCodeHealthResponse() {
        withSingleRequestHttpServer(body = "ok") { url ->
            assertFalse(
                OpenCodeServerProtocol.checkServerResponding(
                    url,
                    basicAuthHeader = "Basic test-token",
                    connectTimeoutMillis = 1000,
                    readTimeoutMillis = 1000,
                ),
            )
        }
    }

    @Test
    fun checkServerRespondingRequiresRootBooleanHealthField() {
        for (body in listOf("""{"meta":{"healthy":true}}""", """{"healthy":"true"}""", """[{"healthy":true}]""")) {
            withSingleRequestHttpServer(body = body) { url ->
                assertFalse(OpenCodeServerProtocol.checkServerResponding(url))
            }
        }
    }

    @Test
    fun classifyEmbeddedProtocolMatchesTheSpaProbe() {
        val v1 = OpenCodeProtocolResult.Success("""{"healthy":true,"version":"1.18.10"}""")
        val v2 = OpenCodeProtocolResult.Success("""{"pid":12}""")
        val notFound = OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 404)
        val timeout = OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.TIMEOUT)

        assertEquals(OpenCodeEmbeddedProtocol.V1, OpenCodeServerProtocol.classifyEmbeddedProtocolForTest(v1, null))
        assertEquals(OpenCodeEmbeddedProtocol.V1, OpenCodeServerProtocol.classifyEmbeddedProtocolForTest(notFound, v1))
        assertEquals(OpenCodeEmbeddedProtocol.V2, OpenCodeServerProtocol.classifyEmbeddedProtocolForTest(notFound, v2))
        assertEquals(OpenCodeEmbeddedProtocol.V2, OpenCodeServerProtocol.classifyEmbeddedProtocolForTest(notFound, notFound))
        assertEquals(OpenCodeEmbeddedProtocol.UNKNOWN, OpenCodeServerProtocol.classifyEmbeddedProtocolForTest(timeout, timeout))
        assertEquals(OpenCodeEmbeddedProtocol.UNKNOWN, OpenCodeServerProtocol.classifyEmbeddedProtocolForTest(timeout, null))
    }

    @Test
    fun fetchServerVersionRequiresRootStringField() {
        withSingleRequestHttpServer(
            body = """{"healthy":true,"version":"1.18.2"}""",
            expectedRequestLine = "GET ${OpenCodeServerProtocol.GLOBAL_HEALTH_PATH} HTTP/1.1",
        ) { url ->
            assertEquals("1.18.2", OpenCodeServerProtocol.fetchServerVersion(url, null))
        }
        withSingleRequestHttpServer(
            body = """{"metadata":{"version":"9.9.9"}}""",
            expectedRequestLine = "GET ${OpenCodeServerProtocol.GLOBAL_HEALTH_PATH} HTTP/1.1",
        ) { url ->
            assertNull(OpenCodeServerProtocol.fetchServerVersion(url, null))
        }
    }

    @Test
    fun checkServerRespondingReturnsFalseForInvalidUrl() {
        assertFalse(
            OpenCodeServerProtocol.checkServerResponding(
                "not-a-url",
                connectTimeoutMillis = 100,
                readTimeoutMillis = 100,
            ),
        )
    }

    @Test
    fun readBoundedAcceptsBodiesWithinLimitAndRejectsOversized() {
        assertEquals("hello", OpenCodeServerProtocol.readBoundedForTest("hello", 10))
        assertEquals("exact", OpenCodeServerProtocol.readBoundedForTest("exact", 5))
        assertNull(OpenCodeServerProtocol.readBoundedForTest("too-long", 4))
    }

    @Test
    fun disposeServerPostsAuthenticatedGlobalDisposeRequest() {
        withSingleRequestHttpServer(
            expectedRequestLine = "POST ${OpenCodeServerProtocol.DISPOSE_PATH} HTTP/1.1",
            expectedAuthorization = "Basic test-token",
            body = "true",
        ) { url ->
            assertTrue(
                OpenCodeServerProtocol.disposeServer(
                    url,
                    basicAuthHeader = "Basic test-token",
                    connectTimeoutMillis = 1000,
                    readTimeoutMillis = 1000,
                ),
            )
        }
    }

    @Test
    fun disposeServerReturnsFalseForInvalidUrl() {
        assertFalse(
            OpenCodeServerProtocol.disposeServer(
                "not-a-url",
                basicAuthHeader = "Basic test-token",
                connectTimeoutMillis = 100,
                readTimeoutMillis = 100,
            ),
        )
    }

    @Test
    fun startFailureBackoffUsesExponentialDelayWithCap() {
        assertEquals(5_000L, OpenCodeServerProtocol.startFailureBackoffMillis(1))
        assertEquals(10_000L, OpenCodeServerProtocol.startFailureBackoffMillis(2))
        assertEquals(20_000L, OpenCodeServerProtocol.startFailureBackoffMillis(3))
        assertEquals(60_000L, OpenCodeServerProtocol.startFailureBackoffMillis(10))
    }

    private fun withSingleRequestHttpServer(
        body: String,
        expectedRequestLine: String = "GET ${OpenCodeServerProtocol.HEALTH_PATH} HTTP/1.1",
        expectedAuthorization: String? = null,
        block: (String) -> Unit,
    ) {
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val responseFuture = executor.submit {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = reader.readLine()
                    if (requestLine != expectedRequestLine) {
                        throw AssertionError("Unexpected request line: $requestLine")
                    }
                    var authorization: String? = null
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Authorization:", ignoreCase = true)) {
                            authorization = header.substringAfter(':').trim()
                        }
                    }
                    if (expectedAuthorization != null && authorization != expectedAuthorization) {
                        throw AssertionError("Unexpected Authorization header: $authorization")
                    }
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            .toByteArray(Charsets.UTF_8),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (_: SocketException) {
                // Closing the server socket during cleanup is expected if the assertion fails early.
            }
        }

        try {
            block("http://127.0.0.1:${serverSocket.localPort}")
            responseFuture.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    // ─── Interrupted-session detection ──────────────────────────────────────────

    @Test
    fun userStoppedMessageWithErrorFieldIsNotInterrupted() {
        val json = """{"type":"assistant","error":{"type":"unknown","message":"Provider turn interrupted"},"time":{"created":12345}}"""
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun interruptedMessageWithoutCompletedTimeIsDetected() {
        val json = """{"type":"assistant","time":{"created":12345}}"""
        assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun interruptedMessageWithPendingToolIsDetected() {
        val json = """{"type":"assistant","time":{"created":12345,"completed":12346},"content":[{"type":"tool","state":{"status":"pending","input":"{}"}}]}"""
        assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun interruptedMessageWithRunningToolIsDetected() {
        val json = """{"type":"assistant","time":{"created":12345,"completed":12346},"content":[{"type":"tool","state":{"status":"running","input":{},"structured":{},"content":[]}}]}"""
        assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun completedAssistantMessageIsNotInterrupted() {
        val json = """{"type":"assistant","time":{"created":12345,"completed":12346},"content":[{"type":"tool","state":{"status":"completed","input":{},"content":[],"structured":{}}}]}"""
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun unansweredUserMessageIsInterrupted() {
        // Captured from opencode 1.17.13 after a hard kill mid-turn and restart: the partial
        // assistant reply is never persisted, so the unanswered user prompt is the last message.
        val json = """{"id":"msg_f2636da93001Cx6EOSD1Ib7hkV","time":{"created":1783053188346},"text":"Count from one to two hundred as words, one per line.","type":"user"}"""
        assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun nonUserNonAssistantMessageIsNotInterrupted() {
        val json = """{"type":"compaction","time":{"created":12345}}"""
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun emptyMessageIsNotInterrupted() {
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(""))
    }

    @Test
    fun completedMessageMentioningRunningStatusInTextIsNotInterrupted() {
        val json = """{"type":"assistant","time":{"created":1,"completed":2},"content":[{"type":"text","id":"text-0","text":"the part was {\"status\":\"running\"} earlier"}]}"""
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun realMidTurnMessageFromOpenCodeIsInterrupted() {
        // Captured from opencode 1.17.13 while a turn was still streaming.
        val json = """{"id":"msg_f2634f542001uygREY9qiW1ixO","time":{"created":1783052432706},"type":"assistant","agent":"build","model":{"id":"north-mini-code-free","providerID":"opencode"},"content":[{"type":"text","id":"text-0","text":""}],"snapshot":{"start":"4b825dc642cb6eb9a060e54bf8d69288fbee4904"}}"""
        assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun realUserInterruptedMessageFromOpenCodeIsNotInterrupted() {
        // Captured from opencode 1.17.13 after POST /api/session/{id}/interrupt: the stop
        // settles the message with time.completed plus a top-level error.
        val json = """{"id":"msg_f2634f542001uygREY9qiW1ixO","time":{"created":1783052432706,"completed":1783052433991},"type":"assistant","agent":"build","model":{"id":"north-mini-code-free","providerID":"opencode"},"content":[{"type":"text","id":"text-0","text":"Here are the numbers from one to five hundred as words:\n\none"}],"snapshot":{"start":"4b825dc642cb6eb9a060e54bf8d69288fbee4904"},"finish":"error","error":{"type":"unknown","message":"Provider turn interrupted"}}"""
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun nestedErrorObjectDoesNotMaskAnInterruptedMessage() {
        val json = """{"type":"assistant","time":{"created":1},"content":[{"type":"tool","state":{"status":"running","input":{}},"metadata":{"error":{"type":"x"}}}]}"""
        assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(json))
    }

    @Test
    fun userMessageCreatedOnLiveServerIsNotInterrupted() {
        // A prompt sent after the current server launched is an in-flight turn, not a
        // restart casualty; continuing it would steer a spurious prompt into a live turn.
        val json = """{"type":"user","time":{"created":2000},"text":"hi"}"""
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(json, createdBeforeMillis = 1000L))
    }

    @Test
    fun userMessageCreatedBeforeServerStartIsInterruptedWhenBounded() {
        val json = """{"type":"user","time":{"created":500},"text":"hi"}"""
        assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(json, createdBeforeMillis = 1000L))
    }

    @Test
    fun streamingAssistantMessageCreatedOnLiveServerIsNotInterrupted() {
        val json = """{"type":"assistant","time":{"created":2000}}"""
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(json, createdBeforeMillis = 1000L))
    }

    @Test
    fun messageWithoutCreationTimeIsNotInterruptedWhenBounded() {
        // Without a timestamp the pre-restart origin cannot be proven; stay conservative.
        val json = """{"type":"user","text":"hi"}"""
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(json, createdBeforeMillis = 1000L))
    }

    // ─── Suspend/resume detection ────────────────────────────────────────────────

    @Test
    fun schedulerJitterGapIsNotASuspend() {
        assertNull(OpenCodeServerProtocol.detectSuspendGapMillis(1_000L, 1_000L + 89_000L, 30_000L))
    }

    @Test
    fun oversizedWallClockGapIsASuspend() {
        assertEquals(
            600_000L,
            OpenCodeServerProtocol.detectSuspendGapMillis(1_000L, 601_000L, 30_000L),
        )
    }

    @Test
    fun firstPeriodicRunNeverReportsASuspend() {
        assertNull(OpenCodeServerProtocol.detectSuspendGapMillis(0L, 10_000_000L, 30_000L))
    }

    @Test
    fun errorSettledTurnSpanningTheSuspendIsSevered() {
        // Same shape as the captured user-stop message; created before the sleep and
        // error-settled after the wake is the suspend-severed signature.
        val json = """{"id":"msg_1","time":{"created":900000,"completed":2000000},"type":"assistant","agent":"build","content":[{"type":"text","id":"text-0","text":"partial"}],"finish":"error","error":{"type":"unknown","message":"fetch failed"}}"""
        assertTrue(OpenCodeServerProtocol.isSuspendSeveredLastMessage(json, 1_000_000L, 1_900_000L))
    }

    @Test
    fun turnStoppedByTheUserBeforeTheSuspendIsNotSevered() {
        val json = """{"id":"msg_1","time":{"created":900000,"completed":950000},"type":"assistant","finish":"error","error":{"type":"unknown","message":"Provider turn interrupted"}}"""
        assertFalse(OpenCodeServerProtocol.isSuspendSeveredLastMessage(json, 1_000_000L, 1_900_000L))
    }

    @Test
    fun turnStartedAfterTheResumeIsNotSevered() {
        val json = """{"id":"msg_1","time":{"created":1950000,"completed":1960000},"type":"assistant","finish":"error","error":{"type":"unknown","message":"whatever"}}"""
        assertFalse(OpenCodeServerProtocol.isSuspendSeveredLastMessage(json, 1_000_000L, 1_900_000L))
    }

    @Test
    fun cleanlyCompletedTurnSpanningTheSuspendIsNotSevered() {
        val json = """{"id":"msg_1","time":{"created":900000,"completed":2000000},"type":"assistant","content":[{"type":"text","id":"text-0","text":"done"}]}"""
        assertFalse(OpenCodeServerProtocol.isSuspendSeveredLastMessage(json, 1_000_000L, 1_900_000L))
    }

    @Test
    fun unsettledTurnIsNotSeveredYet() {
        val json = """{"id":"msg_1","time":{"created":900000},"type":"assistant"}"""
        assertFalse(OpenCodeServerProtocol.isSuspendSeveredLastMessage(json, 1_000_000L, 1_900_000L))
    }

    @Test
    fun userMessageIsNotSevered() {
        val json = """{"id":"msg_1","time":{"created":900000},"type":"user","text":"prompt"}"""
        assertFalse(OpenCodeServerProtocol.isSuspendSeveredLastMessage(json, 1_000_000L, 1_900_000L))
    }

    @Test
    fun unsettledTurnFromBeforeTheSleepIsAPollingCandidate() {
        val json = """{"id":"msg_1","time":{"created":900000},"type":"assistant"}"""
        assertTrue(OpenCodeServerProtocol.isUnsettledTurnFromBefore(json, 1_000_000L))
    }

    @Test
    fun unsettledTurnStartedAfterTheSleepIsNotAPollingCandidate() {
        val json = """{"id":"msg_1","time":{"created":1950000},"type":"assistant"}"""
        assertFalse(OpenCodeServerProtocol.isUnsettledTurnFromBefore(json, 1_000_000L))
    }

    @Test
    fun settledTurnIsNotAPollingCandidate() {
        val json = """{"id":"msg_1","time":{"created":900000,"completed":950000},"type":"assistant"}"""
        assertFalse(OpenCodeServerProtocol.isUnsettledTurnFromBefore(json, 1_000_000L))
    }

    @Test
    fun userMessageIsNotAPollingCandidate() {
        val json = """{"id":"msg_1","time":{"created":900000},"type":"user","text":"prompt"}"""
        assertFalse(OpenCodeServerProtocol.isUnsettledTurnFromBefore(json, 1_000_000L))
    }

    @Test
    fun parseSessionListFiltersByRecency() {
        val now = System.currentTimeMillis()
        val json = """{"data":[
            {"id":"ses_recent","time":{"created":${now - 60000},"updated":${now - 60000}}},
            {"id":"ses_old","time":{"created":${now - 600000},"updated":${now - 600000}}}]}"""
        val sessions = OpenCodeServerProtocol.parseSessionList(json, maxAgeMillis = 300_000, nowMillis = now)
        assertEquals(1, sessions.size)
        assertEquals("ses_recent", sessions[0].id)
    }

    @Test
    fun parseSessionListParsesMultipleSessions() {
        val now = System.currentTimeMillis()
        val recent = now - 10000
        val json = """{"data":[
            {"id":"ses_a","time":{"created":$recent,"updated":$recent}},
            {"id":"ses_b","time":{"created":$recent,"updated":$recent}}]}"""
        val sessions = OpenCodeServerProtocol.parseSessionList(json, maxAgeMillis = 300_000, nowMillis = now)
        assertEquals(2, sessions.size)
        assertTrue(sessions.map { it.id }.containsAll(listOf("ses_a", "ses_b")))
    }

    @Test
    fun parseSessionListReturnsEmptyForEmptyData() {
        val sessions = OpenCodeServerProtocol.parseSessionList(
            """{"data":[]}""",
            maxAgeMillis = 300_000,
            nowMillis = System.currentTimeMillis(),
        )
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun parseSessionListDeduplicatesById() {
        val now = System.currentTimeMillis()
        val recent = now - 1000
        val json = """{"data":[
            {"id":"ses_dup","time":{"created":$recent,"updated":$recent}},
            {"id":"ses_dup","time":{"created":$recent,"updated":$recent}}]}"""
        val sessions = OpenCodeServerProtocol.parseSessionList(json, maxAgeMillis = 300_000, nowMillis = now)
        assertEquals(1, sessions.size)
    }

    @Test
    fun parseSessionListExposesParentIdOfChildSessions() {
        val now = System.currentTimeMillis()
        val recent = now - 1000
        val json = """{"data":[
            {"id":"ses_top","time":{"created":$recent,"updated":$recent}},
            {"id":"ses_child","parentID":"ses_top","time":{"created":$recent,"updated":$recent}}]}"""
        val sessions = OpenCodeServerProtocol.parseSessionList(json, maxAgeMillis = 300_000, nowMillis = now)
        assertEquals(listOf(null, "ses_top"), sessions.map { it.parentID })
    }

    @Test
    fun parseSessionListReadsV2LocationDirectory() {
        val now = System.currentTimeMillis()
        val recent = now - 1000
        val json = """{"data":[
            {"id":"ses_loc","time":{"created":$recent,"updated":$recent},
             "location":{"directory":"/private/tmp/project"}}]}"""
        val sessions = OpenCodeServerProtocol.parseSessionList(json, maxAgeMillis = 300_000, nowMillis = now)
        assertEquals(listOf("/private/tmp/project"), sessions.map { it.directory })
    }

    @Test
    fun parseSessionListReadsLegacyDirectoryField() {
        val now = System.currentTimeMillis()
        val recent = now - 1000
        val json = """{"data":[
            {"id":"ses_dir","directory":"/Users/me/project","time":{"created":$recent,"updated":$recent}}]}"""
        val sessions = OpenCodeServerProtocol.parseSessionList(json, maxAgeMillis = 300_000, nowMillis = now)
        assertEquals(listOf("/Users/me/project"), sessions.map { it.directory })
    }

    @Test
    fun parseSessionDirectoryPrefersLocationOverLegacyField() {
        assertEquals(
            "/private/tmp/project",
            OpenCodeServerProtocol.parseSessionDirectory(
                """{"id":"ses_1","directory":"/tmp/project","location":{"directory":"/private/tmp/project"}}""",
            ),
        )
    }

    @Test
    fun parseSessionListDoesNotPairIdWithNextSessionsTimestamp() {
        val now = System.currentTimeMillis()
        val recent = now - 1000
        val json = """{"data":[
            {"id":"ses_no_time"},
            {"id":"ses_recent","time":{"created":$recent,"updated":$recent}}]}"""
        val sessions = OpenCodeServerProtocol.parseSessionList(json, maxAgeMillis = 300_000, nowMillis = now)
        assertEquals(listOf("ses_recent"), sessions.map { it.id })
    }

    @Test
    fun extractFirstDataObjectIgnoresBracesInsideStrings() {
        val body = """{"data":[{"type":"assistant","text":"fun main() { if (x) { y() } }","time":{"created":12345,"completed":12346}},{"type":"user"}]}"""
        val extracted = OpenCodeServerProtocol.extractFirstDataObject(body)
        assertEquals(
            """{"type":"assistant","text":"fun main() { if (x) { y() } }","time":{"created":12345,"completed":12346}}""",
            extracted,
        )
    }

    @Test
    fun extractFirstDataObjectIgnoresEscapedQuotesInsideStrings() {
        val body = """{"data":[{"type":"assistant","text":"say \"}\" now","time":{"created":1,"completed":2}}]}"""
        val extracted = OpenCodeServerProtocol.extractFirstDataObject(body)
        assertEquals("""{"type":"assistant","text":"say \"}\" now","time":{"created":1,"completed":2}}""", extracted)
    }

    @Test
    fun fetchRecentSessionsReturnsEmptyOnConnectionError() {
        val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
        val sessions = OpenCodeServerProtocol.fetchRecentSessions(
            "http://127.0.0.1:1",
            auth,
            "/tmp/project",
            connectTimeoutMillis = 100,
            readTimeoutMillis = 100,
        )
        assertTrue(sessions.isEmpty())
        val result = OpenCodeServerProtocol.fetchRecentSessionsResult(
            "http://127.0.0.1:1",
            auth,
            "/tmp/project",
            connectTimeoutMillis = 100,
            readTimeoutMillis = 100,
        )
        assertTrue(result is OpenCodeProtocolResult.Failure)
    }

    @Test
    fun fetchRecentSessionsFollowsCursorPages() {
        val requests = AtomicInteger()
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val now = System.currentTimeMillis()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/session") { exchange ->
            val request = requests.incrementAndGet()
            queries.add(exchange.requestURI.rawQuery.orEmpty())
            val body = if (exchange.requestURI.rawQuery.orEmpty().contains("cursor=")) {
                """{"data":[{"id":"ses_page2","time":{"updated":${now - 1}}}],"cursor":{}}"""
            } else {
                """{"data":[{"id":"ses_page1","time":{"updated":${now - 2}}}],"cursor":{"next":"page-2"}}"""
            }
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            assertTrue(request <= 2)
        }
        server.start()
        try {
            val result = OpenCodeServerProtocol.fetchRecentSessionsResult(
                "http://127.0.0.1:${server.address.port}",
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/tmp/project",
                maxAgeMillis = Long.MAX_VALUE,
                nowMillis = now,
            )
            assertTrue(result is OpenCodeProtocolResult.Success)
            assertEquals(
                listOf("ses_page1", "ses_page2"),
                (result as OpenCodeProtocolResult.Success).value.map { it.id },
            )
            assertEquals(2, requests.get())
            assertTrue(queries[0].contains("directory="))
            assertTrue(queries[0].contains("order=desc"))
            assertTrue(queries[1].contains("limit=20"))
            assertTrue(queries[1].contains("directory="))
            assertTrue(queries[1].contains("order=desc"))
            assertTrue(queries[1].contains("cursor="))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun normalizeLastMessageMapsV1InfoPartsOntoClassifierShape() {
        // Live SPA sessions return `{info:{role,time}, parts:[…]}`; classifiers read the flat
        // v2 shape. Without this map every real panel session looks empty to recovery.
        val v1 = """
            {"info":{"id":"msg_1","role":"assistant","time":{"created":100,"completed":2000},
              "error":{"name":"FetchError"}},
             "parts":[]}
        """.trimIndent()
        val normalized = OpenCodeServerProtocol.normalizeLastMessageForClassification(v1)
        assertNotNull(normalized)
        assertTrue(OpenCodeServerProtocol.isSuspendSeveredLastMessage(normalized!!, 200, 1900))
        assertFalse(OpenCodeServerProtocol.isInterruptedLastMessage(normalized)) // top-level error

        val running = """
            {"info":{"role":"assistant","time":{"created":100}},
             "parts":[{"type":"tool","state":{"status":"running"}}]}
        """.trimIndent()
        assertTrue(
            OpenCodeServerProtocol.isInterruptedLastMessage(
                OpenCodeServerProtocol.normalizeLastMessageForClassification(running)!!,
            ),
        )

        val user = """{"info":{"role":"user","time":{"created":50}},"parts":[]}"""
        assertTrue(
            OpenCodeServerProtocol.isInterruptedLastMessage(
                OpenCodeServerProtocol.normalizeLastMessageForClassification(user)!!,
            ),
        )

        // v2 shape is already flat and must pass through unchanged.
        val v2 = """{"type":"assistant","time":{"created":1}}"""
        assertEquals(v2, OpenCodeServerProtocol.normalizeLastMessageForClassification(v2))
    }

    @Test
    fun extractLastMessageRawAcceptsV1ArrayAndV2Envelope() {
        assertEquals(
            """{"type":"assistant"}""",
            OpenCodeServerProtocol.extractLastMessageRaw("""[{"type":"assistant"},{"type":"user"}]"""),
        )
        assertEquals(
            """{"type":"assistant"}""",
            OpenCodeServerProtocol.extractLastMessageRaw(
                """{"data":[{"type":"assistant"}],"cursor":{}}""",
            ),
        )
        assertNull(OpenCodeServerProtocol.extractLastMessageRaw("""[]"""))
        assertNull(OpenCodeServerProtocol.extractLastMessageRaw("""{"data":[]}"""))
    }

    @Test
    fun fetchLastMessageJsonPrefersV1StoreAndFallsBackToV2() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/session") { exchange ->
            seen.add(exchange.requestURI.path + "?" + exchange.requestURI.rawQuery.orEmpty())
            val body = """[{"info":{"role":"assistant","time":{"created":99}},"parts":[]}]"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/session") { exchange ->
            seen.add("v2:" + exchange.requestURI.path)
            val body = """{"data":[{"type":"user","time":{"created":1}}],"cursor":{}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val msg = OpenCodeServerProtocol.fetchLastMessageJson(
                "http://127.0.0.1:${server.address.port}",
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/tmp/project",
                "ses_abc123",
            )
            assertNotNull(msg)
            assertTrue(msg!!.contains("\"type\":\"assistant\""))
            assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(msg))
            // v1 answered → v2 must not be consulted.
            assertTrue(seen.any { it.startsWith("/session/ses_abc123/message?") })
            assertTrue(seen.none { it.startsWith("v2:") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchLastMessageJsonFallsBackToV2WhenV1IsEmpty() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/session") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("[]".toByteArray()) }
        }
        server.createContext("/api/session") { exchange ->
            val body = """{"data":[{"type":"assistant","time":{"created":12345}}],"cursor":{}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val msg = OpenCodeServerProtocol.fetchLastMessageJson(
                "http://127.0.0.1:${server.address.port}",
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/tmp/project",
                "ses_abc123",
            )
            assertNotNull(msg)
            assertTrue(msg!!.contains("\"type\":\"assistant\""))
            assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(msg))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sendContinuePromptReturnsFalseForInvalidSessionId() {
        val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
        assertFalse(OpenCodeServerProtocol.sendContinuePrompt("http://127.0.0.1:1", auth, "invalid"))
        assertEquals(
            OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER),
            OpenCodeServerProtocol.sendContinuePromptResult("http://127.0.0.1:1", auth, "invalid"),
        )
    }

    @Test
    fun sendContinuePromptResultPreservesHttpStatus() {
        for (status in listOf(409, 500)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/api/session/ses_1/prompt") { exchange ->
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            server.start()
            try {
                assertEquals(
                    OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, status),
                    OpenCodeServerProtocol.sendContinuePromptResult(
                        "http://127.0.0.1:${server.address.port}",
                        OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                        "ses_1",
                    ),
                )
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun sendContinuePromptSendsResumeTrueBody() {
        val serverSocket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val capturedBody = java.util.concurrent.CompletableFuture<String>()
        val responseFuture = executor.submit {
            try {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream()
                    val reader = input.bufferedReader()
                    reader.readLine()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine()
                        if (line.isNullOrEmpty()) break
                        val parts = line.split(": ", limit = 2)
                        if (parts.size == 2) headers[parts[0]] = parts[1]
                    }
                    val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
                    val bodyChars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(bodyChars, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    capturedBody.complete(String(bodyChars, 0, read))
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.UTF_8),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (_: SocketException) {}
        }
        try {
            val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
            val accepted = OpenCodeServerProtocol.sendContinuePrompt(
                "http://127.0.0.1:${serverSocket.localPort}",
                auth,
                "ses_abc123",
            )
            assertTrue(accepted)
            val body = capturedBody.get(5, TimeUnit.SECONDS)
            assertTrue(body.contains("\"resume\":true"))
            assertTrue(body.contains("\"text\":\"Continue\""))
            responseFuture.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun replyToPermissionReturnsFalseForInvalidIds() {
        val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
        assertFalse(
            OpenCodeServerProtocol.replyToPermission(
                "http://127.0.0.1:1", auth, "/tmp", "invalid", "per_1",
                OpenCodeServerProtocol.PermissionResponse.ONCE,
            ),
        )
        assertFalse(
            OpenCodeServerProtocol.replyToPermission(
                "http://127.0.0.1:1", auth, "/tmp", "ses_1", "not a valid id",
                OpenCodeServerProtocol.PermissionResponse.ONCE,
            ),
        )
    }

    @Test
    fun replyToPermissionPostsToNonDeprecatedReplyEndpoint() {
        val serverSocket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val capturedRequestLine = java.util.concurrent.CompletableFuture<String>()
        val capturedBody = java.util.concurrent.CompletableFuture<String>()
        val responseFuture = executor.submit {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    capturedRequestLine.complete(reader.readLine())
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine()
                        if (line.isNullOrEmpty()) break
                        val parts = line.split(": ", limit = 2)
                        if (parts.size == 2) headers[parts[0]] = parts[1]
                    }
                    val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
                    val bodyChars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(bodyChars, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    capturedBody.complete(String(bodyChars, 0, read))
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.UTF_8),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (_: SocketException) {}
        }
        try {
            val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
            val accepted = OpenCodeServerProtocol.replyToPermission(
                "http://127.0.0.1:${serverSocket.localPort}",
                auth,
                "/tmp/project",
                "ses_abc123",
                "per_abc123",
                OpenCodeServerProtocol.PermissionResponse.ONCE,
            )
            assertTrue(accepted)
            val requestLine = capturedRequestLine.get(5, TimeUnit.SECONDS)
            // Non-deprecated successor: POST /permission/{requestID}/reply, not the deprecated
            // POST /session/{id}/permissions/{id} form.
            assertTrue(requestLine.startsWith("POST /permission/per_abc123/reply?directory="))
            assertFalse(requestLine.contains("/permissions/"))
            val body = capturedBody.get(5, TimeUnit.SECONDS)
            assertEquals("{\"reply\":\"once\"}", body)
            responseFuture.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun parseSessionDiffParsesFileEntries() {
        val json = """
            [
              {"file":"src/Foo.kt","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1,"status":"modified"},
              {"file":"new.txt","patch":"@@ -0,0 +1 @@\n+x","additions":1,"deletions":0,"status":"added"},
              {"additions":0,"deletions":0}
            ]
        """.trimIndent()
        val diffs = OpenCodeServerProtocol.parseSessionDiff(json)
        assertEquals(3, diffs.size)
        assertEquals("src/Foo.kt", diffs[0].file)
        assertEquals("@@ -1 +1 @@\n-a\n+b", diffs[0].patch)
        assertEquals(1L, diffs[0].additions)
        assertEquals(1L, diffs[0].deletions)
        assertEquals("modified", diffs[0].status)
        assertEquals("added", diffs[1].status)
        assertNull(diffs[2].file)
        assertNull(diffs[2].patch)
        assertEquals(0L, diffs[2].additions)
    }

    @Test
    fun parseSessionDiffReturnsEmptyForNonArray() {
        assertTrue(OpenCodeServerProtocol.parseSessionDiff("{}").isEmpty())
        assertTrue(OpenCodeServerProtocol.parseSessionDiff("").isEmpty())
        assertTrue(OpenCodeServerProtocol.parseSessionDiff("not json").isEmpty())
    }

    @Test
    fun parseToolPartChangeReadsEditFilediff() {
        val json = """
            {"id":"prt_edit","type":"tool","tool":"edit","state":{"status":"completed",
             "input":{"filePath":"/proj/src/A.kt"},
             "metadata":{"filediff":{"file":"/proj/src/A.kt","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1}}}}
        """.trimIndent()
        val change = OpenCodeServerProtocol.parseToolPartChange(json)
        assertEquals(1, change.diffs.size)
        assertEquals("/proj/src/A.kt", change.diffs[0].file)
        assertEquals("@@ -1 +1 @@\n-a\n+b", change.diffs[0].patch)
        assertNull(change.fileHint)
    }

    @Test
    fun parseToolPartChangeReadsApplyPatchFiles() {
        val json = """
            {"id":"prt_patch","type":"tool","tool":"apply_patch","state":{"status":"completed","metadata":{"files":[
              {"filePath":"/proj/src/A.kt","relativePath":"src/A.kt","type":"update","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1},
              {"filePath":"/proj/src/B.kt","relativePath":"src/B.kt","type":"add","patch":"@@ -0,0 +1 @@\n+n","additions":1,"deletions":0}
            ]}}}
        """.trimIndent()
        val change = OpenCodeServerProtocol.parseToolPartChange(json)
        assertEquals(listOf("src/A.kt", "src/B.kt"), change.diffs.map { it.file })
        assertEquals("added", change.diffs[1].status)
        assertNull(change.fileHint)
    }

    @Test
    fun parseToolPartChangeWriteYieldsFileHint() {
        val json = """
            {"id":"prt_write","type":"tool","tool":"write","state":{"status":"completed",
             "input":{"filePath":"/proj/src/New.kt","content":"hi"},
             "metadata":{"filepath":"/proj/src/New.kt","exists":false}}}
        """.trimIndent()
        val change = OpenCodeServerProtocol.parseToolPartChange(json)
        assertTrue(change.diffs.isEmpty())
        assertEquals("/proj/src/New.kt", change.fileHint)
    }

    @Test
    fun findToolPartInMessagesReturnsMatchingPart() {
        val json = """
            [{"info":{"id":"msg_user","role":"user"},"parts":[]},
             {"info":{"id":"msg_asst","role":"assistant"},"parts":[
               {"id":"prt_other","type":"text","text":"x"},
               {"id":"prt_edit","type":"tool","tool":"edit","state":{"status":"completed","metadata":{"filediff":{"file":"A.kt","patch":"p","additions":1,"deletions":0}}}}
             ]}]
        """.trimIndent()
        val found = OpenCodeServerProtocol.findToolPartInMessages(json, "prt_edit")
        assertNotNull(found)
        assertTrue(found!!.contains("\"id\":\"prt_edit\""))
        assertNull(OpenCodeServerProtocol.findToolPartInMessages(json, "prt_missing"))
    }

    @Test
    fun fetchToolPartChangePagesUntilPartIsFound() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/session/ses_abc123/message") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            seen.add(query)
            val (body, cursor) = if (!query.contains("before=")) {
                """[{"info":{"id":"msg_1","role":"user"},"parts":[]}]""" to "cursor1"
            } else {
                """[{"info":{"id":"msg_2","role":"assistant"},"parts":[
                  {"id":"prt_edit","type":"tool","tool":"edit","state":{"status":"completed",
                   "metadata":{"filediff":{"file":"A.kt","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1}}}}
                ]}]""" to null
            }
            val bytes = body.toByteArray()
            if (cursor != null) exchange.responseHeaders.add("X-Next-Cursor", cursor)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val result = OpenCodeServerProtocol.fetchToolPartChange(
                "http://127.0.0.1:${server.address.port}",
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/tmp/project",
                "ses_abc123",
                "prt_edit",
            )
            assertTrue(result is OpenCodeProtocolResult.Success)
            val change = (result as OpenCodeProtocolResult.Success).value
            assertEquals("A.kt", change.diffs.single().file)
            assertTrue(seen[0].contains("limit=50"))
            assertFalse(seen[0].contains("before="))
            assertTrue(seen[1].contains("before=cursor1"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchToolPartChangeRejectsInvalidPartId() {
        val result = OpenCodeServerProtocol.fetchToolPartChange(
            "http://127.0.0.1:1",
            OpenCodeServerProtocol.buildBasicAuthHeader("test"),
            "/tmp",
            "ses_abc123",
            "msg_not_a_part",
        )
        assertEquals(
            OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER,
            (result as OpenCodeProtocolResult.Failure).kind,
        )
    }

    @Test
    fun fetchSessionDiffReturnsEmptyForInvalidSessionId() {
        val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
        assertTrue(
            OpenCodeServerProtocol.fetchSessionDiff("http://127.0.0.1:1", auth, "/tmp", "invalid").isEmpty(),
        )
        val invalidMessage = OpenCodeServerProtocol.fetchSessionDiffResult(
            "http://127.0.0.1:1",
            auth,
            "/tmp",
            "ses_valid",
            "ses_wrong_kind",
        )
        assertEquals(
            OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER,
            (invalidMessage as OpenCodeProtocolResult.Failure).kind,
        )
    }

    @Test
    fun fetchSessionDiffDistinguishesEmptyHttpFailureAndInvalidBody() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/session/ses_test/diff") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            when {
                query.contains("directory=fail") -> exchange.sendResponseHeaders(503, -1)
                query.contains("directory=invalid") -> {
                    val body = "{}"
                    exchange.sendResponseHeaders(200, body.length.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                else -> {
                    val body = "[]"
                    exchange.sendResponseHeaders(200, body.length.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
            val empty = OpenCodeServerProtocol.fetchSessionDiffResult(base, auth, "ok", "ses_test")
            assertTrue(empty is OpenCodeProtocolResult.Success && empty.value.isEmpty())

            val failed = OpenCodeServerProtocol.fetchSessionDiffResult(base, auth, "fail", "ses_test")
            assertEquals(OpenCodeProtocolResult.Failure.Kind.HTTP, (failed as OpenCodeProtocolResult.Failure).kind)
            assertEquals(503, failed.statusCode)

            val invalid = OpenCodeServerProtocol.fetchSessionDiffResult(base, auth, "invalid", "ses_test")
            assertEquals(OpenCodeProtocolResult.Failure.Kind.INVALID_BODY, (invalid as OpenCodeProtocolResult.Failure).kind)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchSessionDiffRequestsDiffUrlAndParsesResult() {
        val body = """[{"file":"src/Foo.kt","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1,"status":"modified"}]"""
        val serverSocket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val capturedRequestLine = java.util.concurrent.CompletableFuture<String>()
        val responseFuture = executor.submit {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    capturedRequestLine.complete(reader.readLine())
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // Drain request headers.
                    }
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body"
                            .toByteArray(Charsets.UTF_8),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (_: SocketException) {}
        }
        try {
            val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
            val diffs = OpenCodeServerProtocol.fetchSessionDiff(
                "http://127.0.0.1:${serverSocket.localPort}",
                auth,
                "/tmp/project",
                "ses_abc123",
            )
            val requestLine = capturedRequestLine.get(5, TimeUnit.SECONDS)
            assertTrue(requestLine.startsWith("GET /session/ses_abc123/diff?directory="))
            assertFalse(requestLine.contains("messageID"))
            assertEquals(1, diffs.size)
            assertEquals("src/Foo.kt", diffs[0].file)
            responseFuture.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun fetchSessionDiffAppendsMessageIdParam() {
        val serverSocket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val capturedRequestLine = java.util.concurrent.CompletableFuture<String>()
        val responseFuture = executor.submit {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    capturedRequestLine.complete(reader.readLine())
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // Drain request headers.
                    }
                    val body = "[]"
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            .toByteArray(Charsets.UTF_8),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (_: SocketException) {}
        }
        try {
            val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
            OpenCodeServerProtocol.fetchSessionDiff(
                "http://127.0.0.1:${serverSocket.localPort}",
                auth,
                "/tmp/project",
                "ses_abc123",
                "msg_xyz",
            )
            val requestLine = capturedRequestLine.get(5, TimeUnit.SECONDS)
            assertTrue(requestLine.contains("/session/ses_abc123/diff?directory="))
            assertTrue(requestLine.contains("&messageID=msg_xyz"))
            responseFuture.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun buildDiffNavigationScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildDiffNavigationScript(enabled = false, openDiffCallback = "cb(payload)"))
    }

    @Test
    fun buildDiffNavigationScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeBrowserSnippets.buildDiffNavigationScript(enabled = true, openDiffCallback = null))
    }

    @Test
    fun buildDiffNavigationScriptInstallsAltClickHandlerForDiffTargets() {
        val script = OpenCodeBrowserSnippets.buildDiffNavigationScript(
            enabled = true,
            openDiffCallback = "window.__openDiff(messageID, filePath, partID)",
        )!!
        assertTrue(script.contains("event.altKey"))
        assertTrue(script.contains("isDiffGesture"))
        assertTrue(script.contains("event.metaKey"))
        assertTrue(script.contains("event.ctrlKey"))
        assertTrue(script.contains("isMac"))
        assertTrue(script.contains(".replace(/\\\\/g, '/')"))
        assertTrue(script.contains("addEventListener('click'"))
        assertTrue(script.contains("[data-file]"))
        assertTrue(script.contains("edit-tool"))
        assertTrue(script.contains("write-tool"))
        assertTrue(script.contains("apply-patch-tool"))
        assertTrue(script.contains("apply-patch-trigger-content"))
        assertTrue(script.contains("apply-patch-filename"))
        assertTrue(script.contains("diff-changes"))
        assertTrue(script.contains("session-turn-diff-trigger"))
        assertTrue(script.contains("session-turn-diff-filename"))
        assertTrue(script.contains("[data-message-id]"))
        assertTrue(script.contains("[data-timeline-part-id]"))
        assertTrue(script.contains("partIdOf(patchRow)"))
        assertTrue(script.contains("partIdOf(editBlock)"))
        assertTrue(script.contains("messageIdOf(turnRow)"))
        assertTrue(script.contains("window.__openDiff(messageID, filePath, partID)"))
        assertTrue(script.contains("}, true)"))
    }

    @Test
    fun fileLinkHandlerReservesDiffGesture() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!
        assertTrue(script.contains("event.altKey"))
        assertTrue(script.contains("event.metaKey"))
        assertTrue(script.contains("event.ctrlKey"))
    }
}
