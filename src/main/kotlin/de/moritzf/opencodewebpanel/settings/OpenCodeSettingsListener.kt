package de.moritzf.opencodewebpanel.settings

import com.intellij.util.messages.Topic

/** The UI-behavior toggles whose changes the tool window must apply to the live page. */
enum class OpenCodeUiSetting {
    FILE_LINK_NAVIGATION,
    EXTERNAL_LINK_NAVIGATION,
    CODE_NAVIGATION,
    DIFF_NAVIGATION,
    CHAT_FILE_DROP,
    COMPACT_LAYOUT,
    HIDE_WEBSITE_BUTTON,
    IDE_THEME_SYNC,
    PROJECT_SWITCH_PROMPT_SUPPRESSION,
    BROWSER_CURSOR_MIRROR,
    AGENT_STATUS_BADGE,
}

interface OpenCodeSettingsListener {
    fun uiZoomChanged(zoomPercent: Int) {}

    fun uiSettingChanged(setting: OpenCodeUiSetting, enabled: Boolean) {}

    fun serverRestartRequested() {}

    companion object {
        val TOPIC: Topic<OpenCodeSettingsListener> = Topic.create(
            "OpenCode Web Panel settings",
            OpenCodeSettingsListener::class.java,
        )
    }
}
