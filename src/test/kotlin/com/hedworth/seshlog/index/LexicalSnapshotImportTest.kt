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

    @Test fun `dense category round trip preserves source winners and rejects malformed or legacy snapshots atomically`() {
        val snapshot = temporary.root.toPath().resolve("categories.sqlite")
        val text = (0 until 350).joinToString(" ") { "t${it.toString().padStart(3, '0')}" }
        val keys = SearchEntrySource.entries.associateWith { importedKey.copy(id = it.name) }
        val query = RetrievalQuery.compile("t100")
        val expected = LexicalSearchStore.open().use { store ->
            keys.forEach { (source, key) ->
                store.replace(key, "s", null, searchHash("m"), false, "", "", "",
                    (0 until 256).map { LexicalPassage("entry:$it", 0, text.length, searchHash(text), text, source) })
            }
            store.export(snapshot)
            store.scores(query, keys.values, SearchSources.ALL)
        }
        val target = temporary.root.toPath().resolve("categories-target.sqlite")
        LexicalSearchStore.open(target).use { store ->
            assertEquals(4, store.importSnapshot(snapshot))
            assertEquals(1024, store.passageCount)
            assertEquals(expected, store.scores(query, keys.values, SearchSources.ALL))
            assertEquals(setOf(SearchMatchSource.USER, SearchMatchSource.ASSISTANT), store.scores(query, keys.values).map { it.source }.toSet())
            assertTrue(store.scores(query, keys.values, SearchSources.METADATA_ONLY).isEmpty())
            assertEquals(0, store.importSnapshot(snapshot))
        }
        LexicalSearchStore.open(target).use { store ->
            assertEquals(expected, store.scores(query, keys.values, SearchSources.ALL))
            for (mutation in listOf("UPDATE associations SET category='UNKNOWN'", "UPDATE associations SET category='USER' WHERE field='tool_body'", "PRAGMA user_version=2")) {
                val invalid = temporary.root.toPath().resolve("bad-${mutation.hashCode()}.sqlite")
                java.nio.file.Files.copy(snapshot, invalid)
                org.sqlite.JDBC.createConnection("jdbc:sqlite:$invalid", java.util.Properties()).use { c -> c.createStatement().use { it.execute(mutation) } }
                assertThrows(IllegalArgumentException::class.java) { store.importSnapshot(invalid) }
                assertEquals(expected, store.scores(query, keys.values, SearchSources.ALL))
                assertEquals(4, store.sourceCount)
            }
        }
    }

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
