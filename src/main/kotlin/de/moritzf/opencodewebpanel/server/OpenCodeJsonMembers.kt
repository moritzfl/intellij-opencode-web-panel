package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// Null-safe accessors for optional members of loosely-shaped OpenCode JSON: a member that
// is absent or of an unexpected type reads as null instead of throwing.

internal fun JsonObject.stringMember(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal fun JsonObject.longMember(name: String): Long? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.let { runCatching { it.asLong }.getOrNull() }

internal fun JsonObject.booleanMember(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

internal fun JsonObject.objectMember(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun parseJsonObject(text: String): JsonObject? {
    if (text.isBlank()) return null
    return runCatching { JsonParser.parseString(text) }
        .getOrNull()
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
}

internal fun parseJsonArray(text: String): JsonArray? {
    if (text.isBlank()) return null
    return runCatching { JsonParser.parseString(text) }
        .getOrNull()
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
}
