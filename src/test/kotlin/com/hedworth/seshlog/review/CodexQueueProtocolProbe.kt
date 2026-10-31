package com.hedworth.seshlog.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * D1 contract experiment, not a delivery adapter. It has no CLI/socket/terminal implementation.
 * Field names come from the installed 0.166.0 schema. Error policy and delivery semantics remain
 * hypotheses exercised by synthetic receivers, not verified guarantees of a real Codex server.
 */
internal object CodexQueueProtocolProbe {
    enum class Outcome { QUEUED, REJECTED, UNKNOWN, UNAVAILABLE }
    data class Result(val outcome: Outcome, val receipt: String? = null)
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    const val MESSAGE_LIMIT = 128 * 1024 // Seshlog's proposed bound, not an upstream payload limit.

    fun submit(thread: String, batch: String, message: String, exchange: (String) -> String?): Result {
        if (!uuid.matches(thread) || !uuid.matches(batch) || message.isBlank() ||
            message.toByteArray(Charsets.UTF_8).size > MESSAGE_LIMIT) return Result(Outcome.UNAVAILABLE)
        val input = JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", message); add("text_elements", JsonArray()) })
        }
        val request = JsonObject().apply {
            addProperty("id", batch)
            addProperty("method", "thread/queue/add")
            add("params", JsonObject().apply {
                addProperty("threadId", thread)
                addProperty("clientUserMessageId", batch)
                add("input", input)
            })
        }
        // A missing/malformed/mismatched receipt is uncertain. Never resend automatically.
        return try {
            val response = exchange(request.toString()) ?: return Result(Outcome.UNKNOWN)
            if (response.toByteArray(Charsets.UTF_8).size > 2 * MESSAGE_LIMIT) return Result(Outcome.UNKNOWN)
            val root = JsonParser.parseString(response).asJsonObject
            if (root["id"] != request["id"]) return Result(Outcome.UNKNOWN)
            if (root.has("error")) {
                val error = root["error"].asJsonObject
                // Internal/provider errors may occur after persistence. Only recognized request
                // refusals are definite rejection; never turn an uncertain commit into a retry.
                val code = error["code"]?.asJsonPrimitive
                return Result(if (!root.has("result") && code?.isNumber == true &&
                    code.asString in setOf("-32600", "-32601", "-32602") &&
                    error["message"]?.asJsonPrimitive?.isString == true) Outcome.REJECTED else Outcome.UNKNOWN)
            }
            val queued = root["result"].asJsonObject["queuedSubmission"].asJsonObject
            val receipt = queued["id"].asString
            if (queued["clientUserMessageId"] != request["params"].asJsonObject["clientUserMessageId"] ||
                queued["input"] != input || receipt.isBlank() || receipt.length > 256) Result(Outcome.UNKNOWN)
            else Result(Outcome.QUEUED, receipt)
        } catch (_: Exception) {
            Result(Outcome.UNKNOWN)
        }
    }
}

/** In-memory hypothetical receiver: no model, agent roots, shell, process or filesystem access. */
internal class SyntheticReviewQueue(private val thread: String) {
    enum class Policy { QUEUE, HOLD, REFUSE, CLOSE, LOSE_RECEIPT }
    var policy = Policy.QUEUE
    val submissions = mutableListOf<JsonObject>()

    fun exchange(wire: String): String? {
        val request = JsonParser.parseString(wire).asJsonObject
        val params = request["params"].asJsonObject
        val response = JsonObject().apply { add("id", request["id"]) }
        if (request["method"].asString != "thread/queue/add" || params["threadId"].asString != thread ||
            policy == Policy.REFUSE || policy == Policy.CLOSE) {
            response.add("error", JsonObject().apply {
                addProperty("code", -32600)
                addProperty("message", "Synthetic receiver refused the request")
            })
        } else {
            submissions += params.deepCopy()
            if (policy == Policy.LOSE_RECEIPT) return null
            response.add("result", JsonObject().apply {
                add("queuedSubmission", JsonObject().apply {
                    addProperty("id", "synthetic-receipt-${submissions.size}")
                    add("clientUserMessageId", params["clientUserMessageId"])
                    add("input", params["input"])
                })
            })
        }
        return response.toString()
    }
}
