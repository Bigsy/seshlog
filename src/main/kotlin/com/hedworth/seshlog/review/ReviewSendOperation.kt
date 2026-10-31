package com.hedworth.seshlog.review

/** Pure submission orchestration; injected effects allow lifecycle/race regressions without IDE mocks. */
internal class ReviewSendOperation(
    private val store: ReviewDraftStore,
    private val snapshot: ReviewDraft,
    private val validate: (ReviewDraft) -> ReviewSnapshots.Validation,
    private val verifyTarget: () -> Boolean,
    private val sender: (ReviewSubmission, () -> Boolean) -> ReviewDeliveryResult,
    private val cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    private val prepared: (ReviewSubmission) -> Unit = {},
    private val requireFreshScan: () -> Unit = {},
) {
    private var dispatched = false
    private var blocked: String? = null
    private var submission: ReviewSubmission? = null
    private fun check(): Boolean {
        if (cancelled()) { blocked = "cancelled_before_dispatch"; return false }
        try { requireFreshScan() } catch (_: Exception) {
            blocked = if (cancelled()) "cancelled_before_dispatch" else "session_refresh_unavailable"
            return false
        }
        val validation = validate(snapshot)
        if (!validation.valid) {
            blocked = if (validation.unsaved.isNotEmpty()) "unsaved_buffers" else "files_changed"
            val draft = store.get(snapshot.id)
            if (draft.generation == snapshot.generation || (submission != null && draft.pending?.id == submission?.id))
                store.markStale(snapshot.id, validation.changed + validation.unsaved)
            return false
        }
        if (!verifyTarget()) { blocked = "recipient_changed"; return false }
        if (cancelled()) { blocked = "cancelled_before_dispatch"; return false }
        return true
    }
    fun run(): ReviewDeliveryResult {
        val result = try {
            if (!check()) ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, blocked!!)
            else {
                val batch = store.prepareIfCurrent(snapshot.id, snapshot.generation)
                submission = batch
                prepared(batch)
                val sent = sender(batch) { check().also { if (it) dispatched = true } }
                if (sent.status == ReviewDeliveryStatus.UNAVAILABLE && blocked != null) sent.copy(reason = blocked!!) else sent
            }
        } catch (_: Exception) {
            if (dispatched) ReviewDeliveryResult(ReviewDeliveryStatus.UNKNOWN, "dispatch_interrupted")
            else ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, if (cancelled()) "cancelled_before_dispatch" else "draft_or_validation_changed")
        }
        // Apply a genuine addressed receipt even if the tab/view changes or closes after dispatch.
        runCatching {
            submission?.let { store.finish(it, result) } ?: store.recordResult(snapshot.id, result)
        }
        return result
    }
}
