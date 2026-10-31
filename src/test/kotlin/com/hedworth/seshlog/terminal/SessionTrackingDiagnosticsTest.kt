package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.codex.CodexTranscriptParser
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class SessionTrackingDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val trace = DiagnosticTrace { event, fields -> events += event to fields }.with("poll" to 123)
    private val handle = ProcessHandle.current()
    private val cwd = Path.of("/project")
    private fun session(path: Path) = Session(AgentKind.CODEX, "terminal", "private session title", cwd, null, null,
        Instant.EPOCH, path, false, null, "private prompt", 1, true)

    private fun rollout(): Path = temporary.root.toPath().resolve("rollout-synthetic.jsonl").also {
        javaClass.getResourceAsStream("/fixtures/codex_app_server/terminal.jsonl")!!.use { source -> Files.copy(source, it) }
    }

    private fun row(pid: Long, parent: Long, command: String, vararg args: String) =
        ProcessRecord(pid, parent, command, args.toList(), handle)

    @Test fun `batch traces daemon evidence and final shell identity without private data`() {
        val path = rollout()
        val table = ProcessTableSnapshot.of(listOf(row(10, 1, "zsh"), row(11, 10, "codex", "private prompt --token secret"),
            row(900, 1, "codex", "app-server")))
        val inspector = ProcessBatchInspector(snapshot = { table }, writableFiles = {
            ProcessTranscripts.Result(mapOf(11L to emptySet()), cwds = mapOf(11L to cwd))
        })
        val result = inspector.inspect(setOf(10), listOf(session(path)), mapOf(AgentKind.CODEX to "codex"), temporary.root.toPath(), trace)
        assertTrue(result.discoveries.getValue(10).discovery.sessionIds.isEmpty())
        assertTrue(events.any { it.first == "daemon_match_skipped" && it.second["reason"] == "not_terminal_identity" })
        assertTrue(events.any { it.first == "shell_discovery" && it.second["sessions"] == emptyList<String>() })
        assertTrue(events.all { it.second["poll"] == 123 })
        val text = events.toString()
        listOf("private prompt", "private session title", "--token", "secret", path.toString(), cwd.toString()).forEach { assertFalse(it, text.contains(it)) }
        assertFalse("daemon paths are no longer inspected", text.contains(DiagnosticRef.path(path)!!))
    }

    @Test fun `unknown evidence and stale generations are distinguishable`() {
        val inspector = ProcessBatchInspector(snapshot = { ProcessTableSnapshot.of(emptyList(), complete = false) })
        val result = inspector.inspect(setOf(10), emptyList(), emptyMap(), trace = trace)
        assertTrue(result.discoveries.getValue(10).discovery.unknown)
        assertTrue(events.any { it.first == "process_table" && it.second["complete"] == false })
        val tab = Any()
        val input = TerminalReconcileInput(
            sessions = listOf(session(temporary.root.toPath())), ownership = emptyMap<String, Any>(),
            ownershipAtInspection = mapOf(tab to null), discoveries = listOf(TabProcessDiscovery(tab, "terminal", handle)),
            running = emptySet(), observedBefore = emptyMap(), observedNow = emptyMap(), exited = emptySet(),
            tabTitles = emptyMap(), displayTitle = { it.title }, generationsAtInspection = mapOf(tab to 1L), generationsNow = mapOf(tab to 2L),
        )
        assertTrue(TerminalReconciler.reconcile(input, trace).adopt.isEmpty())
        val rejection = events.single { it.second["reason"] == "generation_changed" }.second
        assertEquals(1L, rejection["inspectedGeneration"])
        assertEquals(2L, rejection["currentGeneration"])
    }

    @Test fun `historical CLI version is metadata and malformed versions are omitted`() {
        val path = temporary.root.toPath().resolve("rollout-version.jsonl")
        val fixture = javaClass.getResource("/fixtures/codex_session.jsonl")!!.readText()
        Files.writeString(path, fixture)
        assertEquals("0.42.0", CodexTranscriptParser.rolloutHeader(path)?.cliVersion)
        Files.writeString(path, fixture.replace("0.42.0", "private prompt"))
        assertNull(CodexTranscriptParser.rolloutHeader(path)?.cliVersion)
    }

    @Test fun `pending expiry is reported once even after repeated polling`() {
        var clock = 0L
        val expired = mutableListOf<String>()
        val pending = PendingSessionAssociations<String>({ clock }, 10, expired::add)
        pending.mark("tab", emptySet())
        clock = 10
        repeat(3) { assertFalse(pending.isPending("tab")) }
        assertEquals(listOf("tab"), expired)
    }
    @Test fun `title provenance and excluded identities are hashed without raw title data`() {
        val id = "01a18370-5a52-7d90-bb35-d6cf1976ebf9"
        val old = session(temporary.root.toPath())
        val table = ProcessTableSnapshot.of(listOf(row(10, 1, "zsh"), row(11, 10, "codex")))
        ProcessBatchInspector(snapshot = { table }, writableFiles = { error("title needs no descriptors") })
            .inspect(setOf(10), listOf(old), mapOf(AgentKind.CODEX to "codex"), trace = trace,
                titles = mapOf(10L to "private title | $id"))
        val discovery = events.single { it.first == "shell_discovery" }.second
        assertEquals(SessionProcess.IdentityState.UNINDEXED, discovery["state"])
        assertEquals(SessionProcess.Provenance.TITLE, discovery["provenance"])
        assertEquals(listOf(DiagnosticRef.of(old.id)), discovery["excluded"])
        assertFalse(events.toString().contains(id))
        assertFalse(events.toString().contains("private title"))
    }

    @Test fun `unavailable enumeration and successful empty enumeration remain distinct`() {
        for (complete in listOf(true, false)) {
            var descriptors = 0
            val result = ProcessBatchInspector(snapshot = { ProcessTableSnapshot.of(emptyList(), complete) },
                writableFiles = { descriptors++; ProcessTranscripts.Result(emptyMap()) })
                .inspect(setOf(10), emptyList(), emptyMap(), trace = trace)
            assertEquals(0, descriptors)
            assertEquals(if (complete) SessionProcess.IdentityState.EXITED else SessionProcess.IdentityState.UNAVAILABLE,
                result.discoveries.getValue(10).discovery.state)
        }
    }

}
