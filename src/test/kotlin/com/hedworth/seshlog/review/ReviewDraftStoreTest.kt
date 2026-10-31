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
    @Test fun `editor text stays bounded after a draft is discarded and closing releases exactly once`() {
        val store = ReviewDraftStore(100)
        val draft = store.open(recipient)
        val snapshot = ReviewFileSnapshot("small", "base", "x".repeat(10), "y".repeat(10))
        store.load(draft.id, snapshot) // 40 bytes, shared by the draft and native documents.
        val lease = store.retainDiff(draft.id, snapshot.path, snapshot.revision)
        val second = store.retainDiff(draft.id, snapshot.path, snapshot.revision)
        store.discard(draft.id)
        val next = store.open(recipient)
        val bigger = snapshot.copy(current = "z".repeat(30)) // 80 plus the retained 40 exceeds 100.
        rejects { store.load(next.id, bigger) }
        lease.close(); lease.close()
        rejects { store.load(next.id, bigger) } // Another native tab still owns the old text.
        second.close()
        store.load(next.id, bigger)
        store.retainDiff(next.id, bigger.path, bigger.revision).close()
        assertEquals(bigger, store.get(next.id).files[bigger.path])
    }
    @Test fun `sharing normalized display text preserves raw revision hashes and immutable anchors`() {
        val file = ReviewFileSnapshot("lines", "base", "old\r\n", "new\rkeep\r\n")
        assertEquals(reviewHash("old\r\n"), file.beforeHash)
        assertEquals(reviewHash("new\rkeep\r\n"), file.currentHash)
        assertEquals("old\n", file.before)
        assertEquals("new\nkeep\n", file.current)
        val revision = file.revision
        rejects { file.shareImmutableText("changed", file.current) }
        file.shareImmutableText(file.before, file.current)
        assertEquals(revision, file.revision)
        assertEquals(revision, file.copy().revision)
        assertEquals("new\nkeep", file.selection(ReviewSide.CURRENT, 1, 2))
    }
    @Test fun `retained revisions remain bounded while unchanged refreshes reuse their text`() {
        val store = ReviewDraftStore(64)
        val draft = store.open(recipient)
        val first = ReviewFileSnapshot("a", "base", "", "a".repeat(10))
        store.load(draft.id, first)
        val leases = mutableListOf(store.retainDiff(draft.id, first.path, first.revision))
        assertSame(first, store.load(draft.id, first.copy()).files[first.path])
        for (text in listOf("b", "c")) {
            val next = first.copy(current = text.repeat(10))
            store.load(draft.id, next)
            leases += store.retainDiff(draft.id, next.path, next.revision)
        }
        rejects { store.load(draft.id, first.copy(current = "d".repeat(10))) }
        leases.first().close()
        store.load(draft.id, first.copy(current = "d".repeat(10)))
        leases.forEach(AutoCloseable::close)
    }
    @Test fun `maximum file sides leave room for comments submission and late edits`() {
        val sideBytes = 8 * 1024 * 1024
        val snapshot = ReviewFileSnapshot("maximum.txt", "base",
            "old\n" + "a".repeat(sideBytes - 4), "new\n" + "b".repeat(sideBytes - 4))
        assertEquals(sideBytes, snapshot.before.toByteArray(Charsets.UTF_8).size)
        assertEquals(sideBytes, snapshot.current.toByteArray(Charsets.UTF_8).size)
        assertEquals(32L * 1024 * 1024, snapshot.memoryBytes)
        val store = ReviewDraftStore()
        val draft = store.open(recipient)
        store.load(draft.id, snapshot)
        val lease = store.retainDiff(draft.id, snapshot.path, snapshot.revision)
        try {
            rejects { store.load(draft.id, snapshot.copy(path = "extra.txt", before = "", current = "x")) }
            store.add(draft.id, snapshot.path, ReviewSide.CURRENT, 1, 1, "Check this boundary")
            store.add(draft.id, snapshot.path, ReviewSide.BASE, 1, 1, "x".repeat(4000))
            store.overall(draft.id, "o".repeat(4000))
            val batch = store.prepare(draft.id)
            store.edit(draft.id, batch.comments.first().id, "Edited while sending")
            store.overall(draft.id, "New overall note")
            val receipt = ReviewDeliveryResult(ReviewDeliveryStatus.QUEUED, "queued_by_agent", "r".repeat(256))
            val finished = store.finish(batch, receipt)
            assertEquals(listOf(batch), finished.sent)
            assertEquals(receipt, finished.sentResults[batch.id])
            assertEquals("Edited while sending", finished.comments.single().note)
            assertEquals("New overall note", finished.overallNote)
            assertNull(finished.pending)
            val next = store.prepare(draft.id)
            assertTrue(next.payload.contains("Edited while sending"))
            store.finish(next, ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, "synthetic refusal"))
            assertEquals("Edited while sending", store.get(draft.id).comments.single().note)
        } finally {
            lease.close()
        }
    }
    @Test fun `review state headroom is bounded across drafts including archived batches`() {
        val store = ReviewDraftStore(128)
        val reviews = (0..1).map { index ->
            val draft = store.open(recipient.copy(sessionId = "review-$index"))
            store.load(draft.id, file)
            repeat(20) { store.add(draft.id, file.path, ReviewSide.CURRENT, 1, 1, "x".repeat(4000)) }
            draft.id
        }
        var accepted = 0
        var limited: ReviewDraft? = null
        while (accepted < 12 && limited == null) {
            val draft = store.get(reviews[accepted % reviews.size])
            val batch = try {
                store.prepare(draft.id)
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("notes and sent batches limit"))
                assertSame(draft, store.get(draft.id)) // No lost notes or phantom in-flight batch.
                limited = draft
                continue
            }
            // Keep newer revisions unsent while accumulating genuine archived batches.
            draft.comments.forEach { store.edit(draft.id, it.id, "$accepted ".padEnd(4000, 'x')) }
            store.finish(batch, ReviewDeliveryResult(ReviewDeliveryStatus.QUEUED, "queued_by_agent", "r".repeat(256)))
            accepted++
        }
        assertNotNull("Archived state must stay bounded", limited)
        assertTrue("The 2 MiB state allowance is shared across drafts", accepted in 1..5)
        reviews.forEach {
            assertEquals(20, store.get(it).comments.size)
            assertTrue(store.get(it).sent.isNotEmpty())
        }
        val blocked = limited!!
        val other = reviews.single { it != blocked.id }
        store.discard(other) // Free state explicitly; the blocked draft and its notes are retained.
        val retry = store.prepare(blocked.id)
        assertEquals(blocked.comments, retry.comments)
        val finished = store.finish(retry, ReviewDeliveryResult(ReviewDeliveryStatus.QUEUED, "queued_by_agent", "last"))
        assertTrue(finished.comments.isEmpty())
        assertEquals("last", finished.sentResults.getValue(retry.id).receipt)
    }
}
