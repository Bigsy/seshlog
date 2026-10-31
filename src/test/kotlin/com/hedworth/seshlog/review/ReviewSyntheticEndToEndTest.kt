package com.hedworth.seshlog.review

import com.google.gson.JsonParser
import com.hedworth.seshlog.codex.CodexSessionProvider
import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.sandbox.SandboxLayout
import com.hedworth.seshlog.sandbox.SyntheticSandbox
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ReviewSyntheticEndToEndTest {
    private class Client(val process: Process) : AutoCloseable {
        private val lines = LinkedBlockingQueue<String>()
        private val writer = process.outputStream.bufferedWriter()
        var id = ""
        init { Thread.ofVirtual().start { process.inputStream.bufferedReader().useLines { it.forEach(lines::add) } } }
        fun await(marker: String): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                val line = lines.poll(1, TimeUnit.SECONDS) ?: continue
                if (line.contains(marker)) return line.substringAfter(marker)
            }
            error("Synthetic review readiness barrier missing")
        }
        fun send(command: String) { writer.write(command); writer.newLine(); writer.flush() }
        fun ready() { id = JsonParser.parseString(await("SESHLOG_READY ")).asJsonObject["id"].asString }
        fun policy(value: String) { send("/review $value"); await("SESHLOG_REVIEW_POLICY $value") }
        fun change(command: String) { send(command); ready() }
        override fun close() { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
    }
    private fun scenario(work: (SandboxLayout, Client, Client, CodexSessionProvider) -> Unit) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        assumeTrue(SyntheticSandbox.findNode() != null)
        val base = Files.createTempDirectory(Path.of("/tmp"), "sl-e2e-").toRealPath()
        val root = base.resolve("profile")
        val layout = SandboxLayout(root, root.resolve("ide/config"), root.resolve("ide/log"), root.resolve("ide/plugins"))
        val clients = mutableListOf<Client>()
        try {
            SyntheticSandbox.prepare(layout, filler = 0, env = emptyMap(), home = base.resolve("home"))
            val executable = layout.executable(AgentKind.CODEX)
            fun launch(vararg args: String): Client {
                val builder = ProcessBuilder(listOf(executable.toString()) + args).directory(layout.projects.harbor.toFile()).redirectErrorStream(true)
                builder.environment()["SESHLOG_STUB_DELAY_MS"] = "0"
                return Client(builder.start()).also { clients += it }
            }
            launch("app-server", "--synthetic-review").await("SESHLOG_REVIEW_SERVER_READY")
            val first = launch().also { it.ready() }
            val second = launch().also { it.ready() }
            work(layout, first, second, CodexSessionProvider({ layout.codex }, { executable.toString() }))
        } finally { clients.asReversed().forEach(Client::close); base.toFile().deleteRecursively() }
    }
    private fun draft(layout: SandboxLayout, id: String): Pair<ReviewDraftStore, ReviewDraft> {
        val root = layout.projects.harbor
        Files.writeString(root.resolve("README.md"), "# Harbor\n\nChanged fictional upload service.\n")
        Files.writeString(root.resolve("review-added.kt"), "fun syntheticReview() = true\n")
        val recipient = ReviewRecipient(AgentKind.CODEX, id, GitRepository.checkout(root)!!)
        val scan = ReviewSnapshots.scan(recipient, "HEAD")
        val store = ReviewDraftStore()
        val review = store.open(recipient)
        for (file in scan.files.filter { it.path in setOf("README.md", "review-added.kt") }) store.load(review.id, ReviewSnapshots.read(scan, file))
        store.add(review.id, "README.md", ReviewSide.BASE, 1, 1, "Preserve this heading")
        store.add(review.id, "review-added.kt", ReviewSide.CURRENT, 1, 1, "Keep delivery addressed to this synthetic session")
        return store to store.get(review.id)
    }
    @Test fun `one batch across files and sides queues while held then reaches only the intended fictional session`() = scenario { layout, first, second, provider ->
        if (System.getProperty("os.name").startsWith("Mac"))
            assertTrue(com.hedworth.seshlog.terminal.CodexReviewEndpoint.connected(first.process.toHandle(), layout.codex))
        first.policy("hold")
        val (store, review) = draft(layout, first.id)
        val batch = store.prepare(review.id)
        val result = CodexReviewTransport().submit(batch, layout.codex) { first.id == review.recipient.sessionId }
        store.finish(batch, result)
        assertEquals(ReviewDeliveryStatus.QUEUED, result.status)
        assertEquals(1, Files.readAllLines(layout.root.resolve("review-receipts.jsonl")).size)
        fun text(id: String) = provider.conversationText(provider.scan(emptyMap()).single { it.id == id })
        assertFalse(text(first.id).contains(batch.payload)) // Provider queue has not been consumed.
        first.policy("accept")
        val received = JsonParser.parseString(first.await("SESHLOG_REVIEW_RECEIVED ")).asJsonObject
        assertEquals(first.id, received["id"].asString)
        assertEquals(batch.id, received["batchId"].asString)
        first.await("SESHLOG_REVIEW_DONE ")
        assertEquals(1, text(first.id).count { it == batch.payload })
        assertFalse(text(second.id).contains(batch.payload))
        assertTrue(store.get(review.id).comments.isEmpty())
    }
    @Test fun `a copied loaded thread and checkout in another home cannot receive this TUIs review`() = scenario { layout, first, _, _ ->
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val (store, snapshot) = draft(layout, first.id)
        val otherHome = Files.createDirectories(layout.root.parent.resolve("b")) // macOS Unix socket path bound.
        SyntheticReviewServer(otherHome, layout.projects.harbor, first.id).use { other ->
            var sends = 0
            val result = ReviewSendOperation(store, snapshot, { ReviewSnapshots.validate(it, emptySet()) },
                { com.hedworth.seshlog.terminal.CodexReviewEndpoint.connected(first.process.toHandle(), otherHome) },
                { batch, before -> sends++; CodexReviewTransport().submit(batch, otherHome, before) }).run()
            assertEquals(ReviewDeliveryStatus.UNAVAILABLE, result.status)
            assertEquals(0, sends)
            assertEquals(0, other.enqueued.get())
            assertEquals(2, store.get(snapshot.id).comments.size)
        }
    }
    @Test fun `refusal and lost receipt retain drafts and never automatically retry`() = scenario { layout, first, _, provider ->
        val (store, review) = draft(layout, first.id)
        first.policy("refuse")
        val rejected = store.prepare(review.id)
        val refusal = CodexReviewTransport().submit(rejected, layout.codex) { true }
        store.finish(rejected, refusal)
        assertEquals(ReviewDeliveryStatus.REJECTED, refusal.status)
        assertEquals(2, store.get(review.id).comments.size)
        first.policy("lost")
        val unknown = store.prepare(review.id)
        val result = CodexReviewTransport().submit(unknown, layout.codex) { true }
        store.finish(unknown, result)
        assertEquals(ReviewDeliveryStatus.UNKNOWN, result.status)
        first.await("SESHLOG_REVIEW_DONE ")
        val session = provider.scan(emptyMap()).single { it.id == first.id }
        assertEquals(1, provider.conversationText(session).count { it == unknown.payload })
        assertEquals(2, Files.readAllLines(layout.root.resolve("review-receipts.jsonl")).size)
        assertEquals(2, store.get(review.id).comments.size)
        assertTrue(store.get(review.id).sent.isEmpty())
    }
    @Test fun `thread switches and two TUI swaps cannot redirect a captured review`() = scenario { layout, first, second, _ ->
        val (store, review) = draft(layout, first.id)
        val firstId = first.id
        val secondId = second.id
        first.change("/resume $secondId")
        second.change("/resume $firstId")
        val batch = store.prepare(review.id)
        val result = CodexReviewTransport().submit(batch, layout.codex) { first.id == firstId }
        store.finish(batch, result)
        assertEquals(ReviewDeliveryStatus.UNAVAILABLE, result.status)
        assertFalse(Files.exists(layout.root.resolve("review-receipts.jsonl")))
        second.change("/new")
        val closed = CodexReviewTransport().submit(batch, layout.codex) { true }
        assertEquals(ReviewDeliveryStatus.UNAVAILABLE, closed.status)
        assertFalse(Files.exists(layout.root.resolve("review-receipts.jsonl")))
    }
}
