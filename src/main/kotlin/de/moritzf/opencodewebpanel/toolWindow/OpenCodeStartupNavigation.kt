package de.moritzf.opencodewebpanel.toolWindow

/**
 * Pure policy for the one-shot "open most recent conversation" boot intent.
 *
 * The page must paint immediately; the session listing is only used to navigate afterwards.
 * A still-running lookup must not drop the navigate intent, or onLoadEnd would seed-only and
 * never open the conversation when the listing finishes.
 */
internal object OpenCodeStartupNavigation {
    fun shouldKeepNavigateIntent(
        openMostRecent: Boolean,
        sessionId: String?,
        lookupInFlight: Boolean,
    ): Boolean {
        if (!openMostRecent) return false
        if (!sessionId.isNullOrBlank()) return true
        return lookupInFlight
    }
}
