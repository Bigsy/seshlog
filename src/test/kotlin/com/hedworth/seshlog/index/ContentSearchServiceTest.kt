package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths
import java.time.Instant

class ContentSearchServiceTest : BasePlatformTestCase() {
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
