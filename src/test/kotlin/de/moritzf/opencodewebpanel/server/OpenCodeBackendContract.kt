package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** Shared assertions exercised against both concrete backends after real cleanup completion. */
internal fun assertStoppedBackend(
    backend: OpenCodeServerBackend,
    authUrl: String? = null,
    authPassword: String? = null,
) {
    assertEquals(OpenCodeServerLifecycleState.STOPPED, backend.getLifecycleState())
    assertNull(backend.getConnection())
    assertNull(backend.getServerUrl())
    assertNull(backend.getServerPassword())
    assertEquals(authUrl, backend.getAuthServerUrl())
    assertEquals(authPassword, backend.getAuthPassword())
}
