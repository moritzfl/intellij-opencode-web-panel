package de.moritzf.opencodewebpanel.features

/**
 * Trailing debounce with a max-wait cap, used to collapse bursts of OpenCode workspace events
 * (many `file.edited` during a single agent turn, chatty `file.watcher.updated`, etc.) into as
 * few IDE refreshes as possible while still bounding how long a continuous stream can defer one.
 *
 * Semantics:
 * - The first request opens a *burst* and schedules a refresh [debounceMillis] later.
 * - Each further request within the burst pushes the refresh out by another [debounceMillis]
 *   (trailing debounce), but never past `burstStart + maxWaitMillis` (the cap), so a stream that
 *   never pauses still refreshes at most [maxWaitMillis] after it began.
 * - [onFire] closes the burst; the next request opens a new one.
 *
 * Pure and clock-injected so the scheduling decisions are deterministically unit-testable; the
 * caller ([OpenCodeWorkspaceRefreshCoordinator]) turns the returned delays into Alarm requests.
 * Not thread-safe; callers serialize access.
 */
internal class RefreshDebouncer(
    private val debounceMillis: Long,
    private val maxWaitMillis: Long,
) {
    init {
        require(debounceMillis > 0) { "debounceMillis must be positive" }
        require(maxWaitMillis >= debounceMillis) { "maxWaitMillis must be >= debounceMillis" }
    }

    private var pending = false
    private var burstStartMillis = 0L
    private var scheduledFireMillis = 0L

    /**
     * Records a refresh request observed at [nowMillis]. Returns the delay (in milliseconds from
     * now) at which the caller should (re)schedule the refresh, or null when the already-scheduled
     * fire time is unchanged and the caller must keep its existing timer.
     *
     * The scheduled fire time is monotonically non-decreasing within a burst, so a non-null result
     * always means "cancel the previous timer and schedule this later one".
     */
    fun onRequest(nowMillis: Long): Long? {
        if (!pending) {
            pending = true
            burstStartMillis = nowMillis
            scheduledFireMillis = nowMillis + debounceMillis
            return debounceMillis
        }
        val target = minOf(nowMillis + debounceMillis, burstStartMillis + maxWaitMillis)
        if (target <= scheduledFireMillis) return null
        scheduledFireMillis = target
        return target - nowMillis
    }

    /** Closes the current burst so the next [onRequest] starts a fresh debounce window. */
    fun onFire() {
        pending = false
    }

    fun isPending(): Boolean = pending
}
