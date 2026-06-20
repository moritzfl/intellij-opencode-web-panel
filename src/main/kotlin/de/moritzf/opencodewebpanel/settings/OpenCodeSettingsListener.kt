package de.moritzf.opencodewebpanel.settings

import com.intellij.util.messages.Topic

interface OpenCodeSettingsListener {
    fun uiZoomChanged(zoomPercent: Int)

    fun fileLinkNavigationChanged(enabled: Boolean)

    fun codeNavigationChanged(enabled: Boolean) {}

    fun compactLayoutChanged(enabled: Boolean) {}

    fun projectSwitchPromptSuppressionChanged(enabled: Boolean) {}

    companion object {
        val TOPIC: Topic<OpenCodeSettingsListener> = Topic.create(
            "OpenCode Web Panel settings",
            OpenCodeSettingsListener::class.java,
        )
    }
}
