package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class ProcessBatchSnapshotTest {
    private val handle = ProcessHandle.current()

    private fun row(pid: Long, parent: Long?, command: String, vararg args: String) = ProcessRecord(
        pid, parent, command, args.toList(), handle,
    )

    private fun session(id: String) = Session(
        AgentKind.CODEX, id, id, Path.of("/synthetic"), null, null, Instant.EPOCH, null,
        false, null, null, 1, true,
    )

    @Test
    fun `one process snapshot and one writable-files query serve every inspected shell`() {
        val table = ProcessTableSnapshot.of(listOf(
            row(10, 1, "zsh"), row(11, 10, "/bin/codex", "resume", "one"),
            row(20, 1, "zsh"), row(21, 20, "/bin/codex", "resume", "two"),
        ))
        var snapshots = 0
        var descriptorQueries = 0
        val inspector = ProcessBatchInspector(
            snapshot = { snapshots++; table },
            writableFiles = { pids ->
                descriptorQueries++
                assertEquals(setOf(11L, 21L), pids)
                ProcessTranscripts.Result(emptyMap())
            },
        )
        val result = inspector.inspect(
            setOf(10L, 20L), listOf(session("one"), session("two")),
            mapOf(AgentKind.CODEX to "codex"),
        )
        assertEquals(1, snapshots)
        assertEquals(1, descriptorQueries)
        assertEquals(setOf("one"), result.discoveries.getValue(10).discovery.sessionIds)
        assertEquals(setOf("two"), result.discoveries.getValue(20).discovery.sessionIds)
        assertTrue(result.table.isDescendantOf(11L, setOf(10L)))
        assertEquals(handle, result.handles.getValue(11L))
    }

    @Test
    fun `incomplete process snapshot preserves unknown discovery`() {
        val table = ProcessTableSnapshot.of(listOf(row(10, 1, "zsh")), complete = false)
        val result = ProcessBatchInspector(snapshot = { table }, writableFiles = {
            ProcessTranscripts.Result(emptyMap())
        }).inspect(setOf(10L), listOf(session("one")), mapOf(AgentKind.CODEX to "codex"))
        val discovery = result.discoveries.getValue(10L).discovery
        assertTrue(discovery.unknown)
        assertTrue(discovery.hasAgent)
        assertTrue(discovery.sessionIds.isEmpty())
    }
}
