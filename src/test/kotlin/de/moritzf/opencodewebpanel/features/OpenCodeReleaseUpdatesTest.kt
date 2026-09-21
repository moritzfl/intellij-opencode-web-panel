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
        assertEquals("1.18.31", OpenCodeReleaseUpdates.parseNpmLatestVersion("""{"version":"1.18.31"}"""))
        assertEquals("2.0.8", OpenCodeReleaseUpdates.parseNpmLatestVersion("""{"name":"@opencode/cli","version":"2.0.8"}"""))
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
        val notice = OpenCodeReleaseUpdates.evaluate(
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
        val notice = OpenCodeReleaseUpdates.evaluate(
            installed = "1.18.25",
            fetch = { """{"version":"1.18.31"}""" },
        )
        assertEquals(OpenCodeReleaseUpdates.Notice("1.18.25", "1.18.31"), notice)
    }

    @Test
    fun evaluateReturnsNullWhenFetchFails() {
        assertNull(
            OpenCodeReleaseUpdates.evaluate("2.0.5", fetch = { null }),
        )
    }

    @Test
    fun tooltipAndHostUpgradeCommand() {
        val notice = OpenCodeReleaseUpdates.Notice("2.0.8", "2.0.9")
        assertEquals(
            "OpenCode 2.0.9 is available. Consider upgrading. Installed: 2.0.8. Click for Host CLI upgrade steps.",
            OpenCodeReleaseUpdates.tooltip(notice),
        )
        assertEquals(
            "OpenCode 2.0.9 is available. Consider upgrading. Installed: 2.0.8. Click to upgrade this sandbox.",
            OpenCodeReleaseUpdates.tooltip(notice, sandbox = true),
        )
        assertEquals("opencode upgrade", OpenCodeReleaseUpdates.HOST_UPGRADE_COMMAND)
        assertTrue(OpenCodeReleaseUpdates.hostUpgradeIntro(notice).contains("2.0.9"))
    }
}
