package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.*
import org.junit.Test

class SessionProcessTest {
    private fun session(kind: AgentKind, id: String, pid: Long? = null) = Session(
        kind, id, id, java.nio.file.Path.of("/project"), null, null, java.time.Instant.EPOCH,
        null, pid != null, pid, null, 1, true,
    )

    @Test fun `unrelated commands and other sessions do not count as this agent`() {
        assertFalse(SessionProcess.matches(AgentKind.CODEX, "s1", null, listOf("sleep", "60")))
        assertFalse(SessionProcess.matches(AgentKind.CODEX, "s1", null, listOf("resume", "s2")))
        assertFalse(SessionProcess.matches(AgentKind.CODEX, "s1", null, listOf("-c", "codex resume s1")))
        assertFalse(SessionProcess.matches(AgentKind.CODEX, "s1", null, emptyList()))
        assertTrue(SessionProcess.matches(AgentKind.CODEX, "s1", null, listOf("resume", "s1", "--model", "example")))
    }

    @Test fun `resume identities cover every provider including Pi paths with spaces`() {
        assertTrue(SessionProcess.matches(AgentKind.CLAUDE_CODE, "s1", null, listOf("--resume", "s1")))
        assertTrue(SessionProcess.matches(AgentKind.OPENCODE, "s1", null, listOf("--session", "s1")))
        assertTrue(SessionProcess.matches(AgentKind.PI, "s1", "/a b/s.jsonl", listOf("--session", "/a b/s.jsonl")))
        assertFalse(SessionProcess.matches(AgentKind.PI, "s1", "/a b/s.jsonl", listOf("--session", "s1")))
    }

    @Test fun `manual resume adoption requires the agent executable and exact session identity`() {
        val sessions = listOf(session(AgentKind.CODEX, "one"), session(AgentKind.CODEX, "two"))
        val executables = mapOf(AgentKind.CODEX to "codex")
        for (process in listOf(
            SessionProcess.Evidence(123, "/usr/bin/codex", listOf("resume", "two")),
            SessionProcess.Evidence(123, "/usr/bin/node", listOf("/npm/codex/bin/codex.js", "resume", "two")),
        )) assertEquals(setOf("two"), SessionProcess.identify(process, sessions, executables))
        for (process in listOf(
            SessionProcess.Evidence(123, "/usr/bin/printf", listOf("resume", "two")),
            SessionProcess.Evidence(123, "/bin/zsh", listOf("-c", "codex resume two")),
            SessionProcess.Evidence(123, "/usr/bin/codex", listOf("resume", "other")),
            SessionProcess.Evidence(123, "/usr/bin/codex", emptyList()),
            SessionProcess.Evidence(123, null, listOf("resume", "two")),
        )) assertTrue(SessionProcess.identify(process, sessions, executables).isEmpty())
    }

    @Test fun `fresh manual Claude session uses its marker even without process arguments`() {
        val sessions = listOf(session(AgentKind.CLAUDE_CODE, "one", 123), session(AgentKind.CODEX, "two"))
        assertEquals(setOf("one"), SessionProcess.identify(SessionProcess.Evidence(123, null, emptyList()), sessions, emptyMap()))
        assertTrue(SessionProcess.identify(SessionProcess.Evidence(124, null, emptyList()), sessions, emptyMap()).isEmpty())
    }

    @Test fun `forks and session switches cannot adopt their resume source`() {
        val executables = mapOf(AgentKind.CLAUDE_CODE to "claude")
        val source = session(AgentKind.CLAUDE_CODE, "source")
        val target = session(AgentKind.CLAUDE_CODE, "target", 123)
        val fork = SessionProcess.Evidence(123, "/bin/claude", listOf("--resume", "source", "--fork-session"))
        assertTrue(SessionProcess.identify(fork, listOf(source), executables).isEmpty())
        assertEquals(setOf("target"), SessionProcess.identify(fork, listOf(source, target), executables))
        assertEquals(setOf("target"), SessionProcess.identify(fork.copy(arguments = listOf("--resume", "source")),
            listOf(source, target), executables))
        assertFalse(SessionProcess.matches(AgentKind.OPENCODE, "source", null, listOf("--session", "source", "--fork")))
    }

    @Test fun `discovery and running checks include an agent that replaced the shell process`() {
        val process = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        try {
            val live = session(AgentKind.CLAUDE_CODE, "exec-agent", process.pid())
            assertEquals(setOf(live.id), SessionProcess.discover(process.pid(), listOf(live), emptyMap()))
            assertTrue(SessionProcess.isRunning(process.pid(), live))
        } finally {
            process.destroyForcibly()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
