package de.moritzf.opencodewebpanel.server

/** [skipped]: the step has not happened or did not run yet, which is no failure. */
internal data class SbxSetupStep(
    val label: String,
    val done: Boolean,
    val detail: String,
    val skipped: Boolean = false,
)

internal object SbxSetupChecklist {
    fun projectSteps(owned: Boolean, running: Boolean): List<SbxSetupStep> =
        listOf(
            SbxSetupStep(
                "Sandbox",
                owned,
                if (owned) "created" else "created when OpenCode starts",
                skipped = !owned,
            ),
            SbxSetupStep(
                "OpenCode",
                running,
                if (running) "running" else "not running",
                skipped = !running,
            ),
        )

    /** ✓ done, ○ not run yet, ✗ failed. */
    fun format(steps: List<SbxSetupStep>): String {
        return steps.joinToString("\n") { step ->
            val mark =
                when {
                    step.done -> "✓"
                    step.skipped -> "○"
                    else -> "✗"
                }
            "$mark ${step.label}: ${step.detail}"
        }
    }
}
