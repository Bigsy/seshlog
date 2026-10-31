package com.hedworth.seshlog.review

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Checkout
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class ReviewDraftStoreTest {
    private val checkout = Checkout(Paths.get("/fictional/harbor"), Paths.get("/fictional/harbor/.git"),
        Paths.get("/fictional/harbor/.git"), Checkout.Kind.MAIN, "topic", "abc", directoryKey = "synthetic-inode")
    private val recipient = ReviewRecipient(AgentKind.CODEX, "thread", checkout)
    private val file = ReviewFileSnapshot("src/船\n\".kt", "a".repeat(40), "old\nkeep\n", "new\nkeep\n")
    private fun seeded(store: ReviewDraftStore): ReviewDraft = store.open(recipient).also { store.load(it.id, file) }
        .let { store.add(it.id, file.path, ReviewSide.CURRENT, 1, 2, "Keep 船 visible\n```\nuser note") }
    private fun rejects(work: () -> Unit) { try { work(); fail("Expected limit/validation rejection") } catch (_: IllegalArgumentException) {
    } catch (_: IllegalStateException) {} }

    @Test fun `draft identity uses provider exact session and checkout independently of view`() {
        val store = ReviewDraftStore()
        val first = store.open(recipient)
        assertEquals(first.id, store.open(recipient.copy(checkout = checkout.copy(branch = "renamed"))).id)
        assertNotEquals(first.id, store.open(recipient.copy(kind = AgentKind.CLAUDE_CODE)).id)
        assertNotEquals(first.id, store.open(recipient.copy(checkout = checkout.copy(directoryKey = "replaced"))).id)
    }
    @Test fun `both sides excerpts and robust formatting preserve filenames Unicode and delimiters`() {
        val store = ReviewDraftStore()
        val draft = seeded(store)
        store.add(draft.id, file.path, ReviewSide.BASE, 1, 1, "Preserve old behavior")
        store.overall(draft.id, "Overall\n````\n船")
        val value = store.get(draft.id)
        val text = ReviewFormatter.format(value, "batch")
        assertEquals(text, ReviewFormatter.format(value, "batch"))
        assertTrue(text.contains("src/船\\n\\\".kt"))
        assertTrue(text.contains("current side, lines 1–2"))
        assertTrue(text.contains("base side, lines 1–1"))
        assertTrue(text.contains("`````\nOverall\n````\n船\n`````"))
        assertTrue(text.contains("new\nkeep"))
        assertTrue(text.contains("old"))
    }
    @Test fun `added deleted and nonexistent lines never invent an anchor`() {
        val added = file.copy(before = "", current = "added\n")
        val deleted = file.copy(before = "deleted\n", current = "")
        assertEquals("added", added.selection(ReviewSide.CURRENT, 1, 1))
        assertEquals("deleted", deleted.selection(ReviewSide.BASE, 1, 1))
        rejects { added.selection(ReviewSide.BASE, 1, 1) }
        rejects { deleted.selection(ReviewSide.CURRENT, 1, 1) }
        rejects { added.selection(ReviewSide.CURRENT, 2, 2) }
        rejects { added.selection(ReviewSide.CURRENT, 0, 1) }
        rejects { added.copy(current = "a\n".repeat(21)).selection(ReviewSide.CURRENT, 1, 21) }
        rejects { added.copy(current = "x".repeat(4001)).selection(ReviewSide.CURRENT, 1, 1) }
    }
    @Test fun `limits reject without dropping notes or setting an in flight batch`() {
        val store = ReviewDraftStore()
        val draft = seeded(store)
        val original = store.get(draft.id)
        rejects { store.edit(draft.id, original.comments.single().id, "x".repeat(4001)) }
        rejects { store.prepare(draft.id, 1) }
        assertEquals(original, store.get(draft.id))
        repeat(49) { store.add(draft.id, file.path, ReviewSide.BASE, 1, 1, "Note $it") }
        rejects { store.add(draft.id, file.path, ReviewSide.BASE, 1, 1, "More") }
        assertEquals(50, store.get(draft.id).comments.size)
        val small = ReviewDraftStore(5)
        val empty = small.open(recipient)
        rejects { small.load(empty.id, file) }
        assertTrue(small.get(empty.id).files.isEmpty())
        repeat(9) { store.open(recipient.copy(sessionId = "other-$it")) }
        rejects { store.open(recipient.copy(sessionId = "eleventh")) }
        assertEquals(10, store.all().size)
    }
    @Test fun `payload limit counts UTF8 and empty reviews cannot submit`() {
        val store = ReviewDraftStore()
        val draft = seeded(store)
        val value = store.get(draft.id)
        val text = ReviewFormatter.format(value, "0".repeat(36))
        val bytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > text.length)
        rejects { store.prepare(draft.id, bytes - 1) }
        assertEquals(bytes, store.prepare(draft.id, bytes).payload.toByteArray(Charsets.UTF_8).size)
        val empty = store.open(recipient.copy(sessionId = "empty"))
        rejects { store.prepare(empty.id) }
    }
    @Test fun `duplicate clicks lock dispatch and late success archives only frozen revisions`() {
        val store = ReviewDraftStore()
        val draft = seeded(store)
        store.overall(draft.id, "First overall")
        val submission = store.prepare(draft.id)
        rejects { store.prepare(draft.id) }
        rejects { store.discard(draft.id) }
        store.edit(draft.id, submission.comments.single().id, "Edited while sending")
        store.add(draft.id, file.path, ReviewSide.BASE, 2, 2, "New while sending")
        store.overall(draft.id, "Changed")
        store.overall(draft.id, "First overall")
        val result = store.finish(submission, ReviewDeliveryResult(ReviewDeliveryStatus.QUEUED, "queued", "receipt"))
        assertEquals(2, result.comments.size)
        assertEquals("First overall", result.overallNote)
        assertEquals(listOf(submission), result.sent)
        assertFalse(submission.payload.contains("Edited while sending"))
        assertEquals(result, store.finish(submission, ReviewDeliveryResult(ReviewDeliveryStatus.ACCEPTED, "late duplicate")))
    }
    @Test fun `unchanged accepted comments clear but failures and uncertain delivery retain notes`() {
        for (status in ReviewDeliveryStatus.entries) {
            val store = ReviewDraftStore()
            val draft = seeded(store)
            val batch = store.prepare(draft.id)
            val result = store.finish(batch, ReviewDeliveryResult(status, "synthetic"))
            assertEquals(if (status in setOf(ReviewDeliveryStatus.ACCEPTED, ReviewDeliveryStatus.QUEUED)) 0 else 1, result.comments.size)
            assertNull(result.pending)
            if (status == ReviewDeliveryStatus.UNKNOWN) {
                val retry = store.prepare(draft.id)
                assertNotEquals(batch.id, retry.id) // No claimed server idempotency.
            }
        }
    }
    @Test fun `refresh invalidates base side too and requires explicit unchanged text confirmation`() {
        val store = ReviewDraftStore()
        val draft = seeded(store)
        store.add(draft.id, file.path, ReviewSide.BASE, 1, 1, "Old behavior")
        val refreshed = store.load(draft.id, file.copy(current = "new\nkeep\nextra\n"))
        assertTrue(refreshed.comments.all { it.validation == ReviewValidation.NEEDS_RECHECK })
        rejects { store.prepare(draft.id) }
        refreshed.comments.forEach { store.confirm(draft.id, it.id) }
        assertTrue(store.get(draft.id).comments.all { it.validation == ReviewValidation.CURRENT })
        store.load(draft.id, file.copy(current = "replacement\n"))
        rejects { store.confirm(draft.id, refreshed.comments[0].id) }
        rejects { store.load(draft.id, file.copy(base = "b".repeat(40))) }
    }
    @Test fun `failed retries cannot erase an earlier unknown delivery`() {
        val store = ReviewDraftStore()
        val draft = seeded(store)
        val original = store.prepare(draft.id)
        store.finish(original, ReviewDeliveryResult(ReviewDeliveryStatus.UNKNOWN, "connection_lost_after_dispatch"))
        store.recordResult(draft.id, ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, "target_unavailable"))
        assertEquals(ReviewDeliveryStatus.UNAVAILABLE, store.get(draft.id).lastResult?.status)
        assertTrue(store.get(draft.id).hasUnknownDelivery)
        for (status in listOf(ReviewDeliveryStatus.UNAVAILABLE, ReviewDeliveryStatus.REJECTED, ReviewDeliveryStatus.QUEUED)) {
            val retry = store.prepare(draft.id)
            store.finish(retry, ReviewDeliveryResult(status, "synthetic retry"))
            assertEquals(status, store.get(draft.id).lastResult?.status)
            assertTrue(store.get(draft.id).hasUnknownDelivery)
        }
    }
}
