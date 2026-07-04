package de.moritzf.opencodewebpanel.server

import com.intellij.util.messages.Topic

/**
 * Application-level notification that the machine resumed from a system suspend (sleep,
 * hibernate). Published by [SharedOpenCodeServerManager]'s periodic checker when the
 * wall-clock gap between two check runs is too large to be scheduler jitter, on the
 * checker thread and before the accompanying health check runs.
 */
interface OpenCodeSuspendResumeListener {
    /**
     * @param lastAliveMillis wall-clock time of the last periodic check before the suspend;
     *   the machine went to sleep at most one check interval after it.
     * @param resumedAtMillis wall-clock time the suspend was detected; the machine woke at
     *   most one check interval before it.
     */
    fun resumedFromSuspend(lastAliveMillis: Long, resumedAtMillis: Long)

    companion object {
        val TOPIC: Topic<OpenCodeSuspendResumeListener> = Topic.create(
            "OpenCode Web Panel suspend resume",
            OpenCodeSuspendResumeListener::class.java,
        )
    }
}
