package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ProxyAuthentication
import com.intellij.util.net.ProxyConfiguration
import com.intellij.util.net.ProxySettings
import de.moritzf.opencodewebpanel.settings.OpenCodeProxyMode
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale

internal data class IdeHttpProxy(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val exceptions: String = "",
    val username: String? = null,
    val password: String? = null,
) {
    enum class Protocol { HTTP, SOCKS }
}

internal object OpenCodeProcessProxyEnvironment {
    private val LOOPBACK_NO_PROXY = listOf("127.0.0.1", "localhost", "::1")
    private val PROXY_URL_NAMES = listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")
    private val PROXY_PROBE_URI = URI.create("https://example.com")

    fun apply(environment: MutableMap<String, String>, ideProxy: IdeHttpProxy?) {
        val usable = ideProxy?.takeIf { it.host.isNotBlank() && it.port in 1..65535 }
        if (usable != null) {
            val url = formatProxyUrl(usable)
            putVar(environment, "HTTP_PROXY", url)
            putVar(environment, "HTTPS_PROXY", url)
            if (usable.protocol == IdeHttpProxy.Protocol.SOCKS) {
                putVar(environment, "ALL_PROXY", url)
            }
            mergeNoProxy(environment, usable.exceptions)
            return
        }
        if (hasProxyUrl(environment)) {
            mergeNoProxy(environment, extra = "")
        }
    }

    fun strip(environment: MutableMap<String, String>) {
        for (name in PROXY_URL_NAMES) {
            environment.keys.filter { it.equals(name, ignoreCase = true) }.forEach { environment.remove(it) }
        }
    }

    fun resolveFromSettings(settings: OpenCodeSettingsState): IdeHttpProxy? {
        return when (settings.proxyModeValue()) {
            OpenCodeProxyMode.IDE -> readIdeProxy()
            OpenCodeProxyMode.ENVIRONMENT, OpenCodeProxyMode.NONE -> null
        }
    }

    fun readIdeProxy(): IdeHttpProxy? {
        return try {
            val application = ApplicationManager.getApplication() ?: return null
            if (application.isDisposed) return null
            val resolved = when (val configuration = ProxySettings.getInstance().getProxyConfiguration()) {
                is ProxyConfiguration.StaticProxyConfiguration -> fromStaticProxy(configuration)
                is ProxyConfiguration.AutoDetectProxy,
                is ProxyConfiguration.ProxyAutoConfiguration -> resolveSelectedProxy()
                else -> null
            }
            resolved?.let { withKnownCredentials(it) }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun fromJavaProxy(proxy: Proxy): IdeHttpProxy? {
        if (proxy.type() == Proxy.Type.DIRECT) return null
        val address = proxy.address() as? InetSocketAddress ?: return null
        val host = address.hostString?.trim().orEmpty()
        if (host.isBlank() || address.port !in 1..65535) return null
        val protocol = when (proxy.type()) {
            Proxy.Type.HTTP -> IdeHttpProxy.Protocol.HTTP
            Proxy.Type.SOCKS -> IdeHttpProxy.Protocol.SOCKS
            else -> return null
        }
        return IdeHttpProxy(protocol, host, address.port)
    }

    private fun fromStaticProxy(static: ProxyConfiguration.StaticProxyConfiguration): IdeHttpProxy? {
        if (static.host.isBlank() || static.port !in 1..65535) return null
        return IdeHttpProxy(
            protocol = when (static.protocol) {
                ProxyConfiguration.ProxyProtocol.HTTP -> IdeHttpProxy.Protocol.HTTP
                ProxyConfiguration.ProxyProtocol.SOCKS -> IdeHttpProxy.Protocol.SOCKS
            },
            host = static.host.trim(),
            port = static.port,
            exceptions = static.exceptions,
        )
    }

    private fun resolveSelectedProxy(): IdeHttpProxy? {
        val selected = JdkProxyProvider.getInstance().proxySelector.select(PROXY_PROBE_URI) ?: return null
        return selected.asSequence().mapNotNull { fromJavaProxy(it) }.firstOrNull()
    }

    private fun withKnownCredentials(proxy: IdeHttpProxy): IdeHttpProxy {
        if (!proxy.username.isNullOrBlank()) return proxy
        val credentials = try {
            ProxyAuthentication.getInstance().getKnownAuthentication(proxy.host, proxy.port)
        } catch (_: Throwable) {
            null
        } ?: return proxy
        return proxy.copy(
            username = credentials.userName?.takeIf { it.isNotBlank() } ?: proxy.username,
            password = credentials.getPasswordAsString()?.takeIf { it.isNotEmpty() } ?: proxy.password,
        )
    }

    internal fun formatProxyUrl(proxy: IdeHttpProxy): String {
        val scheme = when (proxy.protocol) {
            IdeHttpProxy.Protocol.HTTP -> "http"
            IdeHttpProxy.Protocol.SOCKS -> "socks5"
        }
        val host = formatHost(proxy.host)
        val userInfo = formatUserInfo(proxy.username, proxy.password)
        return "$scheme://$userInfo$host:${proxy.port}"
    }

    private fun formatHost(host: String): String {
        return if (':' in host && !host.startsWith('[')) "[$host]" else host
    }

    private fun formatUserInfo(username: String?, password: String?): String {
        if (username.isNullOrEmpty()) return ""
        val user = encodeUserInfo(username)
        val pass = password?.takeIf { it.isNotEmpty() }?.let { ":${encodeUserInfo(it)}" }.orEmpty()
        return "$user$pass@"
    }

    private fun encodeUserInfo(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char.isLetterOrDigit() || char == '-' || char == '.' || char == '_' || char == '~') {
                out.append(char)
            } else {
                out.append('%').append("%02X".format(code))
            }
        }
        return out.toString()
    }

    private fun mergeNoProxy(environment: MutableMap<String, String>, extra: String) {
        val items = linkedSetOf<String>()
        val seen = hashSetOf<String>()
        fun addParts(raw: String) {
            for (part in raw.split(',', ';', ' ', '\t', '\n')) {
                val item = part.trim()
                if (item.isEmpty()) continue
                if (seen.add(item.lowercase(Locale.ROOT))) items.add(item)
            }
        }
        addParts(getVar(environment, "NO_PROXY").orEmpty())
        addParts(extra)
        for (loopback in LOOPBACK_NO_PROXY) addParts(loopback)
        putVar(environment, "NO_PROXY", items.joinToString(","))
    }

    private fun hasProxyUrl(environment: Map<String, String>): Boolean {
        return PROXY_URL_NAMES.any { name -> !getVar(environment, name).isNullOrBlank() }
    }

    private fun getVar(environment: Map<String, String>, name: String): String? {
        return environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun putVar(environment: MutableMap<String, String>, canonicalUpper: String, value: String) {
        val lower = canonicalUpper.lowercase(Locale.ROOT)
        environment.keys.filter { it.equals(canonicalUpper, ignoreCase = true) }.forEach { environment.remove(it) }
        environment[canonicalUpper] = value
        environment[lower] = value
    }
}
