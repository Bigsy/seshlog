package com.hedworth.seshlog.index

import com.google.gson.JsonParser
import com.hedworth.seshlog.index.lexical.*
import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test

class LexicalRankingTest {
    private val key = SearchSessionKey(AgentKind.CLAUDE_CODE, "a")

    @Test
    fun `FTS computes known BM25 score and separate metadata beats repetitive body`() {
        LexicalSearchStore.open().use { s ->
            s.replace(
                key,
                "1",
                null,
                "1",
                false,
                "needle",
                "needle",
                "",
                listOf(LexicalPassage("e", 0, 6, "h", "needle")),
            )
            val score = s.scores(RetrievalQuery.compile("needle"), listOf(key)).single().score
            // One document in each independent title/body population: FTS floors IDF to 1e-6,
            // tf=1 and length=avgdl, so each BM25 contribution is exactly 1e-6.
            assertEquals(9e-6, score, 1e-12)
            val b = key.copy(id = "b")
            s.replace(
                b,
                "1",
                null,
                "1",
                false,
                "unrelated",
                "",
                "",
                listOf(LexicalPassage("e", 0, 10000, "h", "needle ".repeat(1000))),
            )
            assertTrue(
                s.scores(RetrievalQuery.compile("needle"), listOf(key, b)).maxBy { it.score }.key ==
                    key
            )
            assertTrue(s.scores(RetrievalQuery.compile("missing"), listOf(key, b)).isEmpty())
            assertEquals(1, s.scores(RetrievalQuery.compile("needle"), listOf(b)).size)
        }
    }

    @Test
    fun `phrases retain punctuation entry boundaries stopwords and Unicode`() {
        val rows =
            javaClass
                .getResourceAsStream("/fixtures/search/query-contract.json")!!
                .bufferedReader()
                .use { JsonParser.parseReader(it).asJsonArray }
        rows.forEach { row ->
            val r = row.asJsonObject
            val entries =
                r["fields"].asJsonArray.mapIndexed { i, t ->
                    ConversationEntry(ConversationMessage(Role.ASSISTANT, t.asString, null), "e$i")
                }
            val q = RetrievalQuery.compile(r["query"].asString)
            assertEquals(
                r.toString(),
                r["required"].asBoolean,
                LiteralEvidence.verify(q, emptyList(), "", entries).allowed,
            )
        }
    }

    @Test
    fun `query syntax is literal bounded and aliases are deduplicated`() {
        val q =
            RetrievalQuery.compile(
                "foo_bar SearchRequestScope.kt C++ --retry a.b.C C:\\work\\main.kt"
            )
        assertTrue(q.technical)
        assertEquals(q.tokens.distinct(), q.tokens)
        assertTrue("foo_bar" in q.tokens && "scope" in LexicalTokens.tokens("SearchRequestScope"))
        assertTrue(RetrievalQuery.compile("the and of").tokens.isEmpty())
        assertTrue(RetrievalQuery.compile("OR NOT NEAR*").match.contains("\"not\""))
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalQuery.compile("x".repeat(4097))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalQuery.compile((0..128).joinToString(" ") { "term$it" })
        }
    }

    @Test
    fun `rare terms and document length follow FTS5 BM25 and empty scopes clear literal candidates`() {
        LexicalSearchStore.open().use { store ->
            val keys = (0..5).map { key.copy(id = "document$it") }
            keys.forEachIndexed { i, k ->
                val text =
                    when (i) {
                        0 -> "rare common"
                        1 -> "rare common " + "filler ".repeat(100)
                        else -> "common filler"
                    }
                store.replace(
                    k,
                    "1",
                    null,
                    searchHash("metadata"),
                    false,
                    "",
                    "",
                    "",
                    listOf(LexicalPassage("entry", 0, text.length, searchHash(text), text)),
                )
            }
            val rare = store.scores(RetrievalQuery.compile("rare"), keys).associateBy { it.key }
            val averageLength = 112.0 / 6
            val expected =
                kotlin.math.ln(4.5 / 2.5) * 2.2 / (1 + 1.2 * (0.25 + 0.75 * 2 / averageLength))
            assertEquals(expected, rare.getValue(keys[0]).score, 1e-10)
            assertTrue(rare.getValue(keys[0]).score > rare.getValue(keys[1]).score)
            assertTrue(
                rare.getValue(keys[0]).score >
                    store.scores(RetrievalQuery.compile("common"), keys).first().score
            )
            assertFalse(
                store.substringCandidates(RetrievalQuery.compile("rar")) { false }.isEmpty()
            )
            store.scores(RetrievalQuery.compile("rare"), emptyList())
            assertTrue(store.substringCandidates(RetrievalQuery.compile("rar")) { false }.isEmpty())
        }
    }
}
