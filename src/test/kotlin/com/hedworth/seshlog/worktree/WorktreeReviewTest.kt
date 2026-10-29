package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.index.GitRepository
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WorktreeReviewTest {
    @Test fun `review compares committed staged unstaged and untracked changes with chosen base`() = repository { root ->
        Files.writeString(root.resolve("tracked.txt"), "base\n")
        git(root, "add", ".")
        commit(root)
        val base = GitRead.commit(root, "HEAD")
        Files.writeString(root.resolve("tracked.txt"), "committed\n")
        git(root, "add", "."); commit(root)
        Files.writeString(root.resolve("staged.txt"), "staged\n")
        git(root, "add", ".")
        Files.writeString(root.resolve("tracked.txt"), "working\n")
        Files.writeString(root.resolve("space\n雪.txt"), "untracked\n")
        val checkout = GitRepository.checkout(root)!!
        val scan = WorktreeReview.scan(checkout, base)
        assertEquals(setOf("tracked.txt", "staged.txt", "space\n雪.txt"), scan.files.map { it.path }.toSet())
        val content = WorktreeReview.content(scan, scan.files.single { it.path == "tracked.txt" })
        assertEquals("base\n", content.before)
        assertEquals("working\n", content.current)
        assertEquals("", WorktreeReview.content(scan, scan.files.single { it.status == "?" }).before)
        assertTrue(WorktreeReview.scan(checkout, "HEAD").files.any { it.path == "tracked.txt" })
        assertThrows(IllegalStateException::class.java) { WorktreeReview.scan(checkout, "--help") }
    }

    @Test fun `review refuses replaced checkout symlinks binary and oversized content`() = repository { root ->
        Files.writeString(root.resolve("file.txt"), "base")
        git(root, "add", "."); commit(root)
        val checkout = GitRepository.checkout(root)!!
        assertThrows(IllegalStateException::class.java) { WorktreeReview.scan(checkout.copy(directoryKey = "replaced"), "HEAD") }
        Files.write(root.resolve("binary"), byteArrayOf(1, 0, 2))
        Files.write(root.resolve("large"), ByteArray(8 * 1024 * 1024 + 1) { 65 })
        Files.createSymbolicLink(root.resolve("link"), Path.of("file.txt"))
        val scan = WorktreeReview.scan(checkout, "HEAD")
        for (name in listOf("binary", "large", "link")) {
            assertThrows(IllegalStateException::class.java) { WorktreeReview.content(scan, scan.files.single { it.path == name }) }
        }
        assertThrows(IllegalStateException::class.java) { WorktreeReview.content(scan, ReviewFile("../outside", "?")) }
        assertThrows(IllegalStateException::class.java) { GitRead.run(root, listOf("show", "HEAD:file.txt"), 2) }
    }

    @Test fun `renames and deletions remain reviewable without ambiguous path parsing`() = repository { root ->
        Files.writeString(root.resolve("old"), "original")
        git(root, "add", "."); commit(root)
        Files.move(root.resolve("old"), root.resolve("new"))
        git(root, "add", "-A")
        val scan = WorktreeReview.scan(GitRepository.checkout(root)!!, "HEAD")
        assertEquals(setOf(ReviewFile("old", "D"), ReviewFile("new", "A")), scan.files.toSet())
        assertEquals("", WorktreeReview.content(scan, scan.files.single { it.status == "D" }).current)
        assertEquals("original", WorktreeReview.content(scan, scan.files.single { it.status == "A" }).current)
    }

    private fun repository(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("seshlog-review-test").toRealPath()
        try { git(root, "init", "-q"); block(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun git(root: Path, vararg args: String) { GitRead.run(root, args.toList()) }
    private fun commit(root: Path) = git(root, "-c", "user.name=Synthetic", "-c", "user.email=test@example.invalid",
        "-c", "commit.gpgsign=false", "commit", "-qm", "synthetic")
}
