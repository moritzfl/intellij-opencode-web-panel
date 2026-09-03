package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol

/**
 * Pure policy for retrying a hung embedded page load.
 *
 * Never interrupt a navigation that has already started and is still within [timeoutMillis].
 * `stopLoad` + `loadURL` during that window is what left the first panel stuck on
 * "Opening the OpenCode page…".
 */
internal object OpenCodePageLoadWatchdog {
    const val DEFAULT_TIMEOUT_MILLIS = 20_000
    const val MAX_RETRIES = 2
    const val DOCUMENT_START_INSTALL_TIMEOUT_MILLIS = 20_000L

    /**
     * How long a *first* navigation may wait for CDP document-start before loading anyway.
     * Restart's new JCEF often never completes the about:blank bootstrap; sitting on
     * “Opening the OpenCode page…” for [DOCUMENT_START_INSTALL_TIMEOUT_MILLIS] is worse
     * than using the onLoadStart fallback.
     */
    const val DOCUMENT_START_WAIT_BEFORE_LOAD_MILLIS = 2_000L

    fun shouldRetry(
        succeeded: Boolean,
        retryCount: Int,
        elapsedMillis: Long,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
        maxRetries: Int = MAX_RETRIES,
    ): Boolean {
        if (succeeded) return false
        if (retryCount >= maxRetries) return false
        return elapsedMillis >= timeoutMillis
    }

    fun retryTarget(serverUrl: String, requestedUrl: String?, currentUrl: String?): String {
        return requestedUrl
            ?.takeIf { OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, it) }
            ?: currentUrl?.takeIf { OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, it) }
            ?: OpenCodeServerProtocol.buildServerSessionUrl(serverUrl)
    }
}
