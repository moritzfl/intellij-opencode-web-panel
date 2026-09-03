package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.Alarm
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.util.concurrent.atomic.AtomicBoolean

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
 * stale while the panel is showing and the page is visible, the watchdog first reloads the page
 * in place and, after a persistent stall, recreates the whole JCEF panel. A recreation that does
 * not restore heartbeats is tried only a bounded number of times before giving up, so a broken
 * JCEF stack cannot loop recovery forever — the panel's failure card (Retry) is the final state.
 *
 * Everything rides the panel's own lifecycle: heartbeats are only processed while the panel is
 * the active content and the page reports visible, and a fresh navigation resets the timestamps
 * so a legitimate reload is never itself recovered.
 */
internal class OpenCodeRendererWatchdog(
    parentDisposable: Disposable,
    private val isActiveContent: () -> Boolean,
    /** True while an agent turn may legitimately starve the page's event loop (long IPC bursts). */
    private val isAgentBusy: () -> Boolean,
    private val isEnabledInSettings: () -> Boolean,
    private val onReloadPage: () -> Unit,
    private val onRecreatePanel: () -> Unit,
    private val onGiveUp: () -> Unit = {},
) {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastHeartbeatAtMillis = 0L

    @Volatile
    private var lastPageVisible = true
    private var consecutiveStalls = 0
    private var recreatesAfterStall = 0
    private var lastRecoveryAtMillis = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        resetForFreshDocument()
        scheduleNext()
    }

    fun stop() {
        running.set(false)
        alarm.cancelAllRequests()
    }

    /** Fresh browser/panel state: heartbeats start from now and the recovery budget is cleared. */
    fun resetForFreshDocument() {
        lastHeartbeatAtMillis = System.currentTimeMillis()
        lastPageVisible = true
        consecutiveStalls = 0
        recreatesAfterStall = 0
    }

    /** Called from the JBCefJSQuery callback; may run on any thread. */
    fun handleHeartbeat(visibility: String?) {
        lastHeartbeatAtMillis = System.currentTimeMillis()
        if (visibility != null) lastPageVisible = visibility != "hidden"
        consecutiveStalls = 0
    }

    /** A reload solved an earlier stall; do not escalate an old stall into a recreate. */
    fun noteReloadedForStall() {
        lastRecoveryAtMillis = System.currentTimeMillis()
        lastHeartbeatAtMillis = System.currentTimeMillis()
    }

    private fun scheduleNext() {
        if (!running.get()) return
        alarm.addRequest(
            {
                try {
                    checkHealth()
                } finally {
                    scheduleNext()
                }
            },
            OpenCodeRendererWatchdogPolicy.CHECK_INTERVAL_MILLIS,
        )
    }

    private fun checkHealth() {
        if (!running.get() || !isEnabledInSettings()) return
        if (!OpenCodeRendererWatchdogPolicy.shouldMonitor(isActiveContent(), lastPageVisible)) return

        val now = System.currentTimeMillis()
        val silenceMillis = now - lastHeartbeatAtMillis
        if (silenceMillis <= 0L) return
        val timeout = OpenCodeRendererWatchdogPolicy.effectiveStallTimeout(isAgentBusy())
        if (silenceMillis <= timeout) return

        val action = OpenCodeRendererWatchdogPolicy.stalledAction(
            consecutiveStalls = consecutiveStalls + 1,
            recreatesAfterStall = recreatesAfterStall,
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
                Logger.getInstance(OpenCodeRendererWatchdog::class.java)
                    .warn("OpenCode page heartbeat stalled (${silenceMillis}ms); reloading the page")
                onReloadPage()
            }
            OpenCodeRendererWatchdogPolicy.Action.RECREATE -> {
                recreatesAfterStall++
                consecutiveStalls = 0
                lastHeartbeatAtMillis = now
                Logger.getInstance(OpenCodeRendererWatchdog::class.java)
                    .warn("OpenCode page heartbeat stayed stalled after reload; recreating the panel")
                onRecreatePanel()
            }
            OpenCodeRendererWatchdogPolicy.Action.GIVE_UP -> {
                Logger.getInstance(OpenCodeRendererWatchdog::class.java)
                    .warn("OpenCode page could not be revived; leaving recovery to the failure card")
                stop()
                onGiveUp()
            }
        }
    }

    init {
        // The alarm is the only self-managed resource; Disposer ties it to the panel so a
        // replaced panel can never leak its scheduler.
        Disposer.register(parentDisposable, Disposable { stop() })
    }
}