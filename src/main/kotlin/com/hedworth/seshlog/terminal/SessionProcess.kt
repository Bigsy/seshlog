package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.codex.CodexTranscriptParser
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import java.nio.file.Path

/** Launch arguments are intent, not evidence of the current Codex thread. */
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

    internal data class Evidence(val pid: Long, val command: String?, val arguments: List<String>, val parentPid: Long? = null)

    internal fun isAgent(process: Evidence, executable: String): Boolean {
        fun basename(value: String?): String? = value?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
        val expected = basename(executable) ?: return false
        return basename(process.command) == expected ||
            (basename(process.command) in setOf("node", "nodejs", "bun") &&
                basename(process.arguments.firstOrNull()) in setOf(expected, "$expected.js"))
    }

    internal fun isCodexTui(process: Evidence, executable: String): Boolean =
        isAgent(process, executable) && process.arguments.none { it in setOf("app-server", "exec", "mcp-server") }

    internal fun tuiRoots(processes: List<Evidence>): Set<Long> {
        val pids = processes.mapTo(HashSet()) { it.pid }
        return processes.filter { it.parentPid !in pids }.mapTo(HashSet()) { it.pid }
    }

    /** A PID marker takes precedence over old resume arguments after an in-agent session switch. */
    internal fun identify(process: Evidence, sessions: List<Session>, executables: Map<AgentKind, String>): Set<String> {
        val live = sessions.filter { it.kind != AgentKind.CODEX && it.isLive && it.livePid == process.pid }
        if (live.isNotEmpty()) return live.mapTo(HashSet()) { it.id }
        return sessions.filter { session ->
            if (session.kind == AgentKind.CODEX) return@filter false
            val executable = executables[session.kind] ?: return@filter false
            isAgent(process, executable) && matches(session.kind, session.id, session.transcriptPath?.toString(), process.arguments)
        }.mapTo(HashSet()) { it.id }
    }

    internal enum class IdentityState { VERIFIED, UNINDEXED, AMBIGUOUS, UNAVAILABLE, EXITED }
    internal enum class Provenance { TITLE, WRITER, PROVIDER, NONE }

    /** Current evidence only; launch reservations and ended context live outside discovery. */
    internal data class Discovery(
        val sessionIds: Set<String>, val hasAgent: Boolean,
        val processes: Map<String, Long> = emptyMap(), val unknown: Boolean = false,
        val state: IdentityState = when {
            unknown -> IdentityState.UNAVAILABLE
            sessionIds.size == 1 -> IdentityState.VERIFIED
            hasAgent -> IdentityState.AMBIGUOUS
            else -> IdentityState.EXITED
        },
        val provenance: Provenance = Provenance.NONE,
        /** Indexed identities positively excluded by a recognized current thread. */
        val excludedSessionIds: Set<String> = emptySet(),
    ) {
        fun copySession(previous: String?): String? = sessionIds.singleOrNull()
            ?: previous?.takeIf { state == IdentityState.EXITED && !hasAgent }
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
        trace: DiagnosticTrace = DiagnosticTrace.NONE,
        /** The terminal title the foreground program set; see [CodexTitle]. */
        title: String? = null,
        titleTrusted: Boolean = true,
    ): Discovery {
        val writableFiles = evidence.files
        val codex = executables[AgentKind.CODEX]
        val codexProcesses = processes.filter { codex != null && isCodexTui(it, codex) }
        val singleTui = tuiRoots(codexProcesses).size == 1
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
        // The title names the thread the TUI shows now, even after an in-TUI switch, so it overrides
        // every other Codex evidence. A thread the index lacks still rules out the older ones.
        val titleEvidence = if (singleTui && titleTrusted) CodexTitle.read(title) else CodexTitle.Reading.Absent
        val titled = (titleEvidence as? CodexTitle.Reading.Named)?.thread
        val titleCandidates = titled?.let { prefix -> sessions.filter { it.kind == AgentKind.CODEX && it.id.lowercase().startsWith(prefix) }.mapTo(HashSet()) { it.id } }.orEmpty()
        val titledId = titleCandidates.singleOrNull()
        val owners = identified.filterKeys { it !in codexIds } + when {
            !singleTui -> emptyMap()
            titled != null -> listOfNotNull(titledId).associateWith { codexProcesses.last().pid }
            titleEvidence == CodexTitle.Reading.Invalid -> emptyMap()
            paths.size == 1 -> written
            else -> emptyMap()
        }
        trace.event("identity_evidence", "argumentOrMarker" to DiagnosticRef.ids(identified.keys), "writers" to DiagnosticRef.ids(written.keys),
            "rolloutCount" to paths.size, "descriptorOrHeaderUnknown" to unknown,
            "title" to when { titled == null -> "none"; titledId == null -> "unindexed"; else -> "matched" }, "titleSession" to DiagnosticRef.of(titledId),
            "titleTrusted" to titleTrusted, "tuiCount" to tuiRoots(codexProcesses).size,
            "selected" to DiagnosticRef.ids(owners.keys))
        return Discovery(owners.keys, processes.any { p -> executables.values.any { isAgent(p, it) } } || owners.isNotEmpty(), owners,
            unknown = titled == null && unknown && paths.isEmpty(),
            state = when {
                owners.size == 1 -> IdentityState.VERIFIED
                titled != null -> if (titleCandidates.isEmpty()) IdentityState.UNINDEXED else IdentityState.AMBIGUOUS
                titleEvidence == CodexTitle.Reading.Invalid || !singleTui && codexProcesses.isNotEmpty() -> IdentityState.AMBIGUOUS
                paths.size == 1 && written.isEmpty() && !unknown -> IdentityState.UNINDEXED
                unknown -> IdentityState.UNAVAILABLE
                processes.any { p -> executables.values.any { isAgent(p, it) } } -> IdentityState.AMBIGUOUS
                else -> IdentityState.EXITED
            },
            provenance = when { titled != null -> Provenance.TITLE; written.isNotEmpty() -> Provenance.WRITER
                identified.isNotEmpty() -> Provenance.PROVIDER; else -> Provenance.NONE },
            excludedSessionIds = when {
                titled != null -> codexIds - titleCandidates
                paths.size == 1 && !unknown -> codexIds - written.keys
                !titleTrusted && codexProcesses.isNotEmpty() -> codexIds - owners.keys
                else -> emptySet()
            })
    }

    /** Discover fresh or resumed agents under one shell, entirely off the EDT, as polling does. */
    fun inspect(shell: Long, sessions: List<Session>, executables: Map<AgentKind, String>, codexHome: Path? = null, title: String? = null): Discovery {
        val trace = SessionDiagnostics.trace("copy_inspection", "poll" to SessionDiagnostics.nextId())
        return try {
            ProcessBatchInspector().inspect(setOf(shell), sessions, executables, codexHome, trace, mapOf(shell to title)).discoveries.getValue(shell).discovery
        } catch (e: Exception) {
            trace.failure("inspection_failed", e)
            Discovery(emptySet(), true, unknown = true) // Preserve association when evidence is unavailable.
        }
    }

    fun discover(shell: Long, sessions: List<Session>, executables: Map<AgentKind, String>): Set<String> =
        inspect(shell, sessions, executables).sessionIds

    fun isRunning(shell: Long, session: Session): Boolean = runningProcess(shell, session) != null

    /** The process running [session] under [shell]. Pooled thread; unreadable arguments mean unknown, never live. */
    fun runningProcess(shell: Long, session: Session): ProcessHandle? = try {
        val parent = ProcessHandle.of(shell).orElse(null)
        if (parent == null) null else parent.descendants().use { descendants ->
            (sequenceOf(parent) + descendants.iterator().asSequence()).firstOrNull { process ->
                process.isAlive && (session.kind != AgentKind.CODEX && (session.livePid == process.pid() ||
                    matches(session.kind, session.id, session.transcriptPath?.toString(),
                        process.info().arguments().orElse(emptyArray()).toList())))
            }
        }
    } catch (_: Exception) {
        null
    }
}
