package de.moritzf.opencodewebpanel.browser

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery

/**
 * A page-to-JVM callback channel that tolerates a JCEF message router that could not be created.
 *
 * IntelliJ runs JCEF out of process on Windows, so [JBCefJSQuery.create] builds a
 * `RemoteMessageRouter` over the CEF server RPC. That RPC can answer with no remote object —
 * `NullPointerException: Cannot read field "isNull" because "robj" is null` — while the CEF
 * server is still settling, most reproducibly right after another browser of the same client was
 * torn down (Restart OpenCode Server recreates the panel). The raw exception escaped the panel
 * constructor and left the tool window without any content at all, recoverable only by an IDE
 * restart.
 *
 * A failed channel is therefore captured here: the panel still shows OpenCode, and only the
 * IDE-integration features that need this particular channel stay off. [inject] returns `null`
 * then, which the script builders already treat as "do not inject this feature".
 */
internal class OpenCodeJsQuery private constructor(private val delegate: JBCefJSQuery?) {

    /** False when the JCEF message router could not be created for this channel. */
    val isAvailable: Boolean get() = delegate != null

    fun addHandler(handler: (String) -> JBCefJSQuery.Response?) {
        delegate?.addHandler { handler(it) }
    }

    /** The in-page call expression, or `null` when this callback channel is unavailable. */
    fun inject(queryResult: String): String? = delegate?.inject(queryResult)

    companion object {
        private val LOG = Logger.getInstance(OpenCodeJsQuery::class.java)

        fun create(browser: JBCefBrowserBase): OpenCodeJsQuery {
            val delegate = try {
                JBCefJSQuery.create(browser)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn("Could not create a JCEF callback channel; some IDE integrations stay off in this panel", e)
                null
            }
            return OpenCodeJsQuery(delegate)
        }
    }
}
