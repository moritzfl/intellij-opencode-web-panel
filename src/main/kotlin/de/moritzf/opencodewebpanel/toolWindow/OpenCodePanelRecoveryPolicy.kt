package de.moritzf.opencodewebpanel.toolWindow

/**
 * Decides whether a panel whose JCEF callback channels could not be created may be recreated.
 *
 * Creating those channels fails transiently while the out-of-process CEF server settles (see
 * `OpenCodeJsQuery`), so one delayed recreate usually restores a fully functional panel. The
 * throttle is application-wide and deliberately coarse: recreating the panel is visible to the
 * user, and a permanently broken JCEF stack must not turn into a recreate loop.
 */
internal object OpenCodePanelRecoveryPolicy {
    /** Long enough for the CEF server connection to settle, short enough to feel automatic. */
    const val RETRY_DELAY_MILLIS = 2_000

    const val RETRY_THROTTLE_MILLIS = 60_000L

    /** [lastAttemptAtMillis] is 0 when no recreate was attempted yet. */
    fun shouldRecreatePanel(lastAttemptAtMillis: Long, nowMillis: Long): Boolean {
        if (lastAttemptAtMillis <= 0L) return true
        return nowMillis - lastAttemptAtMillis >= RETRY_THROTTLE_MILLIS
    }
}
