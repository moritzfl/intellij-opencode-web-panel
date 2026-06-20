package de.moritzf.opencodewebpanel.settings

import com.intellij.util.messages.Topic

interface OpenCodeSettingsListener {
    fun uiZoomChanged(zoomPercent: Int)

    companion object {
        val TOPIC: Topic<OpenCodeSettingsListener> = Topic.create(
            "OpenCode Web Panel settings",
            OpenCodeSettingsListener::class.java,
        )
    }
}
