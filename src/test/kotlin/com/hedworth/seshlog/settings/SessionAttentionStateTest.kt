package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.SessionAttention
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionAttentionStateTest : BasePlatformTestCase() {
    fun `test unread timestamps survive XML roundtrip and state copies are independent`() {
        val store = SessionAttentionState()
        val initial = SessionAttentionState.State().apply {
            unread["synthetic-session"] = SessionAttention.Completion("2026-09-26T12:00:00Z", "2026-09-26T11:59:59Z")
        }
        store.loadState(initial)
        initial.unread.clear()
        val xml = XmlSerializer.serialize(store.state)
        val copy = SessionAttentionState()
        copy.loadState(XmlSerializer.deserialize(xml, SessionAttentionState.State::class.java))
        assertEquals(setOf("synthetic-session"), copy.unreadIds)
        val saved = copy.state
        assertEquals("2026-09-26T12:00:00Z", saved.unread.getValue("synthetic-session").activityAt)
        saved.unread.getValue("synthetic-session").activityAt = "changed"
        assertEquals("2026-09-26T12:00:00Z", copy.state.unread.getValue("synthetic-session").activityAt)
        copy.viewedTerminal("synthetic-session")
        assertTrue(copy.unreadIds.isEmpty())
        assertEquals(setOf("synthetic-session"), store.unreadIds)
    }

    fun `test saving while viewed uses the immutable snapshot`() {
        val store = SessionAttentionState()
        store.loadState(SessionAttentionState.State().apply {
            repeat(2_000) { index ->
                unread["synthetic-session-$index"] = SessionAttention.Completion("activity", "content")
            }
        })
        val failures = mutableListOf<Throwable>()
        val start = CountDownLatch(1)
        val saver = Thread {
            try {
                start.await()
                repeat(2_000) { store.state }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            }
        }
        saver.start()
        start.countDown()
        repeat(2_000) { store.viewedTerminal("synthetic-session-$it") }
        saver.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("save thread did not finish", saver.isAlive)
        assertTrue(failures.toString(), failures.isEmpty())
    }

    fun `test a scan prunes unread sessions that disappeared from the index`() {
        val store = SessionAttentionState()
        store.loadState(SessionAttentionState.State().apply {
            unread["removed"] = SessionAttention.Completion("activity", "content")
            unread["kept"] = SessionAttention.Completion("activity", "content")
        })
        val kept = com.hedworth.seshlog.model.Session(
            com.hedworth.seshlog.model.AgentKind.CODEX, "kept", "kept", java.nio.file.Paths.get("/synthetic"),
            null, null, java.time.Instant.EPOCH, null, false, null, null, 0, false,
        )
        store.observe(listOf(kept), emptySet())
        assertEquals(setOf("kept"), store.unreadIds)
        assertEquals(setOf("kept"), store.state.unread.keys)
    }
}
