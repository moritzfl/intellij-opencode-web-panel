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

    /**
     * A spent recreate budget decays: after this silence without any heartbeat it is halved, so
     * a later Retry from the failure card gets a real recovery attempt again instead of giving up
     * instantly on the first stall. User Restart / Retry also zero it immediately.
     */
    const val RECREATE_BUDGET_HALVING_MILLIS = 60L * 60L * 1000L

    /**
     * How long a not-yet-ready first load (the “Opening the OpenCode page…” strip) may freeze
     * the stall clock. Each page-load attempt can spend a document-start install wait plus the
     * load timeout, times the full retry budget, so the two watchdogs do not fight. After this
     * a hung opening is a stall and the renderer watchdog may recover it.
     */
    const val NOT_READY_GRACE_MILLIS =
        (OpenCodePageLoadWatchdog.DEFAULT_TIMEOUT_MILLIS +
            OpenCodePageLoadWatchdog.DOCUMENT_START_INSTALL_TIMEOUT_MILLIS) *
            (OpenCodePageLoadWatchdog.MAX_RETRIES + 1L)

    enum class Action { NONE, RELOAD, RECREATE, GIVE_UP }

    fun effectiveStallTimeout(agentBusy: Boolean): Long {
        return if (agentBusy) BUSY_STALL_TIMEOUT_MILLIS else STALL_TIMEOUT_MILLIS
    }

    /**
     * The effective process-wide recreate budget: halved once per [RECREATE_BUDGET_HALVING_MILLIS]
     * of heartbeat-free silence. [elapsedSinceLastDecay] is clamped at zero by the caller.
     */
    fun decayedRecreateBudget(current: Int, elapsedSinceLastDecayMillis: Long): Int {
        if (current <= 0) return 0
        if (elapsedSinceLastDecayMillis < RECREATE_BUDGET_HALVING_MILLIS) return current
        return current / 2
    }

    /** Hidden pages throttle rAF to a standstill; treating them as stalled recovers them spuriously. */
    fun shouldMonitor(panelShowing: Boolean, pageVisible: Boolean): Boolean {
        return panelShowing && pageVisible
    }

    /**
     * Heartbeats are only expected after a document has painted, succeeded, or the page-load
     * watchdog has given up. An in-flight first load is not ready — but a failed load is, so
     * the renderer watchdog can still recreate a panel stuck on “Opening…”.
     */
    fun isPageReadyForRendererWatchdog(
        pageLoadInProgress: Boolean,
        pagePainted: Boolean,
        loadSucceeded: Boolean,
        loadGaveUp: Boolean,
    ): Boolean {
        if (pageLoadInProgress) return false
        return pagePainted || loadSucceeded || loadGaveUp
    }

    /**
     * Chooses the recovery action for a stalled page.
     *
     * [consecutiveStalls] counts soft-reload attempts on this panel that did not restore the
     * heartbeat; [recreatesAfterStall] is the process-wide count of panel recreations that also
     * failed (a successful recreate still builds a new watchdog). Give-up is checked before
     * reload so a new panel cannot spend another reload cycle after the budget is already spent.
     */
    fun stalledAction(
        consecutiveStalls: Int,
        recreatesAfterStall: Int,
        nowMillis: Long,
        lastRecoveryAtMillis: Long,
        cooldownMillis: Long = RECOVERY_COOLDOWN_MILLIS,
    ): Action {
        if (nowMillis - lastRecoveryAtMillis < cooldownMillis) return Action.NONE
        if (recreatesAfterStall >= MAX_STALLED_RECREATES) return Action.GIVE_UP
        if (consecutiveStalls <= 1) return Action.RELOAD
        return Action.RECREATE
    }
}