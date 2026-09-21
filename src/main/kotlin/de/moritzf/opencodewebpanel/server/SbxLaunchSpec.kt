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
            return if (values["installOpenCodeV2"]?.toBooleanStrictOrNull() == true) V2 else V1
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

        fun fromSettings(
            settings: OpenCodeSettingsState,
            canonicalDirectory: String,
            hostPort: Int? = settings.hostPortOrNull(),
        ): SbxLaunchSpec {
            val directory = canonicalDirectory.trim()
            return SbxLaunchSpec(
                canonicalDirectory = directory,
                name = SbxCli.sandboxName(directory),
                memory = settings.sbxMemoryValue(),
                cpus = settings.sbxCpusValue(),
                kits = SbxCli.parseLineList(settings.sbxExtraKits),
                extraMounts = SbxCli.parseExtraMountRows(settings.sbxExtraWorkspaces)
                    .map { SbxExtraMount(it.first, it.second) },
                shareHostOpencodeConfig = settings.sbxShareHostOpencodeConfig,
                openCodeVersion = SbxOpenCodeVersion.V1,
                enableIntellijMcp = settings.sbxEnableIntellijMcp,
                useSandbox = settings.runtimeModeValue() ==
                    de.moritzf.opencodewebpanel.settings.OpenCodeRuntimeMode.DOCKER_SANDBOX,
                hostPort = hostPort?.takeIf { it in 1..65535 },
            )
        }

        fun parseYaml(text: String): SbxLaunchSpec? {
            val values = LinkedHashMap<String, String>()
            val lists = LinkedHashMap<String, MutableList<String>>()
            val mounts = ArrayList<SbxExtraMount>()
            var listKey: String? = null
            var pendingHost: String? = null
            for (raw in text.lineSequence()) {
                val line = stripYamlComment(raw).trimEnd()
                if (line.isBlank()) continue
                val mountHost = Regex("^\\s*-\\s*host:\\s*(.*)$").matchEntire(line)
                if (mountHost != null) {
                    pendingHost = unquote(mountHost.groupValues[1].trim())
                    listKey = "extraMounts"
                    continue
                }
                val mountSandbox = Regex("^\\s*sandbox:\\s*(.*)$").matchEntire(line)
                if (mountSandbox != null && pendingHost != null) {
                    val sandbox = unquote(mountSandbox.groupValues[1].trim())
                    if (pendingHost.isNotBlank()) {
                        mounts += SbxExtraMount(
                            SbxCli.posixPath(pendingHost),
                            SbxCli.posixPath(sandbox.ifBlank { pendingHost }),
                        )
                    }
                    pendingHost = null
                    continue
                }
                val listItem = Regex("^\\s*-\\s+(.*)$").matchEntire(line)
                if (listItem != null && listKey != null && listKey != "extraMounts") {
                    val item = unquote(listItem.groupValues[1].trim())
                    if (item.isNotBlank()) {
                        val stored = if (listKey == "kits") SbxCli.posixPath(item) else item
                        lists.getOrPut(listKey) { ArrayList() }.add(stored)
                    }
                    continue
                }
                val keyValue = Regex("^([A-Za-z][A-Za-z0-9_]*):\\s*(.*)$").matchEntire(line.trim())
                    ?: continue
                val key = keyValue.groupValues[1]
                val value = keyValue.groupValues[2].trim()
                listKey = key
                pendingHost = null
                if (value.isEmpty() || value == "|" || value == ">") {
                    lists.getOrPut(key) { ArrayList() }
                    continue
                }
                if (value == "[]") {
                    lists[key] = ArrayList()
                    continue
                }
                if (value.startsWith("[")) {
                    val items = parseFlowSequence(value) ?: return null
                    lists[key] = items.map { item ->
                        if (key == "kits") SbxCli.posixPath(item) else item
                    }.toMutableList()
                    continue
                }
                values[key] = unquote(value)
            }
            val directory = values["canonicalDirectory"]?.trim().orEmpty()
            if (directory.isEmpty()) return null
            val schema = values["schemaVersion"]?.toIntOrNull() ?: SCHEMA_VERSION
            if (schema != SCHEMA_VERSION) return null
            val name = values["name"]?.trim()?.ifBlank { null } ?: SbxCli.sandboxName(directory)
            return SbxLaunchSpec(
                schemaVersion = schema,
                canonicalDirectory = directory,
                name = name,
                memory = SbxCli.sanitizeMemory(values["memory"]),
                cpus = SbxCli.sanitizeCpus(values["cpus"]),
                kits = lists["kits"].orEmpty(),
                extraMounts = mounts,
                shareHostOpencodeConfig = values["shareHostOpencodeConfig"]?.toBooleanStrictOrNull() ?: false,
                openCodeVersion = SbxOpenCodeVersion.fromYaml(values),
                enableIntellijMcp = values["enableIntellijMcp"]?.toBooleanStrictOrNull() ?: true,
                useSandbox = values["useSandbox"]?.toBooleanStrictOrNull() ?: true,
                hostPort = values["hostPort"]?.toIntOrNull()?.takeIf { it in 1..65535 },
                protectSandboxFiles = values["protectSandboxFiles"]?.toBooleanStrictOrNull() ?: true,
                persistSandboxSessions = values["persistSandboxSessions"]?.toBooleanStrictOrNull() ?: true,
            )
        }

        fun inspect(canonicalDirectory: String?): SbxLaunchSpecInspection {
            val directory = OpenCodeServerProtocol.canonicalOpenCodeDirectory(canonicalDirectory)
                ?: canonicalDirectory?.trim()?.takeIf { it.isNotBlank() }
                ?: return SbxLaunchSpecInspection.Missing
            val path = projectSpecCandidates(directory).firstOrNull { Files.isRegularFile(it) }
                ?: return SbxLaunchSpecInspection.Missing
            return runCatching {
                val spec = parseYaml(Files.readString(path))
                    ?: return SbxLaunchSpecInspection.Invalid(path, "Unsupported or incomplete sandbox spec.")
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
                SbxLaunchSpecInspection.Missing -> OpenCodeSettingsState.getInstance().runtimeModeValue() ==
                    de.moritzf.opencodewebpanel.settings.OpenCodeRuntimeMode.DOCKER_SANDBOX
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

        fun requiresVmRecreation(old: SbxLaunchSpec, new: SbxLaunchSpec): Boolean {
            if (old.shareHostOpencodeConfig != new.shareHostOpencodeConfig) return true
            if (new.kits.size < old.kits.size || new.kits.take(old.kits.size) != old.kits) return true
            return new.extraMounts.any { extra ->
                old.extraMounts.none { OpenCodeServerProtocol.isSameFilesystemPath(it.hostPath, extra.hostPath) }
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

        fun specPath(name: String): Path = configDir().resolve("sbx").resolve("$name.yaml")

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
                    val yaml = named.copy(canonicalDirectory = "./").toYaml()
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
