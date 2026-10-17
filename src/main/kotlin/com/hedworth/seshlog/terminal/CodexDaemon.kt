package com.hedworth.seshlog.terminal

import com.google.gson.JsonParser
import com.hedworth.seshlog.codex.CodexTranscriptParser
import com.hedworth.seshlog.codex.CodexTranscriptParser.RolloutHeader
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * From codex-cli 0.158 the TUI runs on a shared app-server daemon that holds each thread's rollout,
 * so nothing under the terminal's shell has it open and no record maps a TUI to its thread. The
 * daemon's open rollouts are the threads with a client attached. A TUI is matched to one by working
 * directory only when that is one-to-one across every Codex TUI on the machine, not just those in
 * IntelliJ, using resume arguments and when each thread was created to tell apart TUIs that share a
 * directory; anything less certain stays unknown. Runs only on workers.
 */
internal object CodexDaemon {
    private val log = logger<CodexDaemon>()
    // Never a TUI. Leaving out a real TUI could pair its thread with another, so list only these.
    private val NON_TUI_COMMANDS = setOf("app-server", "exec", "mcp-server")
    // A plain TUI creates its thread within a few seconds of starting (0.5 to 3.5 s seen) and writes
    // nothing to it until the first prompt (2 s or more later in every daemon-era rollout seen). A
    // thread written as it was created (about 45 ms for forks and subagents) says nothing about when
    // its TUI started: if Codex ever creates threads at the first prompt, timing must not pair them.
    private val STARTUP: Duration = Duration.ofSeconds(15)
    private val CLOCK_SLACK: Duration = Duration.ofSeconds(1)
    private val UNWRITTEN: Duration = Duration.ofSeconds(1)

    /**
     * The codex processes of one launch: a Node wrapper and its native binary, or just the binary.
     * [startedAt] is the earliest of their starts; [resumed] is any `resume`, with an id or a picker;
     * [forkOf] is the source thread of `fork <id>`.
     */
    data class Instance(val pids: Set<Long>, val resumeId: String?, val startedAt: Instant? = null, val resumed: Boolean = resumeId != null,
                        val forkOf: String? = null)

    private data class OpenThread(val id: String, val cwd: Path, val createdAt: Instant?, val writtenAt: Instant?, val forkedFrom: String?) {
        val startup: Boolean get() = startupShaped(createdAt, writtenAt)
    }

    /** Created, then left unwritten until a prompt: the shape of a thread made as its TUI started. */
    internal fun startupShaped(createdAt: Instant?, writtenAt: Instant?): Boolean =
        createdAt != null && writtenAt != null && writtenAt >= createdAt.plus(UNWRITTEN)

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
            val resumeId = rows.mapNotNull { subcommandTarget(it.arguments, "resume") }.distinct().singleOrNull()
            Instance(rows.mapTo(HashSet()) { it.pid }, resumeId, rows.mapNotNull { it.startedAt }.minOrNull(),
                resumeId != null || rows.any { "resume" in it.arguments },
                rows.mapNotNull { subcommandTarget(it.arguments, "fork") }.distinct().singleOrNull())
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
            val left = threads.filter { it.id in unpaired }
            val started = startupPairs(rest, left, here)
            for ((launch, thread) in started) launch.instance.pids.forEach { result[it] = thread.id }
            val launch = (rest - started.keys).singleOrNull() ?: continue
            val thread = (left - started.values.toSet()).singleOrNull() ?: continue
            launch.instance.pids.forEach { result[it] = thread.id }
        }
        return result
    }

    /**
     * Threads a launch created as it started. A fork claims the threads forked from its source since
     * it started. A plain launch claims the [OpenThread.startup] threads created within [STARTUP] of
     * it and before the next launch in [directory] started. A launch pairs only when it claims one
     * thread that no other launch claims. A resumed TUI creates nothing at startup, and without every
     * start and creation time nothing is claimed.
     */
    private fun startupPairs(launches: List<Launch>, threads: List<OpenThread>, directory: List<Launch>): Map<Launch, OpenThread> {
        val starts = directory.map { it.instance.startedAt ?: return emptyMap() }
        val created = threads.associateWith { it.createdAt ?: return emptyMap() }
        val claimed = launches.filterNot { it.instance.resumed }.associateWith { launch ->
            val start = launch.instance.startedAt ?: return emptyMap()
            val since = threads.filter { created.getValue(it) >= start.minus(CLOCK_SLACK) }
            val source = launch.instance.forkOf
            if (source != null) since.filter { it.forkedFrom == source } else {
                val end = (starts.filter { it > start } + start.plus(STARTUP)).min()
                since.filter { it.startup && created.getValue(it) < end }
            }
        }
        val claims = claimed.values.flatten().groupingBy { it.id }.eachCount()
        return claimed.mapNotNull { (launch, mine) -> mine.singleOrNull()?.takeIf { claims[it.id] == 1 }?.let { launch to it } }.toMap()
    }

    /** Terminal threads among [pid]'s writable rollouts; null when any of them cannot be read. */
    private fun threads(pid: Long, evidence: ProcessTranscripts.Result, header: (Path) -> RolloutHeader?): List<OpenThread>? {
        val result = ArrayList<OpenThread>()
        for (path in evidence.files[pid].orEmpty()) {
            if (!path.fileName.toString().let { it.startsWith("rollout-") && it.endsWith(".jsonl") }) continue
            val meta = header(path) ?: return null
            if (!meta.terminal) continue
            val cwd = meta.cwd?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
            result += OpenThread(meta.id ?: return null, canonical(cwd), meta.createdAt, meta.writtenAt, meta.forkedFrom)
        }
        return result
    }

    private fun canonical(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path.normalize())

    private fun subcommandTarget(arguments: List<String>, subcommand: String): String? =
        arguments.zipWithNext().firstOrNull { it.first == subcommand }?.second?.takeUnless { it.startsWith("-") }
}
