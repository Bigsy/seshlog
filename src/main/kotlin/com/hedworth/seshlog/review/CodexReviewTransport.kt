package com.hedworth.seshlog.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.model.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal fun interface ReviewDeliveryTransport {
    fun submit(batch: ReviewSubmission, home: Path, beforeDispatch: () -> Boolean): ReviewDeliveryResult
}

/** Exact-version local protocol. No terminal input, CLI invocation, agent-root writes or fallback. */
internal class CodexReviewTransport(private val timeoutMillis: Long = 10_000) : ReviewDeliveryTransport {
    override fun submit(batch: ReviewSubmission, home: Path, beforeDispatch: () -> Boolean): ReviewDeliveryResult {
        fun unavailable(reason: String) = ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, reason)
        if (batch.recipient.kind != AgentKind.CODEX) return unavailable("provider_unsupported")
        if (batch.payload.toByteArray(Charsets.UTF_8).size > 128 * 1024) return unavailable("payload_limit")
        var dispatched = false
        try {
            val socket = home.resolve("app-server-control/app-server-control.sock").toRealPath()
            val owner = Files.getOwner(Path.of(System.getProperty("user.home")))
            val publicBits = PosixFilePermission.entries.filter { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }.toSet()
            if (Files.getOwner(socket) != owner || Files.getOwner(socket.parent) != owner ||
                Files.getPosixFilePermissions(socket).any { it in publicBits } || Files.getPosixFilePermissions(socket.parent).any { it in publicBits })
                return unavailable("socket_not_private")
            LocalReviewSocket(socket, timeoutMillis).use { client ->
                val initialized = client.request("initialize", "initialize", JsonObject().apply {
                    add("clientInfo", JsonObject().apply { addProperty("name", "seshlog-review"); addProperty("version", "1") })
                    add("capabilities", JsonObject().apply { addProperty("experimentalApi", true) })
                })
                if (initialized.has("error")) return unavailable("protocol_unavailable")
                val server = initialized["result"].asJsonObject
                val version = server["userAgent"].asString.substringAfter('/', "").substringBefore(' ')
                if (version != "0.166.0") return unavailable("codex_version_unsupported")
                if (Path.of(server["codexHome"].asString).toRealPath() != home.toRealPath()) return unavailable("server_home_mismatch")
                client.send(JsonObject().apply { addProperty("method", "initialized") })
                val read = client.request("review-thread", "thread/read", JsonObject().apply {
                    addProperty("threadId", batch.recipient.sessionId); addProperty("includeTurns", false)
                })
                if (read.has("error")) return unavailable("thread_unavailable")
                val thread = read["result"].asJsonObject["thread"].asJsonObject
                if (thread["id"].asString != batch.recipient.sessionId) return unavailable("thread_mismatch")
                if (thread["status"].asJsonObject["type"].asString !in setOf("idle", "active")) return unavailable("thread_not_loaded")
                val checkout = GitRepository.checkout(Path.of(thread["cwd"].asString), batch.recipient.checkout)
                if (checkout == null || !checkout.available || !batch.recipient.checkout.sameIdentity(checkout)) return unavailable("checkout_changed")
                if (!beforeDispatch()) return unavailable("recipient_or_files_changed")
                val input = JsonArray().apply { add(JsonObject().apply {
                    addProperty("type", "text"); addProperty("text", batch.payload); add("text_elements", JsonArray())
                }) }
                val params = JsonObject().apply {
                    addProperty("threadId", batch.recipient.sessionId); addProperty("clientUserMessageId", batch.id); add("input", input)
                }
                // Once a write is attempted, failures/timeouts are uncertain even if only part reached the server.
                dispatched = true
                val response = client.request(batch.id, "thread/queue/add", params)
                if (response.has("error")) {
                    if (response.has("result")) return ReviewDeliveryResult(ReviewDeliveryStatus.UNKNOWN, "invalid_receipt")
                    val code = response["error"].asJsonObject["code"]?.asString
                    return when (code) {
                        "-32601" -> unavailable("queue_method_unsupported")
                        "-32600", "-32602" -> ReviewDeliveryResult(ReviewDeliveryStatus.REJECTED, "agent_refused")
                        else -> ReviewDeliveryResult(ReviewDeliveryStatus.UNKNOWN, "server_error_after_dispatch")
                    }
                }
                val queued = response["result"].asJsonObject["queuedSubmission"].asJsonObject
                val receipt = queued["id"].asString
                if (queued["clientUserMessageId"]?.asString != batch.id || queued["input"] != input || receipt.isBlank() || receipt.length > 256)
                    return ReviewDeliveryResult(ReviewDeliveryStatus.UNKNOWN, "receipt_mismatch")
                return ReviewDeliveryResult(ReviewDeliveryStatus.QUEUED, "queued_by_agent", receipt)
            }
        } catch (_: Exception) {
            return if (dispatched) ReviewDeliveryResult(ReviewDeliveryStatus.UNKNOWN, "connection_lost_after_dispatch")
            else unavailable("local_transport_unavailable")
        }
    }
}
