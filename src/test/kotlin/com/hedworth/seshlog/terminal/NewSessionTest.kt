package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSessionTest {
    @Test
    fun `fresh command uses configured executable and arguments for every agent`() {
        val expected = mapOf(
            AgentKind.CLAUDE_CODE to ("claude" to "--permission-mode plan"),
            AgentKind.CODEX to ("codex" to "--model gpt-5"),
            AgentKind.OPENCODE to ("opencode" to "--model anthropic/sonnet"),
            AgentKind.PI to ("pi" to "--provider local"),
        )
        for ((kind, args) in expected) {
            assertEquals("${args.first} ${args.second}", NewSessionCommand.build(kind, args.first, args.second))
        }
    }

    @Test
    fun `fresh command quotes executable and preserves empty extra arguments`() {
        assertEquals("'/opt/claude code' --model sonnet",
            NewSessionCommand.build(AgentKind.CLAUDE_CODE, "/opt/claude code", "--model sonnet"))
        assertEquals("opencode", NewSessionCommand.build(AgentKind.OPENCODE, "opencode", " \t "))
    }

    @Test
    fun `pending association ignores old ids and resolves exact tab evidence`() {
        val pending = PendingSessionAssociations<String>()
        pending.mark("tab-a", setOf("parent", "old"))
        assertTrue(pending.isPending("tab-a"))
        assertEquals("fresh", pending.resolve("tab-a", setOf("parent", "old", "fresh")))
        assertFalse(pending.isPending("tab-a"))
    }

    @Test
    fun `ambiguous or global-only ids never guess an association`() {
        val pending = PendingSessionAssociations<String>()
        pending.mark("tab-a", setOf("old-a"))
        pending.mark("tab-b", setOf("old-b"))
        assertNull(pending.resolve("tab-a", setOf("old-a", "fresh-a", "fresh-b")))
        assertTrue(pending.isPending("tab-a"))
        assertEquals("fresh-b", pending.resolve("tab-b", setOf("old-b", "fresh-b")))
        pending.forget("tab-a")
        assertFalse(pending.isPending("tab-a"))
    }

    @Test
    fun `pending association expires conservatively`() {
        var now = 0L
        val pending = PendingSessionAssociations<String>(clock = { now }, timeoutMillis = 60_000)
        pending.mark("tab", emptySet())
        now = 59_999
        assertTrue(pending.isPending("tab"))
        now = 60_000
        assertFalse(pending.isPending("tab"))
    }
}
