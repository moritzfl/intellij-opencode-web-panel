package de.moritzf.opencodewebpanel.configuration

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePasswordStoreTest {
    @Test
    fun generatePasswordReturnsIndependentUrlSafeSecrets() {
        val store = OpenCodePasswordStore()
        val first = store.generatePasswordForEditing()
        val second = store.generatePasswordForEditing()
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(32, Base64.getUrlDecoder().decode(first).size)
        assertNotEquals(first, second)
    }
}
