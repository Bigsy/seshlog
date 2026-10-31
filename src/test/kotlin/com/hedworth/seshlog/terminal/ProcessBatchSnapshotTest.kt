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
        assertTrue(result.discoveries.getValue(10).discovery.sessionIds.isEmpty())
        assertTrue(result.discoveries.getValue(20).discovery.sessionIds.isEmpty())
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
    @Test fun `process replacement cannot inherit a prior title including reused PIDs`() {
        val id = "01a18370-5a52-7d90-bb35-d6cf1976ebf9"
        val start = Instant.parse("2026-10-31T12:00:00Z")
        val before = TitleProcessBinding(id, setOf(11L to start), true)
        for (pid in listOf(11L, 12L)) {
            val table = ProcessTableSnapshot.of(listOf(row(10, 1, "zsh"),
                row(pid, 10, "codex").copy(startedAt = start.plusSeconds(10))))
            val inspector = ProcessBatchInspector(snapshot = { table }, writableFiles = { ProcessTranscripts.Result(emptyMap()) })
            val blocked = inspector.inspect(setOf(10L), listOf(session(id)), mapOf(AgentKind.CODEX to "codex"),
                titles = mapOf(10L to id), previousTitles = mapOf(10L to before))
            val discovery = blocked.discoveries.getValue(10L).discovery
            assertTrue(discovery.sessionIds.isEmpty())
            assertTrue(id in discovery.excludedSessionIds)
            val stillBlocked = inspector.inspect(setOf(10L), listOf(session(id)), mapOf(AgentKind.CODEX to "codex"),
                titles = mapOf(10L to id), previousTitles = blocked.titleBindings)
            assertTrue(stillBlocked.discoveries.getValue(10L).discovery.sessionIds.isEmpty())
            val fresh = inspector.inspect(setOf(10L), listOf(session(id)), mapOf(AgentKind.CODEX to "codex"),
                titles = mapOf(10L to "new title | $id"), previousTitles = blocked.titleBindings)
            assertEquals(setOf(id), fresh.discoveries.getValue(10L).discovery.sessionIds)
        }
    }

    @Test fun `confirmed no agent prevents a later TUI from inheriting the leftover title`() {
        val first = TitleProcessBinding.observe(null, "leftover", setOf(11L to Instant.EPOCH))
        val exited = TitleProcessBinding.observe(first, "leftover", emptySet())
        assertTrue(!exited.trusted)
        assertTrue(!TitleProcessBinding.observe(exited, "leftover", setOf(12L to Instant.EPOCH)).trusted)
    }

    @Test fun `indexed and unindexed titles skip descriptors and daemon work entirely`() {
        val id = "01a18370-5a52-7d90-bb35-d6cf1976ebf9"
        val table = ProcessTableSnapshot.of(listOf(row(10, 1, "zsh"), row(11, 10, "codex")))
        for (sessions in listOf(emptyList(), listOf(session(id)))) {
            var calls = 0
            val inspector = ProcessBatchInspector(snapshot = { table }, writableFiles = {
                calls++; ProcessTranscripts.Result(emptyMap())
            })
            val result = inspector.inspect(setOf(10L), sessions, mapOf(AgentKind.CODEX to "codex"), titles = mapOf(10L to id))
            assertEquals(0, calls)
            assertEquals(if (sessions.isEmpty()) emptySet<String>() else setOf(id), result.discoveries.getValue(10L).discovery.sessionIds)
        }
    }

    @Test fun `mixed titles request only unresolved local TUI descriptors in one batch`() {
        val id = "01a18370-5a52-7d90-bb35-d6cf1976ebf9"
        val table = ProcessTableSnapshot.of(listOf(row(10, 1, "zsh"), row(11, 10, "codex"),
            row(20, 1, "zsh"), row(21, 20, "codex"), row(900, 1, "codex", "app-server"), row(30, 1, "codex")))
        val requests = arrayListOf<Set<Long>>()
        ProcessBatchInspector(snapshot = { table }, writableFiles = {
            requests += it; ProcessTranscripts.Result(emptyMap())
        }).inspect(setOf(10L, 20L), listOf(session(id)), mapOf(AgentKind.CODEX to "codex"), titles = mapOf(10L to id))
        assertEquals(listOf(setOf(21L)), requests)
    }

}
