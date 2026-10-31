package com.hedworth.seshlog.review

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Checkout
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

internal data class ReviewRecipient(val kind: AgentKind, val sessionId: String, val checkout: Checkout)
internal enum class ReviewSide { BASE, CURRENT }
internal enum class ReviewValidation { CURRENT, NEEDS_RECHECK }
internal data class ReviewFileSnapshot(
    val path: String, val base: String, val before: String, val current: String,
    val gitStatus: String = "M", val beforeBytesHash: String? = null, val currentBytesHash: String? = null,
) {
    val beforeHash = beforeBytesHash ?: reviewHash(before)
    val currentHash = currentBytesHash ?: reviewHash(current)
    val revision = reviewHash("$base\n$beforeHash\n$currentHash")
    val memoryBytes: Long get() = (before.length.toLong() + current.length) * 2
    fun text(side: ReviewSide) = if (side == ReviewSide.BASE) before else current
    fun selection(side: ReviewSide, first: Int, last: Int): String {
        val text = text(side)
        val lines = if (text.isEmpty()) emptyList() else text.split('\n').let {
            if (text.endsWith('\n')) it.dropLast(1) else it
        }
        require(first >= 1 && last >= first && last <= lines.size) { "Select real lines on this side of the diff" }
        require(last - first < 20) { "Select at most 20 excerpt lines" }
        val selected = lines.subList(first - 1, last).joinToString("\n")
        require(selected.length <= 4000) { "Narrow the selection to at most 4,000 excerpt characters" }
        return selected
    }
}
internal data class ReviewComment(
    val id: String, val revision: Long, val path: String, val side: ReviewSide,
    val firstLine: Int, val lastLine: Int, val selected: String, val selectedHash: String,
    val fileRevision: String, val note: String, val validation: ReviewValidation = ReviewValidation.CURRENT,
)
internal enum class ReviewDeliveryStatus { ACCEPTED, QUEUED, REJECTED, UNAVAILABLE, UNKNOWN }
internal data class ReviewDeliveryResult(val status: ReviewDeliveryStatus, val reason: String, val receipt: String? = null)
internal data class ReviewSubmission(
    val id: String, val draftId: String, val recipient: ReviewRecipient, val base: String,
    val comments: List<ReviewComment>, val overallNote: String, val payload: String, val overallRevision: Long,
)
internal data class ReviewDraft(
    val id: String, val recipient: ReviewRecipient, val base: String? = null,
    val files: Map<String, ReviewFileSnapshot> = emptyMap(), val comments: List<ReviewComment> = emptyList(),
    val overallNote: String = "", val overallRevision: Long = 0, val generation: Long = 0, val pending: ReviewSubmission? = null,
    val lastResult: ReviewDeliveryResult? = null, val sent: List<ReviewSubmission> = emptyList(),
    val sentResults: Map<String, ReviewDeliveryResult> = emptyMap(),
    // A different attempt's receipt cannot resolve whether an earlier batch arrived.
    val hasUnknownDelivery: Boolean = false,
)

internal fun reviewHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Pure, bounded, in-memory ownership. Returned collections cannot mutate the stored draft. */
internal class ReviewDraftStore(private val memoryLimit: Long = 32L * 1024 * 1024) {
    private val drafts = LinkedHashMap<String, ReviewDraft>()
    @Synchronized fun all(): List<ReviewDraft> = drafts.values.toList()
    @Synchronized fun get(id: String): ReviewDraft = drafts[id] ?: error("This review has been discarded")
    @Synchronized fun open(recipient: ReviewRecipient): ReviewDraft {
        drafts.values.firstOrNull { it.recipient.kind == recipient.kind && it.recipient.sessionId == recipient.sessionId &&
            it.recipient.checkout.sameIdentity(recipient.checkout) }?.let { return it }
        check(drafts.size < 10) { "10 reviews are already open; explicitly discard a review before opening another" }
        return ReviewDraft(UUID.randomUUID().toString(), recipient).also { drafts[it.id] = it }
    }
    @Synchronized fun discard(id: String) {
        check(get(id).pending == null) { "Wait for the current submission before discarding this review" }
        drafts.remove(id)
    }
    @Synchronized fun load(id: String, file: ReviewFileSnapshot, expectedGeneration: Long? = null): ReviewDraft {
        val draft = get(id)
        check(expectedGeneration == null || expectedGeneration == draft.generation) { "The draft changed while loading; refresh this file again" }
        check(draft.pending == null) { "Wait for this submission before refreshing snapshots; your draft has been kept" }
        require(draft.base == null || draft.base == file.base) { "The review base is fixed; start a new review to change it" }
        val comments = draft.comments.map { comment ->
            if (comment.path == file.path && comment.fileRevision != file.revision && comment.validation != ReviewValidation.NEEDS_RECHECK)
                comment.copy(revision = comment.revision + 1, validation = ReviewValidation.NEEDS_RECHECK) else comment
        }
        return put(draft.copy(base = file.base, files = draft.files + (file.path to file), comments = comments))
    }
    @Synchronized fun add(id: String, path: String, side: ReviewSide, first: Int, last: Int, note: String, expectedRevision: String? = null): ReviewDraft {
        val draft = get(id)
        check(draft.comments.size < 50) { "A review can contain at most 50 comments" }
        validateNote(note)
        val file = draft.files[path] ?: error("Load this file's reviewed snapshot first")
        check(expectedRevision == null || expectedRevision == file.revision) { "This diff is outdated; reopen the refreshed diff before commenting" }
        val selected = file.selection(side, first, last)
        return put(draft.copy(comments = draft.comments + ReviewComment(UUID.randomUUID().toString(), 1,
            path, side, first, last, selected, reviewHash(selected), file.revision, note)))
    }
    @Synchronized fun edit(id: String, commentId: String, note: String): ReviewDraft {
        validateNote(note)
        val draft = get(id)
        require(draft.comments.any { it.id == commentId }) { "This comment is no longer in the draft" }
        return put(draft.copy(comments = draft.comments.map { if (it.id == commentId) it.copy(note = note, revision = it.revision + 1) else it }))
    }
    @Synchronized fun editFromEditor(id: String, original: ReviewComment, note: String): ReviewDraft {
        val draft = get(id)
        val current = draft.comments.firstOrNull { it.id == original.id }
        if (current != null) {
            check(current.revision == original.revision) { "This comment changed in another view; your editor text has been kept" }
            return edit(id, original.id, note)
        }
        validateNote(note)
        check(draft.sent.any { batch -> batch.comments.any { it.id == original.id && it.revision == original.revision } }) {
            "This comment was removed in another view; your editor text has been kept"
        }
        check(draft.comments.size < 50) { "This draft already contains 50 comments; your editor text has been kept" }
        val validation = if (draft.files[original.path]?.revision == original.fileRevision) original.validation else ReviewValidation.NEEDS_RECHECK
        return put(draft.copy(comments = draft.comments + original.copy(revision = original.revision + 1, note = note, validation = validation)))
    }
    @Synchronized fun remove(id: String, commentId: String): ReviewDraft = get(id).let {
        put(it.copy(comments = it.comments.filterNot { comment -> comment.id == commentId }))
    }
    @Synchronized fun overall(id: String, note: String): ReviewDraft {
        require(note.length <= 4000) { "The overall note can contain at most 4,000 characters" }
        return get(id).let { put(it.copy(overallNote = note, overallRevision = it.overallRevision + 1)) }
    }
    /** Explicit user confirmation only: refreshing never moves or accepts an anchor. */
    @Synchronized fun confirm(id: String, commentId: String): ReviewDraft {
        val draft = get(id)
        return put(draft.copy(comments = draft.comments.map { comment ->
            if (comment.id != commentId) comment else {
                val file = draft.files[comment.path] ?: error("Refresh and open this file first")
                check(file.selection(comment.side, comment.firstLine, comment.lastLine) == comment.selected) {
                    "The selected text has moved or disappeared; remove this comment and select a new anchor"
                }
                comment.copy(revision = comment.revision + 1, fileRevision = file.revision, validation = ReviewValidation.CURRENT)
            }
        }))
    }
    @Synchronized fun markStale(id: String, paths: Set<String>): ReviewDraft = get(id).let { draft ->
        put(draft.copy(comments = draft.comments.map { if (it.path in paths && it.validation != ReviewValidation.NEEDS_RECHECK)
            it.copy(revision = it.revision + 1, validation = ReviewValidation.NEEDS_RECHECK) else it }))
    }
    @Synchronized fun prepare(id: String, limit: Int = 128 * 1024): ReviewSubmission {
        val draft = get(id)
        check(draft.pending == null) { "This review already has a submission in progress" }
        check(draft.comments.isNotEmpty()) { "Add at least one review comment" }
        check(draft.comments.all { it.validation == ReviewValidation.CURRENT && draft.files[it.path]?.revision == it.fileRevision }) {
            "Some comments need rechecking against the refreshed diff"
        }
        val batch = batchId(draft)
        val payload = ReviewFormatter.format(draft, batch)
        require(payload.toByteArray(Charsets.UTF_8).size <= limit) { "Review exceeds the outgoing UTF-8 message limit; shorten notes or split the review" }
        val submission = ReviewSubmission(batch, id, draft.recipient, draft.base!!, draft.comments, draft.overallNote, payload, draft.overallRevision)
        put(draft.copy(pending = submission))
        return submission
    }
    @Synchronized fun preview(id: String): String = get(id).let { it.pending?.payload ?: ReviewFormatter.format(it, batchId(it)) }
    @Synchronized fun prepareIfCurrent(id: String, generation: Long): ReviewSubmission {
        check(get(id).generation == generation) { "The draft changed during validation; send again after reviewing it" }
        return prepare(id)
    }
    @Synchronized fun recordResult(id: String, result: ReviewDeliveryResult): ReviewDraft = get(id).let {
        if (it.pending != null) it else put(it.copy(lastResult = result,
            hasUnknownDelivery = it.hasUnknownDelivery || result.status == ReviewDeliveryStatus.UNKNOWN), enforceLimit = false)
    }
    private fun batchId(draft: ReviewDraft) = UUID.nameUUIDFromBytes("${draft.id}:${draft.generation}".toByteArray(Charsets.UTF_8)).toString()
    @Synchronized fun finish(submission: ReviewSubmission, result: ReviewDeliveryResult): ReviewDraft {
        val draft = get(submission.draftId)
        if (draft.pending?.id != submission.id) return draft
        val accepted = result.status in setOf(ReviewDeliveryStatus.ACCEPTED, ReviewDeliveryStatus.QUEUED)
        val included = submission.comments.associate { it.id to it.revision }
        // A late receipt can remove only the exact revisions frozen before dispatch.
        return put(draft.copy(pending = null, lastResult = result,
            hasUnknownDelivery = draft.hasUnknownDelivery || result.status == ReviewDeliveryStatus.UNKNOWN,
            comments = if (accepted) draft.comments.filterNot { included[it.id] == it.revision } else draft.comments,
            overallNote = if (accepted && draft.overallRevision == submission.overallRevision) "" else draft.overallNote,
            sent = if (accepted) draft.sent + submission else draft.sent,
            sentResults = if (accepted) draft.sentResults + (submission.id to result) else draft.sentResults), enforceLimit = false)
    }
    private fun validateNote(note: String) {
        require(note.isNotBlank()) { "Enter a review comment" }
        require(note.length <= 4000) { "A comment can contain at most 4,000 characters" }
    }
    private fun put(draft: ReviewDraft, enforceLimit: Boolean = true): ReviewDraft {
        val frozen = draft.copy(generation = draft.generation + 1,
            files = Collections.unmodifiableMap(LinkedHashMap(draft.files)),
            comments = Collections.unmodifiableList(ArrayList(draft.comments)),
            sent = Collections.unmodifiableList(ArrayList(draft.sent)),
            sentResults = Collections.unmodifiableMap(LinkedHashMap(draft.sentResults)))
        if (enforceLimit) {
            if (frozen.comments.isNotEmpty()) require(ReviewFormatter.format(frozen, "0".repeat(36)).toByteArray(Charsets.UTF_8).size <= 128 * 1024) {
                "Review would exceed 128 KiB UTF-8; shorten this note or submit the current batch first"
            }
            val bytes = (drafts.values.filterNot { it.id == draft.id } + frozen).sumOf(::memory)
            check(bytes <= memoryLimit) { "Review memory limit reached; explicitly discard a review before loading more content" }
        }
        drafts[draft.id] = frozen
        return frozen
    }
    private fun memory(draft: ReviewDraft): Long = draft.files.values.sumOf { it.memoryBytes } +
        draft.comments.sumOf { (it.selected.length.toLong() + it.note.length) * 2 } + draft.overallNote.length * 2L +
        draft.sent.sumOf(::submissionMemory) + (draft.pending?.let { submissionMemory(it) + 1024 } ?: 0) +
        draft.sentResults.values.sumOf { (it.receipt?.length ?: 0) * 2L + it.reason.length * 2L + 32 }
    private fun submissionMemory(submission: ReviewSubmission): Long = submission.payload.length * 2L +
        submission.comments.sumOf { (it.selected.length.toLong() + it.note.length) * 2 }
}

internal object ReviewFormatter {
    fun format(draft: ReviewDraft, batch: String): String = buildString {
        append("Review feedback for the current task\nCheckout: ").append(quote(draft.recipient.checkout.root.fileName.toString()))
        append("; base: ").append(draft.base).append("; review: ").append(batch)
        append("\nRecipient: ").append(draft.recipient.kind.displayName).append("; session: ").append(quote(draft.recipient.sessionId))
        append("\nThese comments refer to captured code. Check whether the code still matches before editing.\n")
        if (draft.overallNote.isNotBlank()) append("\nOverall note:\n").append(block(draft.overallNote))
        draft.comments.forEachIndexed { index, comment ->
            append("\n").append(index + 1).append(". ").append(quote(comment.path)).append(" — ")
            append(if (comment.side == ReviewSide.BASE) "base" else "reviewed on-disk current")
            append(" side, lines ").append(comment.firstLine).append('–').append(comment.lastLine)
            append("\nReviewed revision: ").append(comment.fileRevision)
            append("\nSelected text SHA-256: ").append(comment.selectedHash)
            append("\nCode excerpt:\n").append(block(comment.selected))
            append("Comment:\n").append(block(comment.note))
        }
        append("\nPlease address the comments and explain any you leave unresolved.\n")
    }
    private fun block(text: String): String {
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1))
        return "$fence\n$text\n$fence\n"
    }
    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
}
