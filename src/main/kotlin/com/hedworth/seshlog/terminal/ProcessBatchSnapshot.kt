package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree
import java.nio.file.Path
import java.time.Instant

/** A process-table row captured once, retaining the exact handle seen for that pid. */
internal data class ProcessRecord(
    val pid: Long,
    val parentPid: Long?,
    val command: String?,
    val arguments: List<String>,
    val handle: ProcessHandle,
    val startedAt: Instant? = null,
) {
    fun evidence() = SessionProcess.Evidence(pid, command, arguments, parentPid)
}

/** Immutable process table that is also a cheap [ProcessTree] view for one polling tick. */
internal class ProcessTableSnapshot private constructor(
    private val rows: Map<Long, ProcessRecord>,
    /** False when the OS process enumeration could not be completed reliably. */
    val complete: Boolean,
) : ProcessTree {
    // Do not query a live handle after the snapshot. The row's presence is the liveness evidence
    // captured for this tick; querying it later would reintroduce races and process-table walks.
    override fun isAlive(pid: Long): Boolean = pid in rows

    override fun parentPid(pid: Long): Long? = rows[pid]?.parentPid

    fun row(pid: Long): ProcessRecord? = rows[pid]

    fun rows(): Collection<ProcessRecord> = rows.values

    /** Include the shell itself because an agent may have replaced it with exec. */
    fun under(shellPid: Long): List<ProcessRecord> = rows.values.filter {
        it.pid == shellPid || isDescendantOf(it.pid, setOf(shellPid))
    }

    fun handles(): Map<Long, ProcessHandle> = rows.mapValues { it.value.handle }

    companion object {
        fun of(rows: Collection<ProcessRecord>, complete: Boolean = true): ProcessTableSnapshot =
            ProcessTableSnapshot(rows.associateBy { it.pid }, complete)

        /** One OS process enumeration for the whole polling tick. */
        fun capture(trace: DiagnosticTrace = DiagnosticTrace.NONE): ProcessTableSnapshot = try {
            ProcessHandle.allProcesses().use { stream ->
                val rows = ArrayList<ProcessRecord>()
                var complete = true
                stream.forEach { process ->
                    val row = record(process, trace)
                    if (row == null) complete = false else rows.add(row)
                }
                of(rows, complete)
            }
        } catch (e: Exception) {
            trace.failure("process_table_failed", e)
            of(emptyList(), complete = false)
        }

        private fun record(handle: ProcessHandle, trace: DiagnosticTrace): ProcessRecord? = try {
            val info = handle.info()
            ProcessRecord(
                pid = handle.pid(),
                parentPid = handle.parent().map(ProcessHandle::pid).orElse(null),
                command = info.command().orElse(null),
                arguments = info.arguments().map { it.toList() }.orElse(emptyList()),
                handle = handle,
                startedAt = info.startInstant().orElse(null),
            )
        } catch (e: Exception) {
            trace.with("pid" to handle.pid()).failure("process_row_failed", e)
            null
        }
    }
}

/** A title is not transferable to a replacement process, even when its PID is reused. */
internal data class TitleProcessBinding(val title: String?, val processes: Set<Pair<Long, Instant?>>, val trusted: Boolean) {
    companion object {
        fun observe(previous: TitleProcessBinding?, title: String?, processes: Set<Pair<Long, Instant?>>): TitleProcessBinding {
            val trusted = processes.isNotEmpty() && when {
                previous == null -> true
                previous.title != title -> true
                previous.processes != processes -> false
                else -> previous.trusted
            }
            return TitleProcessBinding(title, processes, trusted)
        }
    }
}

internal data class ShellProcessDiscovery(
    val shellPid: Long,
    val discovery: SessionProcess.Discovery,
)

internal data class ProcessBatchResult(
    val table: ProcessTableSnapshot,
    val discoveries: Map<Long, ShellProcessDiscovery>,
    /** Exact handles keyed by pid, from the same process snapshot as [discoveries]. */
    val handles: Map<Long, ProcessHandle>,
    val titleBindings: Map<Long, TitleProcessBinding> = emptyMap(),
)

/**
 * Performs all process and transcript-descriptor inspection for a polling tick in one batch.
 * Callers can inject both runners so this remains deterministic and does not spawn processes in
 * unit tests.
 */
internal class ProcessBatchInspector(
    private val snapshot: (() -> ProcessTableSnapshot)? = null,
    private val writableFiles: ((Set<Long>) -> ProcessTranscripts.Result)? = null,
) {
    /**
     * [codexHome] is diagnostic context only; shared-daemon files cannot identify terminal threads.
     * [titles] are the terminal titles captured on the EDT with each shell pid.
     */
    fun inspect(
        shellPids: Set<Long>,
        sessions: List<Session>,
        executables: Map<AgentKind, String>,
        codexHome: Path? = null,
        trace: DiagnosticTrace = DiagnosticTrace.NONE,
        titles: Map<Long, String?> = emptyMap(),
        previousTitles: Map<Long, TitleProcessBinding> = emptyMap(),
    ): ProcessBatchResult {
        val start = System.nanoTime()
        trace.event("inspection_started", "shells" to shellPids.sorted(), "indexedSessions" to sessions.size,
            "codexHome" to DiagnosticRef.path(codexHome), "executables" to executables.mapKeys { it.key.name }.mapValues { DiagnosticRef.of(it.value) })
        if (shellPids.isEmpty()) {
            trace.event("inspection_finished", "reason" to "no_shells", "elapsedMs" to 0)
            return ProcessBatchResult(ProcessTableSnapshot.of(emptyList()), emptyMap(), emptyMap())
        }
        val table = snapshot?.invoke() ?: ProcessTableSnapshot.capture(trace)
        trace.event("process_table", "complete" to table.complete, "rows" to table.rows().size,
            "commandsUnavailable" to table.rows().count { it.command == null },
            "elapsedMs" to (System.nanoTime() - start) / 1_000_000)
        table.rows().forEach { row ->
            val kind = executables.entries.firstOrNull { SessionProcess.isAgent(row.evidence(), it.value) }?.key
            if (kind != null || row.pid in shellPids) trace.event("process", "pid" to row.pid, "parent" to row.parentPid,
                "kind" to kind, "shell" to (row.pid in shellPids), "started" to row.startedAt?.toString(), "executable" to DiagnosticRef.of(row.command),
                "argumentCount" to row.arguments.size)
            if (kind == AgentKind.CODEX) trace.event("codex_command_shape", "pid" to row.pid,
                "commandToken" to row.arguments.firstOrNull { it in setOf("app-server", "exec", "mcp-server", "resume", "fork") },
                "noDaemon" to ("--no-daemon" in row.arguments))
        }
        val evidenceByShell = shellPids.associateWith { shell -> table.under(shell).map { it.evidence() } }
        val codexExecutable = executables[AgentKind.CODEX]
        val bindings = shellPids.associateWith { shell ->
            val processes = table.under(shell).filter { codexExecutable != null && SessionProcess.isCodexTui(it.evidence(), codexExecutable) }
            TitleProcessBinding.observe(previousTitles[shell], titles[shell], processes.mapTo(HashSet()) { it.pid to it.startedAt })
        }
        // Titles (including known unindexed/ambiguous ones) need no filesystem evidence. Query
        // only local unresolved TUIs; a daemon or unrelated machine-wide client cannot help.
        val requested = if (!table.complete) emptySet() else evidenceByShell.flatMap { (shell, processes) ->
            val tuis = processes.filter { codexExecutable != null && SessionProcess.isCodexTui(it, codexExecutable) }
            val title = if (bindings.getValue(shell).trusted) CodexTitle.read(titles[shell]) else CodexTitle.Reading.Absent
            if (SessionProcess.tuiRoots(tuis).size == 1 && title == CodexTitle.Reading.Absent) tuis.map { it.pid } else emptyList()
        }.toSet()
        trace.event("descriptor_request", "pids" to requested.sorted(), "reason" to if (requested.isEmpty()) "no_direct_evidence_needed" else "unresolved_local_tui")
        val descriptors = when {
            requested.isEmpty() -> ProcessTranscripts.Result(emptyMap())
            writableFiles != null -> writableFiles.invoke(requested)
            else -> ProcessTranscripts.writableFiles(requested, trace)
        }
        requested.sorted().forEach { pid ->
            val paths = descriptors.files[pid].orEmpty()
            trace.event("descriptors", "pid" to pid, "unknown" to (pid in descriptors.unknown), "present" to descriptors.files.containsKey(pid),
                "cwd" to DiagnosticRef.path(descriptors.cwds[pid]), "fileCount" to paths.size,
                "rollouts" to paths.filter { it.fileName.toString().let { name -> name.startsWith("rollout-") && name.endsWith(".jsonl") } }.map(DiagnosticRef::path))
        }
        trace.event("daemon_match_skipped", "reason" to "not_terminal_identity")
        val discoveries = evidenceByShell.mapValues { (shell, processes) ->
            ShellProcessDiscovery(
                shell,
                if (table.complete && processes.none { it.command == null }) SessionProcess.identifyTree(processes, sessions, executables, descriptors,
                    trace = trace.with("shell" to shell), title = titles[shell], titleTrusted = bindings.getValue(shell).trusted)
                else SessionProcess.Discovery(emptySet(), hasAgent = true, unknown = true),
            )
        }
        discoveries.forEach { (shell, result) -> trace.event("shell_discovery", "shell" to shell, "sessions" to DiagnosticRef.ids(result.discovery.sessionIds),
            "hasAgent" to result.discovery.hasAgent, "unknown" to result.discovery.unknown,
            "state" to result.discovery.state, "provenance" to result.discovery.provenance,
            "excluded" to DiagnosticRef.ids(result.discovery.excludedSessionIds),
            "processes" to result.discovery.processes.mapKeys { DiagnosticRef.of(it.key)!! }) }
        trace.event("inspection_finished", "elapsedMs" to (System.nanoTime() - start) / 1_000_000)
        return ProcessBatchResult(table, discoveries, table.handles(), if (table.complete) bindings else previousTitles)
    }
}
