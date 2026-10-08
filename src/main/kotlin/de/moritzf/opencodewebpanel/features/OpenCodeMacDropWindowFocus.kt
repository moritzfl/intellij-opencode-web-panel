package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.MacUtil
import com.intellij.util.Alarm
import java.awt.Container
import java.awt.KeyboardFocusManager
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** A bounded check for the late window-focus loss delivered after a macOS screenshot drag. */
internal class OpenCodeDropWindowFocusRecovery(
    private val isEligible: () -> Boolean,
    private val checkNativeFocus: ((Boolean) -> Unit) -> Unit,
    private val restoreFocus: () -> Unit,
    private val schedule: (Runnable, Int) -> Unit,
) {
    fun afterDrop() {
        // The native drag/Space handoff can finish after the immediate Swing/CEF round trip.
        // Check twice, then stop. A healthy window never needs a native query or focus request.
        for (delay in intArrayOf(100, 500)) {
            schedule(
                Runnable {
                    if (isEligible())
                        checkNativeFocus { isNativeKeyWindow ->
                            // Focus, placement, the safeguard, or disposal may change during the
                            // native hop.
                            if (isNativeKeyWindow && isEligible()) restoreFocus()
                        }
                },
                delay,
            )
        }
    }
}

internal object OpenCodeMacDropWindowFocus {
    private val LOG = Logger.getInstance(OpenCodeMacDropWindowFocus::class.java)

    /** macOS only; called on the EDT after an accepted external drop. */
    fun afterDrop(component: JComponent, alarm: Alarm, isEnabled: () -> Boolean) {
        val window = SwingUtilities.getWindowAncestor(component) ?: return
        val nativeWindow =
            runCatching { MacUtil.getWindowFromJavaWindow(window) }
                .onFailure { LOG.debug("Could not resolve the screenshot drop window", it) }
                .getOrNull() ?: return
        if (Foundation.isNil(nativeWindow)) return

        OpenCodeDropWindowFocusRecovery(
                isEligible = {
                    val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    isEnabled() &&
                        component.isShowing &&
                        SwingUtilities.getWindowAncestor(component) === window &&
                        focus.activeWindow == null &&
                        focus.focusedWindow == null
                },
                checkNativeFocus = { callback ->
                    runCatching {
                        Foundation.executeOnMainThread(true, false) {
                            val nativeKeyWindow = runCatching {
                                val app = Foundation.invoke("NSApplication", "sharedApplication")
                                Foundation.invoke(app, "isActive").toLong() != 0L &&
                                    Foundation.invoke(app, "keyWindow") == nativeWindow
                            }
                                .onFailure {
                                    LOG.debug(
                                        "Could not check the screenshot drop window's native focus",
                                        it,
                                    )
                                }
                                .getOrDefault(false)
                            ApplicationManager.getApplication().invokeLater {
                                callback(nativeKeyWindow)
                            }
                        }
                    }
                        .onFailure {
                            LOG.debug(
                                "Could not schedule the screenshot drop window's native focus check",
                                it,
                            )
                        }
                },
                restoreFocus = {
                    // In the failure, Cocoa still has a key window but AWT's active/focused windows
                    // are null. requestFocusInWindow() cannot repair that window-level mismatch.
                    // This exact toFront/requestFocus pair restored typing in the affected IDE.
                    window.toFront()
                    val target =
                        window.mostRecentFocusOwner?.takeIf {
                            it.isShowing &&
                                SwingUtilities.getWindowAncestor(it) === window &&
                                !(it is Container && it.isAncestorOf(component))
                        } ?: component
                    target.requestFocus()
                },
                schedule = { request, delay -> alarm.addRequest(request, delay) },
            )
            .afterDrop()
    }
}
