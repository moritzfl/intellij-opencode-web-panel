package de.moritzf.opencodewebpanel.browser

import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm

internal class OpenCodeBrowserScriptScheduler(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val alarm: Alarm,
) {
    private companion object {
        private val DEFAULT_DELAYS_MILLIS = listOf(250, 750, 1500, 3000, 5000, 8000, 12000)
        private val EARLY_DELAYS_MILLIS = listOf(50, 250, 750, 1500, 3000)
    }

    fun schedule(script: String, rootUrl: String, early: Boolean = false, shouldRun: () -> Boolean = { true }) {
        scheduleAction(early, shouldRun) {
            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        }
    }

    fun scheduleAction(early: Boolean = false, shouldRun: () -> Boolean = { true }, action: () -> Unit) {
        val delaysMillis = if (early) EARLY_DELAYS_MILLIS else DEFAULT_DELAYS_MILLIS
        delaysMillis.forEach { delayMillis ->
            alarm.addRequest(
                {
                    if (!project.isDisposed && shouldRun()) {
                        action()
                    }
                },
                delayMillis,
            )
        }
    }

    fun scheduleAt(delayMillis: Int, shouldRun: () -> Boolean = { true }, action: () -> Unit) {
        alarm.addRequest(
            {
                if (!project.isDisposed && shouldRun()) {
                    action()
                }
            },
            delayMillis,
        )
    }
}
