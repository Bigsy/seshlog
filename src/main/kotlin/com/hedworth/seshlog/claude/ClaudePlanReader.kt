package com.hedworth.seshlog.claude

import com.google.gson.JsonObject
import com.hedworth.seshlog.claude.TranscriptParser.bool
import com.hedworth.seshlog.claude.TranscriptParser.getAsJsonObjectOrNull
import com.hedworth.seshlog.claude.TranscriptParser.string
import com.hedworth.seshlog.copy.CopyContent
import com.hedworth.seshlog.copy.PlanReader
import com.hedworth.seshlog.model.Session

/** Explicit records only; never discover plan files by directory, filename, or dialogue text. */
object ClaudePlanReader {
    private data class Source(val text: String?, val hasText: Boolean, val path: String?) {
        fun read(): CopyContent = if (hasText) PlanReader.content(text) else PlanReader.file(path)
    }

    fun read(session: Session): CopyContent {
        var latest: Source? = null
        var latestCall: String? = null
        val failure = PlanReader.scan(session.transcriptPath) { line ->
            val obj = TranscriptParser.parseObject(line) ?: return@scan
            if (obj.bool("isSidechain")) return@scan
            if (obj.string("sessionId")?.let { it != session.id } == true) return@scan
            when (obj.string("type")) {
                "attachment" -> {
                    val attachment = obj.getAsJsonObjectOrNull("attachment") ?: return@scan
                    if (attachment.string("type") == "plan_file_reference") {
                        latestCall = null
                        latest = source(attachment, "planContent", "planFilePath")
                    }
                }
                "assistant" -> {
                    val blocks = obj.getAsJsonObjectOrNull("message")?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                    blocks?.forEach { element ->
                        val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        if (block.string("type") != "tool_use" || block.string("name") != "ExitPlanMode") return@forEach
                        latestCall = block.string("id")
                        val input = block.getAsJsonObjectOrNull("input")
                        // Empty input is still a newer plan attempt: never fall back to an old plan.
                        latest = input?.let { source(it, "plan", "planFilePath") } ?: Source(null, true, null)
                    }
                }
                "user" -> {
                    val result = obj.getAsJsonObjectOrNull("toolUseResult") ?: return@scan
                    val blocks = obj.getAsJsonObjectOrNull("message")?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                    val matched = blocks?.any { element ->
                        val block = element.takeIf { it.isJsonObject }?.asJsonObject
                        block?.string("type") == "tool_result" && latestCall != null && block.string("tool_use_id") == latestCall && !block.bool("is_error")
                    } == true
                    if (matched && !result.bool("isAgent")) latest = source(result, "plan", "filePath")
                }
            }
        }
        return failure ?: latest?.read() ?: CopyContent.Absent("No identifiable plan found for this session.")
    }

    private fun source(obj: JsonObject, text: String, path: String) =
        Source(strictString(obj, text), obj.has(text) && !obj.get(text).isJsonNull, strictString(obj, path))

    private fun strictString(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
