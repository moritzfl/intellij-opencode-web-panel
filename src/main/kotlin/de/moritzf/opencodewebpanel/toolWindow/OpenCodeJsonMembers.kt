package de.moritzf.opencodewebpanel.toolWindow

import com.google.gson.JsonObject

// Null-safe accessors for optional members of loosely-shaped OpenCode JSON: a member that
// is absent or of an unexpected type reads as null instead of throwing.

internal fun JsonObject.stringMember(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal fun JsonObject.longMember(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.let { runCatching { it.asLong }.getOrNull() }

internal fun JsonObject.objectMember(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
