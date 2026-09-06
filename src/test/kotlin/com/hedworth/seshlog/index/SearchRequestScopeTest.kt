package com.hedworth.seshlog.index

import org.junit.Assert.*
import org.junit.Test

class SearchRequestScopeTest {
    @Test fun `two callers can search concurrently and cancel independently`() {
        val a = SearchRequestScope()
        val b = SearchRequestScope()
        val firstA = a.begin()
        val firstB = b.begin()
        assertFalse(firstA())
        assertFalse(firstB())
        val secondA = a.begin()
        assertTrue(firstA())
        assertFalse(firstB())
        a.cancel()
        assertTrue(secondA())
        assertFalse(firstB())
        a.dispose()
        assertFalse(firstB())
        b.dispose()
        assertTrue(firstB())
        assertTrue(b.begin()())
    }
}
