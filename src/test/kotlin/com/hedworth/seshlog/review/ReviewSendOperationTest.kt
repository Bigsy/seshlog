package com.hedworth.seshlog.review

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Checkout
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class ReviewSendOperationTest {
    private val checkout = Checkout(Path.of("/synthetic/harbor"), Path.of("/synthetic/harbor/.git"), Path.of("/synthetic/harbor/.git"),
        Checkout.Kind.MAIN, "topic", "a".repeat(40), directoryKey = "synthetic")
    private fun seeded(): Pair<ReviewDraftStore, ReviewDraft> {
        val store = ReviewDraftStore()
        val draft = store.open(ReviewRecipient(AgentKind.CODEX, "synthetic-thread", checkout))
        store.load(draft.id, ReviewFileSnapshot("a.kt", "a".repeat(40), "before\n", "current\n"))
        store.load(draft.id, ReviewFileSnapshot("b.kt", "a".repeat(40), "old\n", "new\n"))
        store.add(draft.id, "a.kt", ReviewSide.BASE, 1, 1, "SYNTHETIC PRIVATE NOTE")
        store.add(draft.id, "b.kt", ReviewSide.CURRENT, 1, 1, "Other comment")
        return store to store.get(draft.id)
    }
    private fun valid(draft: ReviewDraft) = ReviewSnapshots.Validation(emptySet(), emptySet())

    @Test fun `a failed fresh scan blocks cached recipient evidence initially and before dispatch`() {
        for (failedCheck in listOf(1, 2)) {
            val (store, snapshot) = seeded()
            var scans = 0
            var sends = 0
            val result = ReviewSendOperation(store, snapshot, ::valid, { true }, { _, before ->
                if (before()) sends++
                ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, "recipient_or_files_changed")
            }, requireFreshScan = { if (++scans == failedCheck) error("Synthetic failed or unhealthy refresh") }).run()
            assertEquals(0, sends)
            assertEquals(ReviewDeliveryStatus.UNAVAILABLE, result.status)
            assertEquals(2, store.get(snapshot.id).comments.size)
            assertNull(store.get(snapshot.id).pending)
        }
    }

    @Test fun `saving an editor opened before a receipt keeps the new note without modifying the sent batch`() {
        val (store, snapshot) = seeded()
        val original = snapshot.comments.first()
        val batch = store.prepare(snapshot.id)
        store.finish(batch, ReviewDeliveryResult(ReviewDeliveryStatus.QUEUED, "queued_by_agent", "synthetic-receipt"))
        store.editFromEditor(snapshot.id, original, "Typed while waiting for receipt")
        val draft = store.get(snapshot.id)
        assertEquals("Typed while waiting for receipt", draft.comments.single().note)
        assertTrue(draft.comments.single().revision > original.revision)
        assertFalse(draft.sent.single().payload.contains("Typed while waiting for receipt"))
        assertEquals("synthetic-receipt", draft.sentResults.getValue(batch.id).receipt)
    }

    @Test fun `one frozen batch across files survives newer edits and a tab or view closing after receipt`() {
        val (store, snapshot) = seeded()
        var current = true
        var sends = 0
        val result = ReviewSendOperation(store, snapshot, ::valid, { current }, sender = { batch, before ->
            assertTrue(before()); sends++
            assertTrue(batch.payload.contains("a.kt")); assertTrue(batch.payload.contains("b.kt"))
            store.edit(snapshot.id, batch.comments[0].id, "Edited during send")
            store.add(snapshot.id, "b.kt", ReviewSide.CURRENT, 1, 1, "Added during send")
            current = false // A genuine addressed receipt remains valid after tab/window disposal.
            ReviewDeliveryResult(ReviewDeliveryStatus.QUEUED, "queued_by_agent", "synthetic-receipt")
        }).run()
        assertEquals(ReviewDeliveryStatus.QUEUED, result.status)
        assertEquals(1, sends)
        val draft = store.get(snapshot.id)
        assertEquals(listOf("Edited during send", "Added during send"), draft.comments.map { it.note })
        assertEquals(1, draft.sent.size)
        assertEquals("synthetic-receipt", draft.sentResults.getValue(draft.sent.single().id).receipt)
        assertFalse(draft.sent.single().payload.contains("Edited during send"))
    }
    @Test fun `changed or unsaved files block both sides without consuming notes`() {
        for (unsaved in listOf(false, true)) {
            val (store, snapshot) = seeded()
            var sends = 0
            val result = ReviewSendOperation(store, snapshot, { ReviewSnapshots.Validation(if (unsaved) emptySet() else setOf("a.kt"), if (unsaved) setOf("b.kt") else emptySet()) },
                { true }, { _, _ -> sends++; error("Must not dispatch") }).run()
            assertEquals(ReviewDeliveryStatus.UNAVAILABLE, result.status)
            assertEquals(0, sends)
            assertEquals(2, store.get(snapshot.id).comments.size)
            assertEquals(1, store.get(snapshot.id).comments.count { it.validation == ReviewValidation.NEEDS_RECHECK })
        }
    }
    @Test fun `edits during initial validation and recipient revocation before dispatch are rejected`() {
        val (store, snapshot) = seeded()
        var sends = 0
        val changed = ReviewSendOperation(store, snapshot, {
            store.edit(snapshot.id, snapshot.comments[0].id, "New note while validating"); valid(it)
        }, { true }, { _, _ -> sends++; error("Must not dispatch") }).run()
        assertEquals(ReviewDeliveryStatus.UNAVAILABLE, changed.status)
        assertEquals(0, sends)
        val fresh = store.get(snapshot.id)
        var checks = 0
        var dispatched = false
        val revoked = ReviewSendOperation(store, fresh, ::valid, { ++checks == 1 }, { _, before ->
            if (before()) dispatched = true
            ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, "recipient_or_files_changed")
        }).run()
        assertEquals("recipient_changed", revoked.reason)
        assertFalse(dispatched)
        assertEquals(2, store.get(snapshot.id).comments.size)
    }
    @Test fun `refresh and duplicate submits while sending cannot replace snapshots or double dispatch`() {
        val (store, snapshot) = seeded()
        var sends = 0
        val result = ReviewSendOperation(store, snapshot, ::valid, { true }, { _, before ->
            assertTrue(before()); sends++
            assertThrows(IllegalStateException::class.java) { store.prepare(snapshot.id) }
            assertThrows(IllegalStateException::class.java) { store.load(snapshot.id, snapshot.files.getValue("a.kt").copy(current = "changed")) }
            assertEquals(snapshot.files, store.get(snapshot.id).files)
            ReviewDeliveryResult(ReviewDeliveryStatus.REJECTED, "agent_refused")
        }).run()
        assertEquals(1, sends)
        assertEquals(ReviewDeliveryStatus.REJECTED, result.status)
        assertEquals(2, store.get(snapshot.id).comments.size)
    }
    @Test fun `local cancellation before dispatch is unavailable and interruption after dispatch is unknown`() {
        for (after in listOf(false, true)) {
            val (store, snapshot) = seeded()
            var cancelled = false
            val result = ReviewSendOperation(store, snapshot, ::valid, { true }, { _, before ->
                if (after) assertTrue(before())
                cancelled = true
                if (after) throw InterruptedException()
                assertFalse(before())
                ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, "recipient_or_files_changed")
            }, cancelled = { cancelled }).run()
            assertEquals(if (after) ReviewDeliveryStatus.UNKNOWN else ReviewDeliveryStatus.UNAVAILABLE, result.status)
            assertEquals(2, store.get(snapshot.id).comments.size)
            assertTrue(store.get(snapshot.id).sent.isEmpty())
            assertNull(store.get(snapshot.id).pending)
        }
    }
    @Test fun `unknown outcome retains notes and diagnostic fields exclude text paths and receipts`() {
        val (store, snapshot) = seeded()
        var sends = 0
        var batch: ReviewSubmission? = null
        ReviewSendOperation(store, snapshot, ::valid, { true }, { sent, before ->
            assertTrue(before()); sends++; batch = sent
            ReviewDeliveryResult(ReviewDeliveryStatus.UNKNOWN, "connection_lost_after_dispatch", "PRIVATE RECEIPT")
        }).run()
        assertEquals(1, sends)
        assertEquals(2, store.get(snapshot.id).comments.size)
        val fields = ReviewDeliveryDiagnostics.fields(batch!!).toString()
        for (secret in listOf("SYNTHETIC PRIVATE NOTE", "synthetic-thread", "harbor", "a.kt", "current", "PRIVATE RECEIPT", batch!!.id)) assertFalse(fields.contains(secret))
        assertEquals("other", ReviewDeliveryDiagnostics.reason("SYNTHETIC PRIVATE NOTE"))
    }
}
