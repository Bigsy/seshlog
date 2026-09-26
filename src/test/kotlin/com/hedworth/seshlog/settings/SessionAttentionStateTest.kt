package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.SessionAttention
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

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
}
