package de.moritzf.opencodewebpanel.toolWindow

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

    fun schedule(script: String, rootUrl: String, shouldRun: () -> Boolean = { true }) {
        schedule(DEFAULT_DELAYS_MILLIS, shouldRun) {
            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        }
    }

    fun scheduleEarly(script: String, rootUrl: String, shouldRun: () -> Boolean = { true }) {
        schedule(EARLY_DELAYS_MILLIS, shouldRun) {
            browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
        }
    }

    fun scheduleAction(shouldRun: () -> Boolean = { true }, action: () -> Unit) {
        schedule(DEFAULT_DELAYS_MILLIS, shouldRun, action)
    }

    fun scheduleEarlyAction(shouldRun: () -> Boolean = { true }, action: () -> Unit) {
        schedule(EARLY_DELAYS_MILLIS, shouldRun, action)
    }

    private fun schedule(delaysMillis: List<Int>, shouldRun: () -> Boolean, action: () -> Unit) {
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
}
