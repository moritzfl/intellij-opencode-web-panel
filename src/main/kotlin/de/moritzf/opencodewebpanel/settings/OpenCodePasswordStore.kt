package de.moritzf.opencodewebpanel.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.APP)
class OpenCodePasswordStore {
    private val attributes = CredentialAttributes(SERVICE_NAME, USER_NAME)
    private val cachedPassword = AtomicReference<String?>()

    private fun loadBlocking(): String? {
        cachedPassword.get()?.let { return it }
        val password = readPasswordSafe()
        cachedPassword.set(password)
        return password
    }

    fun loadFreshBlocking(): String? {
        val password = readPasswordSafe()
        cachedPassword.set(password)
        return password
    }

    fun cachedPassword(): String? = cachedPassword.get()

    fun ensurePasswordBlocking(): String {
        loadBlocking()?.let { return it }
        return regenerateBlocking()
    }

    private fun regenerateBlocking(): String {
        val password = generatePasswordForEditing()
        saveBlocking(password)
        return password
    }

    fun generatePasswordForEditing(): String {
        return OpenCodeServerProtocol.generateServerPassword()
    }

    fun saveBlocking(password: String?) {
        val sanitized = password?.ifBlank { null }
        PasswordSafe.instance.set(attributes, sanitized?.let { Credentials(USER_NAME, it) })
        cachedPassword.set(sanitized)
    }

    /**
     * Reads the stored password, returning null only when no credential is present.
     *
     * Exceptions from secure storage (e.g. a locked or unavailable keychain) are intentionally
     * propagated rather than swallowed: a transient read failure must not be mistaken for an absent
     * password, which would cause callers such as [ensurePasswordBlocking] to silently regenerate and
     * overwrite the server password.
     */
    private fun readPasswordSafe(): String? {
        return PasswordSafe.instance.get(attributes)?.getPasswordAsString()?.ifBlank { null }
    }

    companion object {
        private const val SERVICE_NAME = "OpenCode Web Panel Server Password"
        private const val USER_NAME = "opencode-server-password"

        fun getInstance(): OpenCodePasswordStore {
            return ApplicationManager.getApplication().getService(OpenCodePasswordStore::class.java)
        }
    }
}
