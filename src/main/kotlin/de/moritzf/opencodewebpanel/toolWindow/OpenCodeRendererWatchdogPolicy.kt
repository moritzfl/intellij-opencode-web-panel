package de.moritzf.opencodewebpanel.toolWindow

/**
 * Pure policy for the renderer watchdog: when a live OpenCode page has gone silent long enough
 * to be considered stuck, and which recovery step to take.
 *
 * A renderer can die silently — on macOS out-of-process JCEF the remote browser stays attached
 * but never fires load/JS callbacks again (no exception, no error event, see JBR-10090). The
 * 20s page-load watchdog cannot help a *running* page in that state, so this policy drives a
 * heartbeat-based escalation: first a soft in-place reload, then (recreateBudgeted) a full panel
 * recreation.
 *
 * Kept separate from [OpenCodeRendererWatchdog] so the decision logic is unit-testable without
 * Swing/JCEF.
 */
internal object OpenCodeRendererWatchdogPolicy {
    /** Three missed beats at the 5s page cadence. */
    const val STALL_TIMEOUT_MILLIS = 15_000L

    /** A busy session can legitimately starve the event loop / IPC for a long time. */
    const val BUSY_STALL_TIMEOUT_MILLIS = 180_000L

    /** Health tick rate; also the recovery cooldown granularity. */
    const val CHECK_INTERVAL_MILLIS = 5_000L
    const val RECOVERY_COOLDOWN_MILLIS = 60_000L

    /** Number of stalled recreates before the watchdog gives up and leaves the failure card. */
    const val MAX_STALLED_RECREATES = 2

    enum class Action { NONE, RELOAD, RECREATE, GIVE_UP }

    fun effectiveStallTimeout(agentBusy: Boolean): Long {
        return if (agentBusy) BUSY_STALL_TIMEOUT_MILLIS else STALL_TIMEOUT_MILLIS
    }

    /** Hidden pages throttle rAF to a standstill; treating them as stalled recovers them spuriously. */
    fun shouldMonitor(panelShowing: Boolean, pageVisible: Boolean): Boolean {
        return panelShowing && pageVisible
    }

    /**
     * Chooses the recovery action for a stalled page.
     *
     * [consecutiveStalls] counts soft-reload attempts that did not restore the heartbeat;
     * [recreatesAfterStall] counts panel recreations that also failed. The first stall reloads
     * in place (keeps the page state); a persistent stall escalates to a recreate, and once the
     * recreate budget is spent the watchdog gives up — the failure card's Retry is then the only
     * way back, so a broken JCEF stack cannot loop reload/recreate forever.
     */
    fun stalledAction(
        consecutiveStalls: Int,
        recreatesAfterStall: Int,
        nowMillis: Long,
        lastRecoveryAtMillis: Long,
        cooldownMillis: Long = RECOVERY_COOLDOWN_MILLIS,
    ): Action {
        if (nowMillis - lastRecoveryAtMillis < cooldownMillis) return Action.NONE
        if (consecutiveStalls <= 1) return Action.RELOAD
        if (recreatesAfterStall >= MAX_STALLED_RECREATES) return Action.GIVE_UP
        return Action.RECREATE
    }
}