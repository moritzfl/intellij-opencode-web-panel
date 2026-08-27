package de.moritzf.opencodewebpanel.browser

import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm

internal class OpenCodeBrowserScriptScheduler(
    private val project: Project,
    private val alarm: Alarm,
    private val executeJavaScript: (script: String, url: String) -> Unit,
) {
    constructor(project: Project, browser: JBCefBrowser, alarm: Alarm) : this(
        project,
        alarm,
        { script, url -> browser.cefBrowser.executeJavaScript(script, url, 0) },
    )

    private companion object {
        private val DEFAULT_DELAYS_MILLIS = listOf(250, 750, 1500, 3000, 5000, 8000, 12000)
        private val EARLY_DELAYS_MILLIS = listOf(50, 250, 750, 1500, 3000)
    }

    fun schedule(script: String, rootUrl: String, early: Boolean = false, shouldRun: () -> Boolean = { true }) {
        scheduleAction(early, shouldRun) {
            executeJavaScript(script, rootUrl)
        }
    }

    fun scheduleAction(early: Boolean = false, shouldRun: () -> Boolean = { true }, action: () -> Unit) {
        if (!canSchedule()) return
        val delaysMillis = if (early) EARLY_DELAYS_MILLIS else DEFAULT_DELAYS_MILLIS
        delaysMillis.forEach { delayMillis ->
            addRequest(delayMillis, shouldRun, action)
        }
    }

    fun scheduleAt(delayMillis: Int, shouldRun: () -> Boolean = { true }, action: () -> Unit) {
        addRequest(delayMillis, shouldRun, action)
    }

    private fun addRequest(delayMillis: Int, shouldRun: () -> Boolean, action: () -> Unit) {
        if (!canSchedule()) return
        alarm.addRequest(
            {
                if (canSchedule() && shouldRun()) {
                    action()
                }
            },
            delayMillis,
        )
    }

    private fun canSchedule(): Boolean = !alarm.isDisposed && !project.isDisposed
}
