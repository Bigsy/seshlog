package com.hedworth.seshlog.review

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.settings.SeshlogSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
internal class ReviewDeliveryService : Disposable {
    private class Job {
        @Volatile var cancelled = false
        lateinit var thread: Thread
        var deadline: ScheduledFuture<*>? = null
        fun cancel() { cancelled = true; thread.interrupt() }
    }
    private val jobs = HashMap<String, Job>()
    private val drafts get() = ReviewDraftService.getInstance()
    @Synchronized fun isSending(id: String) = id in jobs
    @Synchronized fun cancel(id: String) { jobs[id]?.cancel() }
    /** EDT entry; capture UI evidence only, then do every read/hash/protocol operation on a worker. */
    @Synchronized fun start(id: String, allowUnknownRetry: Boolean = false): Boolean {
        if (id in jobs) return false
        val snapshot = drafts.store.get(id)
        if (snapshot.hasUnknownDelivery && !allowUnknownRetry) return false
        val home = SeshlogSettings.getInstance().resolvedCodexDataDir()
        val target = ReviewTargets.capture(snapshot.recipient, deliveryHome = home)
        if (target == null) {
            drafts.store.recordResult(id, ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, "target_unavailable"))
            drafts.changed(); return false
        }
        val app = ApplicationManager.getApplication()
        val job = Job()
        jobs[id] = job
        job.thread = Thread.ofVirtual().name("seshlog-review-send").unstarted {
            val trace = SessionDiagnostics.trace("review", "session" to DiagnosticRef.of(snapshot.recipient.sessionId),
                "draft" to DiagnosticRef.of(id), "project" to DiagnosticRef.of(target.project.basePath), "tab" to target.tabRef)
            try {
                val operation = ReviewSendOperation(drafts.store, snapshot,
                    requireFreshScan = { SessionIndex.getInstance().requireFreshScan(snapshot.recipient.kind) },
                    validate = { draft ->
                        val validated = ReviewSnapshots.validate(draft, emptySet())
                        var unsaved = emptySet<String>()
                        if (!app.isDisposed) app.invokeAndWait({ unsaved = ReviewUnsavedBuffers.capture(draft.recipient) }, ModalityState.any())
                        validated.copy(unsaved = draft.comments.mapTo(HashSet()) { it.path }.intersect(unsaved))
                    },
                    verifyTarget = {
                        val sessions = SessionIndex.getInstance().sessions.filter { it.id == snapshot.recipient.sessionId }
                        val verified = sessions.size == 1 && ReviewSnapshots.verifyRecipient(snapshot.recipient, sessions.single()) && target.proof.inspect()
                        var current = false
                        if (verified && !app.isDisposed) app.invokeAndWait({
                            current = !job.cancelled && !target.project.isDisposed && target.proof.current()
                        }, ModalityState.any())
                        current
                    },
                    sender = { batch, before -> CodexReviewTransport().submit(batch, home, before) },
                    cancelled = { job.cancelled || Thread.currentThread().isInterrupted },
                    prepared = { batch ->
                        trace.event("review_submission_started", *ReviewDeliveryDiagnostics.fields(batch).map { it.key to it.value }.toTypedArray())
                        drafts.changed()
                    })
                val result = operation.run()
                trace.event("review_delivery_result", "status" to result.status, "reason" to ReviewDeliveryDiagnostics.reason(result.reason),
                    "comments" to snapshot.comments.size)
            } catch (_: Exception) {
                runCatching { drafts.store.recordResult(id, ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, "validation_unavailable")) }
                trace.event("review_delivery_result", "status" to ReviewDeliveryStatus.UNAVAILABLE, "reason" to "validation_unavailable")
            } finally {
                job.deadline?.cancel(false)
                synchronized(this) { if (jobs[id] === job) jobs.remove(id) }
                drafts.changed()
            }
        }
        job.deadline = AppExecutorUtil.getAppScheduledExecutorService().schedule({ job.cancel() }, 45, TimeUnit.SECONDS)
        job.thread.start()
        drafts.changed()
        return true
    }
    @Synchronized override fun dispose() { jobs.values.forEach { it.cancel() } }
    companion object {
        fun getInstance(): ReviewDeliveryService = ApplicationManager.getApplication().getService(ReviewDeliveryService::class.java)
    }
}

internal object ReviewDeliveryDiagnostics {
    fun fields(batch: ReviewSubmission): Map<String, Any?> = mapOf("batch" to DiagnosticRef.of(batch.id),
        "kind" to batch.recipient.kind, "comments" to batch.comments.size, "bytes" to batch.payload.toByteArray(Charsets.UTF_8).size)
    fun reason(value: String): String = if (value in setOf("provider_unsupported", "payload_limit", "socket_not_private", "protocol_unavailable",
        "codex_version_unsupported", "server_home_mismatch", "thread_unavailable", "thread_mismatch", "thread_not_loaded", "checkout_changed",
        "recipient_or_files_changed", "queue_method_unsupported", "agent_refused", "server_error_after_dispatch", "receipt_mismatch", "queued_by_agent",
        "connection_lost_after_dispatch", "local_transport_unavailable", "cancelled_before_dispatch", "unsaved_buffers", "files_changed", "recipient_changed",
        "dispatch_interrupted", "draft_or_validation_changed", "target_unavailable", "validation_unavailable", "invalid_receipt", "endpoint_unverified",
        "session_refresh_unavailable")) value else "other"
}
