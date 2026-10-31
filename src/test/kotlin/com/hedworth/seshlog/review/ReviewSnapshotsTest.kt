package com.hedworth.seshlog.review

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.worktree.GitRead
import com.hedworth.seshlog.worktree.ReviewFile
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ReviewSnapshotsTest {
    @Test fun `recorded filter preserves partial operation provenance unusual paths and moves`() = repository { root ->
        val recipient = ReviewRecipient(AgentKind.CODEX, "synthetic", GitRepository.checkout(root)!!)
        val operation = com.hedworth.seshlog.model.ChangedFile(root.resolve("a\\b"),
            com.hedworth.seshlog.model.ChangedFileOperation.MOVE, null, "synthetic patch", "tool-1", "apply_patch", root.resolve("old"))
        val scan = ReviewSnapshots.recorded(recipient, com.hedworth.seshlog.model.ChangedFileScan(
            listOf(operation, operation.copy(path = root.parent.resolve("outside"), previousPath = null)), partial = true))
        assertEquals(setOf("a\\b", "old"), scan.paths)
        assertTrue(scan.partial)
    }
    @Test fun `moving HEAD cannot change captured base and either side goes stale after a disk edit`() = repository { root ->
        val recipient = ReviewRecipient(AgentKind.CODEX, "synthetic", GitRepository.checkout(root)!!)
        Files.writeString(root.resolve("file"), "reviewed\n")
        val scan = ReviewSnapshots.scan(recipient, "HEAD")
        val file = ReviewSnapshots.read(scan, scan.files.single())
        val store = ReviewDraftStore()
        val draft = store.open(recipient)
        store.load(draft.id, file)
        store.add(draft.id, "file", ReviewSide.BASE, 1, 1, "Keep old behavior")
        store.add(draft.id, "file", ReviewSide.CURRENT, 1, 1, "Change this")
        assertTrue(ReviewSnapshots.validate(store.get(draft.id), emptySet()).valid)
        assertEquals(setOf("file"), ReviewSnapshots.validate(store.get(draft.id), setOf("file", "unrelated")).unsaved)
        git(root, "add", "."); commit(root)
        assertNotEquals(scan.base, GitRead.commit(root, "HEAD"))
        assertEquals("base\n", ReviewSnapshots.read(scan, ReviewFile("file", "M")).before)
        Files.writeString(root.resolve("file"), "changed after review\n")
        assertEquals(setOf("file"), ReviewSnapshots.validate(store.get(draft.id), emptySet()).changed)
        val old = store.get(draft.id)
        store.edit(draft.id, old.comments[0].id, "Edited during load")
        assertThrows(IllegalStateException::class.java) { store.load(draft.id, file, old.generation) }
    }
    @Test fun `raw byte hashes distinguish malformed UTF8 with identical replacement text`() = repository { root ->
        val recipient = ReviewRecipient(AgentKind.CODEX, "synthetic", GitRepository.checkout(root)!!)
        Files.write(root.resolve("file"), byteArrayOf(0x80.toByte()))
        val scan = ReviewSnapshots.scan(recipient, "HEAD")
        val first = ReviewSnapshots.read(scan, scan.files.single())
        Files.write(root.resolve("file"), byteArrayOf(0x81.toByte()))
        val second = ReviewSnapshots.read(scan, scan.files.single())
        assertEquals(first.current, second.current)
        assertNotEquals(first.currentHash, second.currentHash)
        assertNotEquals(first.revision, second.revision)
    }
    @Test fun `snapshot reads preserve added deleted containment symlink and checkout guards`() = repository { root ->
        val recipient = ReviewRecipient(AgentKind.CODEX, "synthetic", GitRepository.checkout(root)!!)
        Files.delete(root.resolve("file"))
        Files.writeString(root.resolve("new"), "new\n")
        val scan = ReviewSnapshots.scan(recipient, "HEAD")
        assertEquals("", ReviewSnapshots.read(scan, scan.files.single { it.path == "file" }).current)
        assertEquals("", ReviewSnapshots.read(scan, scan.files.single { it.path == "new" }).before)
        Files.createSymbolicLink(root.resolve("link"), Path.of("new"))
        assertThrows(IllegalStateException::class.java) { ReviewSnapshots.read(scan, ReviewFile("link", "?")) }
        assertThrows(IllegalStateException::class.java) { ReviewSnapshots.read(scan, ReviewFile("../outside", "?")) }
        assertThrows(IllegalStateException::class.java) { ReviewSnapshots.scan(recipient.copy(checkout = recipient.checkout.copy(directoryKey = "replaced")), "HEAD") }
    }
    private fun repository(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("seshlog-review-snapshot-").toRealPath()
        try {
            git(root, "init", "-q")
            Files.writeString(root.resolve("file"), "base\n")
            git(root, "add", "."); commit(root)
            work(root)
        } finally { root.toFile().deleteRecursively() }
    }
    private fun git(root: Path, vararg args: String) = GitRead.run(root, args.toList())
    private fun commit(root: Path) = git(root, "-c", "user.name=Synthetic", "-c", "user.email=test@example.invalid",
        "-c", "commit.gpgsign=false", "commit", "-qm", "synthetic")
}
