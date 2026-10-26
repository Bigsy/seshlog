package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths
import java.time.Instant

class ContentSearchServiceTest : BasePlatformTestCase() {
    fun `test source context is captured and late all activity delivery cannot replace conversation`() {
        val transcript = Paths.get(javaClass.getResource("/fixtures/claude_tools.jsonl")!!.toURI())
        val session = Session(AgentKind.CLAUDE_CODE, "synthetic-sources", "Fixture", transcript.parent, null,
            null, Instant.EPOCH, transcript, false, null, null, 3, true)
        val service = ContentSearchService.getInstance()
        val scope = SearchRequestScope()
        val other = SearchRequestScope()
        try {
            var obsolete = false
            var current: SearchResultSnapshot? = null
            var otherResult: SearchResultSnapshot? = null
            service.searchSnapshot(scope, "TOOL_ONLY", listOf(session), SearchSources.ALL) { obsolete = true }
            service.searchSnapshot(scope, "TOOL_ONLY", listOf(session), SearchSources.DEFAULT) { current = it }
            service.searchSnapshot(other, "TOOL_ONLY", listOf(session), SearchSources.ALL) { otherResult = it }
            pumpUntil { current != null && otherResult != null }
            assertFalse(obsolete)
            assertTrue(current!!.hits.isEmpty())
            assertEquals(SearchContext("TOOL_ONLY"), current!!.context)
            assertEquals(1, otherResult!!.hits.size)
            assertEquals(SearchContext("TOOL_ONLY", SearchSources.ALL), otherResult!!.context)
        } finally { scope.dispose(); other.dispose() }
    }
    fun `test independent callers both receive results and disposing one leaves the other active`() {
        val transcript = Paths.get(javaClass.getResource("/fixtures/custom_and_ai_title.jsonl")!!.toURI())
        val session = Session(AgentKind.CLAUDE_CODE, "synthetic-search", "Fixture", transcript.parent, null,
            null, Instant.EPOCH, transcript, false, null, null, 3, true)
        val service = ContentSearchService.getInstance()
        val a = SearchRequestScope()
        val b = SearchRequestScope()
        try {
            var resultA: List<SearchHit>? = null
            var resultB: List<SearchHit>? = null
            service.search(a, "sure", listOf(session)) { resultA = it }
            service.search(b, "sure", listOf(session)) { resultB = it }
            pumpUntil { resultA != null && resultB != null }
            assertEquals(1, resultA!!.size)
            assertEquals(1, resultB!!.size)
            resultA = null
            resultB = null
            service.search(a, "sure", listOf(session)) { resultA = it }
            service.search(b, "sure", listOf(session)) { resultB = it }
            a.dispose()
            pumpUntil { resultB != null }
            assertNull(resultA)
            assertEquals(1, resultB!!.size)
        } finally { a.dispose(); b.dispose() }
    }

    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue("Search callbacks were not delivered", done())
    }
}
