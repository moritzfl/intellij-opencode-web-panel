package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxCommandResult

internal enum class SbxPolicyStatus {
    CHECKING,
    PRESENT,
    ABSENT,
    UNKNOWN,
}

internal data class SbxPolicyCheck(val status: SbxPolicyStatus, val detail: String? = null)

/**
 * Maps `sbx policy ls --json` to what the settings page shows. Only a readable rule list without a
 * global rule is ABSENT; a failed or unexpected answer stays UNKNOWN, because ABSENT clears the
 * stored consent that sandbox starts rely on.
 */
internal fun sbxPolicyCheckOf(found: Boolean, result: SbxCommandResult?): SbxPolicyCheck =
    when {
        !found -> SbxPolicyCheck(SbxPolicyStatus.UNKNOWN, "sbx was not found")
        result == null -> SbxPolicyCheck(SbxPolicyStatus.UNKNOWN, "sbx could not be run")
        result.exitCode != 0 ->
            SbxPolicyCheck(
                SbxPolicyStatus.UNKNOWN,
                result.output
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?.take(160) ?: "sbx exited with ${result.exitCode}",
            )
        else ->
            when (SbxCli.policyState(result.stdout)) {
                true -> SbxPolicyCheck(SbxPolicyStatus.PRESENT)
                false -> SbxPolicyCheck(SbxPolicyStatus.ABSENT)
                null -> SbxPolicyCheck(SbxPolicyStatus.UNKNOWN, "unexpected answer from sbx")
            }
    }
