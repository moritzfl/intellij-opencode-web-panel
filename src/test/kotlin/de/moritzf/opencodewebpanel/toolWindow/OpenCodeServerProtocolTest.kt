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
    fun buildAuthenticatedServerRootUrlAddsEncodedAuthToken() {
        assertEquals(
            "http://127.0.0.1:60482?auth_token=b3BlbmNvZGU6c2VjcmV0LXBhc3N3b3Jk",
            OpenCodeServerProtocol.buildAuthenticatedServerRootUrl("http://127.0.0.1:60482/", "secret-password"),
        )
        assertEquals(
            "http://127.0.0.1:60482?auth_token=b3BlbmNvZGU6cA%3D%3D",
            OpenCodeServerProtocol.buildAuthenticatedServerRootUrl("http://127.0.0.1:60482/", "p"),
        )
    }

    @Test
    fun buildAuthenticatedServerRootUrlFallsBackToRootWithoutPassword() {
        assertEquals(
            "http://127.0.0.1:60482",
            OpenCodeServerProtocol.buildAuthenticatedServerRootUrl("http://127.0.0.1:60482/", null),
        )
        assertEquals(
            "http://127.0.0.1:60482",
            OpenCodeServerProtocol.buildAuthenticatedServerRootUrl("http://127.0.0.1:60482/", ""),
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
        assertTrue(script.contains("layout.lastProjectSession[directory]"))
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
        assertTrue(script.contains("onProjectSessionRoute && !foundRecentSession"))
        assertFalse(script.contains("getNavigationState() === 'complete'"))
        assertFalse(script.contains("setNavigationState('complete')"))
    }

    @Test
    fun buildFileLinkHandlerScriptInterceptsLocalFileLinks() {
        val script = OpenCodeServerProtocol.buildFileLinkHandlerScript("/tmp/project")!!

        assertTrue(script.contains("window.__opencodeIntellijFileLinksInstalled"))
        assertTrue(script.contains("event.target.closest('a')"))
        assertTrue(script.contains("inferredFileLink(link)"))
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
            openFileCallback = "window.intellijOpenFile(rawHref)",
        )!!

        assertTrue(script.contains("window.intellijOpenFile(rawHref)"))
        assertFalse(script.contains("window.location.assign(target)"))
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
        assertFalse(script.contains(OpenCodeServerProtocol.OPEN_CODE_DEFAULT_SERVER_URL_STORAGE_KEY))
        assertTrue(script.contains("window.intellijStore(payload)"))
    }

    @Test
    fun buildStartupErrorPageHtmlPointsToSettings() {
        val html = OpenCodeServerProtocol.buildStartupErrorPageHtml("/custom/bin/opencode")

        assertTrue(html.contains("/custom/bin/opencode"))
        assertTrue(html.contains("Settings &gt; Tools &gt; OpenCode Web Panel &gt; OpenCode Server Setup"))
        assertTrue(html.contains("configure the OpenCode executable path"))
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
        assertTrue(script.contains("document.dispatchEvent(new DragEvent('drop'"))
        assertTrue(script.contains("hello \\'world\\'.txt"))
        assertTrue(script.contains("aGVsbG8="))
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWithoutFiles() {
        assertNull(OpenCodeServerProtocol.buildDispatchDroppedFilesScript(emptyList()))
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
    fun buildCompactLayoutScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeServerProtocol.buildCompactLayoutScript(enabled = false))
    }

    @Test
    fun buildCompactLayoutScriptPatchesMatchMediaAndInjectsStyle() {
        val script = OpenCodeServerProtocol.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijCompactInstalled"))
        assertTrue(script.contains("window.matchMedia = "))
        assertTrue(script.contains("(min-width: 768px)"))
        assertTrue(script.contains("matches: false"))
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
        assertTrue(script.contains("window.intellijOpenCodeRef(ref)"))
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
        val url = "${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}?href=src%2FMain.kt"

        assertTrue(OpenCodeServerProtocol.isOpenFileLinkRequest(url))
        assertEquals("src/Main.kt", OpenCodeServerProtocol.openFileLinkHref(url))
        assertFalse(OpenCodeServerProtocol.isOpenFileLinkRequest("https://example.com/src/Main.kt"))
    }

    @Test
    fun routeDirectoryFromUrlDecodesOpenCodeProjectRoute() {
        val directory = "/Users/moritz/Desktop/git/benjamin"
        val url = "http://127.0.0.1:57099/${OpenCodeServerProtocol.encodeDirectory(directory)}/session/license.md"

        assertEquals(directory, OpenCodeServerProtocol.routeDirectoryFromUrl(url))
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
    fun checkServerRespondingReturnsTrueForHttpResponse() {
        withSingleRequestHttpServer { url ->
            assertTrue(OpenCodeServerProtocol.checkServerResponding(url, 1000, 1000))
        }
    }

    @Test
    fun checkServerRespondingReturnsFalseForInvalidUrl() {
        assertFalse(OpenCodeServerProtocol.checkServerResponding("not-a-url", 100, 100))
    }

    @Test
    fun shouldRestartServerWhenUrlIsMissingOrHealthCheckFails() {
        assertTrue(OpenCodeServerProtocol.shouldRestartServer(null, serverResponding = false))
        assertTrue(OpenCodeServerProtocol.shouldRestartServer("http://127.0.0.1:60482", serverResponding = false))
        assertFalse(OpenCodeServerProtocol.shouldRestartServer("http://127.0.0.1:60482", serverResponding = true))
    }

    private fun withSingleRequestHttpServer(block: (String) -> Unit) {
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val responseFuture = executor.submit {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // Drain request headers before responding.
                    }
                    val body = "ok"
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
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
}
