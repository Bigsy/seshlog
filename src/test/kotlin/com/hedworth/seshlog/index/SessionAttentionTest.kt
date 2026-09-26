package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class SessionAttentionTest {
    private fun session(id: String = "a", activity: Activity = Activity.WAITING, at: Long = 1, live: Boolean = true) = Session(
        AgentKind.CODEX, id, id, Paths.get("/synthetic"), null, null, Instant.ofEpochSecond(at), null,
        live, null, null, 1, true, activity = activity, activitySince = Instant.ofEpochSecond(at),
    )

    @Test fun `initial history and untracked changes do not create unread completions`() {
        val attention = SessionAttention()
        attention.observe(listOf(session(), session("history", Activity.WORKING, live = false)), emptySet())
        assertTrue(attention.unread.isEmpty())
        attention.observe(listOf(session(), session("history", live = false, at = 2)), emptySet())
        assertTrue(attention.unread.isEmpty())
    }

    @Test fun `tracked turns finish once and viewing stays acknowledged across scans`() {
        val attention = SessionAttention()
        val working = session(activity = Activity.WORKING, live = false)
        val done = session(at = 2, live = false)
        attention.observe(listOf(working), setOf("a"))
        attention.observe(listOf(done), emptySet()) // process exited after completion
        assertEquals(setOf("a"), attention.unread.keys)
        assertTrue(attention.viewed("a", attention.receipt(done)))
        attention.observe(listOf(done), emptySet())
        assertTrue(attention.unread.isEmpty())
    }

    @Test fun `completion timestamp catches a whole turn between polls but metadata edits do not`() {
        val attention = SessionAttention()
        val original = session()
        attention.observe(listOf(original), emptySet())
        attention.observe(listOf(original.copy(title = "Renamed", lastActivityAt = Instant.ofEpochSecond(2))), emptySet())
        assertTrue(attention.unread.isEmpty())
        val done = session(at = 3)
        attention.observe(listOf(done), emptySet())
        assertNotNull(attention.receipt(done))
    }

    @Test fun `stale preview cannot clear a newer turn and resumed work clears old badges`() {
        val attention = SessionAttention()
        attention.observe(listOf(session(activity = Activity.WORKING)), emptySet())
        val done = session(at = 2)
        attention.observe(listOf(done), emptySet())
        val receipt = attention.receipt(done)
        attention.observe(listOf(session(activity = Activity.WORKING, at = 3)), emptySet())
        assertTrue(attention.unread.isEmpty())
        val later = session(at = 4)
        attention.observe(listOf(later), emptySet())
        assertNull(attention.receipt(done))
        assertFalse(attention.viewed("a", receipt))
        assertFalse(attention.viewed("a", null))
        assertTrue(attention.viewed("a", attention.receipt(later)))
    }

    @Test fun `interruptions and unknown activity are not completed turns`() {
        val attention = SessionAttention()
        attention.observe(listOf(session(activity = Activity.WORKING)), emptySet())
        attention.observe(listOf(session(activity = Activity.INTERRUPTED, at = 2)), emptySet())
        assertTrue(attention.unread.isEmpty())
        attention.observe(listOf(session(activity = Activity.UNKNOWN, at = 3)), emptySet())
        attention.observe(listOf(session(at = 4)), emptySet())
        assertTrue(attention.unread.isEmpty())
    }

    @Test fun `persisted unread survives restart missing storage and loss of live status`() {
        val original = SessionAttention()
        original.observe(listOf(session(activity = Activity.WORKING)), emptySet())
        val done = session(at = 3).copy(lastActivityAt = Instant.ofEpochSecond(2))
        original.observe(listOf(done), emptySet())
        val restored = SessionAttention(original.unread.toMutableMap())
        restored.observe(emptyList(), emptySet())
        val exited = done.copy(activity = Activity.UNKNOWN, activitySince = null, isLive = false)
        restored.observe(listOf(exited, session("old")), emptySet())
        assertEquals(setOf("a"), restored.unread.keys)
        assertTrue(restored.viewed("a", restored.receipt(exited)))
    }

    @Test fun `providers without activity timestamps require an observed working turn`() {
        val attention = SessionAttention()
        val done = session().copy(activitySince = null)
        attention.observe(listOf(done), emptySet())
        attention.observe(listOf(done.copy(lastActivityAt = Instant.ofEpochSecond(2))), emptySet())
        assertTrue(attention.unread.isEmpty())
        attention.observe(listOf(done.copy(activity = Activity.WORKING)), emptySet())
        attention.observe(listOf(done), emptySet())
        assertNotNull(attention.receipt(done))
    }

    @Test fun `next unread follows supplied order wraps and ignores filtered out sessions`() {
        val sessions = listOf(session("pinned"), session("new"), session("old"))
        val unread = setOf("pinned", "old", "hidden")
        assertEquals("pinned", SessionAttention.next(sessions, unread, null)?.id)
        assertEquals("old", SessionAttention.next(sessions, unread, "pinned")?.id)
        assertEquals("pinned", SessionAttention.next(sessions, unread, "old")?.id)
        assertNull(SessionAttention.next(sessions, setOf("hidden"), null))
        assertNull(SessionAttention.next(emptyList(), unread, null))
    }

    @Test fun `group summary includes only tracked working sessions and unread completions`() {
        val sessions = listOf(session("live", Activity.WORKING),
            session("owned", Activity.WORKING, live = false), session("historical", Activity.WORKING, live = false),
            session("unread", live = false), session("idle"))
        assertEquals("2 working · 1 unread", SessionAttention.summary(sessions, setOf("owned"), setOf("unread", "elsewhere")))
        assertEquals("", SessionAttention.summary(listOf(session()), emptySet(), emptySet()))
    }
}
