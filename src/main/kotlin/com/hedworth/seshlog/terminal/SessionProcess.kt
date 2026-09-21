package com.hedworth.seshlog.terminal

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

    internal data class Discovery(val sessionIds: Set<String>, val hasAgent: Boolean) {
        // A different/unidentified running agent must never copy the previous agent's reply.
        fun copySession(previous: String?): String? = sessionIds.singleOrNull()
            ?: previous?.takeIf { sessionIds.isEmpty() && !hasAgent }
    }

    /** Writable CLI rollouts take precedence over stale resume arguments (including Node wrappers). */
    internal fun identifyTree(
        processes: List<Evidence>, sessions: List<Session>, executables: Map<AgentKind, String>,
        writableFiles: Map<Long, Set<Path>>,
        isCliTranscript: (Path) -> Boolean = com.hedworth.seshlog.codex.CodexTranscriptParser::isCliTranscript,
    ): Discovery {
        val codex = executables[AgentKind.CODEX]
        val paths = processes.filter { codex != null && isAgent(it, codex) }
            .flatMap { writableFiles[it.pid].orEmpty() }.filter { path ->
                path.fileName.toString().let { it.startsWith("rollout-") && it.endsWith(".jsonl") } && isCliTranscript(path)
            }.toSet()
        val names = paths.mapTo(HashSet()) { it.fileName }
        val written = sessions.filter { it.kind == AgentKind.CODEX && it.transcriptPath?.let { path ->
            path in paths || (path.fileName in names && runCatching { path.toRealPath() in paths }.getOrDefault(false))
        } == true }.mapTo(HashSet()) { it.id }
        val identified = processes.flatMap { identify(it, sessions, executables) }.toSet()
        val codexIds = sessions.filter { it.kind == AgentKind.CODEX }.mapTo(HashSet()) { it.id }
        val ids = if (paths.isEmpty()) identified else (identified - codexIds) + written
        return Discovery(ids, processes.any { p -> executables.values.any { isAgent(p, it) } } || ids.isNotEmpty())
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
        Discovery(emptySet(), true) // Failed inspection is not proof that the old agent is still the target.
    }

    fun discover(shell: Long, sessions: List<Session>, executables: Map<AgentKind, String>): Set<String> =
        inspect(shell, sessions, executables).sessionIds

    /** Called on a pooled thread. Missing process arguments mean unknown, never guessed live. */
    fun isRunning(shell: Long, session: Session): Boolean = try {
        val parent = ProcessHandle.of(shell).orElse(null)
        if (parent == null) false else parent.descendants().use { descendants ->
            (sequenceOf(parent) + descendants.iterator().asSequence()).any { process ->
                process.isAlive && (session.livePid == process.pid() ||
                    matches(session.kind, session.id, session.transcriptPath?.toString(),
                        process.info().arguments().orElse(emptyArray()).toList()))
            }
        }
    } catch (_: Exception) {
        false
    }
}
