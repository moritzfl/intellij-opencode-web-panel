package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.server.OpenCodeExecutableProbe
import de.moritzf.opencodewebpanel.server.SbxCli

/** One line under an executable choice; [problem] renders it as an error. */
internal data class ExecutableStatus(val text: String, val problem: Boolean)

internal fun openCodeExecutableStatus(
    probe: OpenCodeExecutableProbe,
    custom: Boolean,
    entered: String,
): ExecutableStatus {
    val path = probe.path
    return when {
        path != null ->
            ExecutableStatus(
                "Uses $path" + probe.version?.let { " (OpenCode $it)" }.orEmpty(),
                false,
            )
        custom && entered.isEmpty() ->
            ExecutableStatus("Enter the path to opencode, or choose Auto detect.", true)
        custom -> ExecutableStatus("Not found or not executable: $entered", true)
        else ->
            ExecutableStatus(
                "opencode was not found on PATH or in common install locations. " +
                    "Install OpenCode, or choose its path.",
                true,
            )
    }
}

/** What a sandbox start needs from this computer, in one line under the runtime choice. */
internal fun sandboxPrerequisitesText(
    probe: OpenCodeExecutableProbe,
    policyReady: Boolean,
): ExecutableStatus {
    val sbx =
        when {
            probe.path == null -> "sbx not found"
            probe.tooOld -> "sbx ${probe.version} is too old (needs ${SbxCli.MINIMUM_SBX_VERSION})"
            else -> probe.version?.let { "sbx $it" } ?: "sbx found"
        }
    val policy = if (policyReady) "network policy set up" else "no network policy yet"
    val ready = probe.path != null && !probe.tooOld && policyReady
    return ExecutableStatus((if (ready) "Ready: " else "Needs setup: ") + "$sbx · $policy", !ready)
}

/** sbx is optional: missing is only a problem when the user pointed at a path. */
internal fun sbxExecutableStatus(
    probe: OpenCodeExecutableProbe,
    custom: Boolean,
    entered: String,
): ExecutableStatus {
    val path = probe.path
    return when {
        path != null && probe.tooOld ->
            ExecutableStatus(
                "Uses $path (sbx ${probe.version}). Docker Sandboxes need sbx " +
                    "${SbxCli.MINIMUM_SBX_VERSION} or later; update sbx.",
                true,
            )
        path != null ->
            ExecutableStatus("Uses $path" + probe.version?.let { " (sbx $it)" }.orEmpty(), false)
        custom && entered.isEmpty() ->
            ExecutableStatus("Enter the path to sbx, or choose Auto detect.", true)
        custom -> ExecutableStatus("Not found or not executable: $entered", true)
        else ->
            ExecutableStatus(
                "sbx was not found. Only Docker Sandbox projects need it.",
                false,
            )
    }
}
