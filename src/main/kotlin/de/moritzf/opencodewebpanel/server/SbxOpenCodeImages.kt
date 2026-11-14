package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.text.SemVer

/**
 * Docker Hub's tags of Docker's OpenCode kit image ([SbxCli.OPENCODE_IMAGE_REPOSITORY]). Docker
 * keeps only recent tags, so a list is a snapshot, never proof that a pin stays available.
 */
internal object SbxOpenCodeImages {
    const val TAGS_URL =
        "https://hub.docker.com/v2/repositories/sbx/opencode/tags?page_size=100&ordering=last_updated"

    data class Tag(val name: String, val digest: String)

    fun parseTags(json: String): List<Tag> {
        val results =
            runCatching { JsonParser.parseString(json) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("results")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val tag = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = tag.stringMember("name") ?: return@mapNotNull null
            val digest = tag.stringMember("digest") ?: return@mapNotNull null
            Tag(name, digest)
        }
    }

    /** Plain release tags of [major], newest first. */
    fun releases(tags: List<Tag>, major: Int): List<String> =
        tags
            .mapNotNull { tag ->
                release(tag.name)?.takeIf { it.major == major }?.let { tag.name to it }
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()

    /**
     * The release that `latest` carries: the plain release tag with the same digest. Null without
     * such a tag or for another [major], so a moved `latest` is never misreported.
     */
    fun latestRelease(tags: List<Tag>, major: Int): String? {
        val digest = tags.firstOrNull { it.name == SbxCli.LATEST_IMAGE }?.digest ?: return null
        return releases(tags.filter { it.digest == digest }, major).firstOrNull()
    }

    /** Null when Docker Hub is unreachable or answers with something else. */
    fun fetchTags(): List<Tag>? =
        OpenCodeHttpTransport.httpGet(TAGS_URL, null, 3_000, 5_000, MAX_RESPONSE_CHARS)
            ?.let(::parseTags)
            ?.takeIf { it.isNotEmpty() }

    private const val MAX_RESPONSE_CHARS = 512 * 1024
    private val RELEASE_TAG = Regex("""\d+\.\d+\.\d+""")

    private fun release(name: String): SemVer? =
        if (RELEASE_TAG.matches(name)) SemVer.parseFromText(name) else null

    private fun JsonObject.stringMember(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
