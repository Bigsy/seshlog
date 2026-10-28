package com.hedworth.seshlog.sandbox

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.model.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/** One verification line: [ok] is null for information that is recorded but not judged. */
internal data class SandboxCheck(val ok: Boolean?, val label: String, val detail: String)

/** An IDE process whose `idea.config.path` is the sandbox's, with its launch arguments. */
internal data class SandboxProcess(val pid: Long, val started: Instant?, val command: String?, val arguments: List<String>) {
    fun property(name: String): String? = arguments.firstOrNull { it.startsWith("-D$name=") }?.substringAfter('=')

    companion object {
        fun find(layout: SandboxLayout, processes: Sequence<ProcessHandle> = ProcessHandle.allProcesses().iterator().asSequence()) =
            processes.mapNotNull { handle ->
                val info = handle.info()
                val arguments = info.arguments().orElse(null)?.toList() ?: return@mapNotNull null
                SandboxProcess(handle.pid(), info.startInstant().orElse(null), info.command().orElse(null), arguments)
            }.filter { process ->
                process.property("idea.config.path")?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() } == layout.ideConfig
            }.sortedByDescending { it.started }.toList()
    }
}

/**
 * Establishes that the running sandbox IDE is a verified synthetic profile: its directories are the
 * sandbox's, its settings point only inside the root, it loaded the plugin jar built from this
 * checkout, and its own diagnostics show every provider scanning the synthetic storage.
 */
internal class SyntheticSandboxVerifier(
    private val layout: SandboxLayout,
    private val pluginJar: Path,
    private val pluginVersion: String,
    private val repository: Path,
    private val processes: () -> List<SandboxProcess> = { SandboxProcess.find(layout) },
) {
    fun verify(): List<SandboxCheck> {
        val checks = ArrayList<SandboxCheck>()
        val manifest = SyntheticSandbox.readManifest(layout)
        if (manifest == null) {
            return listOf(SandboxCheck(false, "Sandbox", "No synthetic sandbox at ${layout.root}. Run make sandbox first."))
        }
        checks += SandboxCheck(manifest.version == SyntheticSandbox.VERSION, "Sandbox",
            "${layout.root} (generator ${manifest.version}, created ${manifest.created})")
        val running = processes()
        val ide = running.firstOrNull()
        if (ide == null) {
            checks += SandboxCheck(false, "IDE process", "None uses ${layout.ideConfig}. Start it with make sandbox, wait for the project to open, then rerun.")
            return checks + settings() + checkout()
        }
        checks += SandboxCheck(running.size == 1, "IDE process", "pid ${ide.pid}, started ${ide.started ?: "unknown"}" +
            if (running.size > 1) "; ${running.size} processes share this config directory" else "")
        checks += SandboxCheck(null, "IDE runtime", ide.command ?: "unknown")
        for ((property, expected) in listOf("idea.config.path" to layout.ideConfig, "idea.log.path" to layout.ideLog,
                "idea.plugins.path" to layout.idePlugins, "idea.system.path" to null)) {
            val actual = ide.property(property)
            val inside = actual != null && Path.of(actual).toAbsolutePath().normalize().startsWith(layout.root)
            checks += SandboxCheck(inside && (expected == null || Path.of(actual!!).toAbsolutePath().normalize() == expected),
                property, actual ?: "not set")
        }
        checks += settings()
        checks += diagnostics(ide, manifest)
        checks += plugin(ide)
        checks += checkout()
        return checks
    }

    private fun settings(): List<SandboxCheck> {
        if (!Files.isRegularFile(layout.settingsFile)) {
            return listOf(SandboxCheck(false, "Settings", "${layout.settingsFile} is missing; run make sandbox to restore it."))
        }
        val text = Files.readString(layout.settingsFile)
        val actual = Regex("<option name=\"([A-Za-z]+)\" value=\"([^\"]*)\"").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&") }
        return layout.settings().map { (name, expected) ->
            val value = actual[name]
            SandboxCheck(value == expected, "Setting $name", value ?: "not set (Seshlog would use real agent data or PATH)")
        }
    }

    private fun diagnostics(ide: SandboxProcess, manifest: SandboxManifest): List<SandboxCheck> {
        val directory = layout.ideLog.resolve("seshlog")
        // Oldest first. Rotation can move a file mid-read and a write can leave a partial line.
        val records = listOf("sessions.3.jsonl", "sessions.2.jsonl", "sessions.1.jsonl", "sessions.jsonl")
            .map(directory::resolve)
            .flatMap { file -> runCatching { Files.readAllLines(file) }.getOrDefault(emptyList()) }
            .mapNotNull { line -> runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() }
            .filter { it.getAsJsonObject("env")?.get("idePid")?.asLong == ide.pid }
            // A reused PID from an earlier run must not vouch for this process.
            .filter { record -> ide.started == null || record.string("time")?.let { !Instant.parse(it).isBefore(ide.started.minusSeconds(1)) } != false }
        if (records.isEmpty()) {
            return listOf(SandboxCheck(false, "Diagnostics", "No records from pid ${ide.pid} in $directory. Open the Seshlog tool window, " +
                "and check Settings | Tools | Seshlog | Record session diagnostics."))
        }
        val env = records.last().getAsJsonObject("env")
        fun text(name: String) = env.get(name)?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
        val checks = arrayListOf(
            SandboxCheck(text("plugin") == pluginVersion, "Loaded plugin", "Seshlog ${text("plugin")}"),
            SandboxCheck(null, "IDE build", "${text("ide")}, Java ${text("java")}, ${text("os")} ${text("arch")}"),
        )
        val scans = records.filter { it.get("event")?.asString == "provider_scanned" }.mapNotNull { it.getAsJsonObject("fields") }
        val allowed = AgentKind.entries.associate { it.name to DiagnosticRef.path(layout.storage(it)) }
        val foreign = scans.filter { allowed[it.string("kind")] != it.string("storage") }
        checks += SandboxCheck(foreign.isEmpty(), "Scanned roots", if (foreign.isEmpty()) "every scan in this IDE run read synthetic storage"
            else "${foreign.size} scans read storage outside the sandbox (${foreign.mapNotNull { it.string("kind") }.distinct().joinToString()})")
        for (kind in AgentKind.entries) {
            val latest = scans.lastOrNull { it.string("kind") == kind.name }
            val expected = manifest.expected[kind] ?: 0
            checks += if (latest == null) SandboxCheck(false, "${kind.displayName} scan", "not recorded yet")
            else {
                val count = latest.get("sessions")?.asInt ?: -1
                val health = latest.string("health")
                // Testers add and delete sessions; an empty scan of a populated store is the failure.
                SandboxCheck(health == "READY" && (count > 0 || expected == 0) && latest.string("storage") == allowed[kind.name],
                    "${kind.displayName} scan", "$health, $count sessions ($expected generated)")
            }
        }
        return checks
    }

    private fun plugin(ide: SandboxProcess): List<SandboxCheck> {
        val loaded = layout.idePlugins.resolve("seshlog/lib/seshlog-$pluginVersion.jar")
        if (!Files.isRegularFile(loaded) || !Files.isRegularFile(pluginJar)) {
            return listOf(SandboxCheck(false, "Plugin jar", "missing: ${listOf(loaded, pluginJar).filterNot(Files::isRegularFile).joinToString()}"))
        }
        val hash = sha256(loaded)
        val installed = Files.getLastModifiedTime(loaded).toInstant()
        val newerSource = Files.walk(repository.resolve("src/main")).use { files ->
            files.filter(Files::isRegularFile).anyMatch { Files.getLastModifiedTime(it).toInstant().isAfter(Files.getLastModifiedTime(pluginJar).toInstant()) }
        }
        return listOf(
            SandboxCheck(hash == sha256(pluginJar), "Plugin jar", "sha256 $hash" + if (hash == sha256(pluginJar)) " (same as $pluginJar)" else ", but $pluginJar differs"),
            SandboxCheck(ide.started == null || !installed.isAfter(ide.started), "Jar age", "installed $installed" +
                if (ide.started != null && installed.isAfter(ide.started)) ", after the IDE started: restart it" else ", before the IDE started"),
            SandboxCheck(!newerSource, "Sources", if (newerSource) "src/main changed after the jar was built; run make sandbox again" else "no src/main change since the jar was built"),
        )
    }

    private fun checkout(): List<SandboxCheck> {
        val head = git("rev-parse", "--short", "HEAD")?.trim() ?: "unknown"
        val changes = git("status", "--porcelain")?.lines()?.count { it.isNotBlank() }
        return listOf(SandboxCheck(null, "Checkout", "$head" + when (changes) { null -> ""; 0 -> ", clean"; else -> ", $changes uncommitted changes" }))
    }

    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        output.takeIf { process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 }
    }.getOrNull()

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { !it.isJsonNull }?.asString

    companion object {
        fun sha256(file: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))

        fun report(checks: List<SandboxCheck>, at: Instant = Instant.now()): String = buildString {
            appendLine("Seshlog synthetic sandbox verification, $at")
            for (check in checks) {
                val mark = when (check.ok) { true -> "ok  "; false -> "FAIL"; null -> "    " }
                appendLine("$mark ${check.label.padEnd(26)} ${check.detail}")
            }
            appendLine(if (checks.none { it.ok == false }) "VERIFIED: record this report with any manual check results."
                else "NOT VERIFIED: fix the FAIL lines before recording manual checks.")
        }
    }
}
