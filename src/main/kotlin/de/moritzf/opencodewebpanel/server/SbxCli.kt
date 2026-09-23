package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.io.FileUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class SbxExtraMount @JvmOverloads constructor(
    val hostPath: String,
    val sandboxPath: String,
    val readOnly: Boolean = false,
)

internal data class SbxPortMapping(
    val hostIp: String,
    val hostPort: Int,
    val sandboxPort: Int,
    val protocol: String,
)

internal data class SbxTemplateImage(
    val id: String,
    val repository: String,
    val tag: String,
    val flavor: String,
) {
    fun ref(): String {
        val repo = repository.trim()
        val tagged = tag.trim()
        if (repo.isNotBlank() && tagged.isNotBlank()) return "$repo:$tagged"
        return id.trim()
    }
}

internal data class SbxSandboxListEntry(
    val name: String,
    val id: String,
    val agent: String,
    val status: String,
    val ports: List<SbxPortMapping>,
    val workspaces: List<String>,
)

internal data class SbxSandboxRecord(
    val sandboxId: String,
    val name: String,
    val agent: String,
    val workspace: String,
    val kits: String = "",
    val shareHostConfig: Boolean = false,
    val hostPort: Int? = null,
    val createSnapshot: String = "",
    val adopted: Boolean = false,
) {
    fun matches(entry: SbxSandboxListEntry): Boolean {
        return sandboxId == entry.id
    }
}

/**
 * Argv builders and JSON parsers for the Docker Sandboxes (`sbx`) CLI. Never `shell=true`.
 * Password and config overlay travel as ProcessBuilder env with bare `-e KEY` only.
 */
internal object SbxCli {
    const val DEFAULT_EXECUTABLE = "sbx"
    const val AGENT = "opencode"
    const val DEFAULT_MEMORY = "4g"
    const val DEFAULT_CPUS = "2"
    const val NAME_PREFIX = "ide-ocwp-"
    const val OPENCODE_SERVER_PASSWORD_ENV = "OPENCODE_SERVER_PASSWORD"
    const val OPENCODE_CONFIG_CONTENT_ENV = "OPENCODE_CONFIG_CONTENT"
    const val OPENCODE_AUTH_CONTENT_ENV = "OPENCODE_AUTH_CONTENT"
    const val DATA_DIR_ENV = "OCWP_DATA_DIR"
    const val SANDBOX_HOME = "/home/agent"
    const val DEFAULT_OPENCODE_TEMPLATE = "docker.io/docker/sandbox-templates:opencode-docker"

    const val PROJECT_CONTROL_DIR = "opencode-sbx"
    const val NETWORK_KIT_DIR = "opencode-network-kit"

    val NETWORK_KIT_HOST_PRESETS = listOf(
        "OpenAI" to "api.openai.com",
        "Anthropic" to "api.anthropic.com",
        "Google Gemini" to "generativelanguage.googleapis.com",
        "xAI" to "api.x.ai",
        "Groq" to "api.groq.com",
        "Mistral" to "api.mistral.ai",
        "OpenRouter" to "openrouter.ai",
        "OpenCode Zen" to "opencode.ai",
        "OpenCode models" to "models.opencode.ai",
        "GitHub" to "api.github.com",
        "GitHub Models" to "models.github.ai",
        "DeepSeek" to "api.deepseek.com",
        "Together" to "api.together.xyz",
        "Fireworks" to "api.fireworks.ai",
        "Ollama Cloud" to "ollama.com",
        "npm registry" to "registry.npmjs.org",
    )
    private val SERVE_FLAGS = listOf(
        "serve",
        "--hostname",
        OpenCodeServerProtocol.SANDBOX_SERVE_HOST,
        "--port",
        OpenCodeServerProtocol.SANDBOX_SERVE_PORT.toString(),
        "--print-logs",
    )
    /**
     * Kit PATH still has 1.x `opencode`. When [SbxOpenCodeVersion.V2] is selected,
     * require the validated 2.x at `$HOME/.opencode/bin`. `$0` is dummy;
     * remaining argv are forwarded after `exec`. 1.x launches kit `opencode` even if
     * that 2.x binary exists.
     */
    const val GUEST_OPENCODE_DISPATCH =
        $$"""exec "$HOME/.opencode/bin/opencode" "$@""""
    const val V2_INSTALL_URL = "https://opencode.ai/v2/install"
    // Read the same quoted heredocs the standalone launcher passes to guest sh -c.
    private val launcherScript: String by lazy { guestResource("opencode-sbx.sh").replace("\r\n", "\n") }
    val V2_INSTALL_SCRIPT: String by lazy { guestScript("OCWP_V2_INSTALL") }
    val GUEST_V2_VERSION_SCRIPT: String by lazy { guestScript("OCWP_V2_VERSION") }

    private fun guestScript(delimiter: String): String {
        val script = launcherScript.substringAfter("<<'$delimiter'\n", "")
            .substringBefore("\n$delimiter\n", "").trimEnd()
        check(script.isNotBlank()) { "Missing sandbox script block: $delimiter" }
        return script
    }

    private fun guestResource(resource: String): String =
        checkNotNull(SbxCli::class.java.getResourceAsStream(resource)) { "Missing sandbox resource: $resource" }
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText().trimEnd() }
    private const val SERVE_PKILL_PATTERN =
        $$"[o]pencode serve --hostname $${OpenCodeServerProtocol.SANDBOX_SERVE_HOST} " +
            $$"--port $${OpenCodeServerProtocol.SANDBOX_SERVE_PORT} --print-logs"

    fun sandboxIdentityPath(
        canonicalDirectory: String,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): String {
        val posix = posixPath(canonicalDirectory)
        if (!osName.lowercase(java.util.Locale.ROOT).contains("win")) return canonicalDirectory
        val msys = Regex("^/([A-Za-z])(/.*)?$").matchEntire(posix)
        if (msys != null) {
            return msys.groupValues[1].uppercase(java.util.Locale.ROOT) + ":" + msys.groupValues.getOrElse(2) { "" }
        }
        if (posix.length >= 2 && posix[1] == ':') {
            return posix[0].uppercaseChar() + posix.substring(1)
        }
        return posix
    }

    fun sandboxName(
        canonicalDirectory: String,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sandboxIdentityPath(canonicalDirectory, osName).toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { byte -> "%02x".format(byte) }.take(12)
        return NAME_PREFIX + hex
    }

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

    /** Names are joined into host paths; keep this in sync with the launcher's `valid_sandbox_name`. */
    fun isValidSandboxName(name: String): Boolean {
        if (name.equals("default", ignoreCase = true)) return false
        if (name.length < 2) return false
        if (!name[0].isAsciiLetterOrDigit()) return false
        return name.all { it.isAsciiLetterOrDigit() || it == '.' || it == '-' }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun requireValidSandboxName(name: String): String {
        require(isValidSandboxName(name)) { "Invalid sandbox name: $name" }
        return name
    }

    fun buildCreateCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        workspace: String,
        memory: String = DEFAULT_MEMORY,
        cpus: String = DEFAULT_CPUS,
        inVmPort: Int = OpenCodeServerProtocol.SANDBOX_SERVE_PORT,
        hostPort: Int? = null,
        extraWorkspaces: List<String> = emptyList(),
        kits: List<String> = emptyList(),
    ): List<String> {
        val command = mutableListOf(
            executable,
            "create",
            "-q",
            "--name",
            name,
            "--memory",
            memory,
            "--cpus",
            cpus,
            "--publish",
            publishSpec(hostPort, inVmPort),
        )
        kits.filter { it.isNotBlank() }.forEach { ref ->
            command += listOf("--kit", ref)
        }
        command += listOf(AGENT, workspace)
        command += extraWorkspaces.filter { it.isNotBlank() && it != workspace }
        return command
    }

    fun normalizeLineList(text: String?): String {
        return parseLineList(text).joinToString("\n")
    }

    fun parseKitRefs(
        text: String?,
        home: String = System.getProperty("user.home").orEmpty(),
    ): List<String> {
        return parseLineList(text).map { raw ->
            posixPath(if (raw.startsWith("~")) expandUserHome(raw, home) else raw)
        }.distinct()
    }

    fun posixPath(path: String): String {
        return FileUtil.toSystemIndependentName(path.trim())
    }

    /** In-guest spelling of a host bind. sbx mounts workspaces at the host path; Windows drives become `/c/...`. */
    fun guestBindPath(hostPath: String): String {
        val posix = posixPath(hostPath)
        if (posix.length >= 3 && posix[0].isLetter() && posix[1] == ':' && posix[2] == '/') {
            return "/${posix[0].lowercaseChar()}${posix.substring(2)}"
        }
        return posix
    }

    /**
     * Guest path prefix → host path prefix, longest guest first.
     * Extra mounts may alias `/home/agent/docs` onto a different host folder;
     * Windows primary workspaces appear as `/c/Users/...` in the VM.
     */
    fun guestToHostPathMappings(
        primaryWorkspace: String,
        extraMounts: List<SbxExtraMount> = emptyList(),
        persistHostPath: String? = null,
    ): List<Pair<String, String>> {
        val maps = LinkedHashMap<String, String>()
        fun add(guest: String, host: String) {
            val prefix = posixPath(guest).trimEnd('/')
            val mapped = posixPath(host).trimEnd('/')
            if (prefix.isBlank() || mapped.isBlank() || prefix == mapped) return
            maps.putIfAbsent(prefix, mapped)
        }
        add(guestBindPath(primaryWorkspace), primaryWorkspace)
        extraMounts.forEach { mount ->
            add(mount.sandboxPath, mount.hostPath)
            add(guestBindPath(mount.hostPath), mount.hostPath)
        }
        persistHostPath?.trim()?.takeIf { it.isNotBlank() }?.let { host ->
            add(persistSandboxGuestPath(), host)
            add(guestBindPath(host), host)
        }
        return maps.entries
            .map { it.key to it.value }
            .sortedByDescending { it.first.length }
    }

    fun workspaceHostPath(arg: String): String {
        val posix = posixPath(arg)
        return if (posix.endsWith(":ro", ignoreCase = true)) posix.dropLast(3) else posix
    }

    private fun workspaceIsReadOnly(arg: String): Boolean = posixPath(arg).endsWith(":ro", ignoreCase = true)

    /**
     * Differences between the running VM and the spec that only a new VM can apply. Start never
     * acts on them by itself: a pulled spec must not silently delete VM-only data.
     *
     * [desiredMounts] are the spec's extra mounts plus the shared config mount; [pluginMounts] are
     * the plugin's own workspaces (protect overlays, persist store, 2.x binary copy).
     */
    fun recreateReasons(
        record: SbxSandboxRecord,
        listedWorkspaces: List<String>,
        workspace: String,
        kitsText: String,
        shareHostConfig: Boolean,
        desiredMounts: List<SbxExtraMount>,
        pluginMounts: List<SbxExtraMount>,
        sharedConfigPath: String?,
    ): List<String> {
        if (record.adopted) return emptyList()
        val reasons = ArrayList<String>()
        val installedKits = parseLineList(record.kits)
        val desiredKits = parseLineList(kitsText)
        if (desiredKits.size < installedKits.size || desiredKits.take(installedKits.size) != installedKits) {
            reasons += "kits were removed or reordered"
        }
        if (record.shareHostConfig != shareHostConfig) {
            reasons += if (shareHostConfig) "host OpenCode config sharing was turned on" else "host OpenCode config sharing was turned off"
        }
        fun listed(host: String) = listedWorkspaces.firstOrNull {
            OpenCodeServerProtocol.isSameFilesystemPath(workspaceHostPath(it), host)
        }
        for (mount in extraMountsForCreate(desiredMounts, workspace)) {
            val attached = listed(mount.hostPath)
            if (attached == null) {
                reasons += "new mount ${mount.hostPath}"
                continue
            }
            // A VM created before config sharing became read-only keeps its bind until Reset.
            val sharedConfig = sharedConfigPath != null &&
                OpenCodeServerProtocol.isSameFilesystemPath(mount.hostPath, sharedConfigPath)
            if (!sharedConfig && workspaceIsReadOnly(attached) != mount.readOnly) {
                reasons += if (mount.readOnly) "mount ${mount.hostPath} should be read-only" else "mount ${mount.hostPath} should be writable"
            }
        }
        val known = (desiredMounts + pluginMounts).map { it.hostPath } + workspace
        for (attached in listedWorkspaces) {
            if (workspaceIsReadOnly(attached)) continue
            val host = workspaceHostPath(attached)
            if (known.none { OpenCodeServerProtocol.isSameFilesystemPath(it, host) }) {
                reasons += "mount $host was removed but is still writable in the VM"
            }
        }
        return reasons
    }

    fun networkKitTemplateYaml(): String {
        val out = StringBuilder()
        out.append("schemaVersion: \"2\"\n")
        out.append("kind: mixin\n")
        out.append("name: opencode-network\n")
        out.append("displayName: OpenCode extra network\n")
        out.append("description: Uncomment hosts this sandbox should reach, then recreate the sandbox.\n")
        out.append("permissions:\n")
        out.append("  network:\n")
        out.append("    allow:\n")
        out.append("      # Uncomment what this sandbox should reach, then recreate it.\n")
        out.append("      # Balanced does not include every provider (for example xAI is not included).\n")
        out.append("      #\n")
        out.append("      # All outbound TCP: uncomment ALL THREE lines (hostnames, IPv4 and IPv6).\n")
        out.append("      # This also permits local-network TCP access. UDP and ICMP remain blocked.\n")
        out.append("      # CIDRs alone do not allow hostname requests such as api.x.ai:443.\n")
        out.append("      # - \"*\"\n")
        out.append("      # - 0.0.0.0/0\n")
        out.append("      # - \"::/0\"\n")
        out.append("      #\n")
        out.append("      # Local-network additions (loopback + RFC1918), NOT a local-only restriction.\n")
        out.append("      # Existing global/agent-kit internet allows still apply.\n")
        out.append("      # - localhost\n")
        out.append("      # - 127.0.0.1\n")
        out.append("      # - ::1\n")
        out.append("      # - 10.0.0.0/8\n")
        out.append("      # - 172.16.0.0/12\n")
        out.append("      # - 192.168.0.0/16\n")
        out.append("      #\n")
        out.append("      # Individual hosts:\n")
        NETWORK_KIT_HOST_PRESETS.forEach { (label, host) ->
            out.append("      # - ").append(host).append("  # ").append(label).append('\n')
        }
        return out.toString()
    }

    fun networkKitRef(): String = posixPath("./$PROJECT_CONTROL_DIR/$NETWORK_KIT_DIR")

    fun parseLineList(text: String?): List<String> {
        return text.orEmpty().lineSequence()
            .map { posixPath(it) }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .distinct()
            .toList()
    }

    fun extraMountHostPaths(mounts: List<SbxExtraMount>, primaryWorkspace: String): List<String> {
        return extraMountsForCreate(mounts, primaryWorkspace).map { it.hostPath }
    }

    fun extraMountCreateArgs(mounts: List<SbxExtraMount>, primaryWorkspace: String): List<String> {
        return extraMountsForCreate(mounts, primaryWorkspace).map { mount ->
            if (mount.readOnly) readOnlyWorkspaceArg(mount.hostPath) else mount.hostPath
        }
    }

    fun readOnlyWorkspaceArg(hostPath: String): String {
        val host = posixPath(hostPath)
        return if (host.endsWith(":ro", ignoreCase = true)) host else "$host:ro"
    }

    fun isLocalKitRef(ref: String): Boolean {
        val trimmed = posixPath(ref)
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("git+", ignoreCase = true)) return false
        if ("://" in trimmed) return false
        if (trimmed == "." || trimmed == "..") return true
        if (trimmed.startsWith("./") || trimmed.startsWith("../")) return true
        if (trimmed.startsWith("/") || trimmed.startsWith("~")) return true
        return trimmed.length >= 3 && trimmed[0].isLetter() && trimmed[1] == ':' && trimmed[2] == '/'
    }

    fun sandboxProtectMounts(
        workspace: String,
        kits: List<String>,
        hostHome: String = System.getProperty("user.home").orEmpty(),
        isDirectory: (Path) -> Boolean = { Files.isDirectory(it) },
    ): List<SbxExtraMount> {
        val root = posixPath(workspace)
        val control = Path.of(root, PROJECT_CONTROL_DIR).normalize()
        val mounts = ArrayList<SbxExtraMount>()
        val seen = HashSet<String>()
        fun add(path: Path) {
            val host = posixPath(path.toString())
            if (host.isBlank() || OpenCodeServerProtocol.isSameFilesystemPath(host, root)) return
            if (!seen.add(host)) return
            if (!isDirectory(path)) return
            mounts += SbxExtraMount(host, host, readOnly = true)
        }
        add(control)
        kits.filter { isLocalKitRef(it) }.forEach { ref ->
            val expanded = expandUserHome(ref, hostHome)
            val path = Path.of(root).resolve(expanded).normalize()
            if (isUnder(control, path)) return@forEach
            add(path)
        }
        return mounts
    }

    private fun isUnder(parent: Path, child: Path): Boolean {
        val root = posixPath(parent.toString())
        val path = posixPath(child.toString())
        if (OpenCodeServerProtocol.isSameFilesystemPath(root, path)) return true
        val prefix = if (root.endsWith("/")) root else "$root/"
        return path.startsWith(prefix)
    }

    fun persistDataDir(
        userHome: String = System.getProperty("user.home").orEmpty(),
        xdgDataHome: String? = System.getenv("XDG_DATA_HOME"),
        osName: String = System.getProperty("os.name").orEmpty(),
        override: String? = System.getenv(DATA_DIR_ENV),
        localAppData: String? = System.getenv("LOCALAPPDATA"),
    ): Path {
        val forced = override?.trim()?.ifBlank { null }
        if (forced != null) return Path.of(forced)
        val os = osName.lowercase(java.util.Locale.ROOT)
        return if (os.contains("win")) {
            val local = localAppData?.trim()?.ifBlank { null } ?: userHome
            Path.of(local, "opencode-web-panel")
        } else {
            val base = xdgDataHome?.trim()?.ifBlank { null } ?: Path.of(userHome, ".local", "share").toString()
            Path.of(base, "opencode-web-panel")
        }
    }

    fun sandboxPersistDataHome(sandboxName: String, dataRoot: Path = persistDataDir()): String {
        return posixPath(dataRoot.resolve("sbx").resolve(requireValidSandboxName(sandboxName)).toString())
    }

    fun persistSandboxGuestPath(): String = posixPath("$SANDBOX_HOME/.local/share/opencode")

    fun persistSandboxMount(
        sandboxName: String,
        createDirectories: (Path) -> Unit = { Files.createDirectories(it) },
    ): SbxExtraMount {
        val path = Path.of(sandboxPersistDataHome(sandboxName))
        createDirectories(path)
        val host = posixPath(path.toString())
        return SbxExtraMount(host, persistSandboxGuestPath())
    }

    /** Plugin-owned host copy of guest `$HOME/.opencode`. Not host `~/.opencode`. Survives stop/start; Reset deletes it. */
    fun guestOpenCodeDataHome(sandboxName: String, dataRoot: Path = persistDataDir()): String {
        return posixPath(dataRoot.resolve("sbx-opencode").resolve(requireValidSandboxName(sandboxName)).toString())
    }

    fun guestOpenCodeGuestPath(): String = posixPath("$SANDBOX_HOME/.opencode")

    fun guestOpenCodeMount(
        sandboxName: String,
        createDirectories: (Path) -> Unit = { Files.createDirectories(it) },
    ): SbxExtraMount {
        val path = Path.of(guestOpenCodeDataHome(sandboxName))
        createDirectories(path)
        val host = posixPath(path.toString())
        return SbxExtraMount(host, guestOpenCodeGuestPath())
    }

    fun deleteGuestOpenCode(sandboxName: String, dataRoot: Path = persistDataDir()) {
        val path = Path.of(guestOpenCodeDataHome(sandboxName, dataRoot))
        if (Files.exists(path)) path.toFile().deleteRecursively()
    }

    fun createSnapshot(
        memory: String,
        cpus: String,
        protectSandboxFiles: Boolean,
        extraCreateArgs: List<String>,
    ): String {
        return buildString {
            append(sanitizeMemory(memory))
            append('\n')
            append(sanitizeCpus(cpus))
            append('\n')
            append(if (protectSandboxFiles) "1" else "0")
            extraCreateArgs.forEach { arg ->
                append('\n')
                append(arg)
            }
        }
    }

    fun staleCreateReasons(
        record: SbxSandboxRecord,
        memory: String,
        cpus: String,
        protectSandboxFiles: Boolean,
        extraCreateArgs: List<String>,
        shareHostConfig: Boolean = record.shareHostConfig,
        persistSandboxSessions: Boolean = false,
    ): List<String> {
        val desired = createSnapshot(memory, cpus, protectSandboxFiles, extraCreateArgs)
        if (record.createSnapshot == desired) return emptyList()
        val stored = parseCreateSnapshot(record.createSnapshot)
        val reasons = ArrayList<String>()
        if (stored == null) {
            if (protectSandboxFiles) reasons += "read-only sandbox files"
            if (shareHostConfig) reasons += "read-only host OpenCode config"
            if (persistSandboxSessions) reasons += "persisted sandbox sessions"
            return reasons
        }
        if (stored.memory != sanitizeMemory(memory)) reasons += "memory"
        if (stored.cpus != sanitizeCpus(cpus)) reasons += "CPU count"
        if (stored.protectSandboxFiles != protectSandboxFiles) {
            reasons += "read-only sandbox files"
        }
        if (stored.extraCreateArgs != extraCreateArgs) reasons += "mounts"
        return reasons.distinct()
    }

    internal data class ParsedCreateSnapshot(
        val memory: String,
        val cpus: String,
        val protectSandboxFiles: Boolean,
        val extraCreateArgs: List<String>,
    )

    fun parseCreateSnapshot(raw: String): ParsedCreateSnapshot? {
        if (raw.isBlank()) return null
        val lines = raw.split('\n')
        if (lines.size < 3) return null
        return ParsedCreateSnapshot(
            memory = sanitizeMemory(lines[0]),
            cpus = sanitizeCpus(lines[1]),
            protectSandboxFiles = lines[2] == "1",
            extraCreateArgs = lines.drop(3),
        )
    }

    private fun extraMountsForCreate(
        mounts: List<SbxExtraMount>,
        primaryWorkspace: String,
    ): List<SbxExtraMount> {
        val seen = HashSet<String>()
        val selected = ArrayList<SbxExtraMount>()
        for (mount in mounts) {
            val host = posixPath(mount.hostPath)
            if (host.isBlank() || OpenCodeServerProtocol.isSameFilesystemPath(host, primaryWorkspace)) continue
            if (!seen.add(host)) continue
            selected += mount.copy(hostPath = host)
        }
        return selected
    }

    fun resolveExtraMounts(
        mounts: List<SbxExtraMount>,
        workspace: String,
        hostHome: String = System.getProperty("user.home").orEmpty(),
    ): List<SbxExtraMount> = mounts.map { mount ->
        val host = posixPath(Path.of(workspace).resolve(expandUserHome(mount.hostPath, hostHome)).normalize().toString())
        val sandbox = if (mount.sandboxPath.isBlank() || mount.sandboxPath == mount.hostPath) {
            host
        } else {
            val expanded = posixPath(expandUserHome(mount.sandboxPath, SANDBOX_HOME))
            if (isAbsolutePosixPath(expanded)) expanded else "$SANDBOX_HOME/${expanded.removePrefix("./")}"
        }
        SbxExtraMount(host, sandbox, mount.readOnly)
    }

    fun needsSandboxLink(mount: SbxExtraMount): Boolean {
        return mount.sandboxPath.isNotBlank() &&
            mount.hostPath != mount.sandboxPath &&
            !OpenCodeServerProtocol.isSameFilesystemPath(mount.hostPath, mount.sandboxPath)
    }

    const val LINK_ARGV0 = "opencode-link"

    /** Same guest scripts as `link_mount` in `opencode-sbx.sh`. */
    fun extraMountLinkScript(replaceExistingDirectory: Boolean): String {
        return if (replaceExistingDirectory) {
            // Persist / 2.x stores: host data wins; guest data is only copied into an empty store.
            $$"""
            mkdir -p -- "$(dirname -- "$2")"
            if [ -d "$2" ] && [ ! -L "$2" ]; then
              mkdir -p -- "$1"
              if [ -z "$(ls -A -- "$1" 2>/dev/null)" ]; then
                cp -a -- "$2"/. "$1"/ || exit 1
              fi
              rm -rf -- "$2"
            fi
            ln -sfn -- "$1" "$2"
            """.trimIndent()
        } else {
            // `ln -sfn` onto a real directory would create the link inside it.
            $$"""
            mkdir -p -- "$(dirname -- "$2")" || exit 1
            if [ -d "$2" ] && [ ! -L "$2" ]; then
              echo "opencode-link: $2 already exists as a directory in the sandbox" >&2
              exit 1
            fi
            ln -sfn -- "$1" "$2"
            """.trimIndent()
        }
    }

    fun workspaceContains(workspaces: List<String>, path: String): Boolean {
        return workspaces.any { OpenCodeServerProtocol.isSameFilesystemPath(it, path) }
    }

    fun persistMountIsAttached(workspaces: List<String>, persistMount: SbxExtraMount): Boolean {
        return workspaceContains(workspaces, persistMount.hostPath)
    }

    fun recordHasPersistMount(record: SbxSandboxRecord, persistHostPath: String): Boolean {
        val stored = parseCreateSnapshot(record.createSnapshot) ?: return false
        return stored.extraCreateArgs.any { arg ->
            OpenCodeServerProtocol.isSameFilesystemPath(workspaceHostPath(arg), persistHostPath)
        }
    }

    fun buildLinkExtraMountCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        mount: SbxExtraMount,
        replaceExistingDirectory: Boolean = false,
    ): List<String> {
        return listOf(
            executable, "exec", name, "sh", "-c", extraMountLinkScript(replaceExistingDirectory),
            LINK_ARGV0, guestBindPath(mount.hostPath), mount.sandboxPath,
        )
    }

    fun normalizeExtraMountText(text: String?): String = serializeExtraMountRows(parseExtraMountRows(text))

    fun parseExtraMountRows(text: String?): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        val seen = HashSet<Pair<String, String>>()
        for (rawLine in text.orEmpty().lineSequence()) {
            val line = rawLine.trim().substringBefore('#').trim()
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

    fun serializeExtraMountRows(rows: List<Pair<String, String>>): String {
        return rows.map { posixPath(it.first) to posixPath(it.second) }
            .filter { it.first.isNotBlank() }
            .map { (host, sandbox) ->
                if (sandbox.isBlank() || sandbox == host) host else "$host | $sandbox"
            }
            .distinct()
            .joinToString("\n")
    }

    fun parseExtraMounts(
        text: String?,
        hostHome: String = System.getProperty("user.home").orEmpty(),
        sandboxHome: String = SANDBOX_HOME,
        exists: (Path) -> Boolean = { Files.exists(it) },
    ): List<SbxExtraMount> {
        val mounts = ArrayList<SbxExtraMount>()
        val seen = HashSet<Pair<String, String>>()
        for (rawLine in text.orEmpty().lineSequence()) {
            val line = rawLine.trim().substringBefore('#').trim()
            if (line.isBlank()) continue
            val parts = line.split('|', limit = 2).map { it.trim() }
            val hostRaw = parts[0]
            if (hostRaw.isBlank()) continue
            val sandboxRaw = parts.getOrElse(1) { hostRaw }.ifBlank { hostRaw }
            val hostExpanded = posixPath(expandUserHome(hostRaw, hostHome))
            val sandboxPath = posixPath(expandUserHome(sandboxRaw, sandboxHome))
            if (!isAbsolutePosixPath(sandboxPath)) continue
            val hostPath = runCatching { Path.of(hostExpanded).toAbsolutePath().normalize() }.getOrNull()
                ?: continue
            if (!exists(hostPath)) continue
            val hostStr = posixPath(hostPath.toString())
            if (!seen.add(hostStr to sandboxPath)) continue
            mounts += SbxExtraMount(hostStr, sandboxPath)
        }
        return mounts
    }

    fun isAbsolutePosixPath(path: String): Boolean {
        return path.startsWith("/") &&
            '\u0000' !in path &&
            '\n' !in path &&
            '\r' !in path
    }

    fun expandUserHome(path: String, home: String): String {
        if (home.isBlank()) return path
        if (path == "~") return home
        if (path.startsWith("~/") || path.startsWith("~\\")) return home + path.substring(1)
        return path
    }

    fun buildExecServeCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        workspace: String,
        extraEnvKeys: List<String> = emptyList(),
        preferGuestV2: Boolean = false,
    ): List<String> {
        val command = mutableListOf(executable, "exec", "-e", OPENCODE_SERVER_PASSWORD_ENV)
        extraEnvKeys.distinct().filter { it.isNotBlank() && it != OPENCODE_SERVER_PASSWORD_ENV }.forEach { key ->
            command += listOf("-e", key)
        }
        command += listOf("-w", workspace, name)
        appendGuestOpenCode(command, SERVE_FLAGS, preferGuestV2)
        return command
    }

    fun buildExecUpgradeCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        preferGuestV2: Boolean = false,
    ): List<String> {
        val command = mutableListOf(executable, "exec", name)
        val args = mutableListOf("upgrade", "--print-logs")
        if (preferGuestV2) {
            // CLI 2.x curl-detect is path.resolve(execPath) == $HOME/.opencode/bin/opencode.
            // Persist extra-mounts that dir and symlink ~/.opencode onto it, so execPath is
            // the host sbx-opencode realpath and detection fails without --method.
            args += listOf("--method", "curl")
        }
        appendGuestOpenCode(command, args, preferGuestV2)
        return command
    }

    fun buildExecInstallV2Command(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
    ): List<String> {
        return listOf(executable, "exec", name, "sh", "-c", V2_INSTALL_SCRIPT)
    }

    fun buildNetworkProbeCommand(executable: String, name: String, url: String): List<String> = listOf(
        executable, "exec", name, "curl", "--silent", "--show-error", "--location",
        "--connect-timeout", "5", "--max-time", "10", "--output", "/dev/null", "--write-out", "%{http_code}", url,
    )

    const val GUEST_V2_MISSING_EXIT_CODE = 44

    fun buildExecGuestV2VersionCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
    ): List<String> {
        return listOf(executable, "exec", name, "sh", "-c", GUEST_V2_VERSION_SCRIPT)
    }

    private fun appendGuestOpenCode(
        command: MutableList<String>,
        args: List<String>,
        preferGuestV2: Boolean,
    ) {
        if (preferGuestV2) {
            command += listOf("sh", "-c", GUEST_OPENCODE_DISPATCH, "opencode")
        } else {
            command += "opencode"
        }
        command += args
    }

    fun buildAddKitCommand(executable: String = DEFAULT_EXECUTABLE, name: String, ref: String): List<String> =
        listOf(executable, "kit", "add", name, ref)

    fun buildRemotePkillCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
    ): List<String> {
        return listOf(
            executable,
            "exec",
            name,
            "sh",
            "-lc",
            $$"""
            pkill -TERM -f '$${SERVE_PKILL_PATTERN}' || true
            i=0
            while [ "$i" -lt 5 ]; do
              pkill -0 -f '$${SERVE_PKILL_PATTERN}' || exit 0
              sleep 0.2
              i=$((i+1))
            done
            pkill -KILL -f '$${SERVE_PKILL_PATTERN}' || true
            """.trimIndent(),
        )
    }

    fun buildPortsCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
    ): List<String> {
        return listOf(executable, "ports", name, "--json")
    }

    fun buildPortsPublishCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        publish: String,
    ): List<String> {
        return listOf(executable, "ports", name, "--publish", publish)
    }

    fun buildPortsUnpublishCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        publish: String,
    ): List<String> {
        return listOf(executable, "ports", name, "--unpublish", publish)
    }

    fun buildLsCommand(executable: String = DEFAULT_EXECUTABLE): List<String> {
        return listOf(executable, "ls", "--json")
    }

    fun buildTemplateLsCommand(executable: String = DEFAULT_EXECUTABLE): List<String> {
        return listOf(executable, "template", "ls", "--json")
    }

    fun buildTemplateRmCommand(
        executable: String = DEFAULT_EXECUTABLE,
        ref: String,
    ): List<String> {
        return listOf(executable, "template", "rm", ref)
    }

    fun buildStopCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
    ): List<String> {
        return listOf(executable, "stop", name)
    }

    fun buildRmForceCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
    ): List<String> {
        return listOf(executable, "rm", "--force", name)
    }

    fun buildDaemonStartCommand(executable: String = DEFAULT_EXECUTABLE): List<String> {
        return listOf(executable, "daemon", "start")
    }

    fun buildDiagnoseJsonCommand(executable: String = DEFAULT_EXECUTABLE): List<String> {
        return listOf(executable, "diagnose", "-o", "json")
    }

    fun buildPolicyInitCommand(
        executable: String = DEFAULT_EXECUTABLE,
        profile: String = "balanced",
    ): List<String> {
        return listOf(executable, "policy", "init", profile)
    }

    fun buildPolicyAllowCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        target: String,
    ): List<String> {
        return listOf(executable, "policy", "allow", "network", "--sandbox", name, target)
    }

    fun ideaMcpConfigContent(port: Int): String {
        return """{"mcp":{"idea":{"type":"remote","url":"http://host.docker.internal:$port/sse","enabled":true}}}"""
    }

    fun ideMcpLoopbackPort(statusMessage: String): Int? {
        val match = Regex("""https?://(?:127\.0\.0\.1|localhost|\[::1]):(\d+)""").find(statusMessage)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    fun commandContainsBoundEnvAssignment(command: List<String>): Boolean {
        return command.any { token ->
            token.startsWith("$OPENCODE_SERVER_PASSWORD_ENV=") ||
                token.startsWith("$OPENCODE_CONFIG_CONTENT_ENV=") ||
                token.startsWith("$OPENCODE_AUTH_CONTENT_ENV=")
        }
    }

    fun parseTemplateLsJson(json: String): List<SbxTemplateImage> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val images = root.get("images")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return images.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.stringMember("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SbxTemplateImage(
                id = id,
                repository = obj.stringMember("repository").orEmpty(),
                tag = obj.stringMember("tag").orEmpty(),
                flavor = obj.stringMember("flavor").orEmpty(),
            )
        }
    }

    fun isOfficialOpencodeTemplate(image: SbxTemplateImage): Boolean {
        val repo = image.repository.lowercase()
        val tag = image.tag.lowercase()
        val flavor = image.flavor.lowercase()
        if (image.ref() == DEFAULT_OPENCODE_TEMPLATE) return true
        if (!repo.contains("docker/sandbox-templates")) return false
        return tag.contains("opencode") || flavor.contains("opencode")
    }

    fun officialOpencodeTemplateRefs(images: List<SbxTemplateImage>): List<String> {
        return images.filter(::isOfficialOpencodeTemplate).map { it.ref() }.distinct()
    }

    fun parseLsJson(json: String): List<SbxSandboxListEntry> = parseLsJsonOrNull(json).orEmpty()

    fun parseLsJsonOrNull(json: String): List<SbxSandboxListEntry>? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val sandboxes = root.get("sandboxes")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return sandboxes.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            parseSandboxListEntry(obj) ?: return null
        }
    }

    fun parsePortsJson(json: String): List<SbxPortMapping> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        if (!root.isJsonArray) return emptyList()
        return parsePortArray(root.asJsonArray)
    }

    fun publishSpec(
        hostPort: Int? = null,
        sandboxPort: Int = OpenCodeServerProtocol.SANDBOX_SERVE_PORT,
    ): String {
        val guest = "$sandboxPort/tcp4"
        val pinned = hostPort?.takeIf { it in 1..65535 }
        return if (pinned == null) guest else "127.0.0.1:$pinned:$guest"
    }

    fun unpublishSpec(mapping: SbxPortMapping): String {
        val protocol = mapping.protocol.ifBlank { "tcp4" }
        return "${mapping.hostIp}:${mapping.hostPort}:${mapping.sandboxPort}/$protocol"
    }

    fun sandboxPortMappings(
        ports: List<SbxPortMapping>,
        sandboxPort: Int = OpenCodeServerProtocol.SANDBOX_SERVE_PORT,
    ): List<SbxPortMapping> {
        return ports.filter { it.sandboxPort == sandboxPort }
    }

    fun publishedHostPorts(
        ports: List<SbxPortMapping>,
        sandboxPort: Int = OpenCodeServerProtocol.SANDBOX_SERVE_PORT,
        desiredHostPort: Int? = null,
    ): List<Int> {
        val matching = sandboxPortMappings(ports, sandboxPort).filter { it.hostIp == "127.0.0.1" }
        val preferred = matching.filter { it.protocol.equals("tcp4", ignoreCase = true) }.ifEmpty { matching }
        val hostPorts = preferred.map { it.hostPort }.distinct()
        val pinned = desiredHostPort?.takeIf { it in 1..65535 }
        return if (pinned == null) hostPorts else hostPorts.filter { it == pinned }
    }

    fun publishedHostPort(
        ports: List<SbxPortMapping>,
        sandboxPort: Int = OpenCodeServerProtocol.SANDBOX_SERVE_PORT,
    ): Int? = publishedHostPorts(ports, sandboxPort).firstOrNull()

    fun findOwnedSandbox(
        entries: List<SbxSandboxListEntry>,
        record: SbxSandboxRecord,
    ): SbxSandboxListEntry? {
        return entries.firstOrNull { record.matches(it) }
    }

    /**
     * A VM that already carries this [name], or mounts [workspace] read-write and is not recorded
     * as another directory's sandbox. Another project may legitimately mount this directory as an
     * extra workspace; that VM is not a candidate to adopt or discard.
     */
    fun conflictingSandbox(
        entries: List<SbxSandboxListEntry>,
        name: String,
        workspace: String,
        ownedElsewhere: (SbxSandboxListEntry) -> Boolean = { false },
    ): SbxSandboxListEntry? {
        return entries.firstOrNull { entry ->
            entry.name == name ||
                !ownedElsewhere(entry) &&
                entry.workspaces.any { OpenCodeServerProtocol.isSameFilesystemPath(it, workspace) }
        }
    }

    fun diagnoseReportsUnsupported(json: String): Boolean {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
        if (root != null) {
            if (root.booleanMember("supported") == false) return true
            val virtualization = root.objectMember("virtualization")
            if (virtualization?.booleanMember("supported") == false) return true
            if (virtualization?.booleanMember("available") == false) return true
            val errors = root.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
            if (errors != null) {
                val text = errors.joinToString(" ") { element ->
                    if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else ""
                }
                if (looksUnsupportedText(text)) return true
            }
            return false
        }
        return looksUnsupportedText(json)
    }

    fun looksUnsupportedHost(osName: String, arch: String): Boolean {
        val os = osName.lowercase()
        val cpu = arch.lowercase()
        return os.contains("mac") && (cpu == "x86_64" || cpu == "amd64" || cpu == "x64")
    }

    private fun looksUnsupportedText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("not supported") ||
            lower.contains("unsupported") ||
            lower.contains("apple silicon") ||
            lower.contains("hypervisorplatform") ||
            lower.contains("no kvm")
    }

    private fun parseSandboxListEntry(obj: JsonObject): SbxSandboxListEntry? {
        val name = obj.stringMember("name")?.takeIf { it.isNotBlank() } ?: return null
        val id = obj.stringMember("id")?.takeIf { it.isNotBlank() } ?: return null
        val agent = obj.stringMember("agent").orEmpty()
        val status = obj.stringMember("status").orEmpty()
        val ports = obj.get("ports")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.let { parsePortArray(it) }
            .orEmpty()
        val workspaces = obj.get("workspaces")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            .orEmpty()
        return SbxSandboxListEntry(name, id, agent, status, ports, workspaces)
    }

    private fun parsePortArray(array: JsonArray): List<SbxPortMapping> {
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val hostIp = obj.stringMember("host_ip") ?: return@mapNotNull null
            val hostPort = obj.longMember("host_port")?.toInt() ?: return@mapNotNull null
            val sandboxPort = obj.longMember("sandbox_port")?.toInt() ?: return@mapNotNull null
            val protocol = obj.stringMember("protocol").orEmpty()
            SbxPortMapping(hostIp, hostPort, sandboxPort, protocol)
        }
    }
}
