package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class CodexDaemonTest {
    @get:Rule val temporary = TemporaryFolder()

    private val daemon = 900L
    private val app = Path.of("/synthetic/app")
    private val other = Path.of("/synthetic/other")
    private val guardian = """{"subagent":{"other":"guardian"}}"""

    private val t0 = Instant.parse("2026-10-16T10:00:00Z")

    /**
     * A synthetic rollout whose `session_meta` places thread [id] in [cwd], created at [created]. By
     * default it was [written] at a first prompt well after creation, as a startup thread is.
     */
    private fun rollout(id: String, cwd: Path = app, originator: String = "codex-tui", source: String = "\"vscode\"",
                        created: Instant? = null, written: Instant? = created?.plusSeconds(20), forkedFrom: String? = null): Path {
        val file = temporary.root.toPath().resolve("rollout-2026-10-12T10-00-00-$id.jsonl")
        val timestamp = created?.let { ""","timestamp":"$it"""" }.orEmpty()
        val fork = forkedFrom?.let { ""","forked_from_id":"$it"""" }.orEmpty()
        val record = written?.let { """"timestamp":"$it",""" }.orEmpty()
        Files.writeString(file, """{$record"type":"session_meta","payload":{"id":"$id","cwd":"$cwd","originator":"$originator","source":$source$timestamp$fork}}""" + "\n")
        return file
    }

    private fun evidence(held: List<Path>, cwds: Map<Long, Path>, own: Map<Long, Set<Path>> = emptyMap(), unknown: Set<Long> = emptySet()) =
        ProcessTranscripts.Result(own + (daemon to held.toSet()), unknown, cwds)

    private fun instance(vararg pids: Long, resume: String? = null, started: Instant? = null, fork: String? = null) =
        CodexDaemon.Instance(pids.toSet(), resume, started, forkOf = fork)

    @Test fun `fresh TUI adopts the only daemon-held terminal thread in its directory`() {
        val held = listOf(rollout("fresh"), rollout("review", source = guardian), rollout("editor", originator = "codex_vscode"))
        assertEquals(mapOf(11L to "fresh", 12L to "fresh"),
            CodexDaemon.match(listOf(instance(11, 12)), daemon, evidence(held, mapOf(11L to app, 12L to app))))
    }

    @Test fun `a TUI outside IntelliJ in the same directory leaves both unmatched`() {
        val tab = instance(11)
        val outside = instance(21)
        val cwds = mapOf(11L to app, 21L to app)
        // The tab's thread is not open yet: the outside TUI's thread must not be taken for it.
        assertTrue(CodexDaemon.match(listOf(tab, outside), daemon, evidence(listOf(rollout("outside")), cwds)).isEmpty())
        assertTrue(CodexDaemon.match(listOf(tab, outside), daemon, evidence(listOf(rollout("outside"), rollout("tab")), cwds)).isEmpty())
        // In another directory it is irrelevant.
        val apart = CodexDaemon.match(listOf(tab, outside), daemon,
            evidence(listOf(rollout("tab"), rollout("outside", other)), mapOf(11L to app, 21L to other)))
        assertEquals("tab", apart[11L])
    }

    @Test fun `startup race before the daemon opens the thread matches nothing`() {
        assertTrue(CodexDaemon.match(listOf(instance(11)), daemon, evidence(emptyList(), mapOf(11L to app))).isEmpty())
        assertTrue(CodexDaemon.match(listOf(instance(11, resume = "old")), daemon, evidence(emptyList(), mapOf(11L to app))).isEmpty())
    }

    @Test fun `a stale thread the daemon still holds makes the directory ambiguous`() {
        val held = listOf(rollout("old"), rollout("new"))
        assertTrue(CodexDaemon.match(listOf(instance(11, resume = "old")), daemon, evidence(held, mapOf(11L to app))).isEmpty())
    }

    @Test fun `a released resume target is replaced by the one held thread`() {
        assertEquals(mapOf(11L to "new"),
            CodexDaemon.match(listOf(instance(11, resume = "old")), daemon, evidence(listOf(rollout("new")), mapOf(11L to app))))
    }

    @Test fun `exact pairs leave one fresh TUI matchable in a shared directory`() {
        val resumed = instance(11, resume = "resumed")
        val ownWriter = instance(31)
        val fresh = instance(21)
        val cwds = mapOf(11L to app, 21L to app, 31L to app)
        val own = mapOf(31L to setOf(rollout("own", source = "\"cli\"", originator = "codex_cli_rs")))
        val result = CodexDaemon.match(listOf(resumed, ownWriter, fresh), daemon,
            evidence(listOf(rollout("resumed"), rollout("fresh")), cwds, own))
        assertEquals(mapOf(21L to "fresh"), result)
    }

    @Test fun `fresh TUIs sharing a directory each take the thread they created as they started`() {
        // Two plain `codex` tabs in one project: neither has a resume argument or its own rollout.
        val first = instance(11, 12, started = t0)
        val second = instance(21, 22, started = t0.plusSeconds(22))
        val held = listOf(rollout("second", created = t0.plusSeconds(23)), rollout("first", created = t0.plusMillis(1_500)),
            rollout("review", source = guardian, created = t0.plusSeconds(23)))
        val cwds = mapOf(11L to app, 12L to app, 21L to app, 22L to app)
        assertEquals(mapOf(11L to "first", 12L to "first", 21L to "second", 22L to "second"),
            CodexDaemon.match(listOf(first, second), daemon, evidence(held, cwds)))
    }

    @Test fun `launches started within one window of a thread stay unknown`() {
        val cwds = mapOf(11L to app, 21L to app)
        val first = instance(11, started = t0)
        val second = instance(21, started = t0.plusSeconds(2))
        // Both were running before either thread was created, so either could own either.
        val held = listOf(rollout("a", created = t0.plusSeconds(3)), rollout("b", created = t0.plusSeconds(4)))
        assertTrue(CodexDaemon.match(listOf(first, second), daemon, evidence(held, cwds)).isEmpty())
        // The same start time cannot tell two launches apart either.
        val twin = instance(21, started = t0)
        val apart = listOf(rollout("a", created = t0.plusSeconds(1)), rollout("b", created = t0.plusSeconds(30)))
        assertTrue(CodexDaemon.match(listOf(first, twin), daemon, evidence(apart, cwds)).isEmpty())
        // Without a process start time there is no window at all.
        assertTrue(CodexDaemon.match(listOf(first, instance(21)), daemon, evidence(apart, cwds)).isEmpty())
    }

    @Test fun `a resumed TUI never takes a thread by startup time`() {
        // The fresh TUI's thread came late, after the resumed TUI started; the resumed TUI's own
        // thread came from an in-Codex /new much later. Neither may be paired by timing.
        val fresh = instance(11, started = t0.minusSeconds(5))
        val resumed = instance(21, resume = "gone", started = t0)
        val held = listOf(rollout("fresh", created = t0.plusMillis(500)), rollout("new", created = t0.plusSeconds(300)))
        assertTrue(CodexDaemon.match(listOf(fresh, resumed), daemon, evidence(held, mapOf(11L to app, 21L to app))).isEmpty())
        val picker = instance(21, started = t0).copy(resumed = true)
        assertTrue(CodexDaemon.match(listOf(fresh, picker), daemon, evidence(held, mapOf(11L to app, 21L to app))).isEmpty())
    }

    @Test fun `a thread written as soon as it was created says nothing about which launch started`() {
        // How a Codex that creates threads at the first prompt would look: creation time is then
        // prompt time, and another tab's first prompt can land in a fresh launch's window.
        val first = instance(11, started = t0)
        val second = instance(21, started = t0.plusSeconds(22))
        val held = listOf(rollout("first", created = t0.plusSeconds(1), written = t0.plusMillis(1_045)),
            rollout("second", created = t0.plusSeconds(23), written = t0.plusMillis(23_045)))
        assertTrue(CodexDaemon.match(listOf(first, second), daemon, evidence(held, mapOf(11L to app, 21L to app))).isEmpty())
    }

    @Test fun `a fork takes the thread forked from its source`() {
        // The fork writes its copied history at once, and a fresh launch started just after it.
        val fork = instance(11, started = t0, fork = "source")
        val fresh = instance(21, started = t0.plusMillis(500))
        val held = listOf(rollout("forked", created = t0.plusSeconds(1), written = t0.plusMillis(1_045), forkedFrom = "source"),
            rollout("fresh", created = t0.plusSeconds(2)))
        val cwds = mapOf(11L to app, 21L to app, 31L to app)
        assertEquals(mapOf(11L to "forked", 21L to "fresh"), CodexDaemon.match(listOf(fork, fresh), daemon, evidence(held, cwds)))
        // Two forks of one source cannot be told apart by it.
        val twin = instance(31, started = t0.plusSeconds(30), fork = "source")
        val twins = held + listOf(rollout("twin", created = t0.plusSeconds(31), written = t0.plusMillis(31_045), forkedFrom = "source"))
        assertEquals(mapOf(21L to "fresh"), CodexDaemon.match(listOf(fork, fresh, twin), daemon, evidence(twins, cwds)))
    }

    @Test fun `any gap in the evidence matches nothing`() {
        val held = listOf(rollout("fresh"))
        val cwds = mapOf(11L to app)
        val tui = listOf(instance(11))
        assertEquals(1, CodexDaemon.match(tui, daemon, evidence(held, cwds)).size)
        assertTrue(CodexDaemon.match(tui, daemon, evidence(held, cwds, unknown = setOf(daemon))).isEmpty())
        assertTrue(CodexDaemon.match(tui, daemon, ProcessTranscripts.Result(emptyMap(), cwds = cwds)).isEmpty())
        assertTrue(CodexDaemon.match(tui, daemon, evidence(held, cwds, unknown = setOf(11L))).isEmpty())
        assertTrue(CodexDaemon.match(tui, daemon, evidence(held, emptyMap())).isEmpty())
        val unreadable = temporary.root.toPath().resolve("rollout-unreadable.jsonl").also { Files.writeString(it, "{not json\n") }
        assertTrue(CodexDaemon.match(tui, daemon, evidence(held + listOf(unreadable), cwds)).isEmpty())
    }

    private fun row(pid: Long, parent: Long?, command: String, vararg args: String, started: Instant? = null) =
        ProcessRecord(pid, parent, command, args.toList(), ProcessHandle.current(), started)

    @Test fun `daemon pid is trusted only while the recorded process start still matches`() {
        val home = temporary.newFolder("codex-home").toPath()
        val table = ProcessTableSnapshot.of(listOf(row(daemon, 1, "/x/codex", "app-server", started = Instant.ofEpochSecond(1_791_463_103))))
        assertNull("No marker means no daemon", CodexDaemon.pid(home, table))
        val marker = home.resolve("app-server-daemon/daemon.pid").also { Files.createDirectories(it.parent) }
        fun write(pid: Long, start: Long) = Files.writeString(marker,
            """{"pid":$pid,"processStartTime":"TEST","processIdentity":{"bootId":"TEST","uniqueId":1,"startSeconds":$start,"startMicroseconds":5}}""")
        write(daemon, 1_791_463_103)
        assertEquals(daemon, CodexDaemon.pid(home, table))
        write(daemon, 1_791_000_000)
        assertNull("A reused pid started later", CodexDaemon.pid(home, table))
        write(901, 1_791_463_103)
        assertNull("The recorded process is gone", CodexDaemon.pid(home, table))
        Files.writeString(marker, "{not json")
        assertNull(CodexDaemon.pid(home, table))
    }

    @Test fun `instances group launch wrappers and exclude the daemon, its children and non-TUI commands`() {
        val table = ProcessTableSnapshot.of(listOf(
            row(daemon, 1, "/x/codex", "app-server", "--managed-daemon"),
            row(901, daemon, "/x/codex", "exec", "tool"),
            row(10, 1, "/bin/zsh"),
            row(11, 10, "/bin/node", "/opt/homebrew/bin/codex", "resume", "s1", started = t0),
            row(12, 11, "/x/codex", "resume", "s1", started = t0.plusMillis(300)),
            row(29, 1, "/editor/helper"),
            row(30, 29, "/editor/codex", "app-server"),
            row(40, 1, "/bin/zsh"),
            row(41, 40, "/x/codex", "resume", "--last"),
            row(42, 40, "/x/codex", "exec", "summarise"),
            row(43, 40, "/x/codex", "mcp-server"),
            row(60, 1, "/bin/zsh"),
            row(61, 60, "/x/codex", "fork", "s2"),
            row(50, 10, "/bin/cat"),
        ))
        assertEquals(setOf(CodexDaemon.Instance(setOf(11L, 12L), "s1", t0), CodexDaemon.Instance(setOf(41L), null, resumed = true),
            CodexDaemon.Instance(setOf(61L), null, forkOf = "s2")),
            CodexDaemon.instances(table, "codex", daemon).toSet())
    }

    @Test fun `Seshlog's title override leaves resume and fork targets readable`() {
        // The arguments a shell passes for CodexTitle.OVERRIDE.
        val title = arrayOf("-c", """tui.terminal_title=["activity","thread-name","project-name","thread-id"]""")
        val table = ProcessTableSnapshot.of(listOf(
            row(daemon, 1, "/x/codex", "app-server", "--managed-daemon"),
            row(10, 1, "/bin/zsh"),
            row(11, 10, "/x/codex", "resume", "s1", *title, started = t0),
            row(20, 1, "/bin/zsh"),
            row(21, 20, "/x/codex", "fork", "s2", "--cd", other.toString(), *title, started = t0),
        ))
        assertEquals(setOf(CodexDaemon.Instance(setOf(11L), "s1", t0), CodexDaemon.Instance(setOf(21L), null, t0, forkOf = "s2")),
            CodexDaemon.instances(table, "codex", daemon).toSet())
        assertTrue(SessionProcess.matches(AgentKind.CODEX, "s1", null, listOf("resume", "s1", *title)))
    }

    private fun session(id: String) = Session(AgentKind.CODEX, id, id, app, null, null, Instant.EPOCH,
        temporary.root.toPath().resolve("rollout-2026-10-12T10-00-00-$id.jsonl"), false, null, null, 1, true)

    @Test fun `polling and copy adopt a daemon-held thread and override a resume argument only on a match`() {
        val table = ProcessTableSnapshot.of(listOf(
            row(daemon, 1, "/x/codex", "app-server"),
            row(10, 1, "/bin/zsh"), row(11, 10, "/x/codex", "resume", "old"),
            row(40, 1, "/bin/zsh"), row(41, 40, "/x/codex"),
        ))
        val outside = rollout("outside", other)
        var held = emptyList<Path>()
        val queried = ArrayList<Set<Long>>()
        val inspector = ProcessBatchInspector(
            snapshot = { table },
            writableFiles = { pids -> queried += pids; evidence(held + listOf(outside), mapOf(11L to app, 41L to other)) },
            codexDaemon = { _, _ -> daemon },
        )
        val sessions = listOf(session("old"), session("new"), session("outside"))
        val executables = mapOf(AgentKind.CODEX to "codex")
        fun discovery(sessions: List<Session>, home: Path? = temporary.root.toPath()) =
            inspector.inspect(setOf(10L), sessions, executables, home).discoveries.getValue(10L).discovery

        // Startup: the resumed thread is not open yet, so its argument stands.
        assertEquals(setOf("old"), discovery(sessions).sessionIds)
        assertEquals(setOf(11L, 41L, daemon), queried.last())

        // After an in-Codex /new the daemon holds only the new thread.
        held = listOf(rollout("new"))
        assertEquals(mapOf("new" to 11L), discovery(sessions).processes)
        assertEquals(setOf("old"), discovery(sessions, home = null).sessionIds)
        assertEquals(setOf(11L), queried.last())

        // Not indexed yet: never fall back to the stale argument, so copy rescans instead.
        val unindexed = discovery(sessions - session("new"))
        assertTrue(unindexed.sessionIds.isEmpty())
        assertNull(unindexed.copySession("old"))
        assertEquals("new", SessionProcess.copySession("old", sessions - session("new"), inspect = { discovery(it) }, rescan = { sessions }))
    }
}
