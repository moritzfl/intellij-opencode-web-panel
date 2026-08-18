package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class OpenCodeProcessProxyEnvironmentTest {

    @Test
    fun applySetsHttpAndHttpsProxyFromManualIdeProxy() {
        val environment = mutableMapOf<String, String>()

        OpenCodeProcessProxyEnvironment.apply(
            environment,
            IdeHttpProxy(IdeHttpProxy.Protocol.HTTP, "127.0.0.1", 7897),
        )

        assertEquals("http://127.0.0.1:7897", env(environment, "HTTP_PROXY"))
        assertEquals("http://127.0.0.1:7897", env(environment, "HTTPS_PROXY"))
        assertEquals("http://127.0.0.1:7897", env(environment, "http_proxy"))
        assertEquals("http://127.0.0.1:7897", env(environment, "https_proxy"))
        assertNull(env(environment, "ALL_PROXY"))
        assertContainsLoopback(environment)
    }

    @Test
    fun applyEncodesProxyCredentialsInUserInfo() {
        val environment = mutableMapOf<String, String>()

        OpenCodeProcessProxyEnvironment.apply(
            environment,
            IdeHttpProxy(
                protocol = IdeHttpProxy.Protocol.HTTP,
                host = "127.0.0.1",
                port = 7897,
                username = "admin",
                password = "p@ss:w/d",
            ),
        )

        assertEquals("http://admin:p%40ss%3Aw%2Fd@127.0.0.1:7897", env(environment, "HTTP_PROXY"))
    }

    @Test
    fun applyOmitsPasswordWhenOnlyUsernameIsPresent() {
        val environment = mutableMapOf<String, String>()

        OpenCodeProcessProxyEnvironment.apply(
            environment,
            IdeHttpProxy(
                protocol = IdeHttpProxy.Protocol.HTTP,
                host = "proxy.example",
                port = 8080,
                username = "admin",
            ),
        )

        assertEquals("http://admin@proxy.example:8080", env(environment, "HTTPS_PROXY"))
    }

    @Test
    fun applySetsAllProxyForSocks() {
        val environment = mutableMapOf<String, String>()

        OpenCodeProcessProxyEnvironment.apply(
            environment,
            IdeHttpProxy(IdeHttpProxy.Protocol.SOCKS, "10.0.0.1", 1080),
        )

        assertEquals("socks5://10.0.0.1:1080", env(environment, "HTTP_PROXY"))
        assertEquals("socks5://10.0.0.1:1080", env(environment, "HTTPS_PROXY"))
        assertEquals("socks5://10.0.0.1:1080", env(environment, "ALL_PROXY"))
        assertEquals("socks5://10.0.0.1:1080", env(environment, "all_proxy"))
    }

    @Test
    fun applyWrapsIpv6Host() {
        assertEquals(
            "http://[::1]:8080",
            OpenCodeProcessProxyEnvironment.formatProxyUrl(
                IdeHttpProxy(IdeHttpProxy.Protocol.HTTP, "::1", 8080),
            ),
        )
    }

    @Test
    fun applyMergesExceptionsAndExistingNoProxy() {
        val environment = mutableMapOf("NO_PROXY" to "example.com")

        OpenCodeProcessProxyEnvironment.apply(
            environment,
            IdeHttpProxy(
                protocol = IdeHttpProxy.Protocol.HTTP,
                host = "127.0.0.1",
                port = 7897,
                exceptions = "127.0.0.1,*.internal",
            ),
        )

        val noProxy = env(environment, "NO_PROXY")!!
        assertTrue(noProxy.contains("example.com"))
        assertTrue(noProxy.contains("*.internal"))
        assertContainsLoopback(environment)
        assertEquals(1, noProxy.split(',').count { it.equals("127.0.0.1", ignoreCase = true) })
    }

    @Test
    fun applyOverwritesExistingLowercaseProxyWhenIdeProxyIsPresent() {
        val environment = mutableMapOf("http_proxy" to "http://stale:1")

        OpenCodeProcessProxyEnvironment.apply(
            environment,
            IdeHttpProxy(IdeHttpProxy.Protocol.HTTP, "127.0.0.1", 7897),
        )

        assertEquals("http://127.0.0.1:7897", env(environment, "http_proxy"))
        assertEquals("http://127.0.0.1:7897", env(environment, "HTTP_PROXY"))
    }

    @Test
    fun applyLeavesEnvAloneWhenNoProxyIsConfigured() {
        val environment = mutableMapOf("PATH" to "/usr/bin")

        OpenCodeProcessProxyEnvironment.apply(environment, null)

        assertEquals(mapOf("PATH" to "/usr/bin"), environment)
    }

    @Test
    fun applyIgnoresBlankOrInvalidIdeProxy() {
        val environment = mutableMapOf("PATH" to "/usr/bin")

        OpenCodeProcessProxyEnvironment.apply(environment, IdeHttpProxy(IdeHttpProxy.Protocol.HTTP, "", 7897))
        OpenCodeProcessProxyEnvironment.apply(environment, IdeHttpProxy(IdeHttpProxy.Protocol.HTTP, "host", 0))

        assertEquals(mapOf("PATH" to "/usr/bin"), environment)
        assertNull(env(environment, "HTTP_PROXY"))
    }

    @Test
    fun stripRemovesInheritedProxyVariables() {
        val environment = mutableMapOf(
            "HTTP_PROXY" to "http://corp:8080",
            "https_proxy" to "http://corp:8080",
            "ALL_PROXY" to "socks5://corp:1080",
            "NO_PROXY" to "localhost",
            "PATH" to "/usr/bin",
        )

        OpenCodeProcessProxyEnvironment.strip(environment)

        assertNull(env(environment, "HTTP_PROXY"))
        assertNull(env(environment, "HTTPS_PROXY"))
        assertNull(env(environment, "ALL_PROXY"))
        assertEquals("localhost", env(environment, "NO_PROXY"))
        assertEquals("/usr/bin", env(environment, "PATH"))
    }

    @Test
    fun fromJavaProxyMapsHttpAndSocksAddresses() {
        val http = OpenCodeProcessProxyEnvironment.fromJavaProxy(
            Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 7897)),
        )
        val socks = OpenCodeProcessProxyEnvironment.fromJavaProxy(
            Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("10.0.0.1", 1080)),
        )

        assertEquals(IdeHttpProxy(IdeHttpProxy.Protocol.HTTP, "127.0.0.1", 7897), http)
        assertEquals(IdeHttpProxy(IdeHttpProxy.Protocol.SOCKS, "10.0.0.1", 1080), socks)
    }

    @Test
    fun fromJavaProxyIgnoresDirectAndInvalidAddresses() {
        assertNull(OpenCodeProcessProxyEnvironment.fromJavaProxy(Proxy.NO_PROXY))
        assertNull(
            OpenCodeProcessProxyEnvironment.fromJavaProxy(
                Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 0)),
            ),
        )
    }

    @Test
    fun applyAddsLoopbackNoProxyWhenInheritedProxyExists() {
        val environment = mutableMapOf("HTTP_PROXY" to "http://corp:8080")

        OpenCodeProcessProxyEnvironment.apply(environment, null)

        assertEquals("http://corp:8080", env(environment, "HTTP_PROXY"))
        assertContainsLoopback(environment)
    }

    private fun env(environment: Map<String, String>, name: String): String? {
        return environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun assertContainsLoopback(environment: Map<String, String>) {
        val noProxy = env(environment, "NO_PROXY")
        assertFalse(noProxy.isNullOrBlank())
        val items = noProxy!!.split(',').map { it.trim().lowercase() }
        assertTrue(items.contains("127.0.0.1"))
        assertTrue(items.contains("localhost"))
        assertTrue(items.contains("::1"))
    }
}
