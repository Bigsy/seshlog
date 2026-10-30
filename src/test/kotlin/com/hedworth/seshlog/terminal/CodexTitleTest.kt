package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class CodexTitleTest {
    private val old = "01a1825b-156e-7d01-b714-8318cc151f47"
    private val current = "01a18370-5a52-7d90-bb35-d6cf1976ebf9"
    private val unindexed = "01a18389-e126-7570-9fb1-3f5ee0c4a2d1"
    private val executables = mapOf(AgentKind.CODEX to "codex")
    // As Codex 0.165 titles a terminal: the thread-id item is cut to 29 characters plus "...".
    private fun shortened(id: String, project: String = "unifhir") = "$project | ${id.take(29)}..."

    private fun session(id: String, kind: AgentKind = AgentKind.CODEX) = Session(
        kind, id, id, Path.of("/synthetic"), null, null, Instant.EPOCH, null,
        false, null, null, 1, true,
    )

    private val sessions = listOf(session(old), session(current))
    // `codex resume` from the picker: nothing on the command line names the thread.
    private val picker = listOf(SessionProcess.Evidence(122, "/bin/node", listOf("/opt/homebrew/bin/codex", "resume")),
        SessionProcess.Evidence(123, "/bin/codex", listOf("resume")))

    @Test fun `full and shortened thread ids are read from the title`() {
        assertEquals(current, CodexTitle.thread(current))
        assertEquals(current.take(29), CodexTitle.thread(shortened(current)))
        assertEquals(current.take(29), CodexTitle.thread("${current.take(29)}…"))
        assertEquals(current.take(24), CodexTitle.thread("⠴ unifhir | ${current.take(24).uppercase()}..."))
    }

    @Test fun `titles without exactly one marked thread id name no thread`() {
        assertNull(CodexTitle.thread(null))
        assertNull(CodexTitle.thread(""))
        assertNull(CodexTitle.thread("⠦ unifhir"))
        // A partial id without the ellipsis Codex adds when shortening is other text.
        assertNull(CodexTitle.thread("unifhir | ${current.take(29)}"))
        assertNull(CodexTitle.thread("$old | $current"))
        assertNull(CodexTitle.thread("x$current"))
        assertEquals(current, CodexTitle.thread("$current $current"))
    }

    @Test fun `Seshlog's Codex launches add thread-id to Codex's default title, except on Windows`() {
        assertEquals("""codex resume abc -c 'tui.terminal_title=["activity","thread-name","project-name","thread-id"]'""",
            CodexTitle.launch("codex resume abc", windows = false))
        // Windows PowerShell strips the inner quotes, and Codex then cannot load its config.
        assertEquals("codex resume abc", CodexTitle.launch("codex resume abc", windows = true))
    }

    @Test fun `every title those launch items produce names the thread`() {
        val id = current.take(29)
        assertEquals(id, CodexTitle.thread("⠋ Fix the flaky test | unifhir | $id..."))
        // Idle, and before Codex has named the thread.
        assertEquals(id, CodexTitle.thread("unifhir | $id..."))
        assertEquals(id, CodexTitle.thread("[ ! ] Action Required | Fix the flaky test | unifhir | $id..."))
    }

    @Test fun `a shortened id matches only one indexed Codex session`() {
        assertEquals(current, CodexTitle.match(current.take(29), sessions))
        assertNull(CodexTitle.match(unindexed.take(29), sessions))
        assertNull(CodexTitle.match(current.take(29), listOf(session(current, AgentKind.CLAUDE_CODE))))
        val twin = session(current.take(29) + "0000000")
        assertNull(CodexTitle.match(current.take(29), sessions + twin))
        assertEquals(current, CodexTitle.match(current, sessions + twin))
    }

    @Test fun `title identifies a resumed TUI that nothing else can`() {
        val discovery = SessionProcess.identifyTree(picker, sessions, executables, ProcessTranscripts.Result(emptyMap()),
            title = shortened(current))
        assertEquals(setOf(current), discovery.sessionIds)
        assertTrue(discovery.processes.getValue(current) in setOf(122L, 123L))
        assertFalse(discovery.unknown)
        assertEquals(current, discovery.copySession(old))
    }

    @Test fun `title overrides resume arguments and daemon pairing after an in-TUI thread switch`() {
        val process = SessionProcess.Evidence(123, "/bin/codex", listOf("resume", old))
        val discovery = SessionProcess.identifyTree(listOf(process), sessions, executables, ProcessTranscripts.Result(emptyMap()),
            daemonThreads = mapOf(123L to old), title = shortened(current))
        assertEquals(setOf(current), discovery.sessionIds)
        assertEquals(mapOf(current to 123L), discovery.processes)
    }

    @Test fun `title naming a thread the index lacks rules out the tab's previous thread`() {
        val discovery = SessionProcess.identifyTree(listOf(SessionProcess.Evidence(123, "/bin/codex", listOf("resume", old))), sessions,
            executables, ProcessTranscripts.Result(emptyMap(), setOf(123L)), title = shortened(unindexed))
        assertTrue(discovery.sessionIds.isEmpty())
        assertTrue(discovery.hasAgent)
        // Unreadable descriptors would otherwise fall back to the previous thread and copy it.
        assertFalse(discovery.unknown)
        assertNull(discovery.copySession(old))
        // Copy rescans once, so a thread indexed since the last scan is still found.
        val fresh = session(unindexed)
        assertEquals(unindexed, SessionProcess.copySession(old, sessions, inspect = {
            SessionProcess.identifyTree(picker, it, executables, ProcessTranscripts.Result(emptyMap()), title = shortened(unindexed))
        }, rescan = { sessions + fresh }))
    }

    @Test fun `titles are ignored without a Codex process and leave other agents to their own evidence`() {
        val claude = session("claude-session", AgentKind.CLAUDE_CODE).copy(isLive = true, livePid = 200)
        val discovery = SessionProcess.identifyTree(listOf(SessionProcess.Evidence(200, "/bin/claude", emptyList())),
            sessions + claude, executables + (AgentKind.CLAUDE_CODE to "claude"), ProcessTranscripts.Result(emptyMap()),
            title = shortened(current))
        assertEquals(setOf("claude-session"), discovery.sessionIds)
        // Without a title the picker's TUI stays unidentified, as before.
        assertTrue(SessionProcess.identifyTree(picker, sessions, executables, ProcessTranscripts.Result(emptyMap())).sessionIds.isEmpty())
    }

    @Test fun `batch inspection gives each shell its own title`() {
        val handle = ProcessHandle.current()
        val table = ProcessTableSnapshot.of(listOf(
            ProcessRecord(10, 1, "zsh", emptyList(), handle), ProcessRecord(11, 10, "/bin/codex", emptyList(), handle),
            ProcessRecord(20, 1, "zsh", emptyList(), handle), ProcessRecord(21, 20, "/bin/codex", emptyList(), handle),
        ))
        val result = ProcessBatchInspector(snapshot = { table }, writableFiles = { ProcessTranscripts.Result(emptyMap()) })
            .inspect(setOf(10L, 20L), sessions, executables, titles = mapOf(10L to shortened(old), 20L to shortened(current)))
        assertEquals(setOf(old), result.discoveries.getValue(10L).discovery.sessionIds)
        assertEquals(setOf(current), result.discoveries.getValue(20L).discovery.sessionIds)
    }
}
