package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.io.FileUtil
import de.moritzf.opencodewebpanel.configuration.SbxSettingsValues
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal data class SbxExtraMount
@JvmOverloads
constructor(
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
 * Argv builders and JSON parsers for the Docker Sandboxes (`sbx`) CLI. Never `shell=true`. Password
 * and config overlay travel as ProcessBuilder env with bare `-e KEY` only.
 */
internal object SbxCli {
    const val DEFAULT_EXECUTABLE = SbxSettingsValues.DEFAULT_EXECUTABLE

    /**
     * Built-in agent name. sbx resolves it to Docker's latest published `docker.io/sbx/opencode`
     * kit (OpenCode 2); `sbx ls` reports it for both kits below.
     */
    const val AGENT = "opencode"

    /**
     * Docker's published OpenCode 1.x kit. The tag is fixed; Upgrade OpenCode updates the binary
     * inside the VM (npm), as with the earlier 1.x template.
     */
    const val V1_KIT = "docker.io/sbx/opencode:1.18.31"

    /** First release with Docker's v3 OpenCode kits; older ones only know the retired template. */
    const val MINIMUM_SBX_VERSION = "0.51.0"

    /** The `sbx create` agent argument for [version]. */
    fun agentFor(version: SbxOpenCodeVersion): String =
        if (version == SbxOpenCodeVersion.V2) AGENT else V1_KIT

    const val DEFAULT_MEMORY = SbxSettingsValues.DEFAULT_MEMORY
    const val DEFAULT_CPUS = SbxSettingsValues.DEFAULT_CPUS
    const val NAME_PREFIX = "ide-ocwp-"
    const val OPENCODE_SERVER_PASSWORD_ENV = "OPENCODE_SERVER_PASSWORD"
    const val OPENCODE_CONFIG_CONTENT_ENV = "OPENCODE_CONFIG_CONTENT"
    const val OPENCODE_AUTH_CONTENT_ENV = "OPENCODE_AUTH_CONTENT"

    /** Plugin config overlay; merged over the kit's own [OPENCODE_CONFIG_CONTENT_ENV] in the VM. */
    const val CONFIG_OVERLAY_ENV = "OCWP_CONFIG_OVERLAY"
    const val DATA_DIR_ENV = "OCWP_DATA_DIR"
    const val SANDBOX_HOME = "/home/agent"

    const val PROJECT_CONTROL_DIR = "opencode-sbx"
    const val NETWORK_KIT_DIR = "opencode-network-kit"

    /** Common provider hosts that Docker's OpenCode kits (1.x and 2.x) do not already allow. */
    val NETWORK_KIT_HOST_PRESETS =
        listOf(
            "Mistral" to "api.mistral.ai",
            "GitHub Models" to "models.github.ai",
            "DeepSeek" to "api.deepseek.com",
            "Together" to "api.together.xyz",
            "Fireworks" to "api.fireworks.ai",
            "Ollama Cloud" to "ollama.com",
        )
    private val SERVE_FLAGS =
        listOf(
            "serve",
            "--hostname",
            OpenCodeServerProtocol.SANDBOX_SERVE_HOST,
            "--port",
            OpenCodeServerProtocol.SANDBOX_SERVE_PORT.toString(),
            "--print-logs",
        )

    /**
     * Docker's OpenCode kit sets its own inline config (tool permissions). Merge the plugin overlay
     * over it instead of replacing it; without jq or with invalid kit JSON, the overlay alone
     * applies. Values are read from the environment, never argv.
     */
    private val GUEST_SERVE_WITH_OVERLAY =
        $$"""
        if merged=$(jq -cn '(env.OPENCODE_CONFIG_CONTENT // "{}" | fromjson) * (env.OCWP_CONFIG_OVERLAY | fromjson)' 2>/dev/null); then
          OPENCODE_CONFIG_CONTENT=$merged
        else
          OPENCODE_CONFIG_CONTENT=$OCWP_CONFIG_OVERLAY
        fi
        export OPENCODE_CONFIG_CONTENT
        unset OCWP_CONFIG_OVERLAY
        exec opencode "$@"
        """
            .trimIndent()

    /**
     * Docker's 2.x kit installs OpenCode under /opt/opencode, which `opencode upgrade` does not
     * recognize as an install method. OpenCode's own installer (`--method curl`) puts the release
     * in ~/.opencode/bin; a link in ~/.local/bin, first on the kit's PATH, makes serve, the TUI,
     * ACP and the standalone launcher run it. The link is only set when the installer ran (its copy
     * changed) or already set, so a stale copy never replaces the kit's binary.
     */
    private val GUEST_UPGRADE_V2 =
        $$"""
        installed="$HOME/.opencode/bin/opencode"
        link="$HOME/.local/bin/opencode"
        before=$("$installed" --version </dev/null 2>/dev/null || true)
        opencode upgrade --method curl --print-logs </dev/null || exit $?
        after=$("$installed" --version </dev/null 2>/dev/null || true)
        if [ -n "$after" ] && { [ -L "$link" ] || [ "$after" != "$before" ]; }; then
          mkdir -p "${link%/*}" && ln -sfn "$installed" "$link" || exit 1
        fi
        hash -r 2>/dev/null || true
        if [ -L "$link" ] && [ "$(command -v opencode)" != "$link" ]; then
          echo "The upgraded OpenCode in $installed is not first on the sandbox PATH; opencode runs $(command -v opencode)." >&2
          exit 1
        fi
        opencode --version </dev/null
        """
            .trimIndent()

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
            return msys.groupValues[1].uppercase(java.util.Locale.ROOT) +
                ":" +
                msys.groupValues.getOrElse(2) { "" }
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
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    sandboxIdentityPath(canonicalDirectory, osName)
                        .toByteArray(StandardCharsets.UTF_8),
                )
        val hex = digest.joinToString("") { byte -> "%02x".format(byte) }.take(12)
        return NAME_PREFIX + hex
    }

    fun parseMemory(value: String?): String? = SbxSettingsValues.parseMemory(value)

    fun parseCpus(value: String?): String? = SbxSettingsValues.parseCpus(value)

    fun sanitizeMemory(value: String?): String = SbxSettingsValues.sanitizeMemory(value)

    fun sanitizeCpus(value: String?): String = SbxSettingsValues.sanitizeCpus(value)

    /**
     * Names are joined into host paths; keep this in sync with the launcher's `valid_sandbox_name`.
     */
    fun isValidSandboxName(name: String): Boolean {
        if (name.equals("default", ignoreCase = true)) return false
        if (name.length < 2) return false
        if (!name[0].isAsciiLetterOrDigit()) return false
        return name.all { it.isAsciiLetterOrDigit() || it == '.' || it == '-' }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

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
        agent: String = AGENT,
    ): List<String> {
        val command =
            mutableListOf(
                executable,
                "create",
                "--name",
                name,
                "--memory",
                memory,
                "--cpus",
                cpus,
                "--publish",
                publishSpec(hostPort, inVmPort),
            )
        kits.filter { it.isNotBlank() }.forEach { ref -> command += listOf("--kit", ref) }
        command += listOf(agent, workspace)
        command += extraWorkspaces.filter { it.isNotBlank() && it != workspace }
        return command
    }

    fun normalizeLineList(text: String?): String = SbxSettingsValues.normalizeLineList(text)

    fun parseKitRefs(
        text: String?,
        home: String = System.getProperty("user.home").orEmpty(),
    ): List<String> {
        return parseLineList(text)
            .map { raw -> posixPath(if (raw.startsWith("~")) expandUserHome(raw, home) else raw) }
            .distinct()
    }

    fun posixPath(path: String): String {
        return FileUtil.toSystemIndependentName(path.trim())
    }

    /**
     * In-guest spelling of a host bind. sbx mounts workspaces at the host path; Windows drives
     * become `/c/...`.
     */
    fun guestBindPath(hostPath: String): String {
        val posix = posixPath(hostPath)
        if (posix.length >= 3 && posix[0].isLetter() && posix[1] == ':' && posix[2] == '/') {
            return "/${posix[0].lowercaseChar()}${posix.substring(2)}"
        }
        return posix
    }

    /**
     * Guest path prefix → host path prefix, longest guest first. Extra mounts may alias
     * `/home/agent/docs` onto a different host folder; Windows primary workspaces appear as
     * `/c/Users/...` in the VM.
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
        persistHostPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { host ->
                add(persistSandboxGuestPath(), host)
                add(guestBindPath(host), host)
            }
        return maps.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
    }

    fun workspaceHostPath(arg: String): String {
        val posix = posixPath(arg)
        return if (posix.endsWith(":ro", ignoreCase = true)) posix.dropLast(3) else posix
    }

    private fun workspaceIsReadOnly(arg: String): Boolean =
        posixPath(arg).endsWith(":ro", ignoreCase = true)

    /**
     * Differences between the running VM and the spec that only a new VM can apply. Start never
     * acts on them by itself: a pulled spec must not silently delete VM-only data.
     *
     * [desiredMounts] are the spec's extra mounts plus the shared config mount; [pluginMounts] are
     * the plugin's own workspaces (protect overlays, persist store). [legacyGuestOpenCodePath]
     * marks a VM that older releases gave a plugin-installed OpenCode 2; with [openCodeVersion] 2.x
     * it must move to Docker's kit, while 1.x never launched that copy.
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
        legacyGuestOpenCodePath: String? = null,
        openCodeVersion: SbxOpenCodeVersion = SbxOpenCodeVersion.V2,
    ): List<String> {
        if (record.adopted) return emptyList()
        val reasons = ArrayList<String>()
        val legacyGuestOpenCode =
            openCodeVersion == SbxOpenCodeVersion.V2 &&
                legacyGuestOpenCodePath != null &&
                listedWorkspaces.any {
                    OpenCodeServerProtocol.isSameFilesystemPath(
                        workspaceHostPath(it),
                        legacyGuestOpenCodePath,
                    )
                }
        if (legacyGuestOpenCode) {
            reasons +=
                "OpenCode 2 now comes from Docker's sandbox kit; this VM still runs the plugin-installed copy"
        }
        val installedKits = parseLineList(record.kits)
        val desiredKits = parseLineList(kitsText)
        if (
            desiredKits.size < installedKits.size ||
                desiredKits.take(installedKits.size) != installedKits
        ) {
            reasons += "kits were removed or reordered"
        }
        if (record.shareHostConfig != shareHostConfig) {
            reasons +=
                if (shareHostConfig) "host OpenCode config sharing was turned on"
                else "host OpenCode config sharing was turned off"
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
            val sharedConfig =
                sharedConfigPath != null &&
                    OpenCodeServerProtocol.isSameFilesystemPath(mount.hostPath, sharedConfigPath)
            if (!sharedConfig && workspaceIsReadOnly(attached) != mount.readOnly) {
                reasons +=
                    if (mount.readOnly) "mount ${mount.hostPath} should be read-only"
                    else "mount ${mount.hostPath} should be writable"
            }
        }
        val known =
            (desiredMounts + pluginMounts).map { it.hostPath } +
                workspace +
                listOfNotNull(legacyGuestOpenCodePath)
        for (attached in listedWorkspaces) {
            if (workspaceIsReadOnly(attached)) continue
            val host = workspaceHostPath(attached)
            if (known.none { OpenCodeServerProtocol.isSameFilesystemPath(it, host) }) {
                reasons += "mount $host was removed but is still writable in the VM"
            }
        }
        return reasons
    }

    /** Kit v3 mixin: Docker's OpenCode kits are v3, and sbx refuses to mix them with v1/v2 kits. */
    fun networkKitTemplateYaml(): String {
        val out = StringBuilder()
        out.append("schemaVersion: \"3\"\n")
        out.append("kind: mixin\n")
        out.append("displayName: OpenCode extra network\n")
        out.append(
            "description: Uncomment hosts this sandbox should reach, then recreate the sandbox.\n",
        )
        out.append("capabilities:\n")
        out.append("  - type: com.docker.sandbox/network-policy@1\n")
        out.append("    config:\n")
        out.append("      runtime:\n")
        out.append("        allow:\n")
        val comment = "          #"
        out.append("$comment Uncomment what this sandbox should reach, then recreate it.\n")
        out.append(
            "$comment Docker's OpenCode kits already allow OpenAI, Anthropic, Google, xAI, Groq,\n",
        )
        out.append("$comment OpenRouter, GitHub Copilot, opencode.ai and registry.npmjs.org.\n")
        out.append("$comment\n")
        out.append(
            "$comment All outbound TCP: uncomment ALL THREE lines (hostnames, IPv4 and IPv6).\n",
        )
        out.append(
            "$comment This also permits local-network TCP access. UDP and ICMP remain blocked.\n",
        )
        out.append(
            "$comment CIDRs alone do not allow hostname requests such as api.mistral.ai:443.\n",
        )
        out.append("$comment - \"*\"\n")
        out.append("$comment - 0.0.0.0/0\n")
        out.append("$comment - \"::/0\"\n")
        out.append("$comment\n")
        out.append(
            "$comment Local-network additions (loopback + RFC1918), NOT a local-only restriction.\n",
        )
        out.append("$comment Existing global/agent-kit internet allows still apply.\n")
        out.append("$comment - localhost\n")
        out.append("$comment - 127.0.0.1\n")
        out.append("$comment - ::1\n")
        out.append("$comment - 10.0.0.0/8\n")
        out.append("$comment - 172.16.0.0/12\n")
        out.append("$comment - 192.168.0.0/16\n")
        out.append("$comment\n")
        out.append("$comment Individual hosts:\n")
        NETWORK_KIT_HOST_PRESETS.forEach { (label, host) ->
            out.append("$comment - ").append(host).append("  # ").append(label).append('\n')
        }
        return out.toString()
    }

    fun networkKitRef(): String = posixPath("./$PROJECT_CONTROL_DIR/$NETWORK_KIT_DIR")

    fun parseLineList(text: String?): List<String> = SbxSettingsValues.parseLineList(text)

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
        return trimmed.length >= 3 &&
            trimmed[0].isLetter() &&
            trimmed[1] == ':' &&
            trimmed[2] == '/'
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
        kits
            .filter { isLocalKitRef(it) }
            .forEach { ref ->
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
            val base =
                xdgDataHome?.trim()?.ifBlank { null }
                    ?: Path.of(userHome, ".local", "share").toString()
            Path.of(base, "opencode-web-panel")
        }
    }

    fun sandboxPersistDataHome(sandboxName: String, dataRoot: Path = persistDataDir()): String {
        return posixPath(
            dataRoot.resolve("sbx").resolve(requireValidSandboxName(sandboxName)).toString(),
        )
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

    /**
     * Host copy of the OpenCode 2 binary that older releases installed into the sandbox. Docker's
     * kit replaced it; a VM still mounting it must be recreated, and removing the VM drops it.
     */
    fun legacyGuestOpenCodeDataHome(
        sandboxName: String,
        dataRoot: Path = persistDataDir(),
    ): String {
        return posixPath(
            dataRoot
                .resolve("sbx-opencode")
                .resolve(requireValidSandboxName(sandboxName))
                .toString(),
        )
    }

    fun deleteLegacyGuestOpenCode(sandboxName: String, dataRoot: Path = persistDataDir()) {
        val path = Path.of(legacyGuestOpenCodeDataHome(sandboxName, dataRoot))
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
            if (
                host.isBlank() ||
                    OpenCodeServerProtocol.isSameFilesystemPath(host, primaryWorkspace)
            )
                continue
            if (!seen.add(host)) continue
            selected += mount.copy(hostPath = host)
        }
        return selected
    }

    fun resolveExtraMounts(
        mounts: List<SbxExtraMount>,
        workspace: String,
        hostHome: String = System.getProperty("user.home").orEmpty(),
        environment: Map<String, String> = System.getenv(),
    ): List<SbxExtraMount> = mounts.map { mount ->
        val host =
            posixPath(
                Path.of(workspace)
                    .resolve(expandHostMountPath(mount.hostPath, hostHome, environment))
                    .normalize()
                    .toString(),
            )
        val sandbox =
            if (mount.sandboxPath.isBlank() || mount.sandboxPath == mount.hostPath) {
                host
            } else {
                val expanded = posixPath(expandUserHome(mount.sandboxPath, SANDBOX_HOME))
                if (isAbsolutePosixPath(expanded)) expanded
                else "$SANDBOX_HOME/${expanded.removePrefix("./")}"
            }
        SbxExtraMount(host, sandbox, mount.readOnly)
    }

    fun needsSandboxLink(mount: SbxExtraMount): Boolean {
        return mount.sandboxPath.isNotBlank() &&
            mount.hostPath != mount.sandboxPath &&
            !OpenCodeServerProtocol.isSameFilesystemPath(mount.hostPath, mount.sandboxPath)
    }

    /** Same guest scripts as `link_mount` in `opencode-sbx.sh`. */
    fun extraMountLinkScript(replaceExistingDirectory: Boolean): String {
        return if (replaceExistingDirectory) {
            // Persist store: host data wins; guest data is only copied into an empty store.
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
            """
                .trimIndent()
        } else {
            // `ln -sfn` onto a real directory would create the link inside it.
            $$"""
            mkdir -p -- "$(dirname -- "$2")" || exit 1
            if [ -d "$2" ] && [ ! -L "$2" ]; then
              echo "opencode-link: $2 already exists as a directory in the sandbox" >&2
              exit 1
            fi
            ln -sfn -- "$1" "$2"
            """
                .trimIndent()
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
        return listOf(executable, "exec", "-w", "/", name) +
            guestScriptCommand(
                extraMountLinkScript(replaceExistingDirectory),
                listOf(guestBindPath(mount.hostPath), mount.sandboxPath),
            )
    }

    /**
     * sbx on Windows breaks quotes inside an argv script passed by ProcessBuilder. Decode inside
     * the guest instead.
     */
    private fun guestScriptCommand(script: String, args: List<String> = emptyList()): List<String> {
        val body =
            if (args.isEmpty()) script
            else "set -- ${args.joinToString(" ") { shellQuote(it) }}\n$script"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(StandardCharsets.UTF_8))
        return listOf("sh", "-c", "printf %s $encoded | base64 -d | sh")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun normalizeExtraMountText(text: String?): String =
        SbxSettingsValues.normalizeExtraMountText(text)

    fun parseExtraMountRows(text: String?): List<Pair<String, String>> =
        SbxSettingsValues.parseExtraMountRows(text)

    fun serializeExtraMountRows(rows: List<Pair<String, String>>): String =
        SbxSettingsValues.serializeExtraMountRows(rows)

    fun isAbsolutePosixPath(path: String): Boolean {
        return path.startsWith("/") && '\u0000' !in path && '\n' !in path && '\r' !in path
    }

    fun expandUserHome(path: String, home: String): String {
        if (home.isBlank()) return path
        if (path == "~") return home
        if (path.startsWith("~/") || path.startsWith("~\\")) return home + path.substring(1)
        return path
    }

    /** A leading host variable is data, never shell code; values are not expanded recursively. */
    fun expandHostMountPath(
        path: String,
        home: String,
        environment: Map<String, String> = System.getenv(),
    ): String {
        if (!path.startsWith("\${")) return expandUserHome(path, home)
        val match =
            HOST_MOUNT_VARIABLE.matchEntire(path)
                ?: throw IllegalArgumentException(
                    "Invalid host mount variable; use \${NAME} or \${NAME:-fallback}",
                )
        val name = match.groupValues[1]
        val value =
            environment[name]?.takeIf { it.isNotEmpty() }
                ?: match.groups[2]?.value
                ?: throw IllegalArgumentException(
                    "Host environment variable $name is not set for a sandbox mount",
                )
        val expanded = value + match.groupValues[3]
        require(expanded.isNotBlank()) { "Host mount path must not be empty" }
        return expandUserHome(expanded, home)
    }

    private val HOST_MOUNT_VARIABLE =
        Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^{}]*))?}(.*)$""")

    /**
     * Runs the kit's `opencode` from PATH. With [CONFIG_OVERLAY_ENV] among [extraEnvKeys], a guest
     * script merges that overlay into the kit's inline config before `exec`.
     */
    fun buildExecServeCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        workspace: String,
        extraEnvKeys: List<String> = emptyList(),
    ): List<String> {
        val command = mutableListOf(executable, "exec", "-e", OPENCODE_SERVER_PASSWORD_ENV)
        val envKeys =
            extraEnvKeys.distinct().filter { it.isNotBlank() && it != OPENCODE_SERVER_PASSWORD_ENV }
        envKeys.forEach { key -> command += listOf("-e", key) }
        command += listOf("-w", guestBindPath(workspace), name)
        if (CONFIG_OVERLAY_ENV in envKeys) {
            command += guestScriptCommand(GUEST_SERVE_WITH_OVERLAY, SERVE_FLAGS)
        } else {
            command += "opencode"
            command += SERVE_FLAGS
        }
        return command
    }

    /**
     * In-VM upgrade to the latest release of [version]'s major. 1.x: Docker's kit installed
     * OpenCode with npm, which `opencode upgrade` detects. 2.x: see [GUEST_UPGRADE_V2]. Reset
     * Sandbox drops either upgrade and returns to Docker's image.
     */
    fun buildExecUpgradeCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        version: SbxOpenCodeVersion = SbxOpenCodeVersion.V1,
    ): List<String> {
        val exec = listOf(executable, "exec", "-w", "/", name)
        return if (version == SbxOpenCodeVersion.V2) {
            exec + guestScriptCommand(GUEST_UPGRADE_V2)
        } else {
            exec + listOf("opencode", "upgrade", "--print-logs")
        }
    }

    /**
     * Why a sandbox serving [serverVersion] cannot run [wanted]: switching versions selects another
     * Docker kit, which only a new VM gets. Null when they match or the version is unknown.
     */
    fun openCodeVersionMismatch(wanted: SbxOpenCodeVersion, serverVersion: String?): String? {
        val match = OPENCODE_VERSION_LINE.find(serverVersion?.trim().orEmpty()) ?: return null
        val major = match.groupValues[2].toIntOrNull() ?: return null
        val runs = if (major >= 2) SbxOpenCodeVersion.V2 else SbxOpenCodeVersion.V1
        if (runs == wanted) return null
        return "this sandbox runs OpenCode ${match.groupValues[1]}, " +
            "but opencode-sbx.yaml selects ${wanted.yamlValue()} (${kitLabel(wanted)})"
    }

    /** `1.18.31` (1.x CLI and API) or `opencode v2.3.0` (2.x CLI); group 1 is the version. */
    private val OPENCODE_VERSION_LINE = Regex("""^(?:opencode\s+)?v?((\d+)\.\d+\.\d+[^\s,"]*)""")

    fun kitLabel(version: SbxOpenCodeVersion): String =
        if (version == SbxOpenCodeVersion.V2) "Docker's latest OpenCode 2 kit"
        else "Docker's OpenCode 1.x kit"

    fun buildNetworkProbeCommand(executable: String, name: String, url: String): List<String> =
        listOf(
            executable,
            "exec",
            "-w",
            "/",
            name,
            "curl",
            "--silent",
            "--show-error",
            "--location",
            "--connect-timeout",
            "5",
            "--max-time",
            "10",
            "--output",
            "/dev/null",
            "--write-out",
            "\n$NETWORK_PROBE_MARKER%{http_code}\n",
            url,
        )

    const val NETWORK_PROBE_MARKER = "OCWP_HTTP="

    fun networkProbeStatus(stdout: String): Int? =
        Regex("${NETWORK_PROBE_MARKER}(\\d{3})").find(stdout)?.groupValues?.get(1)?.toIntOrNull()

    fun buildAddKitCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
        ref: String,
    ): List<String> = listOf(executable, "kit", "add", name, ref)

    fun buildRemotePkillCommand(
        executable: String = DEFAULT_EXECUTABLE,
        name: String,
    ): List<String> {
        return listOf(executable, "exec", "-w", "/", name) +
            guestScriptCommand(
                $$"""
            pkill -TERM -f '$${SERVE_PKILL_PATTERN}' || true
            i=0
            while [ "$i" -lt 5 ]; do
              pkill -0 -f '$${SERVE_PKILL_PATTERN}' || exit 0
              sleep 0.2
              i=$((i+1))
            done
            pkill -KILL -f '$${SERVE_PKILL_PATTERN}' || true
            """
                    .trimIndent(),
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

    fun buildVersionCommand(executable: String = DEFAULT_EXECUTABLE): List<String> =
        listOf(executable, "version")

    /** `sbx version v0.53.0 <commit>` → `[0, 53, 0]`; null when no version is printed. */
    fun parseSbxVersion(output: String): List<Int>? {
        val match = SBX_VERSION.find(output) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    /** Null when the CLI is new enough or its version cannot be read; else the reported version. */
    fun unsupportedSbxVersion(output: String): String? {
        val found = parseSbxVersion(output) ?: return null
        val minimum = checkNotNull(parseSbxVersion(MINIMUM_SBX_VERSION))
        val comparison =
            found.zip(minimum).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
        return if (comparison < 0) found.joinToString(".") else null
    }

    private val SBX_VERSION = Regex("""(?<![\w.])v?(\d+)\.(\d+)\.(\d+)""")

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
        return listOf(executable, "daemon", "start", "--detach")
    }

    /**
     * Older sbx versions returned from bare `daemon start`; newer CLIs keep it in the foreground.
     */
    fun buildLegacyDaemonStartCommand(executable: String = DEFAULT_EXECUTABLE): List<String> =
        listOf(executable, "daemon", "start")

    fun daemonDetachUnsupported(output: String): Boolean {
        val message = output.lowercase(Locale.ROOT)
        return "detach" in message &&
            ("unknown flag" in message ||
                "flag provided but not defined" in message ||
                "unrecognized option" in message)
    }

    fun buildDiagnoseJsonCommand(executable: String = DEFAULT_EXECUTABLE): List<String> {
        return listOf(executable, "diagnose", "--format", "json")
    }

    fun buildLegacyDiagnoseJsonCommand(executable: String = DEFAULT_EXECUTABLE): List<String> =
        listOf(executable, "diagnose", "-o", "json")

    fun optionUnsupported(output: String, option: String): Boolean =
        output.contains("unknown flag: $option") ||
            output.contains("flag provided but not defined: $option") ||
            output.contains("unrecognized option '$option'")

    fun buildPolicyInitCommand(
        executable: String = DEFAULT_EXECUTABLE,
        profile: String = "balanced",
    ): List<String> {
        return listOf(executable, "policy", "init", profile)
    }

    fun buildPolicyLsCommand(executable: String = DEFAULT_EXECUTABLE): List<String> =
        listOf(executable, "policy", "ls", "--json")

    /**
     * True once a global network policy exists (`sbx policy init`, a terminal setup, or org
     * governance). Kit and per-sandbox rules alone do not count.
     */
    fun policyIsInitialized(json: String): Boolean {
        val root =
            runCatching { JsonParser.parseString(json) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject ?: return false
        val rules = root.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        return rules.any { element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@any false
            rule.stringMember("scope") == "global" || rule.stringMember("applies_to") == "all"
        }
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
        val match =
            Regex("""https?://(?:127\.0\.0\.1|localhost|\[::1]):(\d+)""").find(statusMessage)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    fun commandContainsBoundEnvAssignment(command: List<String>): Boolean {
        return command.any { token ->
            token.startsWith("$OPENCODE_SERVER_PASSWORD_ENV=") ||
                token.startsWith("$OPENCODE_CONFIG_CONTENT_ENV=") ||
                token.startsWith("$CONFIG_OVERLAY_ENV=") ||
                token.startsWith("$OPENCODE_AUTH_CONTENT_ENV=")
        }
    }

    /**
     * sbx 0.53 asks in a terminal before `kit add` widens an existing sandbox's permissions and has
     * no flag to approve it; creating the sandbox with the kit grants the same without a prompt.
     */
    fun kitAddNeedsTerminalApproval(output: String): Boolean =
        output.contains(
            "requests permissions beyond what this sandbox was granted",
            ignoreCase = true,
        )

    /** sbx refuses Docker's v3 OpenCode kit together with older v1/v2 mixins. */
    fun reportsLegacyKitFormat(output: String): Boolean =
        output.contains("cannot mix v3 kits with v1/v2 kits", ignoreCase = true)

    fun parseLsJson(json: String): List<SbxSandboxListEntry> = parseLsJsonOrNull(json).orEmpty()

    fun parseLsJsonOrNull(json: String): List<SbxSandboxListEntry>? {
        val sandboxes = parseListArray(json, "sandboxes") ?: return null
        // An entry without name or id cannot be matched by ownership (id) or conflict (name)
        // checks; skip it instead of discarding the whole inventory.
        return sandboxes.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseSandboxListEntry)
        }
    }

    /** sbx 0.48 removed the envelope from the sandbox list. */
    private fun parseListArray(json: String, legacyKey: String): JsonArray? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        return when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject ->
                root.asJsonObject.get(legacyKey)?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
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

    /** IPv4 loopback mappings of the serve port: the only ones this plugin publishes or removes. */
    fun loopbackPortMappings(
        ports: List<SbxPortMapping>,
        sandboxPort: Int = OpenCodeServerProtocol.SANDBOX_SERVE_PORT,
    ): List<SbxPortMapping> =
        sandboxPortMappings(ports, sandboxPort).filter { it.hostIp == "127.0.0.1" }

    fun publishedHostPorts(
        ports: List<SbxPortMapping>,
        sandboxPort: Int = OpenCodeServerProtocol.SANDBOX_SERVE_PORT,
        desiredHostPort: Int? = null,
    ): List<Int> {
        val matching = sandboxPortMappings(ports, sandboxPort).filter { it.hostIp == "127.0.0.1" }
        val preferred =
            matching.filter { it.protocol.equals("tcp4", ignoreCase = true) }.ifEmpty { matching }
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
                    entry.workspaces.any {
                        OpenCodeServerProtocol.isSameFilesystemPath(it, workspace)
                    }
        }
    }

    fun diagnoseReportsUnsupported(json: String): Boolean {
        val root = runCatching {
            JsonParser.parseString(json)
        }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        if (root != null) {
            if (root.booleanMember("supported") == false) return true
            val virtualization = root.objectMember("virtualization")
            if (virtualization?.booleanMember("supported") == false) return true
            if (virtualization?.booleanMember("available") == false) return true
            val checks = root.get("checks")?.takeIf { it.isJsonArray }?.asJsonArray
            if (
                checks?.any {
                    val check = it.takeIf { it.isJsonObject }?.asJsonObject
                    check?.stringMember("name").equals("Virtualization", ignoreCase = true) &&
                        check?.stringMember("status") == "fail"
                } == true
            )
                return true
            val errors = root.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
            if (errors != null) {
                val text =
                    errors.joinToString(" ") { element ->
                        if (element.isJsonPrimitive && element.asJsonPrimitive.isString)
                            element.asString
                        else ""
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
        val ports =
            obj.get("ports")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.let { parsePortArray(it) }
                .orEmpty()
        val workspaces =
            obj.get("workspaces")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
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
