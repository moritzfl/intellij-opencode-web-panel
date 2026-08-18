package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeStartupNavigationTest {
    @Test
    fun keepsIntentWhileSessionLookupIsInFlight() {
        assertTrue(OpenCodeStartupNavigation.shouldKeepNavigateIntent(true, null, true))
    }

    @Test
    fun keepsIntentOnceASessionIdIsKnown() {
        assertTrue(OpenCodeStartupNavigation.shouldKeepNavigateIntent(true, "ses_abc", false))
        assertTrue(OpenCodeStartupNavigation.shouldKeepNavigateIntent(true, "ses_abc", true))
    }

    @Test
    fun dropsIntentWhenLookupFinishedWithNothing() {
        assertFalse(OpenCodeStartupNavigation.shouldKeepNavigateIntent(true, null, false))
        assertFalse(OpenCodeStartupNavigation.shouldKeepNavigateIntent(true, "", false))
    }

    @Test
    fun dropsIntentWhenMostRecentStartupIsOff() {
        assertFalse(OpenCodeStartupNavigation.shouldKeepNavigateIntent(false, "ses_abc", true))
        assertFalse(OpenCodeStartupNavigation.shouldKeepNavigateIntent(false, null, true))
    }

    @Test
    fun skipMostRecentLookupWhenRestoringAnExistingSession() {
        assertFalse(OpenCodeStartupNavigation.shouldLookupMostRecentSession(true, true))
        assertFalse(OpenCodeStartupNavigation.shouldLookupMostRecentSession(true, false))
        assertTrue(OpenCodeStartupNavigation.shouldLookupMostRecentSession(false, true))
        assertFalse(OpenCodeStartupNavigation.shouldLookupMostRecentSession(false, false))
    }

    @Test
    fun jvmNavigateOnlyFromTheIdLessShell() {
        assertTrue(OpenCodeStartupNavigation.shouldJvmNavigateToResolvedSession(null, "ses_abc"))
        assertTrue(OpenCodeStartupNavigation.shouldJvmNavigateToResolvedSession("", "ses_abc"))
        assertFalse(OpenCodeStartupNavigation.shouldJvmNavigateToResolvedSession("ses_abc", "ses_abc"))
        assertFalse(OpenCodeStartupNavigation.shouldJvmNavigateToResolvedSession("ses_other", "ses_abc"))
        assertFalse(OpenCodeStartupNavigation.shouldJvmNavigateToResolvedSession(null, null))
    }
}
