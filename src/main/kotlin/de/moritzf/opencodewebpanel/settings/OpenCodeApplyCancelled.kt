package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.options.ConfigurationException

/**
 * Settings keeps its dialog open only when `apply()` throws, and shows the exception in its error
 * banner. A declined confirmation is no error: say plainly that nothing changed.
 */
internal fun applyCancelled(): ConfigurationException =
    ConfigurationException(
        "Nothing was changed. Adjust the settings or apply again.",
        "Apply cancelled",
    )
