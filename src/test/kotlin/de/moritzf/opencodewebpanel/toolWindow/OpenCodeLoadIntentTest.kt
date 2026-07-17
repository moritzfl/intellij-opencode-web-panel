package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeLoadIntentTest {
    @Test
    fun onlyLatestMatchingLoadIsAccepted() {
        val intent = OpenCodeLoadIntent()
        val first = intent.begin()
        val second = intent.begin()

        assertFalse(intent.accepts(first, 1, 1, "http://a", "http://a", "/a", "/a", true))
        assertTrue(intent.accepts(second, 1, 1, "http://a", "http://a", "/a", "/a", true))
        assertFalse(intent.accepts(second, 1, 2, "http://a", "http://a", "/a", "/a", true))
        assertFalse(intent.accepts(second, 1, 1, "http://a", "http://b", "/a", "/a", true))
        assertFalse(intent.accepts(second, 1, 1, "http://a", "http://a", "/a", "/b", true))
        assertFalse(intent.accepts(second, 1, 1, "http://a", "http://a", "/a", "/a", false))

        intent.invalidate()
        assertFalse(intent.accepts(second, 1, 1, "http://a", "http://a", "/a", "/a", true))
    }
}
