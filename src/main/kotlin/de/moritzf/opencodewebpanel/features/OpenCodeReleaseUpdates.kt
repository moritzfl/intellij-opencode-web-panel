package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.text.SemVer
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.net.HttpURLConnection
import java.net.URI

/**
 * Optional same-major OpenCode upgrade action on the tool-window title bar.
 * 1.x → npm `opencode-ai`; 2.x → npm `@opencode/cli`. Never suggests crossing a major version.
 */
internal object OpenCodeReleaseUpdates {
    const val NPM_V1_LATEST_URL = "https://registry.npmjs.org/opencode-ai/latest"
    const val NPM_V2_LATEST_URL = "https://registry.npmjs.org/@opencode/cli/latest"

    data class Notice(
        val installed: String,
        val latest: String,
    )

    fun catalogUrl(major: Int): String? = when {
        major == 1 -> NPM_V1_LATEST_URL
        major >= 2 -> NPM_V2_LATEST_URL
        else -> null
    }

    fun parseNpmLatestVersion(json: String): String? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        return root.get("version")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private class Check {
        @Volatile var notice: Notice? = null
    }
    private val checkKey = Key.create<Check>("opencode.releaseUpdateCheck")

    fun pendingNotice(project: Project, installedVersion: String?): Notice? {
        if (!OpenCodeSettingsState.getInstance().notifyOpenCodeUpdates) return null
        return project.getUserData(checkKey)?.notice
            ?.takeIf { it.installed == installedVersion?.let(::stripPrefix) }
    }

    fun tooltip(notice: Notice, sandbox: Boolean = false): String {
        val base = "OpenCode ${notice.latest} is available. Consider upgrading. Installed: ${notice.installed}."
        return if (sandbox) {
            "$base Click to upgrade this sandbox."
        } else {
            "$base Click for Host CLI upgrade steps."
        }
    }

    const val HOST_UPGRADE_COMMAND = "opencode upgrade"

    fun hostUpgradeIntro(notice: Notice): String =
        "OpenCode ${notice.latest} is available (installed ${notice.installed})."

    fun notice(
        installed: String,
        latest: String?,
    ): Notice? {
        val installedSem = parseSemVer(installed) ?: return null
        val latestSem = parseSemVer(latest) ?: return null
        if (installedSem.major != latestSem.major) return null
        if (latestSem <= installedSem) return null
        return Notice(installed = stripPrefix(installed), latest = stripPrefix(latest!!))
    }

    fun checkAndIndicate(project: Project, installedVersion: String?, onReady: () -> Unit) {
        val check = Check()
        project.putUserData(checkKey, check)
        val application = ApplicationManager.getApplication()
        application.invokeLater {
            if (!project.isDisposed && project.getUserData(checkKey) === check) onReady()
        }
        if (!OpenCodeSettingsState.getInstance().notifyOpenCodeUpdates) return
        val installed = installedVersion?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        application.executeOnPooledThread {
            val notice = evaluate(installed)
            application.invokeLater {
                if (project.isDisposed || project.getUserData(checkKey) !== check) return@invokeLater
                if (OpenCodeSettingsState.getInstance().notifyOpenCodeUpdates) {
                    check.notice = notice
                }
                onReady()
            }
        }
    }

    internal fun evaluate(
        installed: String,
        fetch: (String) -> String? = ::readUrl,
    ): Notice? {
        val major = parseSemVer(installed)?.major ?: return null
        val url = catalogUrl(major) ?: return null
        val latest = parseNpmLatestVersion(fetch(url) ?: return null)
        return notice(installed, latest)
    }

    private fun parseSemVer(version: String?): SemVer? {
        val text = version?.let(::stripPrefix)?.takeIf { it.isNotEmpty() } ?: return null
        return SemVer.parseFromText(text)
    }

    private fun stripPrefix(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")

    private fun readUrl(url: String): String? {
        return try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 3_000
                connection.readTimeout = 5_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "OpenCode-Web-Panel")
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { it.readNBytes(256 * 1024 + 1) }
                    .takeIf { it.size <= 256 * 1024 }?.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
