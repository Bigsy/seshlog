package com.hedworth.seshlog.review

import com.google.gson.JsonParser
import com.hedworth.seshlog.review.CodexQueueProtocolProbe.Outcome
import org.junit.Assert.*
import org.junit.Test

class CodexQueueProtocolProbeTest {
    private val thread = "11111111-1111-4111-8111-111111111111"
    private val batch = "22222222-2222-4222-8222-222222222222"
    private val note = "Fictional review: src/Harbor.kt, current side, line 2\nKeep ‘船’ visible.\n`data` \"only\" ${'$'}(not-a-command)\n"

    @Test fun `addressed submission and busy holding produce a queued receipt not evidence of consumption`() {
        for (policy in listOf(SyntheticReviewQueue.Policy.QUEUE, SyntheticReviewQueue.Policy.HOLD)) {
            val receiver = SyntheticReviewQueue(thread).apply { this.policy = policy }
            val result = CodexQueueProtocolProbe.submit(thread, batch, note, receiver::exchange)
            assertEquals(Outcome.QUEUED, result.outcome)
            assertEquals("synthetic-receipt-1", result.receipt)
            assertEquals(1, receiver.submissions.size)
            assertEquals(thread, receiver.submissions.single()["threadId"].asString)
            assertEquals(batch, receiver.submissions.single()["clientUserMessageId"].asString)
            assertEquals(note, receiver.submissions.single()["input"].asJsonArray.single().asJsonObject["text"].asString)
        }
    }

    @Test fun `receiver refusal closed session and wrong target never enqueue`() {
        for (policy in listOf(SyntheticReviewQueue.Policy.REFUSE, SyntheticReviewQueue.Policy.CLOSE)) {
            val receiver = SyntheticReviewQueue(thread).apply { this.policy = policy }
            assertEquals(Outcome.REJECTED, CodexQueueProtocolProbe.submit(thread, batch, note, receiver::exchange).outcome)
            assertTrue(receiver.submissions.isEmpty())
        }
        val wrongTarget = SyntheticReviewQueue("33333333-3333-4333-8333-333333333333")
        assertEquals(Outcome.REJECTED, CodexQueueProtocolProbe.submit(thread, batch, note, wrongTarget::exchange).outcome)
        assertTrue(wrongTarget.submissions.isEmpty())
    }

    @Test fun `lost receipt preserves uncertainty and never retries automatically`() {
        val receiver = SyntheticReviewQueue(thread).apply { policy = SyntheticReviewQueue.Policy.LOSE_RECEIPT }
        val result = CodexQueueProtocolProbe.submit(thread, batch, note, receiver::exchange)
        assertEquals(Outcome.UNKNOWN, result.outcome)
        assertNull(result.receipt)
        assertEquals(1, receiver.submissions.size)
        // The schema's clientUserMessageId is not proof of server idempotency.
        receiver.policy = SyntheticReviewQueue.Policy.QUEUE
        CodexQueueProtocolProbe.submit(thread, batch, note, receiver::exchange)
        assertEquals(2, receiver.submissions.size)
    }

    @Test fun `synthetic malformed wrong batch and incomplete receipts stay unknown`() {
        val fixture = javaClass.getResource("/fixtures/diff-review/codex-queue-receipts.json")!!.readText()
        for (case in JsonParser.parseString(fixture).asJsonArray) {
            val item = case.asJsonObject
            var calls = 0
            val result = CodexQueueProtocolProbe.submit(thread, batch, "synthetic note") {
                calls++
                item["wire"].asString
            }
            assertEquals(item["name"].asString, Outcome.valueOf(item["outcome"].asString), result.outcome)
            assertEquals(1, calls)
        }
    }

    @Test fun `limits measure UTF8 before dispatch and names are never used as recipients`() {
        var calls = 0
        val receiver = SyntheticReviewQueue(thread)
        val exchange: (String) -> String? = { calls++; receiver.exchange(it) }
        for ((target, text) in listOf("Fix ranking" to note, thread to "", thread to "  ",
            thread to "船".repeat(CodexQueueProtocolProbe.MESSAGE_LIMIT / 3 + 1))) {
            assertEquals(Outcome.UNAVAILABLE, CodexQueueProtocolProbe.submit(target, batch, text, exchange).outcome)
        }
        assertEquals(0, calls)
        assertEquals(Outcome.QUEUED, CodexQueueProtocolProbe.submit(thread, batch,
            "x".repeat(CodexQueueProtocolProbe.MESSAGE_LIMIT), exchange).outcome)
        assertEquals(1, calls)
    }

    @Test fun `transport exceptions and oversized receipts never imply rejection or acceptance`() {
        assertEquals(Outcome.UNKNOWN, CodexQueueProtocolProbe.submit(thread, batch, note) { throw java.io.IOException() }.outcome)
        assertEquals(Outcome.UNKNOWN, CodexQueueProtocolProbe.submit(thread, batch, note) {
            "x".repeat(2 * CodexQueueProtocolProbe.MESSAGE_LIMIT + 1)
        }.outcome)
        assertEquals(Outcome.UNKNOWN, CodexQueueProtocolProbe.submit(thread, batch, note) { "" }.outcome)
    }
}
