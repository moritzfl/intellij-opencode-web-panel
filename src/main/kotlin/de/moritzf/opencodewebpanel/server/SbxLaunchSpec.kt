package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.diagnostic.logger
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal sealed class SbxLaunchSpecInspection {
    data object Missing : SbxLaunchSpecInspection()
    data class Valid(val spec: SbxLaunchSpec, val path: Path) : SbxLaunchSpecInspection()
    data class Invalid(val path: Path, val reason: String) : SbxLaunchSpecInspection()
}

internal enum class SbxOpenCodeVersion {
    V1,
    V2,
    ;

    fun yamlValue(): String = if (this == V2) "2.x" else "1.x"

    fun prefersGuestV2(): Boolean = this == V2

    companion object {
        fun parse(value: String?): SbxOpenCodeVersion? = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "2", "2.x", "v2" -> V2
            "1", "1.x", "v1" -> V1
            else -> null
        }

        fun fromYaml(values: Map<String, String>): SbxOpenCodeVersion {
            parse(values["openCodeVersion"])?.let { return it }
            return if (values["installOpenCodeV2"]?.trim()?.lowercase(Locale.ROOT) == "true") V2 else V1
        }
    }
}

internal data class SbxLaunchSpec(
    val schemaVersion: Int = SCHEMA_VERSION,
    val canonicalDirectory: String,
    val name: String,
    val memory: String,
    val cpus: String,
    val kits: List<String>,
    val extraMounts: List<SbxExtraMount>,
    val shareHostOpencodeConfig: Boolean,
    val openCodeVersion: SbxOpenCodeVersion = SbxOpenCodeVersion.V1,
    val enableIntellijMcp: Boolean,
    val useSandbox: Boolean = true,
    val hostPort: Int? = null,
    val protectSandboxFiles: Boolean = true,
    val persistSandboxSessions: Boolean = true,
) {
    fun toYaml(): String {
        val out = StringBuilder()
        out.append("schemaVersion: ").append(schemaVersion).append('\n')
        out.append("useSandbox: ").append(useSandbox).append('\n')
        out.append("canonicalDirectory: ").append(yamlScalar(canonicalDirectory)).append('\n')
        out.append("name: ").append(yamlScalar(name)).append('\n')
        out.append("memory: ").append(yamlScalar(memory)).append('\n')
        out.append("cpus: ").append(yamlScalar(cpus)).append('\n')
        appendStringList(out, "kits", kits)
        if (extraMounts.isEmpty()) {
            out.append("extraMounts: []\n")
        } else {
            out.append("extraMounts:\n")
            extraMounts.forEach { mount ->
                out.append("  - host: ").append(yamlScalar(mount.hostPath)).append('\n')
                out.append("    sandbox: ").append(yamlScalar(mount.sandboxPath)).append('\n')
                if (mount.readOnly) out.append("    readOnly: true\n")
            }
        }
        out.append("shareHostOpencodeConfig: ").append(shareHostOpencodeConfig).append('\n')
        out.append("openCodeVersion: ").append(openCodeVersion.yamlValue()).append('\n')
        out.append("enableIntellijMcp: ").append(enableIntellijMcp).append('\n')
        out.append("protectSandboxFiles: ").append(protectSandboxFiles).append('\n')
        out.append("persistSandboxSessions: ").append(persistSandboxSessions).append('\n')
        val pinned = hostPort?.takeIf { it in 1..65535 }
        if (pinned != null) {
            out.append("hostPort: ").append(pinned).append('\n')
        }
        return out.toString()
    }

    /**
     * The sandbox `name` is derived from the directory and has no settings-UI field. Comparisons
     * against a stored spec (isModified, stop-decisions) must not flag or overwrite a hand-written
     * name: keep the stored one when it belongs to this directory.
     */
    fun adoptStoredName(stored: SbxLaunchSpec): SbxLaunchSpec {
        if (stored.canonicalDirectory != canonicalDirectory) return this
        return if (name == stored.name) this else copy(name = stored.name)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val CONFIG_DIR_ENV = "OCWP_CONFIG_DIR"

        /**
         * Defaults for a directory without a project spec. The application-level sandbox fields of
         * older releases have no settings UI any more, so they no longer decide a project's runtime
         * or seed its spec: a project without `opencode-sbx.yaml` runs the Host CLI.
         */
        fun fromSettings(
            settings: OpenCodeSettingsState,
            canonicalDirectory: String,
            hostPort: Int? = settings.hostPortOrNull(),
        ): SbxLaunchSpec {
            val directory = canonicalDirectory.trim()
            return SbxLaunchSpec(
                canonicalDirectory = directory,
                name = SbxCli.sandboxName(directory),
                memory = SbxCli.DEFAULT_MEMORY,
                cpus = SbxCli.DEFAULT_CPUS,
                kits = emptyList(),
                extraMounts = emptyList(),
                shareHostOpencodeConfig = false,
                openCodeVersion = SbxOpenCodeVersion.V1,
                enableIntellijMcp = true,
                useSandbox = false,
                hostPort = hostPort?.takeIf { it in 1..65535 },
            )
        }

        fun parseYaml(text: String): SbxLaunchSpec? = parseYamlResult(text).spec

        internal data class ParseResult(val spec: SbxLaunchSpec?, val error: String?)

        /**
         * Strict reader for the small YAML subset this plugin writes. Anything outside that
         * subset (merge markers, tabs, nested maps, unknown mount keys, non-boolean flags) makes
         * the spec invalid instead of being skipped; `opencode-sbx.sh` mirrors these rules.
         */
        internal fun parseYamlResult(text: String): ParseResult {
            fun invalid(line: Int, reason: String) = ParseResult(null, "line $line: $reason")
            val values = LinkedHashMap<String, String>()
            val seenKeys = HashSet<String>()
            val kits = ArrayList<String>()
            val mounts = ArrayList<LinkedHashMap<String, String>>()
            var section: String? = null
            var lineNumber = 0
            for (raw in text.lineSequence()) {
                lineNumber++
                val line = stripYamlComment(raw.removeSuffix("\r")).trimEnd()
                if (line.isBlank()) continue
                if ('\t' in line.takeWhile { it == ' ' || it == '\t' }) return invalid(lineNumber, "tabs are not allowed for indentation")
                val indent = line.length - line.trimStart().length
                val content = line.trimStart()
                if (indent == 0) {
                    val keyValue = TOP_LEVEL_KEY.matchEntire(content)
                        ?: return invalid(lineNumber, "expected \"key: value\"")
                    val key = keyValue.groupValues[1]
                    val value = keyValue.groupValues[2].trim()
                    if (!seenKeys.add(key)) return invalid(lineNumber, "duplicate key $key")
                    section = key
                    when (key) {
                        "kits" -> when {
                            value.isEmpty() -> Unit
                            value.startsWith("[") -> kits += parseFlowSequence(value)
                                ?.map { SbxCli.posixPath(it) }
                                ?: return invalid(lineNumber, "invalid kits list")
                            else -> return invalid(lineNumber, "kits must be a list")
                        }
                        "extraMounts" -> if (value.isNotEmpty() && value != "[]") {
                            return invalid(lineNumber, "extraMounts must be a block list of host/sandbox entries")
                        }
                        in LEGACY_LIST_KEYS -> Unit
                        else -> {
                            if (value == "|" || value == ">" || value.startsWith("{") || value.startsWith("[")) {
                                return invalid(lineNumber, "$key must be a plain value")
                            }
                            values[key] = unquote(value)
                        }
                    }
                    continue
                }
                when (section) {
                    null -> return invalid(lineNumber, "indented line outside a list")
                    "kits" -> {
                        val item = LIST_ITEM.matchEntire(content)
                            ?: return invalid(lineNumber, "expected \"- kit\"")
                        val ref = unquote(item.groupValues[1].trim())
                        if (ref.isNotBlank()) kits += SbxCli.posixPath(ref)
                    }
                    "extraMounts" -> {
                        val start = MOUNT_ITEM_START.matchEntire(content)
                        val entry = start ?: MOUNT_ITEM_KEY.matchEntire(content)
                            ?: return invalid(lineNumber, "expected host, sandbox or readOnly")
                        if (start != null) {
                            mounts += LinkedHashMap()
                        } else if (mounts.isEmpty()) {
                            return invalid(lineNumber, "mount entries start with \"- host:\"")
                        }
                        val current = mounts.last()
                        val key = entry.groupValues[1]
                        if (key in current) return invalid(lineNumber, "duplicate $key in mount")
                        current[key] = unquote(entry.groupValues[2].trim())
                    }
                    in LEGACY_LIST_KEYS -> Unit
                    else -> if (section in KNOWN_SCALAR_KEYS) {
                        return invalid(lineNumber, "$section must be a plain value")
                    }
                }
            }
            val directory = values["canonicalDirectory"]?.trim().orEmpty()
            if (directory.isEmpty()) return ParseResult(null, "canonicalDirectory is required")
            val schema = values["schemaVersion"]?.let { it.trim().toIntOrNull() ?: -1 } ?: SCHEMA_VERSION
            if (schema != SCHEMA_VERSION) return ParseResult(null, "unsupported schemaVersion ${values["schemaVersion"]}")
            val name = values["name"]?.trim()?.ifBlank { null } ?: SbxCli.sandboxName(directory)
            // The name is joined into host paths (machine spec, persist, 2.x binary). Never trust it.
            if (!SbxCli.isValidSandboxName(name)) return ParseResult(null, "invalid sandbox name $name")
            fun flag(key: String, default: Boolean): Boolean? {
                val raw = values[key]?.trim() ?: return default
                return when (raw.lowercase(Locale.ROOT)) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            val flags = BOOLEAN_KEYS.associateWith { (key, default) ->
                flag(key, default) ?: return ParseResult(null, "$key must be true or false")
            }.mapKeys { it.key.first }
            val memory = values["memory"]?.let { SbxCli.parseMemory(it) ?: return ParseResult(null, "memory must look like 4g or 512m") }
                ?: SbxCli.DEFAULT_MEMORY
            val cpus = values["cpus"]?.let { SbxCli.parseCpus(it) ?: return ParseResult(null, "cpus must be 1 to 32") }
                ?: SbxCli.DEFAULT_CPUS
            val version = when {
                values["openCodeVersion"] != null -> SbxOpenCodeVersion.parse(values["openCodeVersion"])
                    ?: return ParseResult(null, "openCodeVersion must be 1.x or 2.x")
                else -> SbxOpenCodeVersion.fromYaml(values)
            }
            val hostPort = values["hostPort"]?.trim()?.ifBlank { null }?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: return ParseResult(null, "hostPort must be 1 to 65535")
            }
            val extraMounts = ArrayList<SbxExtraMount>()
            for (mount in mounts) {
                val rawHost = mount["host"]?.trim().orEmpty()
                if (rawHost.isEmpty()) return ParseResult(null, "every extraMounts entry needs a host")
                val suffixReadOnly = rawHost.endsWith(":ro", ignoreCase = true) && rawHost.length > 3
                val host = SbxCli.posixPath(if (suffixReadOnly) rawHost.dropLast(3) else rawHost)
                val readOnly = when (mount["readOnly"]?.trim()?.lowercase(Locale.ROOT)) {
                    null -> suffixReadOnly
                    "true" -> true
                    "false" -> suffixReadOnly
                    else -> return ParseResult(null, "readOnly must be true or false")
                }
                val sandbox = SbxCli.posixPath(mount["sandbox"]?.trim()?.ifBlank { null } ?: host)
                extraMounts += SbxExtraMount(host, sandbox, readOnly)
            }
            return ParseResult(
                SbxLaunchSpec(
                    schemaVersion = schema,
                    canonicalDirectory = directory,
                    name = name,
                    memory = memory,
                    cpus = cpus,
                    kits = kits.filter { it.isNotBlank() },
                    extraMounts = extraMounts,
                    shareHostOpencodeConfig = flags.getValue("shareHostOpencodeConfig"),
                    openCodeVersion = version,
                    enableIntellijMcp = flags.getValue("enableIntellijMcp"),
                    useSandbox = flags.getValue("useSandbox"),
                    hostPort = hostPort,
                    protectSandboxFiles = flags.getValue("protectSandboxFiles"),
                    persistSandboxSessions = flags.getValue("persistSandboxSessions"),
                ),
                null,
            )
        }

        private val TOP_LEVEL_KEY = Regex("^([A-Za-z][A-Za-z0-9_]*):(?:\\s+(.*)|)$")
        private val LIST_ITEM = Regex("^-\\s+(.*)$")
        private val MOUNT_ITEM_START = Regex("^-\\s+(host|sandbox|readOnly):(?:\\s+(.*)|)$")
        private val MOUNT_ITEM_KEY = Regex("^(host|sandbox|readOnly):(?:\\s+(.*)|)$")
        private val LEGACY_LIST_KEYS = setOf("setupCommands", "networkAllows", "networkAllowPresets", "extraNetworkAllows")
        private val BOOLEAN_KEYS = listOf(
            "shareHostOpencodeConfig" to false,
            "enableIntellijMcp" to true,
            "useSandbox" to true,
            "protectSandboxFiles" to true,
            "persistSandboxSessions" to true,
            "installOpenCodeV2" to false,
        )
        private val KNOWN_SCALAR_KEYS = setOf(
            "schemaVersion", "canonicalDirectory", "name", "memory", "cpus", "openCodeVersion", "hostPort",
        ) + BOOLEAN_KEYS.map { it.first }

        /** Keys [toYaml] owns; any other top-level block in a hand-edited spec is kept as written. */
        private val WRITTEN_KEYS = KNOWN_SCALAR_KEYS + setOf("kits", "extraMounts")

        private data class YamlBlock(val key: String?, val lines: List<String>)

        private fun yamlBlocks(text: String): List<YamlBlock> {
            val blocks = ArrayList<YamlBlock>()
            var key: String? = null
            var lines = ArrayList<String>()
            for (line in text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }) {
                val top = TOP_LEVEL_KEY.matchEntire(stripYamlComment(line).trimEnd())
                if (top != null && !line.startsWith(" ") && !line.startsWith("-")) {
                    if (key != null || lines.isNotEmpty()) blocks += YamlBlock(key, lines)
                    key = top.groupValues[1]
                    lines = arrayListOf(line)
                } else {
                    lines += line
                }
            }
            if (key != null || lines.isNotEmpty()) blocks += YamlBlock(key, lines)
            return blocks
        }

        private fun normalizedBlock(lines: List<String>): List<String> = lines
            .map { stripYamlComment(it).trimEnd() }
            .filter { it.isNotBlank() }
            .map { line ->
                val scalar = TOP_LEVEL_KEY.matchEntire(line)
                if (scalar != null && scalar.groupValues[2].isNotBlank()) {
                    "${scalar.groupValues[1]}: ${unquote(scalar.groupValues[2].trim())}"
                } else {
                    val item = LIST_ITEM.matchEntire(line.trim())
                    if (item != null) "- ${unquote(item.groupValues[1].trim())}" else line.trim()
                }
            }

        /**
         * Applies [generated] (from [toYaml]) to a spec someone may have edited by hand: comments,
         * key order, unknown keys and unchanged values keep their original text; changed keys are
         * replaced in place, removed ones dropped, new ones appended.
         */
        internal fun mergeYaml(existing: String, generated: String): String {
            val next = yamlBlocks(generated).filter { it.key != null }.associateBy { it.key!! }
            val out = ArrayList<String>()
            val written = HashSet<String>()
            for (block in yamlBlocks(existing)) {
                val key = block.key
                if (key == null || key !in WRITTEN_KEYS) {
                    out += block.lines
                    continue
                }
                val replacement = next[key]
                // Trailing comment or blank lines usually introduce the next key; keep them.
                val trailing = block.lines.takeLastWhile { it.isBlank() || it.trimStart().startsWith("#") }
                val body = block.lines.dropLast(trailing.size)
                if (replacement != null && written.add(key)) {
                    out += if (normalizedBlock(body) == normalizedBlock(replacement.lines)) body else replacement.lines
                }
                out += trailing
            }
            next.values.filter { it.key !in written }.forEach { out += it.lines }
            return out.joinToString("\n").trimEnd('\n') + "\n"
        }

        fun inspect(canonicalDirectory: String?): SbxLaunchSpecInspection {
            val directory = OpenCodeServerProtocol.canonicalOpenCodeDirectory(canonicalDirectory)
                ?: canonicalDirectory?.trim()?.takeIf { it.isNotBlank() }
                ?: return SbxLaunchSpecInspection.Missing
            val path = projectSpecCandidates(directory).firstOrNull { Files.isRegularFile(it) }
                ?: return SbxLaunchSpecInspection.Missing
            // Backend selection and settings read the spec on every call (some on the EDT):
            // reuse the parse while the file is unchanged.
            val stamp = runCatching {
                Files.getLastModifiedTime(path).toMillis() to Files.size(path)
            }.getOrNull()
            if (stamp != null) {
                inspectionCache[path]?.takeIf { it.directory == directory && it.stamp == stamp }?.let { return it.result }
            }
            val result = inspectUncached(directory, path)
            if (stamp != null) inspectionCache[path] = CachedInspection(directory, stamp, result)
            return result
        }

        private data class CachedInspection(
            val directory: String,
            val stamp: Pair<Long, Long>,
            val result: SbxLaunchSpecInspection,
        )

        private val inspectionCache = java.util.concurrent.ConcurrentHashMap<Path, CachedInspection>()

        private fun inspectUncached(directory: String, path: Path): SbxLaunchSpecInspection {
            return runCatching {
                val parsed = parseYamlResult(Files.readString(path))
                val spec = parsed.spec
                    ?: return SbxLaunchSpecInspection.Invalid(path, parsed.error ?: "Unsupported or incomplete sandbox spec.")
                val stored = Path.of(SbxCli.posixPath(spec.canonicalDirectory))
                val resolved = OpenCodeServerProtocol.canonicalOpenCodeDirectory(
                    workspaceBaseForSpec(path).resolve(stored).toString(),
                ) ?: return SbxLaunchSpecInspection.Invalid(path, "canonicalDirectory does not resolve.")
                SbxLaunchSpecInspection.Valid(
                    spec.copy(
                        canonicalDirectory = resolved,
                        name = if (stored.isAbsolute) spec.name else SbxCli.sandboxName(resolved),
                    ),
                    path,
                )
            }.getOrElse { error ->
                SbxLaunchSpecInspection.Invalid(path, error.message ?: "Could not read sandbox spec.")
            }
        }

        fun load(canonicalDirectory: String?): SbxLaunchSpec? {
            return (inspect(canonicalDirectory) as? SbxLaunchSpecInspection.Valid)?.spec
        }

        fun usesSandbox(canonicalDirectory: String?): Boolean {
            return when (val result = inspect(canonicalDirectory)) {
                is SbxLaunchSpecInspection.Valid -> result.spec.useSandbox
                is SbxLaunchSpecInspection.Invalid -> true
                SbxLaunchSpecInspection.Missing -> false
            }
        }

        fun portArgument(canonicalDirectory: String?, fallback: String): String {
            return when (val result = inspect(canonicalDirectory)) {
                is SbxLaunchSpecInspection.Valid ->
                    result.spec.hostPort?.takeIf { it in 1..65535 }?.toString()
                        ?: OpenCodeServerProtocol.DYNAMIC_PORT
                is SbxLaunchSpecInspection.Invalid, SbxLaunchSpecInspection.Missing -> fallback
            }
        }

        fun configDir(): Path {
            val override = System.getenv(CONFIG_DIR_ENV)?.trim()?.ifBlank { null }
            if (override != null) return Path.of(override)
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return if (os.contains("win")) {
                val appData = System.getenv("APPDATA")?.trim()?.ifBlank { null }
                    ?: System.getProperty("user.home")
                Path.of(appData, "opencode-web-panel")
            } else {
                Path.of(System.getProperty("user.home"), ".config", "opencode-web-panel")
            }
        }

        const val PROJECT_SPEC_NAME = "opencode-sbx.yaml"
        const val LEGACY_PROJECT_SPEC_NAME = "opencode-web-panel.sbx.yaml"
        val LEGACY_PROJECT_SPEC_NAMES = listOf("opencode.sbx.yaml", LEGACY_PROJECT_SPEC_NAME)
        const val PROJECT_LAUNCHER_UNIX = "opencode-sbx.sh"
        const val PROJECT_LAUNCHER_WINDOWS = "opencode-sbx.cmd"

        fun specPath(name: String): Path {
            require(SbxCli.isValidSandboxName(name)) { "Invalid sandbox name: $name" }
            return configDir().resolve("sbx").resolve("$name.yaml")
        }

        fun projectControlDir(canonicalDirectory: String): Path =
            Path.of(canonicalDirectory, SbxCli.PROJECT_CONTROL_DIR)

        fun projectSpecPath(canonicalDirectory: String): Path =
            projectControlDir(canonicalDirectory).resolve(PROJECT_SPEC_NAME)

        fun projectSpecCandidates(canonicalDirectory: String): List<Path> {
            val root = Path.of(canonicalDirectory)
            return listOf(projectSpecPath(canonicalDirectory)) +
                (listOf(PROJECT_SPEC_NAME) + LEGACY_PROJECT_SPEC_NAMES).map { root.resolve(it) }
        }

        fun workspaceBaseForSpec(specPath: Path): Path {
            val parent = specPath.toAbsolutePath().normalize().parent ?: return specPath
            val nested = parent.fileName?.toString() == SbxCli.PROJECT_CONTROL_DIR &&
                specPath.fileName.toString() == PROJECT_SPEC_NAME
            return if (nested) parent.parent ?: parent else parent
        }

        fun persist(settings: OpenCodeSettingsState, canonicalDirectory: String, hostPort: Int? = null): Path? {
            val directory = OpenCodeServerProtocol.canonicalOpenCodeDirectory(canonicalDirectory)
                ?: canonicalDirectory.trim().takeIf { it.isNotBlank() }
                ?: return null
            val existing = load(directory)
            // The project spec owns every sandbox option. App defaults only seed a new project.
            if (existing == null && projectSpecCandidates(directory).any { Files.exists(it) }) return null
            val spec = existing ?: fromSettings(settings, directory, hostPort = hostPort ?: settings.hostPortOrNull())
            return persist(spec, writeProjectSpec = !Files.exists(projectSpecPath(directory)))
        }

        fun persist(spec: SbxLaunchSpec): Path? = persist(spec, writeProjectSpec = true)

        private fun persist(spec: SbxLaunchSpec, writeProjectSpec: Boolean): Path? {
            val directory = OpenCodeServerProtocol.canonicalOpenCodeDirectory(spec.canonicalDirectory)
                ?: spec.canonicalDirectory.trim().takeIf { it.isNotBlank() }
                ?: return null
            val named = spec.copy(
                canonicalDirectory = directory,
                name = spec.name.ifBlank { SbxCli.sandboxName(directory) },
            )
            return runCatching {
                val projectPath = projectSpecPath(directory)
                Files.createDirectories(projectPath.parent)
                installLaunchers(projectControlDir(directory))
                if (writeProjectSpec) {
                    inspectionCache.clear()
                    val generated = named.copy(canonicalDirectory = "./").toYaml()
                    val existing = runCatching { Files.readString(projectPath) }.getOrNull()
                    val merged = if (existing != null && parseYaml(existing) != null) mergeYaml(existing, generated) else generated
                    // Never let comment preservation change what the file means.
                    val yaml = if (parseYaml(merged) == parseYaml(generated)) merged else generated
                    val tmp = projectPath.resolveSibling("${projectPath.fileName}.tmp")
                    Files.writeString(tmp, yaml, StandardCharsets.UTF_8)
                    runCatching {
                        Files.move(
                            tmp,
                            projectPath,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.getOrElse {
                        Files.move(tmp, projectPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    inspectionCache.clear()
                }
                removeLegacyProjectArtifacts(Path.of(directory))
                val app = com.intellij.openapi.application.ApplicationManager.getApplication()
                if (app != null && !app.isDisposed && !app.isUnitTestMode) {
                    runCatching {
                        val machinePath = specPath(named.name)
                        Files.createDirectories(machinePath.parent)
                        Files.writeString(machinePath, named.toYaml(), StandardCharsets.UTF_8)
                        installLaunchers(configDir().resolve("bin"))
                    }.onFailure { error ->
                        logger<SbxLaunchSpec>().warn("Could not persist machine SBX launch copy: ${error.message}")
                    }
                }
                projectPath
            }.onFailure { error ->
                logger<SbxLaunchSpec>().warn("Could not persist SBX launch spec: ${error.message}")
            }.getOrNull()
        }

        fun yamlScalar(value: String): String {
            if (value.isEmpty()) return "\"\""
            val needsQuotes = value.any { it.isWhitespace() || it in ":#{}[]&*?|>!%@`'\"," } ||
                value.startsWith("-") ||
                value == "true" ||
                value == "false" ||
                value == "null"
            if (!needsQuotes) return value
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        fun unquote(value: String): String {
            val trimmed = value.trim()
            if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
                return trimmed.substring(1, trimmed.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            if (trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'')) {
                return trimmed.substring(1, trimmed.length - 1).replace("''", "'")
            }
            return trimmed
        }

        private fun stripYamlComment(line: String): String {
            var quote: Char? = null
            var escaped = false
            var index = 0
            while (index < line.length) {
                val char = line[index]
                if (escaped) {
                    escaped = false
                } else if (quote == '"' && char == '\\') {
                    escaped = true
                } else if (quote == '\'' && char == '\'' && index + 1 < line.length && line[index + 1] == '\'') {
                    index++
                } else if (quote != null) {
                    if (char == quote) quote = null
                } else if ((char == '\'' || char == '"') && (index == 0 || line[index - 1].isWhitespace() || line[index - 1] == ':')) {
                    quote = char
                } else if (char == '#' && (index == 0 || line[index - 1].isWhitespace())) {
                    return line.take(index)
                }
                index++
            }
            return line
        }

        private fun parseFlowSequence(value: String): List<String>? {
            val trimmed = value.trim()
            if (trimmed == "[]") return emptyList()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
            val inner = trimmed.substring(1, trimmed.length - 1)
            val items = ArrayList<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var escaped = false
            var index = 0
            while (index < inner.length) {
                val char = inner[index]
                if (escaped) {
                    current.append(char)
                    escaped = false
                } else if (quote == '"' && char == '\\') {
                    current.append(char)
                    escaped = true
                } else if (quote == '\'' && char == '\'' && index + 1 < inner.length && inner[index + 1] == '\'') {
                    current.append("''")
                    index++
                } else if (quote != null) {
                    current.append(char)
                    if (char == quote) quote = null
                } else if (char == '\'' || char == '"') {
                    quote = char
                    current.append(char)
                } else if (char == ',') {
                    val item = unquote(current.toString().trim())
                    if (item.isNotBlank()) items += item
                    current.clear()
                } else {
                    current.append(char)
                }
                index++
            }
            if (quote != null) return null
            val last = unquote(current.toString().trim())
            if (last.isNotBlank()) items += last
            return items
        }

        private fun appendStringList(out: StringBuilder, key: String, values: List<String>) {
            if (values.isEmpty()) {
                out.append(key).append(": []\n")
                return
            }
            out.append(key).append(":\n")
            values.forEach { value ->
                out.append("  - ").append(yamlScalar(value)).append('\n')
            }
        }

        private fun removeLegacyProjectArtifacts(directory: Path) {
            val names = listOf(PROJECT_SPEC_NAME, PROJECT_LAUNCHER_UNIX, PROJECT_LAUNCHER_WINDOWS) +
                LEGACY_PROJECT_SPEC_NAMES
            names.forEach { name ->
                val path = directory.resolve(name)
                if (Files.isRegularFile(path)) Files.deleteIfExists(path)
            }
        }

        private fun installLaunchers(directory: Path) {
            Files.createDirectories(directory)
            copyResource("opencode-sbx.sh", directory.resolve(PROJECT_LAUNCHER_UNIX), executable = true)
            // Git for Windows checks out CRLF by default; Git Bash then fails on `set -o pipefail\r`.
            Files.writeString(directory.resolve(".gitattributes"), "*.sh text eol=lf\n", StandardCharsets.UTF_8)
            Files.deleteIfExists(directory.resolve(PROJECT_LAUNCHER_WINDOWS))
            // Older releases shipped these guest scripts beside the launcher.
            Files.deleteIfExists(directory.resolve("opencode-sbx-install-v2.sh"))
            Files.deleteIfExists(directory.resolve("opencode-sbx-version-v2.sh"))
            Files.deleteIfExists(directory.resolve("opencode-sbx-config-v2.json"))
        }

        private fun copyResource(name: String, target: Path, executable: Boolean) {
            val stream = checkNotNull(SbxLaunchSpec::class.java.getResourceAsStream(name)) { "Missing sandbox script: $name" }
            stream.use { input ->
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            if (executable) {
                runCatching {
                    target.toFile().setExecutable(true, false)
                }
            }
        }
    }
}
