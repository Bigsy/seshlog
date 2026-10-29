package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.model.Checkout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal data class ReviewFile(val path: String, val status: String)
internal data class WorktreeReviewSnapshot(val checkout: Checkout, val base: String, val files: List<ReviewFile>)
internal data class ReviewContent(val before: String, val current: String)

/** A net comparison of the chosen commit and current files, including untracked files. */
internal object WorktreeReview {
    private const val LIMIT = 8 * 1024 * 1024

    fun verify(checkout: Checkout) {
        val current = GitRepository.checkout(checkout.root, checkout)
        check(current?.available == true && checkout.sameIdentity(current)) { "Checkout is missing or replaced; select it again" }
    }

    fun scan(checkout: Checkout, ref: String): WorktreeReviewSnapshot {
        verify(checkout)
        val base = GitRead.commit(checkout.root, ref)
        val tracked = GitRead.run(checkout.root, listOf("diff", "--no-ext-diff", "--no-textconv", "--no-renames",
            "--name-status", "-z", base, "--"), 1024 * 1024).toString(Charsets.UTF_8)
        val untracked = GitRead.run(checkout.root, listOf("ls-files", "--others", "--exclude-standard", "-z"),
            1024 * 1024).toString(Charsets.UTF_8)
        val files = parse(tracked, untracked)
        check(files.size <= 5000) { "More than 5,000 changed files; use the IDE Git tools for this checkout" }
        verify(checkout)
        return WorktreeReviewSnapshot(checkout, base, files)
    }

    internal fun parse(tracked: String, untracked: String): List<ReviewFile> {
        val fields = tracked.split('\u0000').dropLastWhile { it.isEmpty() }
        check(fields.size % 2 == 0) { "Incomplete Git change list" }
        return (fields.chunked(2).map { ReviewFile(it[1], it[0]) } +
            untracked.split('\u0000').filter { it.isNotEmpty() }.map { ReviewFile(it, "?") })
            .distinctBy { it.path }.sortedBy { it.path }
    }

    fun content(snapshot: WorktreeReviewSnapshot, file: ReviewFile): ReviewContent {
        val checkout = snapshot.checkout
        verify(checkout)
        val path = checkout.root.resolve(file.path).normalize()
        check(path.startsWith(checkout.root) && path != checkout.root) { "File is outside this checkout" }
        val before = if (file.status == "?" || file.status == "A") byteArrayOf() else
            GitRead.run(checkout.root, listOf("show", "${snapshot.base}:${file.path}"), LIMIT)
        // Do not follow file or parent symlinks into another checkout or private agent storage.
        var cursor = path
        while (cursor != checkout.root) {
            check(!Files.isSymbolicLink(cursor)) { "Symlink content is not previewed; use the IDE Git tools" }
            cursor = cursor.parent
        }
        val current = if (Files.notExists(path, NOFOLLOW_LINKS)) byteArrayOf() else {
            check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Not a regular file; use the IDE Git tools" }
            Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(LIMIT + 1) }
        }
        check(current.size <= LIMIT) { "File exceeds the 8 MiB review limit" }
        check(before.none { it == 0.toByte() } && current.none { it == 0.toByte() }) {
            "Binary files are listed but not previewed; use the IDE Git tools"
        }
        verify(checkout)
        return ReviewContent(before.toString(Charsets.UTF_8), current.toString(Charsets.UTF_8))
    }
}
