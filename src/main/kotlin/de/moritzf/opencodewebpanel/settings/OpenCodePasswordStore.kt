package de.moritzf.opencodewebpanel.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol

@Service(Service.Level.APP)
class OpenCodePasswordStore {
    data class PasswordUpdate(val previous: String?, val current: String)

    private val attributes = CredentialAttributes(SERVICE_NAME, USER_NAME)
    private val lock = Any()
    private var cachedPassword: String? = null

    private fun loadBlocking(): String? {
        return synchronized(lock) {
            cachedPassword ?: readPasswordSafe().also { cachedPassword = it }
        }
    }

    fun loadFreshBlocking(): String? {
        return synchronized(lock) {
            readPasswordSafe().also { cachedPassword = it }
        }
    }

    fun cachedPassword(): String? = synchronized(lock) { cachedPassword }

    fun ensurePasswordBlocking(): String {
        return synchronized(lock) {
            loadBlocking() ?: regenerateBlocking()
        }
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
        synchronized(lock) {
            PasswordSafe.instance.set(attributes, sanitized?.let { Credentials(USER_NAME, it) })
            cachedPassword = sanitized
        }
    }

    /** Resolves the current credential and applies an optional edit as one serialized operation. */
    fun resolveAndSaveBlocking(editedPassword: String?): PasswordUpdate {
        return synchronized(lock) {
            val previous = readPasswordSafe().also { cachedPassword = it }
            val current = editedPassword
                ?: previous
                ?: generatePasswordForEditing()
            if (current != previous) {
                PasswordSafe.instance.set(attributes, Credentials(USER_NAME, current))
            }
            cachedPassword = current
            PasswordUpdate(previous, current)
        }
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
