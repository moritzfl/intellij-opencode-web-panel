package de.moritzf.opencodewebpanel.toolWindow

/**
 * Pure policy for runtime toggles of browser-injected UI features.
 *
 * Safeguard contract: enabling injects the script immediately; disabling reloads the page so
 * listeners/patches are fully removed — never a "disable" script.
 */
internal object OpenCodeInjectedFeaturePolicy {
    enum class Action {
        /** Inject [script] into the current page. */
        INJECT,

        /** Reload the page (and run optional onDisable cleanup) to drop previous injections. */
        RELOAD,

        /** No browser action (missing page, missing script, etc.). */
        NONE,
    }

    data class Decision(
        val action: Action,
        val script: String? = null,
        val clearScheduled: Boolean = true,
        val markScheduled: Boolean = false,
    )

    /**
     * Chooses the browser action for a runtime feature toggle.
     *
     * Returns [Action.RELOAD] when the feature is (or must be) off so prior injections are
     * dropped by a full page load; [Action.INJECT] when it is on and a script is available;
     * [Action.NONE] when the browser is not on the OpenCode page or no script can be built.
     *
     * @param enabled the value the user just set for this feature
     * @param enabledInSettings current settings gate (usually matches [enabled] after apply)
     * @param onOpenCodePage whether the browser is currently on the OpenCode server origin
     * @param script the script builder result when enabling; ignored when disabling
     */
    fun decide(
        enabled: Boolean,
        enabledInSettings: Boolean,
        onOpenCodePage: Boolean,
        script: String?,
    ): Decision {
        if (!onOpenCodePage) return Decision(Action.NONE, clearScheduled = false)
        if (!enabled || !enabledInSettings) {
            return Decision(Action.RELOAD, clearScheduled = true, markScheduled = false)
        }
        if (script.isNullOrBlank()) {
            return Decision(Action.NONE, clearScheduled = true, markScheduled = false)
        }
        return Decision(Action.INJECT, script = script, clearScheduled = true, markScheduled = true)
    }
}
