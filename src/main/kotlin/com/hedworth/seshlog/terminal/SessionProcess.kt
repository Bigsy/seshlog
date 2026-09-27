package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.codex.CodexTranscriptParser
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import java.nio.file.Path

/** Resume arguments identify the session; an arbitrary busy shell is not evidence of an agent. */
internal object SessionProcess {
    fun matches(kind: AgentKind, id: String, transcript: String?, arguments: List<String>): Boolean {
        // These commands name the source session, but run a newly minted session.
        if (kind == AgentKind.CLAUDE_CODE && "--fork-session" in arguments) return false
        if (kind == AgentKind.OPENCODE && "--fork" in arguments) return false
        val option = when (kind) {
            AgentKind.CLAUDE_CODE -> "--resume"
            AgentKind.CODEX -> "resume"
            AgentKind.OPENCODE, AgentKind.PI -> "--session"
        }
        val target = if (kind == AgentKind.PI) transcript ?: return false else id
        return arguments.zipWithNext().any { (key, value) -> key == option && value == target }
    }

    internal data class Evidence(val pid: Long, val command: String?, val arguments: List<String>)

    internal fun isAgent(process: Evidence, executable: String): Boolean {
        fun basename(value: String?): String? = value?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
        val expected = basename(executable) ?: return false
        return basename(process.command) == expected ||
            (basename(process.command) in setOf("node", "nodejs", "bun") &&
                basename(process.arguments.firstOrNull()) in setOf(expected, "$expected.js"))
    }

    /** A PID marker takes precedence over old resume arguments after an in-agent session switch. */
    internal fun identify(process: Evidence, sessions: List<Session>, executables: Map<AgentKind, String>): Set<String> {
        val live = sessions.filter { it.isLive && it.livePid == process.pid }
        if (live.isNotEmpty()) return live.mapTo(HashSet()) { it.id }
        return sessions.filter { session ->
            val executable = executables[session.kind] ?: return@filter false
            isAgent(process, executable) && matches(session.kind, session.id, session.transcriptPath?.toString(), process.arguments)
        }.mapTo(HashSet()) { it.id }
    }

    /** [processes] maps each identified session to the PID seen running it. */
    internal data class Discovery(val sessionIds: Set<String>, val hasAgent: Boolean, val processes: Map<String, Long> = emptyMap(), val unknown: Boolean = false, val uncertainSessionIds: Set<String>? = null) {
        // A different/unidentified running agent must never copy the previous agent's reply.
        fun copySession(previous: String?): String? = sessionIds.singleOrNull()
            ?: previous?.takeIf { sessionIds.isEmpty() && (!hasAgent || (unknown && (uncertainSessionIds == null || previous in uncertainSessionIds))) }
    }

    /**
     * The index trails transcripts by the watcher debounce, so a running agent it cannot identify is
     * usually a fresh session not scanned yet. Rescan once before giving up; never guess.
     */
    internal fun copySession(previous: String?, sessions: List<Session>, inspect: (List<Session>) -> Discovery,
                             rescan: () -> List<Session>): String? {
        val first = inspect(sessions)
        val discovery = if (first.sessionIds.isEmpty() && first.hasAgent && !first.unknown) inspect(rescan()) else first
        return discovery.copySession(previous)
    }

    /** Writable CLI rollouts take precedence over stale resume arguments (including Node wrappers). */
    internal fun identifyTree(
        processes: List<Evidence>, sessions: List<Session>, executables: Map<AgentKind, String>,
        writableFiles: Map<Long, Set<Path>>,
        isCliTranscript: (Path) -> Boolean = CodexTranscriptParser::isCliTranscript,
    ): Discovery {
        return identifyTree(processes, sessions, executables, ProcessTranscripts.Result(writableFiles), isCliTranscript)
    }

    internal fun identifyTree(
        processes: List<Evidence>, sessions: List<Session>, executables: Map<AgentKind, String>,
        evidence: ProcessTranscripts.Result,
        isCliTranscript: (Path) -> Boolean = CodexTranscriptParser::isCliTranscript,
    ): Discovery {
        val writableFiles = evidence.files
        val codex = executables[AgentKind.CODEX]
        val codexProcesses = processes.filter { codex != null && isAgent(it, codex) }
        var unknown = codexProcesses.any { it.pid in evidence.unknown }
        val writers = codexProcesses.flatMap { process ->
            writableFiles[process.pid].orEmpty().filter { path ->
                val rollout = path.fileName.toString().let { it.startsWith("rollout-") && it.endsWith(".jsonl") }
                val cli = rollout && isCliTranscript(path)
                // An unreadable/oversize header must not resurrect an old resume argument.
                if (rollout && !cli) unknown = true
                cli
            }.map { it to process.pid }
        }.toMap()
        val paths = writers.keys
        val names = paths.mapTo(HashSet()) { it.fileName }
        val written = sessions.filter { it.kind == AgentKind.CODEX }.mapNotNull { session ->
            val path = session.transcriptPath ?: return@mapNotNull null
            val real = if (path in paths || path.fileName !in names) path else runCatching { path.toRealPath() }.getOrDefault(path)
            writers[real]?.let { session.id to it }
        }.toMap()
        val identified = processes.flatMap { process -> identify(process, sessions, executables).map { it to process.pid } }.toMap()
        val codexIds = sessions.filter { it.kind == AgentKind.CODEX }.mapTo(HashSet()) { it.id }
        val owners = if (paths.isEmpty() && !unknown) identified else identified.filterKeys { it !in codexIds } + written
        return Discovery(owners.keys, processes.any { p -> executables.values.any { isAgent(p, it) } } || owners.isNotEmpty(), owners, unknown && paths.isEmpty(), codexIds)
    }

    /** Discover fresh or resumed agents once per shell, entirely off the EDT. */
    fun inspect(shell: Long, sessions: List<Session>, executables: Map<AgentKind, String>): Discovery = try {
        val root = ProcessHandle.of(shell).orElse(null)
        val processes = if (root == null) emptyList() else root.descendants().use { descendants ->
            (sequenceOf(root) + descendants.iterator().asSequence()).filter { it.isAlive }.map { process ->
                val info = process.info()
                Evidence(process.pid(), info.command().orElse(null), info.arguments().orElse(emptyArray()).toList())
            }.toList()
        }
        val codex = executables[AgentKind.CODEX]
        val pids = processes.filter { codex != null && isAgent(it, codex) }.mapTo(HashSet()) { it.pid }
        identifyTree(processes, sessions, executables, ProcessTranscripts.writableFiles(pids))
    } catch (_: Exception) {
        Discovery(emptySet(), true, unknown = true) // Preserve association when evidence is unavailable.
    }

    fun discover(shell: Long, sessions: List<Session>, executables: Map<AgentKind, String>): Set<String> =
        inspect(shell, sessions, executables).sessionIds

    fun isRunning(shell: Long, session: Session): Boolean = runningProcess(shell, session) != null

    /** The process running [session] under [shell]. Pooled thread; unreadable arguments mean unknown, never live. */
    fun runningProcess(shell: Long, session: Session): ProcessHandle? = try {
        val parent = ProcessHandle.of(shell).orElse(null)
        if (parent == null) null else parent.descendants().use { descendants ->
            (sequenceOf(parent) + descendants.iterator().asSequence()).firstOrNull { process ->
                process.isAlive && (session.livePid == process.pid() ||
                    matches(session.kind, session.id, session.transcriptPath?.toString(),
                        process.info().arguments().orElse(emptyArray()).toList()))
            }
        }
    } catch (_: Exception) {
        null
    }
}
