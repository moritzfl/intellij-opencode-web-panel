package de.moritzf.opencodewebpanel.configuration

import com.intellij.openapi.util.io.FileUtil

/** Value normalization shared by persisted legacy settings and current sandbox forms/specs. */
internal object SbxSettingsValues {
    const val DEFAULT_EXECUTABLE = "sbx"
    const val DEFAULT_MEMORY = "4g"
    const val DEFAULT_CPUS = "2"

    fun parseMemory(value: String?): String? {
        val trimmed = value?.trim()?.lowercase().orEmpty()
        return trimmed.takeIf { it.matches(Regex("""[1-9]\d*[gm]""")) }
    }

    fun parseCpus(value: String?): String? {
        val parsed = value?.trim()?.toIntOrNull() ?: return null
        return parsed.takeIf { it in 1..32 }?.toString()
    }

    fun sanitizeMemory(value: String?): String = parseMemory(value) ?: DEFAULT_MEMORY

    fun sanitizeCpus(value: String?): String = parseCpus(value) ?: DEFAULT_CPUS

    fun normalizeLineList(text: String?): String = parseLineList(text).joinToString("\n")

    fun parseLineList(text: String?): List<String> =
        text
            .orEmpty()
            .lineSequence()
            .map(::posixPath)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .distinct()
            .toList()

    fun normalizeExtraMountText(text: String?): String =
        serializeExtraMountRows(parseExtraMountRows(text))

    fun parseExtraMountRows(text: String?): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        val seen = HashSet<Pair<String, String>>()
        for (rawLine in text.orEmpty().lineSequence()) {
            // A `#` starts a comment only at the start or after whitespace (`C#Proj` is a path).
            val line = COMMENT_AFTER_SPACE.replace(rawLine, "").trim()
            if (line.isBlank()) continue
            val parts = line.split('|', limit = 2).map { it.trim() }
            val host = posixPath(parts[0])
            if (host.isBlank()) continue
            val sandbox = posixPath(parts.getOrElse(1) { host }.ifBlank { host })
            val row = host to sandbox
            if (seen.add(row)) rows += row
        }
        return rows
    }

    private val COMMENT_AFTER_SPACE = Regex("(^|\\s)#.*$")

    fun serializeExtraMountRows(rows: List<Pair<String, String>>): String =
        rows
            .map { posixPath(it.first) to posixPath(it.second) }
            .filter { it.first.isNotBlank() }
            .map { (host, sandbox) ->
                if (sandbox.isBlank() || sandbox == host) host else "$host | $sandbox"
            }
            .distinct()
            .joinToString("\n")

    fun posixPath(path: String): String = FileUtil.toSystemIndependentName(path.trim())
}
