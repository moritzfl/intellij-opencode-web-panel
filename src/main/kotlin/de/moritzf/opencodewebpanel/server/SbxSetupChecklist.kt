package de.moritzf.opencodewebpanel.server

internal data class SbxSetupStep(
    val label: String,
    val done: Boolean,
    val detail: String,
)

internal object SbxSetupChecklist {
    /** Marks a step that has not happened yet, as opposed to [FAILED]. */
    const val PENDING = "○"
    const val FAILED = "✗"

    fun projectSteps(owned: Boolean, running: Boolean): List<SbxSetupStep> =
        listOf(
            SbxSetupStep(
                "Sandbox",
                owned,
                if (owned) "created" else "created when OpenCode starts",
            ),
            SbxSetupStep(
                "OpenCode",
                running,
                if (running) "running" else "not running",
            ),
        )

    fun format(steps: List<SbxSetupStep>, openMark: String = FAILED): String {
        return steps.joinToString("\n") { step ->
            val mark = if (step.done) "✓" else openMark
            "$mark ${step.label}: ${step.detail}"
        }
    }
}
