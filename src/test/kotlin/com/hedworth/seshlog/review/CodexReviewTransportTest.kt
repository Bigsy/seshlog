package com.hedworth.seshlog.review

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.worktree.GitRead
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CodexReviewTransportTest {
    private fun scenario(work: (SyntheticReviewServer, ReviewSubmission) -> Unit) {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory(Path.of("/tmp"), "sl-rq-").toRealPath()
        try {
            val checkout = Files.createDirectory(root.resolve("repo"))
            GitRead.run(checkout, listOf("init", "-q"))
            val home = Files.createDirectory(root.resolve("home"))
            val recipient = ReviewRecipient(AgentKind.CODEX, "11111111-1111-4111-8111-111111111111", GitRepository.checkout(checkout)!!)
            val store = ReviewDraftStore()
            val draft = store.open(recipient)
            store.load(draft.id, ReviewFileSnapshot("file.kt", "a".repeat(40), "old\n", "new\n"))
            store.add(draft.id, "file.kt", ReviewSide.CURRENT, 1, 1, "Synthetic Unicode 船\n`data` ${'$'}(not-a-command)")
            val batch = store.prepare(draft.id)
            SyntheticReviewServer(home, checkout, recipient.sessionId).use { server -> work(server, batch) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `queued and busy receipts travel over a private socket with exact addressed JSON`() {
        for (mode in listOf(SyntheticReviewServer.Mode.QUEUE, SyntheticReviewServer.Mode.BUSY)) scenario { server, batch ->
            server.mode = mode; server.fragmented = true
            val result = CodexReviewTransport().submit(batch, server.home) { true }
            assertEquals(ReviewDeliveryStatus.QUEUED, result.status)
            assertEquals("synthetic-receipt", result.receipt)
            assertEquals(1, server.enqueued.get())
            val sent = server.requests.single { it["method"].asString == "thread/queue/add" }["params"].asJsonObject
            assertEquals(batch.recipient.sessionId, sent["threadId"].asString)
            assertEquals(batch.id, sent["clientUserMessageId"].asString)
            assertEquals(batch.payload, sent["input"].asJsonArray[0].asJsonObject["text"].asString)
            assertEquals(setOf("initialize", "initialized", "thread/read", "thread/queue/add"), server.requests.map { it["method"].asString }.toSet())
        }
    }
    @Test fun `refusal is rejected but internal error lost and wrong receipts are unknown without retry`() {
        for (mode in listOf(SyntheticReviewServer.Mode.REFUSE, SyntheticReviewServer.Mode.INTERNAL_ERROR,
            SyntheticReviewServer.Mode.LOST_RECEIPT, SyntheticReviewServer.Mode.WRONG_RECEIPT)) scenario { server, batch ->
            server.mode = mode
            val result = CodexReviewTransport().submit(batch, server.home) { true }
            assertEquals(mode.name, if (mode == SyntheticReviewServer.Mode.REFUSE) ReviewDeliveryStatus.REJECTED else ReviewDeliveryStatus.UNKNOWN, result.status)
            assertEquals(1, server.requests.count { it["method"].asString == "thread/queue/add" })
        }
    }
    @Test fun `unloaded wrong thread wrong checkout version and home disable sending before payload`() {
        for (mode in listOf(SyntheticReviewServer.Mode.NOT_LOADED, SyntheticReviewServer.Mode.WRONG_THREAD,
            SyntheticReviewServer.Mode.WRONG_CHECKOUT)) scenario { server, batch ->
            server.mode = mode
            assertEquals(ReviewDeliveryStatus.UNAVAILABLE, CodexReviewTransport().submit(batch, server.home) { true }.status)
            assertEquals(0, server.requests.count { it["method"].asString == "thread/queue/add" })
        }
        scenario { server, batch ->
            server.version = "0.167.0"
            assertEquals("codex_version_unsupported", CodexReviewTransport().submit(batch, server.home) { true }.reason)
            assertEquals(0, server.enqueued.get())
        }
        scenario { server, batch ->
            server.reportedHome = server.home.parent
            assertEquals("server_home_mismatch", CodexReviewTransport().submit(batch, server.home) { true }.reason)
            assertEquals(0, server.enqueued.get())
        }
    }
    @Test fun `late target revocation permissions unsupported providers and payload limits never write a message`() {
        scenario { server, batch ->
            assertEquals("recipient_or_files_changed", CodexReviewTransport().submit(batch, server.home) { false }.reason)
            assertEquals(0, server.enqueued.get())
        }
        scenario { server, batch ->
            Files.setPosixFilePermissions(server.socket, PosixFilePermissions.fromString("rw-r--r--"))
            assertEquals("socket_not_private", CodexReviewTransport().submit(batch, server.home) { true }.reason)
            assertTrue(server.requests.isEmpty())
        }
        scenario { server, batch ->
            assertEquals("provider_unsupported", CodexReviewTransport().submit(batch.copy(recipient = batch.recipient.copy(kind = AgentKind.CLAUDE_CODE)), server.home) { true }.reason)
            assertEquals("payload_limit", CodexReviewTransport().submit(batch.copy(payload = "x".repeat(128 * 1024 + 1)), server.home) { true }.reason)
            assertTrue(server.requests.isEmpty())
        }
    }
    @Test fun `deadline and local cancellation after dispatch preserve unknown outcome`() {
        scenario { server, batch ->
            server.mode = SyntheticReviewServer.Mode.STALL
            assertEquals(ReviewDeliveryStatus.UNKNOWN, CodexReviewTransport(1000).submit(batch, server.home) { true }.status)
            assertEquals(1, server.enqueued.get())
        }
        scenario { server, batch ->
            server.mode = SyntheticReviewServer.Mode.STALL
            val result = AtomicReference<ReviewDeliveryResult>()
            val thread = Thread.ofVirtual().start { result.set(CodexReviewTransport().submit(batch, server.home) { true }) }
            assertTrue(server.received.await(3, TimeUnit.SECONDS))
            thread.interrupt(); thread.join(3000)
            assertFalse(thread.isAlive)
            assertEquals(ReviewDeliveryStatus.UNKNOWN, result.get().status)
            assertEquals(1, server.enqueued.get())
        }
    }
}
