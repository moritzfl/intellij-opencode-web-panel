package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        assertNull(OpenCodeServerProtocol.parseServerUrl("starting server"))
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
    fun buildOpenProjectScriptSeedsProjectStateAndNavigatesToSessionRoute() {
        val script = OpenCodeServerProtocol.buildOpenProjectScript("/tmp/my 'project'", "http://127.0.0.1:60482/")!!

        assertTrue(script.contains("if (window.location.origin !== 'http://127.0.0.1:60482') return;"))
        assertTrue(script.contains("opencode.global.dat:server"))
        assertTrue(script.contains("opencode.global.dat:layout.page"))
        assertTrue(script.contains("opencode.intellij.project.opened:"))
        assertTrue(script.contains("const openMostRecentConversation = false"))
        assertTrue(script.contains("const setNavigationState = (value)"))
        assertTrue(script.contains("if (getNavigationState() === path && !keepWaitingForRecentSession) return"))
        assertTrue(script.contains("state.projects[scope]"))
        assertTrue(script.contains("state.lastProject[scope] = directory"))
        assertTrue(script.contains("if (window.location.pathname !== path)"))
        assertTrue(script.contains("window.location.assign(path)"))
        assertTrue(script.contains("const onProjectSessionRoute = window.location.pathname === projectPath"))
        assertFalse(script.contains("window.location.reload()"))
        assertTrue(script.contains("const projectPath = '/L3RtcC9teSAncHJvamVjdCc/session'"))
        assertTrue(script.contains("const directory = '/tmp/my \\'project\\''"))
        assertTrue(script.contains("onSameProjectRoute"))
        assertTrue(script.contains("decodeRouteDirectory"))
        assertTrue(script.contains("comparableDirectory"))
    }

    @Test
    fun buildOpenProjectScriptIsMissingWithoutProjectPath() {
        assertNull(OpenCodeServerProtocol.buildOpenProjectScript(null))
        assertNull(OpenCodeServerProtocol.buildOpenProjectScript(""))
    }

    @Test
    fun buildOpenProjectScriptCanOpenMostRecentProjectConversation() {
        val script = OpenCodeServerProtocol.buildOpenProjectScript(
            "/tmp/project",
            "http://127.0.0.1:60482/",
            openMostRecentConversation = true,
        )!!

        assertTrue(script.contains("const openMostRecentConversation = true"))
        assertTrue(script.contains("let foundRecentSession = false"))
        assertTrue(script.contains("foundRecentSession = true"))
        assertTrue(script.contains("const findLastProjectSession = (layout)"))
        assertTrue(script.contains("layout.lastProjectSession[directory]"))
        assertTrue(script.contains("Object.entries(layout.lastProjectSession)"))
        assertTrue(script.contains("'/session/' + encodeURIComponent(session.id)"))
    }

    @Test
    fun buildOpenProjectScriptRetriesUntilRecentSessionIsRestored() {
        val script = OpenCodeServerProtocol.buildOpenProjectScript(
            "/tmp/project",
            "http://127.0.0.1:60482/",
            openMostRecentConversation = true,
        )!!

        assertTrue(script.contains("const navigationPendingUntilKey = navigationKey + ':pending-until'"))
        assertTrue(script.contains("pendingUntil = now + 10000"))
        assertTrue(script.contains("const keepWaitingForRecentSession = shouldKeepWaitingForRecentSession()"))
        assertTrue(script.contains("if (!keepWaitingForRecentSession) setNavigationState(path)"))
        assertFalse(script.contains("getNavigationState() === 'complete'"))
        assertFalse(script.contains("setNavigationState('complete')"))
    }

    @Test
    fun buildOpenProjectScriptNeverNavigatesAwayFromAnOpenConversation() {
        val script = OpenCodeServerProtocol.buildOpenProjectScript(
            "/tmp/project",
            "http://127.0.0.1:60482/",
            openMostRecentConversation = true,
        )!!

        // The stay-on-conversation guard must apply even when a most-recent session is known;
        // otherwise the delayed script runs yank the user out of a conversation they opened.
        assertTrue(script.contains("if (window.location.pathname !== projectPath && onProjectSessionRoute) {"))
        assertFalse(script.contains("onProjectSessionRoute && !foundRecentSession"))
    }

    @Test
    fun buildOpenProjectScriptNormalizesLastProjectSessionLookupKey() {
        val script = OpenCodeServerProtocol.buildOpenProjectScript(
            "/tmp/project",
            "http://127.0.0.1:60482/",
            openMostRecentConversation = true,
        )!!

        assertTrue(script.contains("const pathKey = (value)"))
        assertTrue(script.contains("text.startsWith('\\\\\\\\')"))
        assertTrue(script.contains("text.replace(/\\\\/g, '/')"))
        assertTrue(script.contains("pathKey(key) === targetKey"))
        // The stored session's own directory must match the panel's project, so a stale
        // layout entry can never navigate the panel to another project's session.
        assertTrue(script.contains("pathKey(value.directory) === targetKey"))
    }

    @Test
    fun buildFileLinkHandlerScriptInterceptsLocalFileLinks() {
        val script = OpenCodeServerProtocol.buildFileLinkHandlerScript("/tmp/project")!!

        assertTrue(script.contains("window.__opencodeIntellijFileLinksInstalled"))
        assertTrue(script.contains("FILE_LINKS_VERSION = 2"))
        assertTrue(script.contains("window.__opencodeIntellijFileLinksInstalledVersion"))
        assertTrue(script.contains("event.target.closest('a')"))
        assertTrue(script.contains("inferredFileLink(link)"))
        assertTrue(script.contains("changedFileButtonLink(event.target)"))
        assertTrue(script.contains("session-review-view-button"))
        assertTrue(script.contains("session-review-accordion-item"))
        assertTrue(script.contains("getAttribute('data-file')"))
        assertTrue(script.contains("button[aria-label=\"Open file\"]"))
        assertTrue(script.contains("session-review-file-info"))
        assertTrue(script.contains("session-review-directory"))
        assertTrue(script.contains("session-review-filename"))
        assertTrue(script.contains("document.addEventListener('pointerdown'"))
        assertTrue(script.contains("document.addEventListener('mousedown'"))
        assertTrue(script.contains("now - lastOpenedAt < 750"))
        assertTrue(script.contains("unsupportedProtocol"))
        assertTrue(script.contains("decodeRouteDirectory"))
        assertTrue(script.contains("isOpenCodeAppRoute(href)"))
        assertTrue(script.contains("href.startsWith('/')"))
        assertTrue(script.contains("!href.includes('://')"))
        assertTrue(script.contains("${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}"))
    }

    @Test
    fun buildFileLinkHandlerScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeServerProtocol.buildFileLinkHandlerScript("/tmp/project", enabled = false))
    }

    @Test
    fun buildFileLinkHandlerScriptCanUseDirectCallback() {
        val script = OpenCodeServerProtocol.buildFileLinkHandlerScript(
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
        val script = OpenCodeServerProtocol.buildExternalLinkHandlerScript(
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
        assertNull(OpenCodeServerProtocol.buildExternalLinkHandlerScript(enabled = false, openExternalCallback = "callback(href)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeServerProtocol.buildExternalLinkHandlerScript(enabled = true, openExternalCallback = null))
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
        assertNull(OpenCodeServerProtocol.buildRestoreOpenCodeLocalStorageScript(null))
        assertNull(OpenCodeServerProtocol.buildRestoreOpenCodeLocalStorageScript("{}"))
    }

    @Test
    fun buildRestoreOpenCodeLocalStorageScriptRestoresOpenCodeKeysOnlyWhenMissing() {
        val script = OpenCodeServerProtocol.buildRestoreOpenCodeLocalStorageScript(
            "{\"opencode.global.dat:language\":\"{\\\"locale\\\":\\\"de\\\"}\"}",
        )!!

        assertTrue(script.contains("opencode.global.dat:language"))
        assertTrue(script.contains(OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY))
        assertTrue(script.contains("opencode-color-scheme"))
        assertTrue(script.contains("opencode\\.global\\.dat"))
        assertTrue(script.contains("opencode\\.workspace\\."))
        assertTrue(script.contains("opencode\\.window\\.browser\\.dat:tabs"))
        assertTrue(script.contains("'settings.v3'"))
        assertFalse(script.contains(OpenCodeServerProtocol.OPEN_CODE_DEFAULT_SERVER_URL_STORAGE_KEY))
        assertTrue(script.contains("window.localStorage.getItem(key) === null"))
        assertTrue(script.contains("window.localStorage.setItem(key, value)"))
    }

    @Test
    fun buildSyncOpenCodeLocalStorageScriptMirrorsOpenCodeKeys() {
        val script = OpenCodeServerProtocol.buildSyncOpenCodeLocalStorageScript("window.intellijStore(payload)")!!

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
        assertTrue(script.contains("MAX_VALUE_CHARS"))
        assertFalse(script.contains(OpenCodeServerProtocol.OPEN_CODE_DEFAULT_SERVER_URL_STORAGE_KEY))
        assertTrue(script.contains("window.intellijStore(payload)"))
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
    fun directorylessSessionRoutesAreRecognized() {
        assertTrue(OpenCodeServerProtocol.isDirectorylessSessionRouteUrl("http://127.0.0.1:4096/server/abc123/session/ses_1"))
        assertTrue(OpenCodeServerProtocol.isDirectorylessSessionRouteUrl("http://127.0.0.1:4096/server/abc123/session"))
        assertTrue(OpenCodeServerProtocol.isDirectorylessSessionRouteUrl("http://127.0.0.1:4096/new-session"))
        assertTrue(OpenCodeServerProtocol.isDirectorylessSessionRouteUrl("http://127.0.0.1:4096/new-session?draftId=d1"))
        assertFalse(OpenCodeServerProtocol.isDirectorylessSessionRouteUrl("http://127.0.0.1:4096/"))
        assertFalse(OpenCodeServerProtocol.isDirectorylessSessionRouteUrl("http://127.0.0.1:4096/L1VzZXJz/session"))
        assertFalse(OpenCodeServerProtocol.isDirectorylessSessionRouteUrl("http://127.0.0.1:4096/server"))
        assertFalse(OpenCodeServerProtocol.isDirectorylessSessionRouteUrl(null))
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
            initialStatus = IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                "not running",
            ),
            statusProvider = {
                checks += 1
                IntellijMcpServerStartupStatus(
                    if (checks < 2) IntellijMcpServerStartupState.ENABLED_NOT_RUNNING else IntellijMcpServerStartupState.ENABLED,
                    "status $checks",
                )
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
            initialStatus = IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                "not running",
            ),
            statusProvider = {
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                    "not running",
                )
            },
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
            initialStatus = IntellijMcpServerStartupStatus(
                IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                "not running",
            ),
            statusProvider = {
                IntellijMcpServerStartupStatus(
                    IntellijMcpServerStartupState.ENABLED_NOT_RUNNING,
                    "not running",
                )
            },
            shouldWaitForStatus = { status -> IntellijMcpServerStartup.shouldWaitFor(status, enabled) },
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
        val script = OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
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
        assertTrue(script.contains("const previousActive = document.activeElement"))
        assertTrue(script.contains("target.dispatchEvent(new DragEvent('drop'"))
        assertTrue(script.contains("previousActive.isContentEditable"))
        assertTrue(script.contains("hello \\'world\\'.txt"))
        assertTrue(script.contains("aGVsbG8="))
    }

    @Test
    fun buildDispatchDroppedFilesScriptEscapesUnsafeCharactersInFileNames() {
        val script = OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
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
        val script = OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = "file:src/main/App.kt",
            enabled = true,
        )!!

        assertTrue(script.contains("transfer.setData('text/plain', 'file:src/main/App.kt')"))
        assertTrue(script.contains("target.dispatchEvent(new DragEvent('drop'"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptDispatchesTextPlainDropsSeparately() {
        val script = OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("file:CHANGELOG.md", "file:gradle.properties"),
            enabled = true,
        )!!

        assertTrue(script.contains("dispatchDrop((transfer) => transfer.setData('text/plain', 'file:CHANGELOG.md'))"))
        assertTrue(script.contains("dispatchDrop((transfer) => transfer.setData('text/plain', 'file:gradle.properties'))"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWithoutFiles() {
        assertNull(OpenCodeServerProtocol.buildDispatchDroppedFilesScript(emptyList()))
        assertNull(OpenCodeServerProtocol.buildDispatchDroppedFilesScript(emptyList(), textPlain = null, enabled = true))
        assertNull(OpenCodeServerProtocol.buildDispatchDroppedFilesScript(emptyList(), textPlain = emptyList(), enabled = true))
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
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
    fun buildCompactLayoutScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeServerProtocol.buildCompactLayoutScript(enabled = false))
    }

    @Test
    fun buildCompactLayoutScriptPatchesMatchMediaAndInjectsStyle() {
        val script = OpenCodeServerProtocol.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijCompactInstalled"))
        assertTrue(script.contains("window.matchMedia = "))
        assertTrue(script.contains("(min-width: 768px)"))
        assertTrue(script.contains("(max-width: 767px)"))
        assertTrue(script.contains("stub(WIDE_QUERY, false)"))
        assertTrue(script.contains("stub(NARROW_QUERY, true)"))
        assertTrue(script.contains("opencode-intellij-compact-layout"))
        assertTrue(script.contains("md\\\\:flex-row"))
        assertTrue(script.contains("md\\\\:flex-none"))
        assertTrue(script.contains("flex-direction: column"))
    }

    @Test
    fun buildCompactLayoutScriptIsIdempotent() {
        val script = OpenCodeServerProtocol.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("if (window.__opencodeIntellijCompactInstalled) return"))
    }

    @Test
    fun buildIdeThemeSyncScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeServerProtocol.buildIdeThemeSyncScript(enabled = false, dark = true))
    }

    @Test
    fun buildIdeThemeSyncScriptPatchesMatchMediaForPrefersColorScheme() {
        val darkScript = OpenCodeServerProtocol.buildIdeThemeSyncScript(enabled = true, dark = true)!!
        val lightScript = OpenCodeServerProtocol.buildIdeThemeSyncScript(enabled = true, dark = false)!!

        assertTrue(darkScript.contains("(prefers-color-scheme: dark)"))
        assertTrue(darkScript.contains("const dark = true"))
        assertTrue(lightScript.contains("const dark = false"))
        assertTrue(darkScript.contains("window.__opencodeIntellijThemeInstalled"))
        assertTrue(darkScript.contains("window.matchMedia = (q) => q === QUERY ? mql : orig(q)"))
        assertTrue(darkScript.contains("matches: dark"))
        assertFalse(darkScript.contains("window.localStorage.setItem"))
        assertFalse(darkScript.contains("StorageEvent"))
    }

    @Test
    fun buildIdeThemeSyncScriptDispatchesChangeEventOnUpdate() {
        val script = OpenCodeServerProtocol.buildIdeThemeSyncScript(enabled = true, dark = true)!!

        assertTrue(script.contains("MediaQueryListEvent('change'"))
        assertTrue(script.contains("window.__opencodeIntellijThemeMql"))
        assertTrue(script.contains("window.__opencodeIntellijThemeDark !== dark"))
    }

    @Test
    fun buildProjectSwitchPromptSuppressionScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeServerProtocol.buildProjectSwitchPromptSuppressionScript(enabled = false))
    }

    @Test
    fun buildProjectSwitchPromptSuppressionScriptDismissesGoToSessionNotifications() {
        val script = OpenCodeServerProtocol.buildProjectSwitchPromptSuppressionScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijProjectSwitchPromptSuppressionInstalled"))
        assertTrue(script.contains("[data-component=\"toast\"], [data-component=\"toast-v2\"]"))
        assertTrue(script.contains("Permission required"))
        assertTrue(script.contains("Berechtigung erforderlich"))
        assertTrue(script.contains("Go to session"))
        assertTrue(script.contains("Zur Sitzung gehen"))
        assertTrue(script.contains("[data-slot=\"toast-close-button\"], [data-slot=\"toast-v2-close-button\"]"))
        assertTrue(script.contains("new MutationObserver"))
    }

    @Test
    fun buildCursorMirrorScriptIsMissingWhenDisabledOrIncomplete() {
        assertNull(OpenCodeServerProtocol.buildCursorMirrorScript(enabled = false, cursorCallback = "cb(payload)"))
        assertNull(OpenCodeServerProtocol.buildCursorMirrorScript(enabled = true, cursorCallback = null))
    }

    @Test
    fun buildCursorMirrorScriptTracksHoveredElementCursor() {
        val script = OpenCodeServerProtocol.buildCursorMirrorScript(
            enabled = true,
            cursorCallback = "window.intellijCursor(payload)",
        )!!

        assertTrue(script.contains("window.__opencodeIntellijCursorMirrorInstalled"))
        assertTrue(script.contains("getComputedStyle(el).cursor"))
        assertTrue(script.contains("caretRangeFromPoint"))
        assertTrue(script.contains("addEventListener('pointermove'"))
        assertTrue(script.contains("addEventListener('pointerdown'"))
        assertTrue(script.contains("addEventListener('pointerup'"))
        assertTrue(script.contains("addEventListener('scroll'"))
        assertTrue(script.contains("event.buttons !== 0"))
        assertTrue(script.contains("window.intellijCursor(payload)"))
    }

    @Test
    fun awtCursorTypeCoversCommonCssCursors() {
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss(null))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("default"))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("auto"))
        assertEquals(java.awt.Cursor.HAND_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("pointer"))
        assertEquals(java.awt.Cursor.TEXT_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("text"))
        assertEquals(java.awt.Cursor.WAIT_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("progress"))
        assertEquals(java.awt.Cursor.S_RESIZE_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("row-resize"))
        assertEquals(java.awt.Cursor.S_RESIZE_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("ns-resize"))
        assertEquals(java.awt.Cursor.W_RESIZE_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("col-resize"))
        assertEquals(java.awt.Cursor.MOVE_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("grabbing"))
        // Unknown keywords resolve to the default arrow; custom cursors use their keyword fallback.
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("zoom-in"))
        assertEquals(java.awt.Cursor.HAND_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("url(\"custom.png\") 4 4, pointer"))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeServerProtocol.awtCursorTypeForCss("URL(x.cur)"))
    }

    @Test
    fun buildFilePasteSuppressionScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeServerProtocol.buildFilePasteSuppressionScript(enabled = false))
    }

    @Test
    fun buildFilePasteSuppressionScriptCancelsFilePasteEvents() {
        val script = OpenCodeServerProtocol.buildFilePasteSuppressionScript(enabled = true)!!

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
        assertNull(OpenCodeServerProtocol.buildCodeNavigationScript(enabled = false, openCodeCallback = "callback(ref)"))
    }

    @Test
    fun buildCodeNavigationScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeServerProtocol.buildCodeNavigationScript(enabled = true, openCodeCallback = null))
    }

    @Test
    fun buildCodeNavigationScriptInstallsClickListenerOnCodeElements() {
        val script = OpenCodeServerProtocol.buildCodeNavigationScript(enabled = true, openCodeCallback = "window.intellijOpenCodeRef(ref)")!!

        assertTrue(script.contains("window.__opencodeIntellijCodeNavInstalled"))
        assertTrue(script.contains("event.target.closest('code')"))
        assertTrue(script.contains("hasExtension"))
        assertTrue(script.contains("hasPathSeparator"))
        assertTrue(script.contains("isQualifiedClass"))
        assertTrue(script.contains("isPascalCase"))
        assertTrue(script.contains("if (event.defaultPrevented) return"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
        assertTrue(script.contains("window.intellijOpenCodeRef(ref)"))
    }

    @Test
    fun buildFileLinkHandlerScriptStopsAlreadyHandledClicks() {
        val script = OpenCodeServerProtocol.buildFileLinkHandlerScript(
            "/tmp/project",
            enabled = true,
            openFileCallback = "window.intellijOpenFile(rawHref)",
        )!!

        assertTrue(script.contains("if (event.defaultPrevented) return"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
    }

    @Test
    fun buildSystemNotificationBridgeScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeServerProtocol.buildSystemNotificationBridgeScript(enabled = false, notificationCallback = "callback(payload)"))
    }

    @Test
    fun buildSystemNotificationBridgeScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeServerProtocol.buildSystemNotificationBridgeScript(enabled = true, notificationCallback = null))
    }

    @Test
    fun buildSystemNotificationBridgeScriptListensToOpenCodeEventStream() {
        val script = OpenCodeServerProtocol.buildSystemNotificationBridgeScript(
            enabled = true,
            notificationCallback = "window.intellijNotify(payload)",
        )!!

        assertTrue(script.contains("window.__opencodeIntellijNotificationBridgeInstalled"))
        assertTrue(script.contains("fetch('/global/event'"))
        assertTrue(script.contains("Accept: 'text/event-stream'"))
        assertTrue(script.contains("window.__opencodeIntellijEventHub.subscribe"))
        assertTrue(script.contains("type !== 'session.idle'"))
        assertTrue(script.contains("type !== 'permission.asked'"))
        assertTrue(script.contains("type === 'session.status'"))
        assertTrue(script.contains("status.type !== 'idle'"))
        assertTrue(script.contains("recentIdle"))
        assertTrue(script.contains("notification.directory"))
        assertTrue(script.contains("notification.route"))
        assertTrue(script.contains("window.intellijNotify(payload)"))
    }

    @Test
    fun buildAgentStatusBridgeScriptIsMissingWhenDisabledOrIncomplete() {
        assertNull(OpenCodeServerProtocol.buildAgentStatusBridgeScript("/tmp/project", enabled = false, statusCallback = "cb(state)"))
        assertNull(OpenCodeServerProtocol.buildAgentStatusBridgeScript("/tmp/project", enabled = true, statusCallback = null))
        assertNull(OpenCodeServerProtocol.buildAgentStatusBridgeScript(null, enabled = true, statusCallback = "cb(state)"))
        assertNull(OpenCodeServerProtocol.buildAgentStatusBridgeScript(" ", enabled = true, statusCallback = "cb(state)"))
    }

    @Test
    fun buildAgentStatusBridgeScriptTracksBusyAndAttentionState() {
        val script = OpenCodeServerProtocol.buildAgentStatusBridgeScript(
            "/tmp/project",
            enabled = true,
            statusCallback = "window.intellijStatus(state)",
        )!!

        assertTrue(script.contains("window.__opencodeIntellijAgentStatusInstalled"))
        assertTrue(script.contains("fetch('/global/event'"))
        assertTrue(script.contains("window.__opencodeIntellijEventHub.subscribe"))
        assertTrue(script.contains("'/session/status?directory='"))
        assertTrue(script.contains("['/permission', '/question']"))
        assertTrue(script.contains("'session.status'"))
        assertTrue(script.contains("'permission.asked' || type === 'question.asked'"))
        assertTrue(script.contains("'permission.replied' || type === 'question.replied' || type === 'question.rejected'"))
        assertTrue(script.contains("'attention'"))
        assertTrue(script.contains("'busy'"))
        assertTrue(script.contains("window.intellijStatus(state)"))
    }

    @Test
    fun eventBridgeScriptsShareSingleEventStreamConnection() {
        // Chromium allows only six concurrent HTTP/1.1 connections per host across all JCEF
        // browsers, and each panel's SPA already holds one /global/event stream. Both bridges
        // must share a single hub reader (with cross-page leader election) instead of opening
        // one stream each, or two open projects stall every further request in all panels.
        val notificationScript = OpenCodeServerProtocol.buildSystemNotificationBridgeScript(
            enabled = true,
            notificationCallback = "cb(payload)",
        )!!
        val agentScript = OpenCodeServerProtocol.buildAgentStatusBridgeScript(
            "/tmp/project",
            enabled = true,
            statusCallback = "cb(state)",
        )!!

        for (script in listOf(notificationScript, agentScript)) {
            assertEquals(1, Regex("fetch\\('/global/event'").findAll(script).count())
            assertTrue(script.contains("if (window.__opencodeIntellijEventHub) return"))
            assertTrue(script.contains("navigator.locks"))
            assertTrue(script.contains("BroadcastChannel('opencode-intellij-event-hub')"))
        }
    }

    @Test
    fun parseSystemNotificationPayloadReadsPermissionFields() {
        val payload = listOf(
            "id1", "%2Ftmp%2Fproject", "%2Froute", "Permission%20required", "Body",
            "permission", "ses_abc123", "per_xyz789",
        ).joinToString("\n")

        val notification = OpenCodeServerProtocol.parseSystemNotificationPayload(payload)!!

        assertEquals("permission", notification.kind)
        assertEquals("ses_abc123", notification.sessionID)
        assertEquals("per_xyz789", notification.requestID)
        assertTrue(OpenCodeServerProtocol.isPermissionNotification(notification))
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
    fun parseSystemNotificationPayloadDecodesEncodedFields() {
        val payload = "id%201\n%2Ftmp%2Fproject\n%2Fencoded%2Fsession%2Fses_1\nAgent%20done\nLine%201%0ALine%202"

        val notification = OpenCodeServerProtocol.parseSystemNotificationPayload(payload)

        assertNotNull(notification)
        assertEquals("id 1", notification!!.id)
        assertEquals("/tmp/project", notification.directory)
        assertEquals("/encoded/session/ses_1", notification.route)
        assertEquals("Agent done", notification.title)
        assertEquals("Line 1\nLine 2", notification.body)
    }

    @Test
    fun parseSystemNotificationPayloadRejectsMissingIdentity() {
        assertNull(OpenCodeServerProtocol.parseSystemNotificationPayload(null))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationPayload("id-only"))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationPayload("%20\n%2Ftmp%2Fproject\n%2Fencoded\nTitle\nBody"))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationPayload("id\n%20\n%2Fencoded\nTitle\nBody"))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationPayload("id\n%2Ftmp%2Fproject\nencoded\nTitle\nBody"))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationPayload("id\n%2Ftmp%2Fproject\n%2Fencoded\n%20\nBody"))
    }

    @Test
    fun buildSystemNotificationBridgeScriptSendsDismissalSignals() {
        val script = OpenCodeServerProtocol.buildSystemNotificationBridgeScript(
            enabled = true,
            notificationCallback = "window.intellijNotify(payload)",
        )!!

        assertTrue(script.contains("'__opencode_dismiss__'"))
        assertTrue(script.contains("'permission.replied' || type === 'question.replied' || type === 'question.rejected'"))
        assertTrue(script.contains("status.type === 'busy' || status.type === 'retry'"))
        assertTrue(script.contains("addEventListener('pointerdown'"))
        assertTrue(script.contains("addEventListener('keydown'"))
        assertTrue(script.contains("addEventListener('focus'"))
        assertTrue(script.contains("/session\\/"))
    }

    @Test
    fun parseSystemNotificationDismissalReadsRequestAndSessionScopes() {
        assertEquals(
            "request:per_xyz789",
            OpenCodeServerProtocol.parseSystemNotificationDismissal("__opencode_dismiss__\nrequest\nper_xyz789")!!.key,
        )
        assertEquals(
            "session:ses_abc123",
            OpenCodeServerProtocol.parseSystemNotificationDismissal("__opencode_dismiss__\nsession\nses_abc123")!!.key,
        )
    }

    @Test
    fun parseSystemNotificationDismissalRejectsMalformedPayloads() {
        assertNull(OpenCodeServerProtocol.parseSystemNotificationDismissal(null))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationDismissal(""))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationDismissal("__opencode_dismiss__\nrequest"))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationDismissal("__opencode_dismiss__\nelsewhere\nper_1"))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationDismissal("__opencode_dismiss__\nsession\nses%2F..%2Fevil"))
        assertNull(OpenCodeServerProtocol.parseSystemNotificationDismissal("__opencode_dismiss__\nsession\n%20"))
        // A regular notification payload must never be mistaken for a dismissal.
        assertNull(OpenCodeServerProtocol.parseSystemNotificationDismissal("id1\n%2Ftmp%2Fproject\n%2Froute\nTitle\nBody\npermission\nses_1\nper_1"))
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
    }

    @Test
    fun isSameFilesystemPathTreatsWindowsSeparatorsAndDriveCaseAsEquivalent() {
        assertTrue(OpenCodeServerProtocol.isSameFilesystemPath("C:\\Source\\Project", "c:/Source/Project/"))
        assertFalse(OpenCodeServerProtocol.isSameFilesystemPath("C:\\Source\\Project", "C:/Source/Other"))
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
    fun isOpenCodeSessionRouteHrefDetectsOpenCodeAppSessionRoutes() {
        val projectRoute = "/${OpenCodeServerProtocol.encodeDirectory("/tmp/project")}/session/session-id"

        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref(projectRoute))
        assertTrue(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("http://127.0.0.1:60482$projectRoute"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("/tmp/session/readme.md"))
        assertFalse(OpenCodeServerProtocol.isOpenCodeSessionRouteHref("license.md"))
    }

    @Test
    fun resolveFileLinkIgnoresOpenCodeAppSessionRoutes() {
        val projectDir = Files.createTempDirectory("opencode-file-link-test")
        val route = "/${OpenCodeServerProtocol.encodeDirectory(projectDir.toString())}/session/session-id"

        assertNull(OpenCodeServerProtocol.resolveFileLink(route, projectDir.toString()))
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
    fun shouldRestartServerOnlyWhenKnownUrlFailsHealthCheck() {
        assertFalse(OpenCodeServerProtocol.shouldRestartServer(null, serverResponding = false))
        assertTrue(OpenCodeServerProtocol.shouldRestartServer("http://127.0.0.1:60482", serverResponding = false))
        assertFalse(OpenCodeServerProtocol.shouldRestartServer("http://127.0.0.1:60482", serverResponding = true))
    }

    @Test
    fun shouldDelayServerStartOnlyBeforeBackoffExpires() {
        assertTrue(OpenCodeServerProtocol.shouldDelayServerStart(nextStartAllowedAtMillis = 2_000, nowMillis = 1_999))
        assertFalse(OpenCodeServerProtocol.shouldDelayServerStart(nextStartAllowedAtMillis = 2_000, nowMillis = 2_000))
        assertFalse(OpenCodeServerProtocol.shouldDelayServerStart(nextStartAllowedAtMillis = 0, nowMillis = 1_999))
    }

    @Test
    fun startFailureBackoffUsesExponentialDelayWithCap() {
        assertEquals(5_000L, OpenCodeServerProtocol.startFailureBackoffMillis(1))
        assertEquals(10_000L, OpenCodeServerProtocol.startFailureBackoffMillis(2))
        assertEquals(20_000L, OpenCodeServerProtocol.startFailureBackoffMillis(3))
        assertEquals(60_000L, OpenCodeServerProtocol.startFailureBackoffMillis(10))
    }

    private fun withSingleRequestHttpServer(body: String, block: (String) -> Unit) {
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val responseFuture = executor.submit {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = reader.readLine()
                    if (requestLine != "GET ${OpenCodeServerProtocol.HEALTH_PATH} HTTP/1.1") {
                        throw AssertionError("Unexpected request line: $requestLine")
                    }
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // Drain request headers before responding.
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
    }

    @Test
    fun fetchLastMessageJsonExtractsFirstObject() {
        val response = """{"data":[{"type":"assistant","time":{"created":12345}}]}"""
        val serverSocket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val responseFuture = executor.submit {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (reader.readLine()?.isNotEmpty() == true) {}
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response"
                            .toByteArray(Charsets.UTF_8),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (_: SocketException) {}
        }
        try {
            val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
            val msg = OpenCodeServerProtocol.fetchLastMessageJson(
                "http://127.0.0.1:${serverSocket.localPort}",
                auth,
                "ses_abc123",
            )
            assertNotNull(msg)
            assertTrue(msg!!.contains("\"type\":\"assistant\""))
            assertTrue(OpenCodeServerProtocol.isInterruptedLastMessage(msg))
            responseFuture.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun sendContinuePromptReturnsFalseForInvalidSessionId() {
        val auth = OpenCodeServerProtocol.buildBasicAuthHeader("test")
        assertFalse(OpenCodeServerProtocol.sendContinuePrompt("http://127.0.0.1:1", auth, "invalid"))
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
}
