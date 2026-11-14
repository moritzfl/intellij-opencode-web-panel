package de.moritzf.opencodewebpanel.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeReleaseUpdatesTest {

    @Test
    fun catalogUrlUsesOpencodeAiForV1AndScopedCliForV2() {
        assertEquals(OpenCodeReleaseUpdates.NPM_V1_LATEST_URL, OpenCodeReleaseUpdates.catalogUrl(1))
        assertEquals(OpenCodeReleaseUpdates.NPM_V2_LATEST_URL, OpenCodeReleaseUpdates.catalogUrl(2))
        assertEquals(OpenCodeReleaseUpdates.NPM_V2_LATEST_URL, OpenCodeReleaseUpdates.catalogUrl(3))
        assertNull(OpenCodeReleaseUpdates.catalogUrl(0))
    }

    @Test
    fun parseNpmLatestVersionReadsVersionString() {
        assertEquals(
            "1.18.31",
            OpenCodeReleaseUpdates.parseNpmLatestVersion("""{"version":"1.18.31"}"""),
        )
        assertEquals(
            "2.0.8",
            OpenCodeReleaseUpdates.parseNpmLatestVersion(
                """{"name":"@opencode/cli","version":"2.0.8"}""",
            ),
        )
        assertNull(OpenCodeReleaseUpdates.parseNpmLatestVersion("""{"name":"x"}"""))
        assertNull(OpenCodeReleaseUpdates.parseNpmLatestVersion("not json"))
        assertNull(OpenCodeReleaseUpdates.parseNpmLatestVersion("""{"version":1}"""))
    }

    @Test
    fun noticeRequiresNewerSameMajor() {
        assertEquals(
            OpenCodeReleaseUpdates.Notice("1.18.25", "1.18.31"),
            OpenCodeReleaseUpdates.notice("1.18.25", "1.18.31"),
        )
        assertEquals(
            OpenCodeReleaseUpdates.Notice("2.0.5", "2.0.8"),
            OpenCodeReleaseUpdates.notice("v2.0.5", "2.0.8"),
        )
        assertNull(OpenCodeReleaseUpdates.notice("1.18.31", "1.18.31"))
        assertNull(OpenCodeReleaseUpdates.notice("1.18.31", "1.18.25"))
        assertNull(OpenCodeReleaseUpdates.notice("1.18.31", "2.0.8"))
        assertNull(OpenCodeReleaseUpdates.notice("2.0.5", "1.18.31"))
        assertNull(OpenCodeReleaseUpdates.notice("development", "1.18.31"))
        assertNull(OpenCodeReleaseUpdates.notice("1.18.25", null))
    }

    @Test
    fun evaluateFetchesCatalogForInstalledMajor() {
        val fetched = mutableListOf<String>()
        val notice =
            OpenCodeReleaseUpdates.evaluate(
                installed = "2.0.5",
                fetch = { url ->
                    fetched.add(url)
                    """{"version":"2.0.8"}"""
                },
            )
        assertEquals(listOf(OpenCodeReleaseUpdates.NPM_V2_LATEST_URL), fetched)
        assertEquals(OpenCodeReleaseUpdates.Notice("2.0.5", "2.0.8"), notice)
    }

    @Test
    fun evaluateUsesV1CatalogFor1x() {
        val notice =
            OpenCodeReleaseUpdates.evaluate(
                installed = "1.18.25",
                fetch = { """{"version":"1.18.31"}""" },
            )
        assertEquals(OpenCodeReleaseUpdates.Notice("1.18.25", "1.18.31"), notice)
    }

    @Test
    fun evaluateReturnsNullWhenFetchFails() {
        assertNull(OpenCodeReleaseUpdates.evaluate("2.0.5", fetch = { null }))
        assertNull(OpenCodeReleaseUpdates.evaluate("2.0.5", fetch = { null }, dockerImage = true))
    }

    // Shape of hub.docker.com/v2/repositories/sbx/opencode/tags on 2026-11-14 (images trimmed).
    private val dockerTags =
        """
        {"count":5,"next":null,"results":[
          {"name":"latest","digest":"sha256:12a8","images":[]},
          {"name":"2.3.0","digest":"sha256:12a8","images":[]},
          {"name":"2.2.7","digest":"sha256:32c3","images":[]},
          {"name":"2.0.29","digest":"sha256:4d44","images":[]},
          {"name":"1.18.31","digest":"sha256:ffe1","images":[]}
        ]}
        """

    @Test
    fun dockerImageVersionIsTheReleaseTagSharingLatestsDigest() {
        assertEquals("2.3.0", OpenCodeReleaseUpdates.parseDockerImageVersion(dockerTags, 2))
        assertNull(
            "latest is a 2.x image",
            OpenCodeReleaseUpdates.parseDockerImageVersion(dockerTags, 1),
        )
        assertNull(
            "No release tag for latest's digest yet",
            OpenCodeReleaseUpdates.parseDockerImageVersion(
                dockerTags.replace(
                    """"latest","digest":"sha256:12a8"""",
                    """"latest","digest":"sha256:9999"""",
                ),
                2,
            ),
        )
        assertNull(OpenCodeReleaseUpdates.parseDockerImageVersion("""{"results":[]}""", 2))
        assertNull(OpenCodeReleaseUpdates.parseDockerImageVersion("not json", 2))
    }

    @Test
    fun sandboxNoticeComparesNpmAndDockersImage() {
        val fetched = mutableListOf<String>()
        fun evaluate(installed: String, npm: String?) =
            OpenCodeReleaseUpdates.evaluate(
                installed,
                fetch = { url ->
                    fetched += url
                    if (url == OpenCodeReleaseUpdates.DOCKER_IMAGE_TAGS_URL) dockerTags
                    else npm?.let { """{"version":"$it"}""" }
                },
                dockerImage = true,
            )

        assertEquals(
            OpenCodeReleaseUpdates.Notice("2.2.7", "2.3.1", "2.3.0"),
            evaluate("2.2.7", "2.3.1"),
        )
        assertEquals(
            listOf(
                OpenCodeReleaseUpdates.NPM_V2_LATEST_URL,
                OpenCodeReleaseUpdates.DOCKER_IMAGE_TAGS_URL,
            ),
            fetched,
        )
        // Docker lags npm: only an in-VM upgrade gets 2.3.1.
        assertEquals(
            OpenCodeReleaseUpdates.Notice("2.3.0", "2.3.1", null),
            evaluate("2.3.0", "2.3.1"),
        )
        // npm unreachable: Docker's newer image alone still raises the notice.
        assertEquals(
            OpenCodeReleaseUpdates.Notice("2.2.7", "2.3.0", "2.3.0"),
            evaluate("2.2.7", null),
        )
        assertNull(evaluate("2.3.0", "2.3.0"))
        assertNull("An in-VM upgrade can be ahead of Docker's image", evaluate("2.3.1", "2.3.1"))
    }

    @Test
    fun tooltipAndHostUpgradeCommand() {
        val notice = OpenCodeReleaseUpdates.Notice("2.0.8", "2.0.9")
        assertEquals(
            "OpenCode 2.0.9 is available. Consider upgrading. Installed: 2.0.8. Click for Host CLI upgrade steps.",
            OpenCodeReleaseUpdates.tooltip(notice),
        )
        assertEquals("opencode upgrade", OpenCodeReleaseUpdates.HOST_UPGRADE_COMMAND)
        assertTrue(OpenCodeReleaseUpdates.hostUpgradeIntro(notice).contains("2.0.9"))
        assertTrue(
            OpenCodeReleaseUpdates.tooltip(notice, sandbox = true)
                .endsWith("Click to upgrade this sandbox."),
        )
        assertTrue(
            OpenCodeReleaseUpdates.tooltip(notice.copy(dockerImage = "2.0.9"), sandbox = true)
                .endsWith("Click to upgrade or reset this sandbox."),
        )
    }
}
