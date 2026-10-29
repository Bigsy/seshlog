package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class AttentionInboxTest {
    private fun session(id: String, seconds: Long = 0) = Session(AgentKind.CODEX, id, id,
        Path.of("/synthetic/$id"), null, Instant.EPOCH, Instant.ofEpochSecond(seconds), null, false,
        null, null, 1, true)

    @Test fun `inbox crosses project boundaries excludes hidden and orders newest unread first`() {
        val rows = listOf(session("old", 1), session("new", 5), session("hidden", 9), session("read", 10))
        assertEquals(listOf("new", "old"), inboxSessions(rows, setOf("old", "new", "hidden"), setOf("hidden")).map { it.id })
    }

    @Test fun `late previews cannot replace newer selection or changed session content`() {
        val state = InboxPreviewState()
        val old = session("old")
        val newer = session("new", 5)
        val first = state.begin(old, null)
        val second = state.begin(newer, null)
        assertFalse(state.accept(first, old))
        assertFalse(state.accept(second, newer.copy(lastActivityAt = Instant.ofEpochSecond(10))))
        assertTrue(state.accept(second, newer))
        state.clear()
        assertFalse(state.accept(second, newer))
        assertNull(state.delivered)
    }

    @Test fun `marking a delivered preview read preserves a newer completion`() {
        val state = InboxPreviewState()
        val selected = session("selected")
        val old = SessionAttention.Completion("old", "old")
        val request = state.begin(selected, old)
        old.activityAt = "mutated"
        assertTrue(state.accept(request, selected))
        assertEquals("old", state.delivered!!.receipt!!.activityAt)
        val attention = SessionAttention(linkedMapOf(selected.id to SessionAttention.Completion("new", "new")))
        assertFalse(attention.viewed(selected.id, state.delivered!!.receipt))
        assertTrue(selected.id in attention.unread)
    }

    @Test fun `preview takes latest assistant text bounds it and never displays user text as a reply`() {
        val messages = listOf(ConversationMessage(Role.ASSISTANT, "old", null),
            ConversationMessage(Role.USER, "private user prompt", null),
            ConversationMessage(Role.ASSISTANT, "a".repeat(8000), null))
        val preview = InboxPreviewState.text(messages)!!
        assertTrue(preview.startsWith("a".repeat(6000)))
        assertTrue(preview.length < 6100)
        assertTrue(preview.contains("Preview shortened"))
        assertNull(InboxPreviewState.text(listOf(messages[1])))
    }
}
