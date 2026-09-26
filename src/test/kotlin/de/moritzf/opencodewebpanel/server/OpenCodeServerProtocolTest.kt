package de.moritzf.opencodewebpanel.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.atomic.AtomicInteger

class OpenCodeServerProtocolTest {
    private val tempDirs = mutableListOf<Path>()

    @After
    fun deleteTempDirectories() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
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
        val projectDirectory = tempDir("opencode-project")
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
        val projectDirectory = tempDir("opencode-project")
        val executableDirectory = tempDir("opencode-bin")
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
    fun resolveExecutableForLaunchKeepsBareNameWhenMissing() {
        assertEquals(
            "sbx",
            OpenCodeServerProtocol.resolveExecutableForLaunch("sbx", path = "/tmp/does-not-exist-opencode-path"),
        )
    }

    @Test
    fun detectExecutablePathFindsExecutableOnPath() {
        val executableDirectory = tempDir("opencode-bin")
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
        val executableDirectory = tempDir("opencode-bin")
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
        val executableDirectory = tempDir("opencode-bin")
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
        val executableDirectory = tempDir("opencode-bin")
        try {
            assertNull(OpenCodeServerProtocol.detectExecutablePath(path = executableDirectory.toString()))
        } finally {
            executableDirectory.toFile().delete()
        }
    }

    @Test
    fun resolveExecutableForLaunchFallsBackToCommandNameWhenExecutableIsMissing() {
        val executableDirectory = tempDir("opencode-bin")
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
        assertEquals(
            "http://127.0.0.1:50839",
            OpenCodeServerProtocol.parseServerUrl("server listening on http://127.0.0.1:50839"),
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
        assertNull(
            OpenCodeServerProtocol.parseServerUrl("server listening on http://0.0.0.0:4096"),
        )
        assertFalse(OpenCodeServerProtocol.isLoopbackServerUrl("http://10.0.0.1:4096"))
        assertFalse(OpenCodeServerProtocol.isLoopbackServerUrl("http://0.0.0.0:4096"))
        assertTrue(OpenCodeServerProtocol.isLoopbackServerUrl("http://127.0.0.1:4096"))
        assertTrue(OpenCodeServerProtocol.isLoopbackServerUrl("http://localhost:4096"))
        val published = OpenCodeServerProtocol.publishedSandboxUrl(49161)
        assertEquals("http://127.0.0.1:49161", published)
        assertTrue(OpenCodeServerProtocol.isLoopbackServerUrl(published))
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
            "http://127.0.0.1:60482/",
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
    fun externalHttpUrlAllowsOnlyHttpLinksOutsideOpenCodeOrigin() {
        val serverUrl = "http://127.0.0.1:4096"

        assertEquals("https://example.com/docs", OpenCodeServerProtocol.externalHttpUrl("https://example.com/docs", serverUrl))
        assertEquals("http://example.com/docs", OpenCodeServerProtocol.externalHttpUrl(" http://example.com/docs ", serverUrl))
        assertNull(OpenCodeServerProtocol.externalHttpUrl("http://127.0.0.1:4096/docs", serverUrl))
        assertNull(OpenCodeServerProtocol.externalHttpUrl("/docs", serverUrl))
        assertNull(OpenCodeServerProtocol.externalHttpUrl("mailto:test@example.com", serverUrl))
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
    fun lifecycleStatusDetailIncludesRuntimeAndVersion() {
        val runningNative = formatOpenCodeServerStatusDetail(
            OpenCodeServerLifecycleState.RUNNING,
            "http://127.0.0.1:4096",
            "1.18.23",
            OpenCodeServerBackend.nativeBackendId("/tmp/project"),
            OpenCodeWireProtocol.V1_18,
        )
        val runningCli = formatOpenCodeServerStatusDetail(
            OpenCodeServerLifecycleState.RUNNING,
            "http://127.0.0.1:4096",
            "2.0.5",
            OpenCodeServerBackend.nativeBackendId("/tmp/project"),
            OpenCodeWireProtocol.V2_CLI,
        )
        val runningSbx = formatOpenCodeServerStatusDetail(
            OpenCodeServerLifecycleState.RUNNING,
            "http://127.0.0.1:49196",
            "1.18.23",
            "sbx:ide-ocwp-deadbeef",
            OpenCodeWireProtocol.V1_18,
        )
        val stoppedSbx = formatOpenCodeServerStatusDetail(
            OpenCodeServerLifecycleState.STOPPED,
            null,
            null,
            "sbx:ide-ocwp-deadbeef",
        )
        assertEquals(": http://127.0.0.1:4096 (OpenCode 1.18.23, 1.18, native CLI)", runningNative)
        assertEquals(": http://127.0.0.1:4096 (OpenCode 2.0.5, 2.x, native CLI)", runningCli)
        assertEquals(": http://127.0.0.1:49196 (OpenCode 1.18.23, 1.18, sbx)", runningSbx)
        assertEquals(" (sbx)", stoppedSbx)
        assertEquals("native CLI", formatOpenCodeServerRuntimeLabel(OpenCodeServerBackend.NATIVE_ID))
        assertEquals("sbx", formatOpenCodeServerRuntimeLabel("sbx:ide-ocwp-deadbeef"))
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
    fun localFileDropTextUsesOpenCodeProjectRelativeConvention() {
        val projectRoot = tempDir("opencode-project")
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
        val projectRoot = tempDir("opencode-project")
        val outsideRoot = tempDir("opencode-outside")
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
    fun parseSessionInfoReadsBareAndEnvelopedSessions() {
        val bare = OpenCodeServerProtocol.parseSessionInfo(
            """{"id":"ses_1","title":"Fix the build","directory":"/tmp/project"}""",
        )!!
        assertEquals("ses_1", bare.id)
        assertEquals("Fix the build", bare.title)
        assertNull(bare.parentID)
        assertEquals("/tmp/project", bare.directory)

        val enveloped = OpenCodeServerProtocol.parseSessionInfo(
            """{"data":{"id":"ses_2","title":"Subtask","parentID":"ses_1","location":{"directory":"/private/tmp/other"},"directory":"/tmp/other"}}""",
        )!!
        assertEquals("Subtask", enveloped.title)
        assertEquals("ses_1", enveloped.parentID)
        assertEquals("/private/tmp/other", enveloped.directory)

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
    fun parseBusySessionIdsReadsCliActiveRunningMap() {
        assertEquals(
            setOf("ses_f547e1692ffelWnvCLl1OK8i4s"),
            OpenCodeServerProtocol.parseBusySessionIds(wireFixture("v2_cli/session-active-running.json")),
        )
        assertTrue(OpenCodeServerProtocol.parseBusySessionIds(wireFixture("v2_cli/session-active-empty.json")).isEmpty())
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
    fun parsePendingRequestsReadsCliDataEnvelope() {
        val pending = OpenCodeServerProtocol.parsePendingRequests(wireFixture("v2_cli/permission-request-pending.json"))
        assertEquals(
            listOf(
                OpenCodeServerProtocol.PendingRequestSummary(
                    "per_0ab82a936001Ab0k4zIgsRFD7a",
                    "ses_f547e1692ffelWnvCLl1OK8i4s",
                ),
            ),
            pending,
        )
        assertTrue(OpenCodeServerProtocol.parsePendingRequests(wireFixture("v2_cli/permission-request-empty.json")).isEmpty())
    }

    @Test
    fun fetchPendingQuestionsOnCliTwoIsSkipped() {
        val result = OpenCodeServerProtocol.fetchPendingRequestsResult(
            "http://127.0.0.1:1",
            OpenCodeServerProtocol.buildBasicAuthHeader("test"),
            OpenCodeServerProtocol.QUESTION_LIST_PATH,
            "/tmp/project",
            wireProtocol = OpenCodeWireProtocol.V2_CLI,
        )
        assertEquals(OpenCodeProtocolResult.Success(emptyList<OpenCodeServerProtocol.PendingRequestSummary>()), result)
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
        assertNull(payload.partID)
        val withPart = OpenCodeServerProtocol.parseOpenFileLinkPayload("src/Main.kt\n/tmp/project\nprt_1")
        assertEquals("prt_1", withPart!!.partID)
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
        val root = tempDir("opencode-path-alias")
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
        val root = tempDir("opencode-canonical-dir")
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
    fun canonicalOpenCodeDirectoryCachesResolvedAndIdentitySpellings() {
        val root = tempDir("opencode-canonical-cache")
        try {
            val first = OpenCodeServerProtocol.canonicalOpenCodeDirectory(root.toString())
            val second = OpenCodeServerProtocol.canonicalOpenCodeDirectory(root.toString())
            assertEquals(first, second)
            assertEquals(first, OpenCodeServerProtocol.canonicalOpenCodeDirectory(first))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun adoptOpenCodeDirectoryPrefersTheServerSpellingOfTheSameFolder() {
        val root = tempDir("opencode-adopt-dir")
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
        val ideProjectDir = tempDir("opencode-ide-project")
        val openCodeDir = tempDir("opencode-route-project")
        val file = Files.writeString(openCodeDir.resolve("license.md"), "license")

        val target = OpenCodeServerProtocol.resolveFileLink("license.md", ideProjectDir.toString(), openCodeDir.toString())

        assertNotNull(target)
        assertEquals(file.normalize(), target!!.path)
    }

    @Test
    fun resolveFileLinkChecksEveryExactBaseBeforeGuessing() {
        val guessBase = tempDir("opencode-guess-base")
        val exactBase = tempDir("opencode-exact-base")
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
    fun resolveFileLinkSkipsIncompleteGuessWhenDisabled() {
        val guessBase = tempDir("opencode-no-guess")
        Files.createDirectories(guessBase.resolve("nested/src"))
        val guessed = Files.writeString(guessBase.resolve("nested/src/Main.kt"), "guess")
        val exactBase = tempDir("opencode-no-guess-exact")
        Files.createDirectories(exactBase.resolve("src"))
        val exact = Files.writeString(exactBase.resolve("src/Main.kt"), "exact")

        assertEquals(
            guessed.normalize(),
            OpenCodeServerProtocol.resolveFileLinkWithBases("src/Main.kt", listOf(guessBase.toString()))?.path,
        )
        assertNull(
            OpenCodeServerProtocol.resolveFileLinkWithBases(
                "src/Main.kt",
                listOf(guessBase.toString()),
                guessIncomplete = false,
            ),
        )
        assertEquals(
            exact.normalize(),
            OpenCodeServerProtocol.resolveFileLinkWithBases(
                "src/Main.kt",
                listOf(exactBase.toString()),
                guessIncomplete = false,
            )?.path,
        )
    }

    @Test
    fun resolveFileLinkGuessUsesCaseInsensitiveFilesystemSemanticsWhenRequested() {
        val base = tempDir("opencode-case-guess")
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
        val base = tempDir("opencode-case-prune")
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
        val base = tempDir("opencode-encoded")
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
        val base = tempDir("opencode-file-scheme")
        Files.createDirectories(base.resolve("src"))
        val file = Files.writeString(base.resolve("src/Main.kt"), "x")

        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink("file:src/Main.kt", base.toString(), null)?.path)
        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink("file://src/Main.kt", base.toString(), null)?.path)
    }

    @Test
    fun resolveFileLinkFallsBackToProjectRootForRootRelativeLinks() {
        // `/src/Main.kt` is absolute on Unix but is just as often meant project-relative, and on
        // Windows it is not absolute at all (resolve would drop the drive).
        val base = tempDir("opencode-root-relative")
        Files.createDirectories(base.resolve("src"))
        val file = Files.writeString(base.resolve("src/Main.kt"), "x")

        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink("/src/Main.kt", base.toString(), null)?.path)
        // A real absolute path still wins over the project-relative reading.
        val outside = tempDir("opencode-outside")
        val outsideFile = Files.writeString(outside.resolve("Other.kt"), "x")
        assertEquals(
            outsideFile.normalize(),
            OpenCodeServerProtocol.resolveFileLink(outsideFile.toString(), base.toString(), null)?.path,
        )
    }

    @Test
    fun resolveFileLinkOpensRootRelativeWindowsDriveFromMarkdownOutsideTheProject() {
        assumeTrue("Windows drive paths only exist on Windows", File.separatorChar == '\\')
        val project = tempDir("opencode-drive-link-project")
        val outside = tempDir("opencode-drive-link-outside")
        val file = Files.writeString(outside.resolve("Überblick.md"), "x")
        val href = "/${file.toString().replace('\\', '/')}"
        val encoded = href.replace("Ü", "%C3%9C")

        val target = OpenCodeServerProtocol.resolveFileLink("$encoded#L12", project.toString(), null)
        assertEquals(file.normalize(), target?.path)
        assertEquals(11, target?.line)
        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink(href, project.toString(), null)?.path)
        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink(encoded.drop(1), project.toString(), null)?.path)
        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink("file://$href", project.toString(), null)?.path)
        assertEquals(file.normalize(), OpenCodeServerProtocol.resolveFileLink("file://$encoded", project.toString(), null)?.path)
    }

    @Test
    fun resolveFileLinkTrimsDecoratedHrefs() {
        val base = tempDir("opencode-decorated")
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
        val base = tempDir("opencode-root-line")
        Files.createDirectories(base.resolve("src"))
        val file = Files.writeString(base.resolve("src/Main.kt"), "x")

        val target = OpenCodeServerProtocol.resolveFileLink("/src/Main.kt:42", base.toString(), null)
        assertEquals(file.normalize(), target?.path)
        assertEquals(41, target?.line)
    }

    @Test
    fun resolveFileLinkKeepsTrailingColonWhenItIsPartOfTheName() {
        assumeFalse("Windows filenames cannot contain a colon", File.separatorChar == '\\')
        val base = tempDir("opencode-colon")
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
    fun resolveFileLinkUsesTrailingLineNumberWhenTheFileExists() {
        val base = tempDir("opencode-line-locator")
        Files.createDirectories(base.resolve("src"))
        val file = Files.writeString(base.resolve("src/Main.kt"), "x")

        val target = OpenCodeServerProtocol.resolveFileLink("src/Main.kt:42", base.toString(), null)
        assertEquals(file.normalize(), target?.path)
        assertEquals(41, target?.line)
    }

    @Test
    fun resolveFileLinkCarriesPositionsThroughEncodedSpellings() {
        val base = tempDir("opencode-encoded-line")
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
        val base = tempDir("opencode-illegal")
        // Percent-decoding can produce characters that are illegal in a path (NUL everywhere,
        // `<>?*|"` on Windows). Those must fail closed, not throw out of the click handler.
        for (href in listOf("docs/a%00b.md", "docs/a%00b.md:12", "src/%3Cname%3E.kt:10", "docs/a%zz.md", "docs/trailing%")) {
            assertNull("href $href", OpenCodeServerProtocol.resolveFileLink(href, base.toString(), null))
        }
    }

    @Test
    fun resolveFileLinkGuessesIncompleteSubPaths() {
        val base = tempDir("opencode-guess")
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
        val base = tempDir("opencode-guess-rank")
        Files.createDirectories(base.resolve("a/src"))
        Files.createDirectories(base.resolve("b/other"))
        val wanted = Files.writeString(base.resolve("a/src/Main.kt"), "x")
        Files.writeString(base.resolve("b/other/Main.kt"), "x")

        // Both files share the name; only one also matches the `src` segment.
        assertEquals(wanted.normalize(), OpenCodeServerProtocol.resolveFileLink("src/Main.kt", base.toString(), null)?.path)
    }

    @Test
    fun resolveFileLinkGuessSkipsBuildAndVcsDirectories() {
        val base = tempDir("opencode-guess-prune")
        Files.createDirectories(base.resolve("node_modules/pkg/src"))
        Files.createDirectories(base.resolve("build/generated"))
        Files.writeString(base.resolve("node_modules/pkg/src/Only.kt"), "x")
        Files.writeString(base.resolve("build/generated/Only.kt"), "x")

        assertNull(OpenCodeServerProtocol.resolveFileLink("Only.kt", base.toString(), null))
    }

    @Test
    fun resolveFileLinkDoesNotGuessForMissingFiles() {
        val base = tempDir("opencode-guess-miss")
        Files.createDirectories(base.resolve("src"))
        Files.writeString(base.resolve("src/Main.kt"), "x")

        assertNull(OpenCodeServerProtocol.resolveFileLink("src/Missing.kt", base.toString(), null))
        assertNull(OpenCodeServerProtocol.resolveFileLink("Missing.kt", base.toString(), null))
    }

    @Test
    fun resolveFileLinkIgnoresRootRelativeHrefWithoutAFileSuffix() {
        val base = tempDir("opencode-root-rel")
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
        val projectDir = tempDir("opencode-file-link-test")
        val route = "/${OpenCodeServerProtocol.encodeDirectory(projectDir.toString())}/session/session-id"
        val serverRoute = "/server/aHR0cDovLzEyNy4wLjAuMTo2MDQ4Mg/session/ses_child"

        assertNull(OpenCodeServerProtocol.resolveFileLink(route, projectDir.toString()))
        assertNull(OpenCodeServerProtocol.resolveFileLink(serverRoute, projectDir.toString()))
    }

    @Test
    fun resolveFileLinkUsesOpenCodeFileUrlStartQueryAsLine() {
        val projectDir = tempDir("opencode-file-link-test")
        val file = Files.writeString(projectDir.resolve("Main.kt"), "fun main() {}")

        val target = OpenCodeServerProtocol.resolveFileLink("${file.toUri()}?start=10&end=20", projectDir.toString())

        assertNotNull(target)
        assertEquals(file.normalize(), target!!.path)
        assertEquals(9, target.line)
        assertNull(target.column)
    }

    @Test
    fun resolveFileLinkSupportsOpenCodeRelativeFileUrlTabs() {
        val projectDir = tempDir("opencode-file-link-test")
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
        val projectDir = tempDir("opencode-file-link-test")
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
    fun windowsDriveGuestToHostDoesNotTreatUnixRootsAsDrives() {
        assertEquals("C:/Users/me/project/src/Main.kt", OpenCodeServerProtocol.windowsDriveGuestToHost("/c/Users/me/project/src/Main.kt"))
        assertEquals("C:/", OpenCodeServerProtocol.windowsDriveGuestToHost("/c"))
        assertEquals("C:/", OpenCodeServerProtocol.windowsDriveGuestToHost("/c/"))
        assertNull(OpenCodeServerProtocol.windowsDriveGuestToHost("/home/agent/src/Main.kt"))
        assertNull(OpenCodeServerProtocol.windowsDriveGuestToHost("/usr/bin/env"))
        assertNull(OpenCodeServerProtocol.windowsDriveGuestToHost("src/Main.kt"))
    }

    @Test
    fun applyGuestToHostPrefixesPrefersTheLongestGuestPrefix() {
        val prefixes = listOf(
            "/home/agent" to "/tmp/persist",
            "/home/agent/docs" to "/Users/me/docs",
        )
        assertEquals(
            "/Users/me/docs/guide.md",
            OpenCodeServerProtocol.applyGuestToHostPrefixes("/home/agent/docs/guide.md", prefixes),
        )
        assertEquals(
            "/tmp/persist/.local/share/opencode/sessions/x.json",
            OpenCodeServerProtocol.applyGuestToHostPrefixes("/home/agent/.local/share/opencode/sessions/x.json", prefixes),
        )
        assertEquals("src/Main.kt", OpenCodeServerProtocol.applyGuestToHostPrefixes("src/Main.kt", prefixes))
    }

    @Test
    fun resolveFileLinkMapsSandboxGuestPathsOntoHostMounts() {
        val projectDir = tempDir("opencode-host-project")
        val extraHost = tempDir("opencode-extra-host")
        Files.createDirectories(projectDir.resolve("src"))
        val projectFile = Files.writeString(projectDir.resolve("src/Main.kt"), "x")
        val extraFile = Files.writeString(extraHost.resolve("guide.md"), "y")
        val prefixes = listOf(
            "/home/agent/docs" to extraHost.toString(),
            "/home/agent/project" to projectDir.toString(),
        )

        assertEquals(
            extraFile.normalize(),
            OpenCodeServerProtocol.resolveFileLinkWithBases(
                "/home/agent/docs/guide.md",
                listOf(projectDir.toString()),
                guestToHostPrefixes = prefixes,
            )?.path,
        )
        assertEquals(
            extraFile.normalize(),
            OpenCodeServerProtocol.resolveFileLinkWithBases(
                "~/docs/guide.md",
                listOf(projectDir.toString()),
                guestToHostPrefixes = prefixes,
                home = "/home/agent",
            )?.path,
        )
        assertEquals(
            projectFile.normalize(),
            OpenCodeServerProtocol.resolveFileLinkWithBases(
                "/home/agent/project/src/Main.kt:42",
                listOf(projectDir.toString()),
                guestToHostPrefixes = prefixes,
            )?.path,
        )
        assertEquals(
            41,
            OpenCodeServerProtocol.resolveFileLinkWithBases(
                "/home/agent/project/src/Main.kt:42",
                listOf(projectDir.toString()),
                guestToHostPrefixes = prefixes,
            )?.line,
        )
        assertEquals(
            extraFile.normalize(),
            OpenCodeServerProtocol.resolveFileLinkWithBases(
                "guide.md",
                listOf(projectDir.toString()),
                guestToHostPrefixes = prefixes,
            )?.path,
        )
    }

    @Test
    fun fileLinkPathAliasesIncludeWindowsDriveGuestAndTilde() {
        val aliases = OpenCodeServerProtocol.fileLinkPathAliases(
            "~/docs/guide.md",
            guestToHostPrefixes = listOf("/home/agent/docs" to "/Users/me/docs"),
            home = "/home/agent",
        )
        assertTrue(aliases.contains("~/docs/guide.md"))
        assertTrue(aliases.contains("/home/agent/docs/guide.md"))
        assertTrue(aliases.contains("/Users/me/docs/guide.md"))
        assertEquals(
            listOf("/c/Users/me/src/Main.kt", "C:/Users/me/src/Main.kt"),
            OpenCodeServerProtocol.fileLinkPathAliases("/c/Users/me/src/Main.kt"),
        )
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
        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, false, "localhost", 60482))
        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, false, "::1", 60482))
        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, false, "[::1]", 60482))
        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge("http://localhost:60482", false, "127.0.0.1", 60482))
        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge("http://127.0.0.1", false, "127.0.0.1", 80))
        assertTrue(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge("https://127.0.0.1", false, "127.0.0.1", 443))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, true, "127.0.0.1", 60482))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, false, "127.0.0.1", 60483))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(serverUrl, false, "example.com", 60482))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge("http://example.com:60482", false, "localhost", 60482))
        assertFalse(OpenCodeServerProtocol.shouldHandleBasicAuthChallenge(null, false, "127.0.0.1", 60482))
    }

    @Test
    fun unansweredLoopbackChallengeIsCancelledSoChromiumShowsNoLoginDialog() {
        val live = "http://127.0.0.1:4096"
        assertEquals(
            OpenCodeServerProtocol.BasicAuthChallengeReply.CONTINUE,
            OpenCodeServerProtocol.replyToBasicAuthChallenge(false, "127.0.0.1", 4096, live, "secret", true),
        )
        assertEquals(
            OpenCodeServerProtocol.BasicAuthChallengeReply.CONTINUE,
            OpenCodeServerProtocol.replyToBasicAuthChallenge(false, "localhost", 4096, live, "secret", true),
        )
        assertEquals(
            OpenCodeServerProtocol.BasicAuthChallengeReply.CANCEL,
            OpenCodeServerProtocol.replyToBasicAuthChallenge(false, "127.0.0.1", 49262, live, "secret", true),
        )
        assertEquals(
            OpenCodeServerProtocol.BasicAuthChallengeReply.CANCEL,
            OpenCodeServerProtocol.replyToBasicAuthChallenge(false, "127.0.0.1", 49262, null, null, false),
        )
        assertEquals(
            OpenCodeServerProtocol.BasicAuthChallengeReply.IGNORE,
            OpenCodeServerProtocol.replyToBasicAuthChallenge(true, "127.0.0.1", 4096, live, "secret", true),
        )
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
    fun classifyWireProtocolUsesJsonOnlyBodies() {
        val v1 = OpenCodeProtocolResult.Success("""{"healthy":true,"version":"1.18.31"}""")
        val cli = OpenCodeProtocolResult.Success("""{"version":"2.0.5","pid":47000,"urls":["http://127.0.0.1:18732"]}""")
        val embeddedV2 = OpenCodeProtocolResult.Success("""{"pid":12}""")
        val html = OpenCodeProtocolResult.Success("<!doctype html><html lang=\"en\"></html>")
        val notFound = OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 404)
        val timeout = OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.TIMEOUT)

        assertEquals(OpenCodeWireProtocol.V1_18, OpenCodeServerProtocol.classifyWireProtocolForTest(v1, null, null))
        assertEquals(OpenCodeWireProtocol.V1_18, OpenCodeServerProtocol.classifyWireProtocolForTest(notFound, null, v1))
        assertEquals(OpenCodeWireProtocol.V2_CLI, OpenCodeServerProtocol.classifyWireProtocolForTest(html, cli, notFound))
        assertEquals(OpenCodeWireProtocol.V1_18_EMBEDDED_V2, OpenCodeServerProtocol.classifyWireProtocolForTest(notFound, notFound, embeddedV2))
        assertEquals(OpenCodeWireProtocol.UNKNOWN, OpenCodeServerProtocol.classifyWireProtocolForTest(html, notFound, notFound))
        assertEquals(OpenCodeWireProtocol.UNKNOWN, OpenCodeServerProtocol.classifyWireProtocolForTest(timeout, timeout, timeout))
        assertEquals(OpenCodeWireProtocol.UNKNOWN, OpenCodeServerProtocol.classifyWireProtocolForTest(timeout, null, null))
        assertEquals(OpenCodeWireProtocol.UNKNOWN, OpenCodeServerProtocol.classifyWireProtocolForTest(html, OpenCodeProtocolResult.Success("""{"pid":1}"""), notFound))
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
    fun checkServerRespondingAcceptsCliStatusWhenHealthIsMissing() {
        withCliDualStackHttpServer { url ->
            assertTrue(
                OpenCodeServerProtocol.checkServerResponding(
                    url,
                    connectTimeoutMillis = 1000,
                    readTimeoutMillis = 1000,
                ),
            )
        }
    }

    @Test
    fun checkServerRespondingAcceptsCliInfoWhenHealthAndStatusAreMissing() {
        withCliDualStackHttpServer(
            identityPath = OpenCodeServerProtocol.INFO_PATH,
            identityBody = wireFixture("v2_cli/api-info.json"),
        ) { url ->
            assertTrue(
                OpenCodeServerProtocol.checkServerResponding(
                    url,
                    connectTimeoutMillis = 1000,
                    readTimeoutMillis = 1000,
                ),
            )
        }
    }

    @Test
    fun fetchServerVersionReadsCliStatusWhenGlobalHealthIsHtml() {
        withCliDualStackHttpServer { url ->
            assertEquals("2.0.5", OpenCodeServerProtocol.fetchServerVersion(url, null))
        }
    }

    @Test
    fun fetchServerVersionReadsCliInfoWhenStatusIsMissing() {
        withCliDualStackHttpServer(
            identityPath = OpenCodeServerProtocol.INFO_PATH,
            identityBody = wireFixture("v2_cli/api-info.json"),
        ) { url ->
            assertEquals("2.0.8", OpenCodeServerProtocol.fetchServerVersion(url, null))
        }
    }

    @Test
    fun detectWireProtocolClassifiesCliTwoFromLiveStatusJson() {
        withCliDualStackHttpServer { url ->
            assertEquals(
                OpenCodeWireProtocol.V2_CLI,
                OpenCodeServerProtocol.detectWireProtocol(url, null),
            )
        }
    }

    @Test
    fun detectWireProtocolClassifiesCliTwoFromLiveInfoJson() {
        withCliDualStackHttpServer(
            identityPath = OpenCodeServerProtocol.INFO_PATH,
            identityBody = wireFixture("v2_cli/api-info.json"),
        ) { url ->
            assertEquals(
                OpenCodeWireProtocol.V2_CLI,
                OpenCodeServerProtocol.detectWireProtocol(url, null),
            )
        }
    }

    @Test
    fun detectWireProtocolStaysUnknownWhenGlobalHealthIsHtmlAndStatusTimesOut() {
        val html = "<!doctype html><html><title>OpenCode</title></html>"
        withSingleRequestHttpServer(
            body = html,
            expectedRequestLine = "GET ${OpenCodeServerProtocol.GLOBAL_HEALTH_PATH} HTTP/1.1",
        ) { url ->
            assertEquals(
                OpenCodeWireProtocol.UNKNOWN,
                OpenCodeServerProtocol.detectWireProtocol(url, null, 200, 200),
            )
        }
    }

    @Test
    fun classifyWireProtocolMatchesCapturedFixtures() {
        val v1 = OpenCodeProtocolResult.Success(wireFixture("v1_18/global-health.json"))
        val cli = OpenCodeProtocolResult.Success(wireFixture("v2_cli/api-status.json"))
        val html = OpenCodeProtocolResult.Success("<!doctype html><html lang=\"en\"></html>")
        val notFound = OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.HTTP, 404)
        assertEquals(OpenCodeWireProtocol.V1_18, OpenCodeServerProtocol.classifyWireProtocolForTest(v1, null, null))
        assertEquals(OpenCodeWireProtocol.V2_CLI, OpenCodeServerProtocol.classifyWireProtocolForTest(html, cli, notFound))
        val info = OpenCodeProtocolResult.Success(wireFixture("v2_cli/api-info.json"))
        assertEquals(OpenCodeWireProtocol.V2_CLI, OpenCodeServerProtocol.classifyWireProtocolForTest(html, info, notFound))
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
    fun disposeServerSkipsCliTwoWithoutHttp() {
        assertTrue(
            OpenCodeServerProtocol.disposeServer(
                "http://127.0.0.1:1",
                basicAuthHeader = "Basic test-token",
                wireProtocol = OpenCodeWireProtocol.V2_CLI,
            ),
        )
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

    private fun wireFixture(name: String): String {
        return javaClass.getResource("/de/moritzf/opencodewebpanel/server/wire/$name")!!.readText()
    }

    private fun withCliDualStackHttpServer(
        identityPath: String = OpenCodeServerProtocol.STATUS_PATH,
        identityBody: String = """{"version":"2.0.5","pid":47000,"urls":["http://127.0.0.1:18732"]}""",
        block: (String) -> Unit,
    ) {
        val html = "<!doctype html><html lang=\"en\"><title>OpenCode</title></html>"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/global/health") { exchange ->
            val bytes = html.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/health") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.responseBody.close()
        }
        server.createContext(identityPath) { exchange ->
            val bytes = identityBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
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

    private data class CapturedHttpRequest(
        val method: String,
        val target: String,
        val body: String,
    )

    private fun <T> withCapturedHttpRequest(
        status: Int = 200,
        responseBody: String = "",
        block: (baseUrl: String) -> T,
    ): Pair<T, CapturedHttpRequest> {
        val captured = java.util.concurrent.CompletableFuture<CapturedHttpRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val query = exchange.requestURI.rawQuery
            val target = exchange.requestURI.rawPath + if (query.isNullOrEmpty()) "" else "?$query"
            captured.complete(CapturedHttpRequest(exchange.requestMethod, target, body))
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val result = block("http://127.0.0.1:${server.address.port}")
            return result to captured.get(5, TimeUnit.SECONDS)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sendContinuePromptSendsResumeTrueBody() {
        val (accepted, request) = withCapturedHttpRequest { base ->
            OpenCodeServerProtocol.sendContinuePrompt(
                base,
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "ses_abc123",
            )
        }
        assertTrue(accepted)
        assertTrue(request.body.contains("\"resume\":true"))
        assertTrue(request.body.contains("\"text\":\"Continue\""))
        assertTrue(request.body.contains("\"prompt\""))
    }

    @Test
    fun sendContinuePromptOnCliOmitsPromptWrapper() {
        val (accepted, request) = withCapturedHttpRequest { base ->
            OpenCodeServerProtocol.sendContinuePrompt(
                base,
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "ses_abc123",
                wireProtocol = OpenCodeWireProtocol.V2_CLI,
            )
        }
        assertTrue(accepted)
        assertEquals("""{"text":"Continue","resume":true}""", request.body)
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
        val (accepted, request) = withCapturedHttpRequest { base ->
            OpenCodeServerProtocol.replyToPermission(
                base,
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/tmp/project",
                "ses_abc123",
                "per_abc123",
                OpenCodeServerProtocol.PermissionResponse.ONCE,
            )
        }
        assertTrue(accepted)
        // Non-deprecated successor: POST /permission/{requestID}/reply, not the deprecated
        // POST /session/{id}/permissions/{id} form.
        assertEquals("POST", request.method)
        assertTrue(request.target.startsWith("/permission/per_abc123/reply?directory="))
        assertFalse(request.target.contains("/permissions/"))
        assertEquals("{\"reply\":\"once\"}", request.body)
    }

    @Test
    fun replyToPermissionOnCliTwoPostsDecision() {
        val (accepted, request) = withCapturedHttpRequest(status = 204) { base ->
            OpenCodeServerProtocol.replyToPermission(
                base,
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/tmp/project",
                "ses_abc123",
                "per_abc123",
                OpenCodeServerProtocol.PermissionResponse.ONCE,
                wireProtocol = OpenCodeWireProtocol.V2_CLI,
            )
        }
        assertTrue(accepted)
        assertEquals("POST", request.method)
        assertEquals("/api/session/ses_abc123/permission/per_abc123/reply", request.target)
        assertEquals("{\"decision\":\"once\"}", request.body)
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
    fun parseSessionDiffUnwrapsCliDataEnvelope() {
        val json = """{"data":[{"file":"src/Foo.kt","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1,"status":"modified"}]}"""
        val diffs = OpenCodeServerProtocol.parseSessionDiff(json)
        assertEquals(1, diffs.size)
        assertEquals("src/Foo.kt", diffs[0].file)
        assertTrue(OpenCodeServerProtocol.parseSessionDiff(wireFixture("v2_cli/session-diff-empty.json")).isEmpty())
        val live = OpenCodeServerProtocol.parseSessionDiff(wireFixture("v2_cli/session-diff-added.json"))
        assertEquals(1, live.size)
        assertEquals(
            "src/test/resources/de/moritzf/opencodewebpanel/server/wire/v2_cli/cli2-diff-probe.txt",
            live[0].file,
        )
        assertEquals("added", live[0].status)
        assertEquals(1L, live[0].additions)
        assertTrue(live[0].patch!!.contains("CLI2_DIFF_PROBE_EDITED"))
        val vcs = OpenCodeServerProtocol.parseSessionDiff(wireFixture("v2_cli/vcs-diff-working.json"))
        assertEquals("fragility.md", vcs.single().file)
        assertEquals("added", vcs.single().status)
    }

    @Test
    fun fetchVcsDiffResultRequestsWorkingModeOnCli() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/api/vcs/diff") { exchange ->
            seen.add(exchange.requestURI.rawQuery.orEmpty())
            val body = wireFixture("v2_cli/vcs-diff-working.json").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val result = OpenCodeServerProtocol.fetchVcsDiffResult(
                "http://127.0.0.1:${server.address.port}",
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/proj",
                "working",
                wireProtocol = OpenCodeWireProtocol.V2_CLI,
            )
            assertTrue(result is OpenCodeProtocolResult.Success)
            assertEquals("fragility.md", (result as OpenCodeProtocolResult.Success).value.single().file)
            assertTrue(seen.single().contains("mode=working"))
            assertTrue(seen.single().contains("directory="))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchVcsDiffResultSkippedOnV1() {
        val result = OpenCodeServerProtocol.fetchVcsDiffResult(
            "http://127.0.0.1:1",
            OpenCodeServerProtocol.buildBasicAuthHeader("test"),
            "/proj",
            "working",
            wireProtocol = OpenCodeWireProtocol.V1_18,
        )
        assertEquals(
            OpenCodeProtocolResult.Failure.Kind.INVALID_BODY,
            (result as OpenCodeProtocolResult.Failure).kind,
        )
    }

    @Test
    fun fetchVcsDiffResultRejectsUnknownMode() {
        val result = OpenCodeServerProtocol.fetchVcsDiffResult(
            "http://127.0.0.1:1",
            OpenCodeServerProtocol.buildBasicAuthHeader("test"),
            "/proj",
            "committed",
            wireProtocol = OpenCodeWireProtocol.V2_CLI,
        )
        assertEquals(
            OpenCodeProtocolResult.Failure.Kind.INVALID_IDENTIFIER,
            (result as OpenCodeProtocolResult.Failure).kind,
        )
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
    fun parseToolPartChangeReadsCliEditFilesField() {
        val change = OpenCodeServerProtocol.parseToolPartChange(wireFixture("v2_cli/tool-part-edit.json"))
        assertEquals(1, change.diffs.size)
        assertEquals(
            "src/test/resources/de/moritzf/opencodewebpanel/server/wire/v2_cli/cli2-diff-probe.txt",
            change.diffs[0].file,
        )
        assertEquals("modified", change.diffs[0].status)
        assertEquals(1L, change.diffs[0].additions)
        assertEquals(1L, change.diffs[0].deletions)
        assertTrue(change.diffs[0].patch!!.contains("-CLI2_DIFF_PROBE"))
        assertNull(change.fileHint)
    }

    @Test
    fun parseToolPartChangeCliWriteYieldsPathHint() {
        val change = OpenCodeServerProtocol.parseToolPartChange(wireFixture("v2_cli/tool-part-write.json"))
        assertTrue(change.diffs.isEmpty())
        assertTrue(change.fileHint!!.endsWith("cli2-diff-probe.txt"))
    }

    @Test
    fun findToolPartInMessagesFindsCliCallIdInContent() {
        val json = """
            [{"id":"msg_asst","type":"assistant","content":[
              {"id":"call-abc-0","type":"tool","name":"edit","state":{"status":"completed",
               "metadata":{"files":[{"file":"src/A.kt","patch":"p","additions":1,"deletions":0,"status":"modified"}]}}}
            ]}]
        """.trimIndent()
        val found = OpenCodeServerProtocol.findToolPartInMessages(json, "call-abc-0")
        assertNotNull(found)
        assertTrue(found!!.contains("call-abc-0"))
        assertNull(OpenCodeServerProtocol.findToolPartInMessages(json, "prt_edit"))
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
        val (diffs, request) = withCapturedHttpRequest(responseBody = body) { base ->
            OpenCodeServerProtocol.fetchSessionDiff(
                base,
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/tmp/project",
                "ses_abc123",
            )
        }
        assertEquals("GET", request.method)
        assertTrue(request.target.startsWith("/session/ses_abc123/diff?directory="))
        assertFalse(request.target.contains("messageID"))
        assertEquals(1, diffs.size)
        assertEquals("src/Foo.kt", diffs[0].file)
    }

    @Test
    fun fetchSessionDiffAppendsMessageIdParam() {
        val (_, request) = withCapturedHttpRequest(responseBody = "[]") { base ->
            OpenCodeServerProtocol.fetchSessionDiff(
                base,
                OpenCodeServerProtocol.buildBasicAuthHeader("test"),
                "/tmp/project",
                "ses_abc123",
                "msg_xyz",
            )
        }
        assertTrue(request.target.contains("/session/ses_abc123/diff?directory="))
        assertTrue(request.target.contains("&messageID=msg_xyz"))
    }

}
