package de.moritzf.opencodewebpanel.settings

import com.intellij.util.messages.Topic

interface OpenCodeProjectSettingsListener {
    fun projectDirectoryChanged(directory: String?) {}

    fun sandboxSettingsChanged(directory: String?) {
        projectDirectoryChanged(directory)
    }

    fun serverRestartRequested() {}

    companion object {
        val TOPIC: Topic<OpenCodeProjectSettingsListener> = Topic.create(
            "OpenCode Web Panel project settings",
            OpenCodeProjectSettingsListener::class.java,
        )
    }
}
