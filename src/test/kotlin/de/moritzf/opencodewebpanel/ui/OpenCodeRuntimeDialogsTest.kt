package de.moritzf.opencodewebpanel.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeRuntimeDialogsTest {
    @Test
    fun sandboxUpdateNamesWhatEachChoiceInstallsAndDrops() {
        assertEquals(
            "OpenCode 2.3.1 is available. This sandbox runs 2.2.7.\n\n" +
                "Upgrade in Sandbox installs OpenCode 2.3.1 inside this VM with OpenCode's own " +
                "installer and restarts OpenCode. The VM and sessions stay; a later Reset Sandbox " +
                "returns to Docker's image.\n\n" +
                "Reset Sandbox recreates the VM from Docker's OpenCode image, which has OpenCode " +
                "2.3.0. Packages and other VM-only state are dropped. Retention.",
            openCodeSandboxUpdateMessage("2.2.7", "2.3.1", "2.3.0", "Retention."),
        )
        assertEquals(
            "OpenCode 2.3.1 is available. This sandbox runs 2.3.0.\n\n" +
                "Upgrade in Sandbox installs OpenCode 2.3.1 inside this VM with OpenCode's own " +
                "installer and restarts OpenCode. The VM and sessions stay; a later Reset Sandbox " +
                "returns to Docker's image.\n\n" +
                "Docker's OpenCode image has no newer release yet, so Reset Sandbox would not " +
                "update OpenCode.",
            openCodeSandboxUpdateMessage("2.3.0", "2.3.1", null, "Retention."),
        )
        assertTrue(
            openCodeSandboxUpdateMessage(
                    "2.2.7",
                    "2.3.1",
                    null,
                    "Retention.",
                    pinnedImage = "2.2.7",
                )
                .endsWith(
                    "This project pins Docker's OpenCode image to 2.2.7, so Reset Sandbox would not update OpenCode.",
                ),
        )
    }
}
