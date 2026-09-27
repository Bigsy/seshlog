package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree

/** A process-table row captured once, retaining the exact handle seen for that pid. */
internal data class ProcessRecord(
    val pid: Long,
    val parentPid: Long?,
    val command: String?,
    val arguments: List<String>,
    val handle: ProcessHandle,
) {
    fun evidence() = SessionProcess.Evidence(pid, command, arguments)
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

    /** Include the shell itself because an agent may have replaced it with exec. */
    fun under(shellPid: Long): List<ProcessRecord> = rows.values.filter {
        it.pid == shellPid || isDescendantOf(it.pid, setOf(shellPid))
    }

    fun handles(): Map<Long, ProcessHandle> = rows.mapValues { it.value.handle }

    companion object {
        fun of(rows: Collection<ProcessRecord>, complete: Boolean = true): ProcessTableSnapshot =
            ProcessTableSnapshot(rows.associateBy { it.pid }, complete)

        /** One OS process enumeration for the whole polling tick. */
        fun capture(): ProcessTableSnapshot = try {
            ProcessHandle.allProcesses().use { stream ->
                val rows = ArrayList<ProcessRecord>()
                stream.forEach { process -> record(process)?.let(rows::add) }
                of(rows)
            }
        } catch (_: Exception) {
            of(emptyList(), complete = false)
        }

        private fun record(handle: ProcessHandle): ProcessRecord? = try {
            val info = handle.info()
            ProcessRecord(
                pid = handle.pid(),
                parentPid = handle.parent().map(ProcessHandle::pid).orElse(null),
                command = info.command().orElse(null),
                arguments = info.arguments().map { it.toList() }.orElse(emptyList()),
                handle = handle,
            )
        } catch (_: Exception) {
            null
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
)

/**
 * Performs all process and transcript-descriptor inspection for a polling tick in one batch.
 * Callers can inject both runners so this remains deterministic and does not spawn processes in
 * unit tests.
 */
internal class ProcessBatchInspector(
    private val snapshot: () -> ProcessTableSnapshot = ProcessTableSnapshot::capture,
    private val writableFiles: (Set<Long>) -> ProcessTranscripts.Result = ProcessTranscripts::writableFiles,
) {
    fun inspect(
        shellPids: Set<Long>,
        sessions: List<Session>,
        executables: Map<com.hedworth.seshlog.model.AgentKind, String>,
    ): ProcessBatchResult {
        if (shellPids.isEmpty()) return ProcessBatchResult(ProcessTableSnapshot.of(emptyList()), emptyMap(), emptyMap())
        val table = snapshot()
        val evidenceByShell = shellPids.associateWith { shell -> table.under(shell).map { it.evidence() } }
        val codexExecutable = executables[com.hedworth.seshlog.model.AgentKind.CODEX]
        val codexPids = evidenceByShell.values.asSequence().flatten()
            .filter { codexExecutable != null && SessionProcess.isAgent(it, codexExecutable) }
            .mapTo(LinkedHashSet()) { it.pid }
        // One descriptor query covers every Codex process under every inspected shell.
        val descriptors = writableFiles(codexPids)
        val discoveries = evidenceByShell.mapValues { (shell, processes) ->
            ShellProcessDiscovery(
                shell,
                if (table.complete) SessionProcess.identifyTree(processes, sessions, executables, descriptors)
                else SessionProcess.Discovery(emptySet(), hasAgent = true, unknown = true),
            )
        }
        return ProcessBatchResult(table, discoveries, table.handles())
    }
}
