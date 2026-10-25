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
