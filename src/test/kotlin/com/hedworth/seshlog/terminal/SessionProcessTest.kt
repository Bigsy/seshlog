package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import org.junit.Assert.*
import org.junit.Test

class SessionProcessTest {
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
}
