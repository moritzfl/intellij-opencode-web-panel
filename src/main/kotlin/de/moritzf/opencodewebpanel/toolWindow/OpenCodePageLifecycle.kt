package de.moritzf.opencodewebpanel.toolWindow

/**
 * Page-load transitions, independent of CEF, alarms and Swing presentation. UI transitions run on
 * the EDT; document/address callbacks only update the volatile revisions and load acknowledgement.
 */
internal class OpenCodePageLifecycle(
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    @Volatile
    var loadSucceeded = false
        private set

    var loadInProgress = false
        private set

    var painted = false
        private set

    var gaveUp = false
        private set

    var targetUrl: String? = null
        private set

    var retryCount = 0
        private set

    private var startedAtMillis = 0L
    private var watchdogGeneration = 0L
    private var pendingLoadGeneration = 0L
    @Volatile
    var documentRevision = 0L
        private set

    @Volatile
    var browserRevision = 0L
        private set

    @Synchronized
    fun documentStarted(): Long {
        documentRevision++
        browserRevision++
        loadSucceeded = false
        return documentRevision
    }

    @Synchronized
    fun addressChanged() {
        browserRevision++
    }

    fun begin(target: String? = null, resetRetryBudget: Boolean = true) {
        loadSucceeded = false
        loadInProgress = true
        gaveUp = false
        if (target != null) targetUrl = target
        if (resetRetryBudget) {
            retryCount = 0
            startedAtMillis = nowMillis()
        } else if (startedAtMillis == 0L) {
            startedAtMillis = nowMillis()
        }
    }

    fun visible() {
        painted = true
        loadSucceeded = true
        gaveUp = false
        dismissOpening()
    }

    /** SPA address changes hide the strip but are not proof of a loaded/healthy renderer. */
    fun dismissOpening() {
        loadInProgress = false
        watchdogGeneration++
        retryCount = 0
        startedAtMillis = 0L
        targetUrl = null
    }

    fun reset() {
        invalidatePendingLoad()
        dismissOpening()
        gaveUp = false
        painted = false
        loadSucceeded = false
    }

    fun keepCurrentPage() {
        loadInProgress = false
        startedAtMillis = 0L
        targetUrl = null
    }

    fun forgetTarget() {
        targetUrl = null
    }

    fun willNavigate() {
        loadSucceeded = false
    }

    fun invalidatePendingLoad(): Long = ++pendingLoadGeneration

    fun acceptsPendingLoad(token: Long, revision: Long? = null): Boolean =
        token == pendingLoadGeneration &&
            (revision == null || (revision == documentRevision && !loadSucceeded))

    fun armWatchdog(): Long = ++watchdogGeneration

    enum class Timeout {
        NONE,
        RETRY,
        GAVE_UP,
    }

    fun timeout(token: Long): Timeout {
        if (token != watchdogGeneration) return Timeout.NONE
        val elapsed = if (startedAtMillis == 0L) 0L else nowMillis() - startedAtMillis
        if (OpenCodePageLoadWatchdog.shouldRetry(loadSucceeded, retryCount, elapsed)) {
            retryCount++
            return Timeout.RETRY
        }
        if (!loadSucceeded && retryCount >= OpenCodePageLoadWatchdog.MAX_RETRIES) {
            loadInProgress = false
            gaveUp = true
            startedAtMillis = 0L
            return Timeout.GAVE_UP
        }
        return Timeout.NONE
    }
}
