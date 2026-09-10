package de.moritzf.opencodewebpanel.server

internal data class SbxSetupStep(
    val label: String,
    val done: Boolean,
    val detail: String,
)

internal object SbxSetupChecklist {
    fun appSteps(sbxFound: Boolean, policyReady: Boolean): List<SbxSetupStep> = listOf(
        SbxSetupStep(
            "Detect sbx",
            sbxFound,
            if (sbxFound) "sbx is available" else "Install Docker Sandboxes and Detect its path",
        ),
        SbxSetupStep(
            "Network policy",
            policyReady,
            if (policyReady) "sbx policy init succeeded" else "Run Set up policy",
        ),
    )

    fun projectSteps(useSandbox: Boolean, owned: Boolean, running: Boolean): List<SbxSetupStep> = listOf(
        SbxSetupStep(
            "Sandbox runtime",
            useSandbox,
            if (useSandbox) "Docker Sandbox selected" else "Host CLI selected",
        ),
        SbxSetupStep(
            "Owned VM",
            owned,
            if (owned) "Plugin owns this sandbox" else "Start or Adopt to create one",
        ),
        SbxSetupStep(
            "OpenCode serve",
            running,
            if (running) "Healthy" else "Not running",
        ),
    )

    fun format(steps: List<SbxSetupStep>): String {
        return steps.joinToString("\n") { step ->
            val mark = if (step.done) "✓" else "[ ]"
            "$mark ${step.label}: ${step.detail}"
        }
    }
}
