package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.SearchSort
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test

class SessionViewStateTest {
    @Test fun `defaults and explicit independent preferences survive serialization`() {
        val preferences = SessionViewState()
        assertEquals(SearchSort.BEST_MATCH, preferences.searchSort)
        assertTrue(preferences.grouped(false))
        assertFalse(preferences.grouped(true))
        preferences.searchSort = SearchSort.RECENTLY_ACTIVE
        preferences.setGrouped(false, false)
        preferences.setGrouped(true, true)
        val restored = SessionViewState()
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(preferences.state), SessionViewState.State::class.java))
        assertEquals(SearchSort.RECENTLY_ACTIVE, restored.searchSort)
        assertFalse(restored.grouped(false))
        assertTrue(restored.grouped(true))
        restored.state.searchSort = "future-value"
        assertEquals(SearchSort.BEST_MATCH, restored.searchSort)
    }
}
