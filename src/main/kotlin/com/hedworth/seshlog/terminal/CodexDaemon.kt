package com.hedworth.seshlog.terminal

import com.google.gson.JsonParser
import com.hedworth.seshlog.codex.CodexTranscriptParser
import com.hedworth.seshlog.codex.CodexTranscriptParser.RolloutHeader
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * From codex-cli 0.158 the TUI runs on a shared app-server daemon that holds each thread's rollout,
 * so nothing under the terminal's shell has it open and no record maps a TUI to its thread. The
 * daemon's open rollouts are the threads with a client attached. A TUI is matched to one by working
 * directory only when that is one-to-one across every Codex TUI on the machine, not just those in
 * IntelliJ; anything less certain stays unknown. Runs only on workers.
 */
internal object CodexDaemon {
    private val log = logger<CodexDaemon>()
    // Never a TUI. Leaving out a real TUI could pair its thread with another, so list only these.
    private val NON_TUI_COMMANDS = setOf("app-server", "exec", "mcp-server")

    /** The codex processes of one launch: a Node wrapper and its native binary, or just the binary. */
    data class Instance(val pids: Set<Long>, val resumeId: String?)

    private data class OpenThread(val id: String, val cwd: Path)

    private data class Launch(val instance: Instance, val cwd: Path, val own: List<OpenThread>)

    /** The daemon's pid from `daemon.pid`; null when absent, unreadable or since reused by another process. */
    fun pid(codexHome: Path, table: ProcessTableSnapshot): Long? {
        val file = codexHome.resolve("app-server-daemon/daemon.pid")
        if (!Files.isRegularFile(file)) return null
        return try {
            val marker = JsonParser.parseString(Files.readString(file)).asJsonObject
            val pid = marker.get("pid").asLong
            val startSeconds = marker.getAsJsonObject("processIdentity").get("startSeconds").asLong
            val started = table.row(pid)?.startedAt
            pid.takeIf { started != null && abs(started.epochSecond - startSeconds) <= 1 }
        } catch (e: Exception) {
            log.debug("Unreadable Codex daemon marker $file", e)
            null
        }
    }

    /**
     * Every codex launch except the daemon, its children, other app servers (editor integrations),
     * `exec` runs and MCP servers.
     */
    fun instances(table: ProcessTableSnapshot, executable: String, daemon: Long): List<Instance> {
        val agents = table.rows().filter { row ->
            row.pid != daemon && SessionProcess.isAgent(row.evidence(), executable) &&
                row.arguments.none { it in NON_TUI_COMMANDS } && !table.isDescendantOf(row.pid, setOf(daemon))
        }.associateBy { it.pid }
        fun launch(pid: Long): Long {
            var current = pid
            repeat(8) { current = table.parentPid(current)?.takeIf { it in agents } ?: return current }
            return current
        }
        return agents.values.groupBy { launch(it.pid) }.values.map { rows ->
            Instance(rows.mapTo(HashSet()) { it.pid }, rows.mapNotNull { resumeId(it.arguments) }.distinct().singleOrNull())
        }
    }

    /**
     * Pid → thread id for every pid of an instance whose thread can only be one daemon-held rollout.
     * [evidence] must cover the daemon and every instance pid; any gap in it matches nothing.
     */
    fun match(
        instances: List<Instance>, daemon: Long, evidence: ProcessTranscripts.Result,
        header: (Path) -> RolloutHeader? = CodexTranscriptParser::rolloutHeader,
    ): Map<Long, String> {
        if (instances.isEmpty() || daemon in evidence.unknown || daemon !in evidence.files) return emptyMap()
        val held = threads(daemon, evidence, header) ?: return emptyMap()
        val launches = instances.map { instance ->
            if (instance.pids.any { it in evidence.unknown }) return emptyMap()
            val cwd = instance.pids.mapNotNull { evidence.cwds[it] }.distinct().singleOrNull() ?: return emptyMap()
            val own = instance.pids.flatMap { threads(it, evidence, header) ?: return emptyMap() }.distinctBy { it.id }
            Launch(instance, canonical(cwd), own)
        }
        val open = (held + launches.flatMap { it.own }).distinctBy { it.id }
        val result = HashMap<Long, String>()
        for ((cwd, here) in launches.groupBy { it.cwd }) {
            val threads = open.filter { it.cwd == cwd }
            // Unequal counts mean a TUI has not opened its thread yet, or the daemon still holds one
            // a TUI has left. Either way the leftover pair could be wrong.
            if (threads.size != here.size) continue
            val unpaired = threads.mapTo(HashSet()) { it.id }
            if (here.any { it.own.size > 1 }) continue
            val exact = here.filter { it.own.isNotEmpty() }
            if (!exact.all { unpaired.remove(it.own.single().id) }) continue
            // A resume argument pairs only with a held thread; a stale one leaves its TUI unpaired.
            val rest = (here - exact.toSet()).filterNot { launch -> launch.instance.resumeId?.let(unpaired::remove) == true }
            val launch = rest.singleOrNull() ?: continue
            val thread = unpaired.singleOrNull() ?: continue
            launch.instance.pids.forEach { result[it] = thread }
        }
        return result
    }

    /** Terminal threads among [pid]'s writable rollouts; null when any of them cannot be read. */
    private fun threads(pid: Long, evidence: ProcessTranscripts.Result, header: (Path) -> RolloutHeader?): List<OpenThread>? {
        val result = ArrayList<OpenThread>()
        for (path in evidence.files[pid].orEmpty()) {
            if (!path.fileName.toString().let { it.startsWith("rollout-") && it.endsWith(".jsonl") }) continue
            val meta = header(path) ?: return null
            if (!meta.terminal) continue
            val cwd = meta.cwd?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
            result += OpenThread(meta.id ?: return null, canonical(cwd))
        }
        return result
    }

    private fun canonical(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path.normalize())

    private fun resumeId(arguments: List<String>): String? =
        arguments.zipWithNext().firstOrNull { it.first == "resume" }?.second?.takeUnless { it.startsWith("-") }
}
