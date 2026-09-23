package de.moritzf.opencodewebpanel.server

/** Explicit setup checks against an owned, running VM. Never starts a VM or changes network policy. */
internal object SbxSetupDiagnostics {
    fun check(
        executable: String,
        spec: SbxLaunchSpec,
        record: SbxSandboxRecord?,
        runner: SbxCommandRunner = SbxProcessRunner,
    ): List<SbxSetupStep> {
        val steps = mutableListOf<SbxSetupStep>()
        if (record == null) return steps + SbxSetupStep("Guest network", false, "Start or Adopt this sandbox before checking guest access")
        val inventory = runner.run(SbxCli.buildLsCommand(executable), emptyMap(), 15_000L)
        val entries = if (inventory.exitCode == 0) SbxCli.parseLsJsonOrNull(inventory.stdout) else null
        if (entries == null) return steps + SbxSetupStep("Guest network", false, "Could not read sandbox inventory; no guest checks ran")
        val owned = SbxCli.findOwnedSandbox(entries, record)
        if (owned == null) return steps + SbxSetupStep("Guest network", false, "Sandbox ownership changed; no guest checks ran")
        if (owned.status != "running") return steps + SbxSetupStep("Guest network", false, "Start this sandbox before checking guest access")
        for ((host, url) in networkTargets(spec.openCodeVersion)) {
            val result = runner.run(SbxCli.buildNetworkProbeCommand(executable, owned.name, url), emptyMap(), 15_000L)
            val status = SbxCli.networkProbeStatus(result.stdout)
            val ok = result.exitCode == 0 && status != null && status in 200..299
            val detail = when {
                ok -> "Reachable (HTTP $status)"
                status == 403 -> "HTTP 403; check this sandbox's network policy or upstream access rules"
                status != null && status > 0 -> "HTTP $status; check guest network access and upstream availability"
                else -> "Connection failed (exit ${result.exitCode}); check guest DNS, network policy, and connectivity"
            }
            steps += SbxSetupStep(host, ok, detail)
        }
        return steps
    }

    fun networkTargets(version: SbxOpenCodeVersion): List<Pair<String, String>> = buildList {
        if (version == SbxOpenCodeVersion.V2) {
            add("opencode.ai" to SbxCli.V2_INSTALL_URL)
            add("registry.npmjs.org" to "https://registry.npmjs.org/@opencode%2fcli/latest")
        }
        add("models.opencode.ai" to "https://models.opencode.ai/api.json")
    }
}
