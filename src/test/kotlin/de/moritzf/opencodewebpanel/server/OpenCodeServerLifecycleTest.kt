package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeServerLifecycleTest {

    @Test
    fun retryIsVisibleForFailedAndStoppedServers() {
        assertTrue(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.FAILED))
        assertTrue(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.STOPPED))
        assertFalse(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.STARTING))
        assertFalse(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.RESTARTING))
        assertFalse(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.RUNNING))
    }

    @Test
    fun retryLabelDistinguishesStartFromRetry() {
        assertEquals("Start", openCodeServerRetryLabel(OpenCodeServerLifecycleState.STOPPED))
        assertEquals("Retry", openCodeServerRetryLabel(OpenCodeServerLifecycleState.FAILED))
        assertEquals("Retry", openCodeServerRetryLabel(OpenCodeServerLifecycleState.RUNNING))
    }

    @Test
    fun reloadActionIsDisabledOnlyWhenServerIsStopped() {
        assertTrue(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.STARTING))
        assertTrue(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.RUNNING))
        assertTrue(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.FAILED))
        assertTrue(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.RESTARTING))
        assertFalse(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.STOPPED))
    }

    @Test
    fun stopActionIsEnabledForRunningOrStartingServer() {
        assertTrue(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.STARTING))
        assertTrue(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.RUNNING))
        assertTrue(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.RESTARTING))
        assertFalse(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.FAILED))
        assertFalse(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.STOPPED))
    }

    @Test
    fun staleLifecycleEventsAreIgnored() {
        assertTrue(
            shouldApplyPublishedLifecycleState(
                OpenCodeServerLifecycleState.STOPPED,
                OpenCodeServerLifecycleState.STOPPED,
            ),
        )
        assertFalse(
            shouldApplyPublishedLifecycleState(
                OpenCodeServerLifecycleState.STOPPED,
                OpenCodeServerLifecycleState.RUNNING,
            ),
        )
        assertFalse(
            shouldApplyPublishedLifecycleState(
                OpenCodeServerLifecycleState.RUNNING,
                OpenCodeServerLifecycleState.STOPPED,
            ),
        )
    }

    @Test
    fun stopAndRestartHideTheEmbeddedPageWithoutACefNavigation() {
        assertTrue(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.STOPPED))
        assertTrue(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.RESTARTING))
        assertFalse(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.FAILED))
        assertFalse(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.RUNNING))
        assertFalse(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.STARTING))
    }

    @Test
    fun newPanelCreatedDuringRestartParksOnTheIdleCard() {
        assertEquals("idle", parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.RESTARTING))
        assertEquals("idle", parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.STOPPED))
        assertEquals("error", parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.FAILED))
        assertNull(parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.RUNNING))
        assertNull(parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.STARTING))
    }

    @Test
    fun stripStaysVisibleWhileThePageIsOpening() {
        assertTrue(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.RUNNING, pageOpening = true))
        assertFalse(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.RUNNING, pageOpening = false))
        assertTrue(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.STARTING, pageOpening = true))
        assertFalse(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.STOPPED))
        assertTrue(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.RESTARTING))
        assertTrue(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.FAILED))
    }

    @Test
    fun pageOpeningStripStaysHiddenAfterThePageHasPainted() {
        assertTrue(shouldShowPageOpeningStatus(pageLoadInProgress = true, openCodePagePainted = false))
        assertFalse(shouldShowPageOpeningStatus(pageLoadInProgress = true, openCodePagePainted = true))
        assertFalse(shouldShowPageOpeningStatus(pageLoadInProgress = false, openCodePagePainted = false))
        assertFalse(shouldShowPageOpeningStatus(pageLoadInProgress = false, openCodePagePainted = true))
    }

    @Test
    fun pageOpeningStatusUsesTheSameDotStyle() {
        val html = formatOpenCodePageOpeningStatusText()
        assertTrue(html.contains("&#9679;"))
        assertTrue(html.contains("#FFC107"))
        assertTrue(html.contains("Opening the OpenCode page"))
    }

    @Test
    fun startupErrorCardIsShownForFailedStarts() {
        assertTrue(shouldShowStartupError(OpenCodeServerLifecycleState.FAILED))
        assertFalse(shouldShowStartupError(OpenCodeServerLifecycleState.STOPPED))
        assertFalse(shouldShowStartupError(OpenCodeServerLifecycleState.RUNNING))
        assertFalse(shouldShowStartupError(OpenCodeServerLifecycleState.STARTING))
        assertFalse(shouldShowStartupError(OpenCodeServerLifecycleState.RESTARTING))
    }

    @Test
    fun cancelledStripIsNotUnhealthy() {
        val html = formatOpenCodeLifecycleStrip(
            OpenCodeLifecycleStripModel(OpenCodeServerLifecycleState.FAILED, cancelled = true),
        )
        assertTrue(html.contains("Cancelled"))
        assertFalse(html.contains("Failed"))
        assertTrue(isOpenCodeLifecycleStripVisible(OpenCodeLifecycleStripModel(OpenCodeServerLifecycleState.FAILED, cancelled = true)))
    }

    @Test
    fun startingStripIncludesStageElapsedAndRecovery() {
        val html = formatOpenCodeLifecycleStrip(
            OpenCodeLifecycleStripModel(
                OpenCodeServerLifecycleState.STARTING,
                stage = "Creating sandbox…",
                elapsedMillis = 65_000,
                recovery = OpenCodeRecoveryNotice("sandbox serve was not responding", 1_000),
            ),
            nowMillis = 4_000,
        )
        assertTrue(html.contains("Creating sandbox"))
        assertTrue(html.contains("1m 05s"))
        assertTrue(html.contains("Last recovery"))
        assertTrue(html.contains("sandbox serve was not responding"))
    }

    @Test
    fun recoveryKeepsTheStripVisibleOnARunningServer() {
        val notice = OpenCodeRecoveryNotice("stalled renderer heartbeat", 1)
        val model = OpenCodeLifecycleStripModel(OpenCodeServerLifecycleState.RUNNING, recovery = notice)
        assertTrue(isOpenCodeLifecycleStripVisible(model))
        assertTrue(shouldTickLifecycleStrip(model))
        assertTrue(formatOpenCodeLifecycleStrip(model, nowMillis = 1).contains("stalled renderer heartbeat"))
        assertEquals(
            notice,
            visibleRecoveryNotice(notice, OpenCodeServerLifecycleState.RUNNING, true, false, 1 + 5_000),
        )
        assertNull(
            visibleRecoveryNotice(
                notice,
                OpenCodeServerLifecycleState.RUNNING,
                pagePainted = true,
                pageLoadInProgress = false,
                nowMillis = 1 + RECOVERY_BANNER_MILLIS,
            ),
        )
        assertEquals(
            notice,
            visibleRecoveryNotice(notice, OpenCodeServerLifecycleState.RUNNING, true, true, 1 + RECOVERY_BANNER_MILLIS),
        )
    }

    @Test
    fun restartingStripShowsUpgradeStageAndElapsed() {
        val html = formatOpenCodeLifecycleStrip(
            OpenCodeLifecycleStripModel(
                OpenCodeServerLifecycleState.RESTARTING,
                stage = "Upgrading OpenCode…",
                elapsedMillis = 12_000,
            ),
        )
        assertTrue(html.contains("Restarting"))
        assertTrue(html.contains("Upgrading OpenCode"))
        assertTrue(html.contains("12s"))
        assertTrue(
            isOpenCodeLifecycleStripVisible(
                OpenCodeLifecycleStripModel(
                    OpenCodeServerLifecycleState.RESTARTING,
                    stage = "Downloading 50%",
                ),
            ),
        )
    }

    @Test
    fun elapsedFormatterUsesMinutes() {
        assertEquals("0s", formatElapsedMillis(0))
        assertEquals("12s", formatElapsedMillis(12_400))
        assertEquals("1m 05s", formatElapsedMillis(65_000))
    }

    @Test
    fun documentLoadTreatsCefStatusZeroAsSuccess() {
        assertTrue(isSuccessfulOpenCodeDocumentLoad(0))
        assertTrue(isSuccessfulOpenCodeDocumentLoad(200))
        assertTrue(isSuccessfulOpenCodeDocumentLoad(304))
        assertFalse(isSuccessfulOpenCodeDocumentLoad(401))
        assertFalse(isSuccessfulOpenCodeDocumentLoad(404))
        assertFalse(isSuccessfulOpenCodeDocumentLoad(500))
    }
}
