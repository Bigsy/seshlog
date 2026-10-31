package com.hedworth.seshlog.review

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.model.ChangedFileScan
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.worktree.ReviewFile
import com.hedworth.seshlog.worktree.WorktreeReview
import com.hedworth.seshlog.worktree.WorktreeReviewSnapshot

/** Git, source reads and hashes: worker calls only. No documents are saved or source files modified. */
internal object ReviewSnapshots {
    data class Validation(val changed: Set<String>, val unsaved: Set<String>, val unavailable: Boolean = false) {
        val valid: Boolean get() = changed.isEmpty() && unsaved.isEmpty() && !unavailable
    }
    fun scan(recipient: ReviewRecipient, ref: String): WorktreeReviewSnapshot = WorktreeReview.scan(recipient.checkout, ref)
    fun read(scan: WorktreeReviewSnapshot, file: ReviewFile): ReviewFileSnapshot {
        val content = WorktreeReview.content(scan, file)
        return ReviewFileSnapshot(file.path, scan.base, content.before, content.current, file.status, content.beforeHash, content.currentHash)
    }
    fun verifyRecipient(recipient: ReviewRecipient, session: Session?): Boolean {
        if (session == null || session.id != recipient.sessionId || session.kind != recipient.kind || session.location?.uncertain == true) return false
        val checkout = GitRepository.checkout(session.cwd, recipient.checkout) ?: return false
        return checkout.available && recipient.checkout.sameIdentity(checkout)
    }
    fun validate(draft: ReviewDraft, unsaved: Set<String>, read: (ReviewFileSnapshot) -> ReviewFileSnapshot = { file ->
        read(WorktreeReviewSnapshot(draft.recipient.checkout, draft.base!!, emptyList()), ReviewFile(file.path, file.gitStatus))
    }): Validation {
        val paths = draft.comments.mapTo(LinkedHashSet()) { it.path }
        val changed = LinkedHashSet<String>()
        try {
            WorktreeReview.verify(draft.recipient.checkout)
            for (path in paths) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val file = draft.files[path]
                val revision = if (file == null) null else try { read(file).revision }
                    catch (e: InterruptedException) { throw e } catch (_: Exception) { null }
                if (file == null || revision != file.revision) changed += path
            }
            WorktreeReview.verify(draft.recipient.checkout)
        } catch (e: InterruptedException) { throw e
        } catch (_: Exception) { return Validation(paths, paths.intersect(unsaved), unavailable = true) }
        return Validation(changed, paths.intersect(unsaved))
    }
    data class RecordedFiles(val paths: Set<String>, val partial: Boolean)
    /** Tool-operation provenance only; neither success nor exclusive authorship is inferred. */
    fun recorded(recipient: ReviewRecipient, scan: ChangedFileScan): RecordedFiles {
        val root = recipient.checkout.root
        val paths = scan.files.flatMap { listOfNotNull(it.path, it.previousPath) }.map { it.toAbsolutePath().normalize() }
            .filter { it.startsWith(root) && it != root }.map { root.relativize(it).joinToString("/") }.distinct()
        return RecordedFiles(paths.take(5000).toSet(), scan.partial || paths.size > 5000)
    }
}
