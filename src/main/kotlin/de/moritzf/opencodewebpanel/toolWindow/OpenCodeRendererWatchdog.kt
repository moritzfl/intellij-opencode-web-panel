package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Detects a silently dead embedded page and recovers it.
 *
 * On macOS out-of-process JCEF the renderer can die without any exception or load error: the
 * remote browser stays attached but stops delivering JS callbacks, so none of the load handlers
 * fire and the panel sits frozen until an IDE restart (JBR-10090). Every existing guard only
 * watches *loads*; this watchdog watches a *running* page instead.
 *
 * The page posts a heartbeat (and its `visibilityState`) through a JBCefJSQuery every few
 * seconds — a dead renderer never runs the timer and never answers. When the heartbeat goes
 * stale while the panel is showing, the page is visible, and a document has finished loading
 * (or the first load has given up), the watchdog first reloads the page in place and, after a
 * persistent stall, recreates the whole JCEF panel. Recreates are counted process-wide (JCEF
 * is shared): a watchdog-built new panel does not reset the budget. A heartbeat resets it;
 * user Restart / Retry zero it; without those it decays (halved per
 * [OpenCodeRendererWatchdogPolicy.RECREATE_BUDGET_HALVING_MILLIS] of silence), so a broken JCEF
 * stack ends on the failure card but a later Retry still gets a real recovery attempt.
 *
 * Hidden pages freeze the stall clock instead of skipping the check, so showing the panel
 * later does not look like a stall. A not-yet-loaded page freezes only for
 * [OpenCodeRendererWatchdogPolicy.NOT_READY_GRACE_MILLIS]; after that a hung “Opening…”
 * strip is a stall (Restart's new JCEF often needs this). Heartbeat budget mutations hop
 * to the Swing thread; the stall timestamps are volatile so any-thread beats still move
 * the clock. User Restart / Retry zero the process-wide recreate budget.
 */
internal class OpenCodeRendererWatchdog(
    parentDisposable: Disposable?,
    private val isActiveContent: () -> Boolean,
    /** True while an agent turn may legitimately starve the page's event loop (long IPC bursts). */
    private val isAgentBusy: () -> Boolean,
    private val isEnabledInSettings: () -> Boolean,
    /** False while a navigation is in flight or the OpenCode document has not painted yet. */
    private val isPageReady: () -> Boolean,
    private val onReloadPage: () -> Unit,
    private val onRecreatePanel: () -> Unit,
    private val onGiveUp: () -> Unit = {},
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val runOnEdt: (() -> Unit) -> Unit = { action ->
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(action) else action()
    },
) {
    private val alarm = parentDisposable?.let { Alarm(Alarm.ThreadToUse.SWING_THREAD, it) }
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastHeartbeatAtMillis = 0L

    @Volatile
    private var lastPageVisible = true
    private var consecutiveStalls = 0
    private var lastRecoveryAtMillis = 0L
    private var notReadySinceMillis = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastHeartbeatAtMillis = nowMillis()
        lastPageVisible = true
        consecutiveStalls = 0
        notReadySinceMillis = 0L
        scheduleNext()
    }

    fun stop() {
        running.set(false)
        alarm?.cancelAllRequests()
    }

    /**
     * A new document started loading. Move the stall clock so the in-flight navigation is not
     * itself recovered; keep the recreate budget (process-wide) and this panel's stall count.
     */
    fun noteDocumentLoadStarted() {
        lastHeartbeatAtMillis = nowMillis()
    }

    /** Called from the JBCefJSQuery callback; may run on any thread. */
    fun handleHeartbeat(visibility: String?) {
        lastHeartbeatAtMillis = nowMillis()
        if (visibility != null) lastPageVisible = visibility != "hidden"
        runOnEdt {
            consecutiveStalls = 0
            processRecreatesAfterStall.set(0)
        }
    }

    /** A reload started for an earlier stall; do not treat the navigation as a fresh first stall. */
    fun noteReloadedForStall() {
        val now = nowMillis()
        lastRecoveryAtMillis = now
        lastHeartbeatAtMillis = now
    }

    private fun scheduleNext() {
        val alarm = this.alarm ?: return
        if (!running.get()) return
        alarm.addRequest(
            {
                try {
                    checkHealth()
                } finally {
                    scheduleNext()
                }
            },
            OpenCodeRendererWatchdogPolicy.CHECK_INTERVAL_MILLIS.toInt(),
        )
    }

    internal fun checkHealth() {
        if (!running.get() || !isEnabledInSettings()) return
        val now = nowMillis()
        if (!OpenCodeRendererWatchdogPolicy.shouldMonitor(isActiveContent(), lastPageVisible)) {
            lastHeartbeatAtMillis = now
            notReadySinceMillis = 0L
            return
        }
        if (!isPageReady()) {
            if (notReadySinceMillis == 0L) notReadySinceMillis = now
            if (now - notReadySinceMillis < OpenCodeRendererWatchdogPolicy.NOT_READY_GRACE_MILLIS) {
                lastHeartbeatAtMillis = now
                return
            }
            lastHeartbeatAtMillis = notReadySinceMillis
        } else {
            notReadySinceMillis = 0L
        }

        val silenceMillis = now - lastHeartbeatAtMillis
        if (silenceMillis <= 0L) return
        val timeout = OpenCodeRendererWatchdogPolicy.effectiveStallTimeout(isAgentBusy())
        if (silenceMillis <= timeout) return

        val action = OpenCodeRendererWatchdogPolicy.stalledAction(
            consecutiveStalls = consecutiveStalls + 1,
            recreatesAfterStall = effectiveRecreateBudget(now),
            nowMillis = now,
            lastRecoveryAtMillis = lastRecoveryAtMillis,
        )
        if (action == OpenCodeRendererWatchdogPolicy.Action.NONE) return

        lastRecoveryAtMillis = now
        when (action) {
            OpenCodeRendererWatchdogPolicy.Action.NONE -> return
            OpenCodeRendererWatchdogPolicy.Action.RELOAD -> {
                consecutiveStalls++
                lastHeartbeatAtMillis = now
                LOG.warn("OpenCode page heartbeat stalled (${silenceMillis}ms); reloading the page")
                onReloadPage()
            }
            OpenCodeRendererWatchdogPolicy.Action.RECREATE -> {
                processRecreatesAfterStall.incrementAndGet()
                consecutiveStalls = 0
                lastHeartbeatAtMillis = now
                LOG.warn("OpenCode page heartbeat stayed stalled after reload; recreating the panel")
                onRecreatePanel()
            }
            OpenCodeRendererWatchdogPolicy.Action.GIVE_UP -> {
                LOG.warn("OpenCode page could not be revived; leaving recovery to the failure card")
                stop()
                onGiveUp()
            }
        }
    }

    init {
        parentDisposable?.let { parent ->
            Disposer.register(parent, Disposable { stop() })
        }
    }

    companion object {
        private val LOG = Logger.getInstance(OpenCodeRendererWatchdog::class.java)

        /**
         * Application-wide: JCEF is shared, so a recreate that does not restore heartbeats is a
         * property of the CEF server, not of a single project's tool window. Reset only when a
         * heartbeat arrives; without one the budget decays (see [effectiveRecreateBudget]) so the
         * failure card's Retry is not stuck behind a give-up budget forever.
         */
        private val processRecreatesAfterStall = AtomicInteger(0)

        private val budgetDecayLock = Any()
        private var lastBudgetDecayAtMillis = 0L

        private fun effectiveRecreateBudget(nowMillis: Long): Int = synchronized(budgetDecayLock) {
            val current = processRecreatesAfterStall.get()
            if (current <= 0) {
                lastBudgetDecayAtMillis = nowMillis
                return@synchronized 0
            }
            if (lastBudgetDecayAtMillis == 0L) {
                lastBudgetDecayAtMillis = nowMillis
                return@synchronized current
            }
            val elapsed = (nowMillis - lastBudgetDecayAtMillis).coerceAtLeast(0L)
            val decayed = OpenCodeRendererWatchdogPolicy.decayedRecreateBudget(current, elapsed)
            if (decayed != current) {
                processRecreatesAfterStall.set(decayed)
                lastBudgetDecayAtMillis = nowMillis
            }
            decayed
        }

        /**
         * User-initiated recovery (Restart OpenCode Server, failure-card Retry) must get a
         * real reload/recreate cycle. Auto-recreate of a new panel does not call this.
         */
        internal fun resetProcessRecreatesAfterStall() {
            processRecreatesAfterStall.set(0)
            synchronized(budgetDecayLock) { lastBudgetDecayAtMillis = 0L }
        }

        internal fun resetProcessRecreatesAfterStallForTests() {
            resetProcessRecreatesAfterStall()
        }
    }
}
