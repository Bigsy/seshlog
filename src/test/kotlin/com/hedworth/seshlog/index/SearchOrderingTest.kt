package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class SearchOrderingTest {
    private fun session(id: String, title: String = "Unrelated", activity: Long = 0) = Session(
        AgentKind.CLAUDE_CODE, id, title, Path.of("/synthetic/project"), "main",
        Instant.ofEpochSecond(10_000 - activity), Instant.ofEpochSecond(activity), null, false, null, null, 3, true,
    )

    @Test fun `recent retrieval admits a weak newest match outside the relevance top hundred`() {
        val strong = (1..110).map { session("strong-$it", "cache invalidation", it.toLong()) }
        val newest = session("newest", activity = 1000)
        val excludedTool = session("tool", activity = 2000)
        ContentSearchIndex({ s -> if (s.id == newest.id) listOf("cache") else emptyList() }, { 1 },
            entryExtractor = { s -> listOf(ConversationEntry(
                ConversationMessage(Role.USER, if (s.id == newest.id || s.id == excludedTool.id) "cache" else "unrelated", null),
                "entry", if (s.id == excludedTool.id) EntryKind.TOOL_RESULT else EntryKind.DIALOGUE)) },
        ).use { index ->
            val candidates = strong + newest + excludedTool
            val best = index.searchSnapshot("cache invalidation", candidates)
            assertEquals(100, best.hits.size)
            assertTrue(best.resultLimited)
            assertFalse(best.hits.any { it.session.id == newest.id })
            val recent = index.searchSnapshot("cache invalidation", candidates, sort = SearchSort.RECENTLY_ACTIVE)
            assertEquals(100, recent.hits.size)
            assertTrue(recent.resultLimited)
            assertEquals(newest.id, recent.hits.first().session.id)
            assertEquals("cache", recent.hits.first().snippet)
            assertEquals("entry", recent.hits.first().entryId)
            assertEquals((110 downTo 12).map { "strong-$it" }, recent.hits.drop(1).map { it.session.id })
            assertEquals(SearchSort.RECENTLY_ACTIVE, recent.context!!.sort)
            assertFalse(recent.hits.any { it.session.id == excludedTool.id })
            assertEquals(excludedTool.id, index.search("cache invalidation", candidates, SearchSources.ALL,
                SearchSort.RECENTLY_ACTIVE).first().session.id)
            assertFalse(index.search("\"cache invalidation\"", candidates,
                sort = SearchSort.RECENTLY_ACTIVE).any { it.session.id == newest.id })
            assertFalse(index.search("cache invalidation", strong, sort = SearchSort.RECENTLY_ACTIVE)
                .any { it.session.id == newest.id })
        }
    }

    @Test fun `recent ties use provider and id while best match protects exact evidence`() {
        val oldExact = SearchHit(session("old", "needle"), 1.0, true, "needle", exact = true)
        val newer = SearchHit(session("b", activity = 50), 10.0, false, "needle")
        val sameTime = newer.copy(session = newer.session.copy(id = "a"))
        assertEquals(listOf("old", "a", "b"), listOf(newer, oldExact, sameTime)
            .sortedWith(SearchSort.BEST_MATCH.comparator).map { it.session.id })
        assertEquals(listOf("a", "b", "old"), listOf(newer, oldExact, sameTime)
            .sortedWith(SearchSort.RECENTLY_ACTIVE.comparator).map { it.session.id })
    }
}
