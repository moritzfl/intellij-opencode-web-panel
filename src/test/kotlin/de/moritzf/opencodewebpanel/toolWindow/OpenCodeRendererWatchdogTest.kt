package de.moritzf.opencodewebpanel.toolWindow

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeRendererWatchdogTest {
    private var clock = 100_000L
    private var showing = true
    private var pageReady = true
    private var enabled = true
    private val reloads = mutableListOf<String>()
    private val recreates = mutableListOf<String>()
    private val giveUps = mutableListOf<String>()

    @After
    fun tearDown() {
        OpenCodeRendererWatchdog.resetProcessRecreatesAfterStallForTests()
    }

    @Test
    fun hidingThenShowingDoesNotTreatTheGapAsAStall() {
        val watchdog = watchdog()
        watchdog.start()
        showing = false
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 5_000
        watchdog.checkHealth()
        showing = true
        clock += 1_000
        watchdog.checkHealth()
        assertTrue(reloads.isEmpty())
        assertTrue(recreates.isEmpty())
    }

    @Test
    fun aPageThatHasNotFinishedLoadingIsNotRecovered() {
        pageReady = false
        val watchdog = watchdog()
        watchdog.start()
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 5_000
        watchdog.checkHealth()
        assertTrue(reloads.isEmpty())
    }

    @Test
    fun theFirstStallReloadsAndAPersistentStallRecreates() {
        val watchdog = watchdog()
        watchdog.start()
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        watchdog.checkHealth()
        assertEquals(listOf("reload"), reloads)
        assertTrue(recreates.isEmpty())

        clock += OpenCodeRendererWatchdogPolicy.RECOVERY_COOLDOWN_MILLIS
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        watchdog.checkHealth()
        assertEquals(listOf("recreate"), recreates)
    }

    @Test
    fun aHeartbeatClearsTheProcessWideRecreateBudget() {
        val first = watchdog()
        first.start()
        stallUntilRecreate(first)
        stallUntilRecreate(first)
        val second = watchdog()
        second.handleHeartbeat("visible")
        second.start()
        reloads.clear()
        recreates.clear()
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        second.checkHealth()
        assertEquals(listOf("reload"), reloads)
        assertTrue(giveUps.isEmpty())
    }

    @Test
    fun spendingTheRecreateBudgetGivesUpEvenOnANewWatchdog() {
        val first = watchdog()
        first.start()
        stallUntilRecreate(first)
        stallUntilRecreate(first)

        reloads.clear()
        recreates.clear()
        val second = watchdog()
        second.start()
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        second.checkHealth()
        assertEquals(listOf("give-up"), giveUps)
        assertTrue(reloads.isEmpty())
        assertTrue(recreates.isEmpty())
    }

    @Test
    fun aDocumentLoadDoesNotResetTheRecreateBudget() {
        val watchdog = watchdog()
        watchdog.start()
        stallUntilRecreate(watchdog)
        stallUntilRecreate(watchdog)
        watchdog.noteDocumentLoadStarted()
        clock += OpenCodeRendererWatchdogPolicy.RECOVERY_COOLDOWN_MILLIS
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        watchdog.checkHealth()
        assertEquals(listOf("give-up"), giveUps)
    }

    @Test
    fun aHungOpeningPastTheGracePeriodIsRecovered() {
        pageReady = false
        val watchdog = watchdog()
        watchdog.start()
        watchdog.checkHealth()
        clock += OpenCodeRendererWatchdogPolicy.NOT_READY_GRACE_MILLIS
        watchdog.checkHealth()
        assertEquals(listOf("reload"), reloads)
    }

    @Test
    fun aUserRestartResetLetsANewPanelReloadInsteadOfGivingUp() {
        val first = watchdog()
        first.start()
        stallUntilRecreate(first)
        stallUntilRecreate(first)

        OpenCodeRendererWatchdog.resetProcessRecreatesAfterStall()
        reloads.clear()
        recreates.clear()
        giveUps.clear()
        val second = watchdog()
        second.start()
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        second.checkHealth()
        assertEquals(listOf("reload"), reloads)
        assertTrue(giveUps.isEmpty())
    }

    @Test
    fun theGiveUpBudgetDecaysSoALaterRetryRecovers() {
        val first = watchdog()
        first.start()
        stallUntilRecreate(first)
        stallUntilRecreate(first)

        // An hour of heartbeat-free silence halves the spent budget; the next panel then gets a
        // real recovery cycle again instead of giving up on its first stall.
        reloads.clear()
        recreates.clear()
        clock += OpenCodeRendererWatchdogPolicy.RECREATE_BUDGET_HALVING_MILLIS
        val second = watchdog()
        second.start()
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        second.checkHealth()
        assertEquals(listOf("reload"), reloads)
        assertTrue(giveUps.isEmpty())
    }

    private fun stallUntilRecreate(watchdog: OpenCodeRendererWatchdog) {
        clock += OpenCodeRendererWatchdogPolicy.RECOVERY_COOLDOWN_MILLIS
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        watchdog.checkHealth()
        clock += OpenCodeRendererWatchdogPolicy.RECOVERY_COOLDOWN_MILLIS
        clock += OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS + 1
        watchdog.checkHealth()
    }

    private fun watchdog(): OpenCodeRendererWatchdog {
        return OpenCodeRendererWatchdog(
            parentDisposable = null,
            isActiveContent = { showing },
            isAgentBusy = { false },
            isEnabledInSettings = { enabled },
            isPageReady = { pageReady },
            onReloadPage = { reloads.add("reload") },
            onRecreatePanel = { recreates.add("recreate") },
            onGiveUp = { giveUps.add("give-up") },
            nowMillis = { clock },
            runOnEdt = { it() },
        )
    }
}
