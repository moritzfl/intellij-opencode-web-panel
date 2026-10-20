package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Opt-in check of the installed CLI, using the plugin's command builders and parsers. */
object SbxContractCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val executable = args[0]
        val live = args[1].toBooleanStrict()
        val output = Path.of(args[2])
        Files.createDirectories(output)
        var invocation = 0
        fun run(command: List<String>, allowFailure: Boolean = false): String {
            val stem = "%02d-%s".format(++invocation, command[1])
            val stdout = output.resolve("$stem.stdout")
            val stderr = output.resolve("$stem.stderr")
            val process =
                ProcessBuilder(command)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start()
            process.outputStream.close()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Timed out: ${command.joinToString(" ")}")
            }
            check(allowFailure || process.exitValue() == 0) {
                "Failed (${process.exitValue()}): ${command.joinToString(" ")}\n${Files.readString(stderr)}"
            }
            return Files.readString(stdout)
        }

        println(run(listOf(executable, "version")).trim())
        val name = "ocwp-contract"
        val workspace = output.toAbsolutePath().toString()
        val help = mutableMapOf<List<String>, String>()
        fun helpFor(path: List<String>): String =
            help.getOrPut(path) { run(listOf(executable) + path + "--help") }
        fun surface(path: List<String>, command: List<String>) {
            val text = helpFor(path)
            check(text.contains("Usage:")) { "Missing command: $path" }
            // Inspect only sbx options; guest argv has its own independent interface.
            val flags = command.drop(1 + path.size).filter { it.startsWith("-") }
            for (flag in flags) {
                check(
                    Regex("(?m)^\\s*(?:-[A-Za-z],\\s*)?${Regex.escape(flag)}(?:[ ,=]|$)")
                        .containsMatchIn(text)
                ) {
                    "${path.joinToString(" ")} no longer advertises $flag"
                }
            }
        }

        surface(
            listOf("create"),
            SbxCli.buildCreateCommand(executable, name, workspace, kits = listOf("./mixin")),
        )
        val exec = SbxCli.buildExecServeCommand(executable, name, workspace)
        surface(listOf("exec"), exec.take(exec.indexOf(name)))
        // Standalone TUI/ACP flags, absent from the foreground serve command.
        surface(listOf("exec"), listOf(executable, "exec", "-i", "-t"))
        surface(listOf("ls"), SbxCli.buildLsCommand(executable))
        surface(listOf("ports"), SbxCli.buildPortsCommand(executable, name))
        surface(listOf("ports"), SbxCli.buildPortsPublishCommand(executable, name, "4096/tcp4"))
        surface(
            listOf("ports"),
            SbxCli.buildPortsUnpublishCommand(executable, name, "127.0.0.1:4096:4096/tcp4"),
        )
        surface(listOf("template", "ls"), SbxCli.buildTemplateLsCommand(executable))
        surface(
            listOf("template", "rm"),
            SbxCli.buildTemplateRmCommand(
                executable,
                "unused",
                force = "--force" in helpFor(listOf("template", "rm")),
            ),
        )
        surface(listOf("stop"), SbxCli.buildStopCommand(executable, name))
        surface(listOf("rm"), SbxCli.buildRmForceCommand(executable, name))
        surface(listOf("kit", "add"), SbxCli.buildAddKitCommand(executable, name, "./mixin"))
        surface(listOf("policy", "ls"), SbxCli.buildPolicyLsCommand(executable))
        surface(listOf("policy", "init"), SbxCli.buildPolicyInitCommand(executable))
        surface(
            listOf("policy", "allow", "network"),
            SbxCli.buildPolicyAllowCommand(executable, name, "localhost:64342"),
        )
        val daemonHelp = helpFor(listOf("daemon", "start"))
        surface(
            listOf("daemon", "start"),
            if ("--detach" in daemonHelp) SbxCli.buildDaemonStartCommand(executable)
            else SbxCli.buildLegacyDaemonStartCommand(executable),
        )
        val diagnoseHelp = helpFor(listOf("diagnose"))
        val diagnose =
            if ("--format" in diagnoseHelp) SbxCli.buildDiagnoseJsonCommand(executable)
            else SbxCli.buildLegacyDiagnoseJsonCommand(executable)
        surface(listOf("diagnose"), diagnose)
        check(
            runCatching {
                surface(listOf("create"), listOf(executable, "create", "--ocwp-missing-flag"))
            }
                .isFailure
        ) {
            "The flag-removal canary must fail"
        }
        println("PASS: ${help.size} sbx command surfaces (production argv + standalone exec flags)")

        if (!live) {
            println("NOT RUN: live JSON (use -PsbxLive=true on a Docker-authenticated host)")
            return
        }

        val inventory = run(SbxCli.buildLsCommand(executable))
        val entries = checkNotNull(SbxCli.parseLsJsonOrNull(inventory)) { "Unrecognized ls JSON" }
        check(entries.size == listArray(inventory, "sandboxes").size()) {
            "Sandbox identities were lost"
        }
        for ((index, entry) in entries.withIndex()) {
            val raw = listArray(inventory, "sandboxes")[index].asJsonObject
            check(
                entry.workspaces == raw.getAsJsonArray("workspaces")?.map { it.asString }.orEmpty()
            ) {
                "Workspace paths were lost"
            }
            check(entry.status.isNotBlank()) { "Sandbox status missing" }
            val ports = run(SbxCli.buildPortsCommand(executable, entry.name))
            check(
                SbxCli.parsePortsJson(ports).size ==
                    JsonParser.parseString(ports).asJsonArray.size()
            ) {
                "Port mappings were lost"
            }
        }
        val templates = run(SbxCli.buildTemplateLsCommand(executable))
        check(SbxCli.parseTemplateLsJson(templates).size == listArray(templates, "images").size()) {
            "Template identities were lost"
        }
        val policy = run(SbxCli.buildPolicyLsCommand(executable))
        val rules = JsonParser.parseString(policy).asJsonObject.getAsJsonArray("rules")
        checkNotNull(rules) { "Unrecognized policy JSON" }
        check(
            SbxCli.policyIsInitialized(policy) ==
                rules.any {
                    val rule = it.asJsonObject
                    rule.get("scope")?.asString == "global" ||
                        rule.get("applies_to")?.asString == "all"
                }
        ) {
            "Global policy detection changed"
        }
        // A host diagnostic can legitimately exit nonzero; the JSON contract must still hold.
        val diagnostics = JsonParser.parseString(run(diagnose, allowFailure = true)).asJsonObject
        check(
            diagnostics.has("checks") ||
                diagnostics.has("virtualization") ||
                diagnostics.has("supported")
        ) {
            "Unrecognized diagnose JSON"
        }
        println(
            "PASS: live ls (${entries.size} rows), ports (${entries.size} sandboxes), template ls, policy ls, diagnose JSON"
        )
        println("Reports: ${output.toAbsolutePath()}")
    }

    private fun listArray(json: String, legacyKey: String): JsonArray {
        val root = JsonParser.parseString(json)
        return if (root.isJsonArray) root.asJsonArray
        else root.asJsonObject.getAsJsonArray(legacyKey)
    }
}
