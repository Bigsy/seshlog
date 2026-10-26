package com.hedworth.seshlog.index

import com.hedworth.seshlog.index.lexical.*
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchStorageBoundsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `passage ceiling is shared by user assistant and tool populations`() {
        val path = temporary.root.toPath().resolve("shared-cap.sqlite")
        val key = SearchSessionKey(com.hedworth.seshlog.model.AgentKind.PI, "synthetic")
        LexicalSearchStore.open(path).use { it.replace(key, "s", null, searchHash("m"), false, "", "", "", emptyList()) }
        org.sqlite.JDBC.createConnection("jdbc:sqlite:$path", java.util.Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""WITH RECURSIVE numbers(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM numbers WHERE n<100000)
                    INSERT INTO associations(kind,session,field,entry,start,finish,hash,category)
                    SELECT 'PI','synthetic',CASE n%3 WHEN 0 THEN 'user_body' WHEN 1 THEN 'assistant_body' ELSE 'tool_body' END,
                        'entry:'||n,0,1,'${searchHash("x")}',CASE n%3 WHEN 0 THEN 'USER' WHEN 1 THEN 'ASSISTANT' ELSE 'TOOL_RESULT' END FROM numbers""")
            }
        }
        LexicalSearchStore.open(path).use { store ->
            assertEquals(100000, store.passageCount)
            assertFalse(store.hasRoom(1, key.copy(id = "another")))
            assertTrue(store.hasRoom(0, key.copy(id = "another")))
            assertTrue(store.hasRoom(100000, key))
            assertFalse(store.hasRoom(100001, key))
        }
    }

    @Test
    fun `fingerprinting bounds the whole source set and honors cancellation`() {
        val paths = (0..2).map { temporary.newFile("source$it").toPath() }
        paths.forEach {
            RandomAccessFile(it.toFile(), "rw").use { file -> file.setLength(6_000_000) }
        }
        assertNull(sourceFingerprint(paths))
        assertNull(sourceFingerprint(listOf(paths.first())) { true })
        assertNotNull(sourceFingerprint(listOf(paths.first())))
    }

    @Test
    fun `export never removes or overwrites an existing destination`() {
        val destination = temporary.root.toPath().resolve("already-exists.sqlite")
        Files.writeString(destination, "existing file")
        LexicalSearchStore.open().use { store ->
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                store.export(destination)
            }
        }
        assertEquals("existing file", Files.readString(destination))
    }

    @Test
    fun `recovery copies count toward disk bounds and live stores cannot follow symlinks`() {
        val directory = temporary.root.toPath()
        val recovery = directory.resolve("lexical-recovery-1.sqlite")
        RandomAccessFile(recovery.toFile(), "rw").use { it.setLength(224L * 1024 * 1024) }
        val target = directory.resolve("lexical.sqlite")
        assertThrows(IllegalArgumentException::class.java) { LexicalSearchStore.open(target) }
        assertEquals(224L * 1024 * 1024, Files.size(recovery))
        Files.delete(recovery)
        val foreign = directory.resolve("foreign.sqlite")
        Files.writeString(foreign, "preserve foreign content")
        Files.deleteIfExists(target)
        try {
            Files.createSymbolicLink(target, foreign)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            // Windows may require an administrator/developer-mode grant for symbolic links.
            return
        }
        assertThrows(IllegalArgumentException::class.java) { LexicalSearchStore.open(target) }
        assertEquals("preserve foreign content", Files.readString(foreign))
    }
}
