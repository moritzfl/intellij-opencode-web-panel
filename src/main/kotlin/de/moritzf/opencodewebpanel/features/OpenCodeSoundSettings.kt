package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonParser
import de.moritzf.opencodewebpanel.server.booleanMember
import de.moritzf.opencodewebpanel.server.objectMember
import de.moritzf.opencodewebpanel.server.stringMember

/**
 * OpenCode web sound preferences from the mirrored `settings.v3` localStorage snapshot.
 * Defaults match OpenCode's SPA (`packages/app/src/context/settings.tsx`).
 */
internal data class OpenCodeSoundSettings(
    val agentEnabled: Boolean = true,
    val agent: String = DEFAULT_AGENT,
    val permissionsEnabled: Boolean = true,
    val permissions: String = DEFAULT_PERMISSIONS,
    val errorsEnabled: Boolean = true,
    val errors: String = DEFAULT_ERRORS,
) {
    companion object {
        const val DEFAULT_AGENT = "staplebops-01"
        const val DEFAULT_PERMISSIONS = "staplebops-02"
        const val DEFAULT_ERRORS = "nope-03"

        val KNOWN_SOUND_IDS: Set<String> = buildSet {
            for (i in 1..10) add("alert-%02d".format(i))
            for (i in 1..10) add("bip-bop-%02d".format(i))
            for (i in 1..7) add("staplebops-%02d".format(i))
            for (i in 1..12) add("nope-%02d".format(i))
            for (i in 1..6) add("yup-%02d".format(i))
        }
    }
}

internal fun parseOpenCodeSoundSettings(snapshot: String?): OpenCodeSoundSettings {
    val defaults = OpenCodeSoundSettings()
    return runCatching {
        val snapshotObject = JsonParser.parseString(snapshot.orEmpty())
            .takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return defaults
        val settingsValue = snapshotObject.get("settings.v3")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?: return defaults
        val settings = JsonParser.parseString(settingsValue)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return defaults
        val sounds = settings.objectMember("sounds") ?: return defaults
        OpenCodeSoundSettings(
            agentEnabled = sounds.booleanMember("agentEnabled") ?: defaults.agentEnabled,
            agent = normalizeSoundId(sounds.stringMember("agent"), defaults.agent),
            permissionsEnabled = sounds.booleanMember("permissionsEnabled") ?: defaults.permissionsEnabled,
            permissions = normalizeSoundId(sounds.stringMember("permissions"), defaults.permissions),
            errorsEnabled = sounds.booleanMember("errorsEnabled") ?: defaults.errorsEnabled,
            errors = normalizeSoundId(sounds.stringMember("errors"), defaults.errors),
        )
    }.getOrDefault(defaults)
}

private fun normalizeSoundId(id: String?, fallback: String): String {
    val value = id?.trim().orEmpty()
    if (value.isEmpty()) return fallback
    return if (value in OpenCodeSoundSettings.KNOWN_SOUND_IDS) value else fallback
}
