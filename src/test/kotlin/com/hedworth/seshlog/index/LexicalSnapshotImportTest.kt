package com.hedworth.seshlog.index

import com.hedworth.seshlog.index.lexical.*
import com.hedworth.seshlog.model.AgentKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LexicalSnapshotImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val importedKey = SearchSessionKey(AgentKind.CLAUDE_CODE, "dense-passages")
    private val existingKey = SearchSessionKey(AgentKind.CODEX, "existing")

    @Test
    fun `256 valid 350 token passages import without exceeding the working buffer`() {
        val text = (0 until 350).joinToString(" ") { "t${it.toString().padStart(3, '0')}" }
        assertTrue(text.length < 2048)
        val passages =
            (0 until 256).map {
                LexicalPassage("entry:$it", 0, text.length, searchHash(text), text)
            }
        val source = temporary.root.toPath().resolve("source.sqlite")
        val snapshot = temporary.root.toPath().resolve("snapshot.sqlite")
        val query = RetrievalQuery.compile("t100")
        val expected =
            LexicalSearchStore.open(source).use { store ->
                store.replace(
                    importedKey,
                    "stamp",
                    null,
                    searchHash("metadata"),
                    false,
                    "Dense passages",
                    "",
                    "",
                    passages,
                )
                store.export(snapshot)
                store.scores(query, listOf(importedKey)).single()
            }
        val target = temporary.root.toPath().resolve("target.sqlite")
        LexicalSearchStore.open(target).use { store ->
            store.replace(
                existingKey,
                "stamp",
                null,
                searchHash("existing"),
                false,
                "preserve",
                "",
                "",
                emptyList(),
            )
            assertEquals(1, store.importSnapshot(snapshot))
            assertEquals(256, store.passageCount)
            assertEquals(2, store.sourceCount)
            val actual = store.scores(query, listOf(importedKey)).single()
            assertEquals(expected.entry, actual.entry)
            assertEquals(expected.score, actual.score, 0.0)
            assertEquals(
                1,
                store.scores(RetrievalQuery.compile("preserve"), listOf(existingKey)).size,
            )
            assertEquals(0, store.importSnapshot(snapshot))
        }
        LexicalSearchStore.open(target).use { store ->
            assertEquals(256, store.passageCount)
            assertEquals(
                expected.score,
                store.scores(query, listOf(importedKey)).single().score,
                0.0,
            )
        }
    }
}
