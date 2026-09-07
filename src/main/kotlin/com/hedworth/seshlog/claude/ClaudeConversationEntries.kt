package com.hedworth.seshlog.claude

import com.google.gson.JsonElement
import com.hedworth.seshlog.claude.TranscriptParser.bool
import com.hedworth.seshlog.claude.TranscriptParser.string
import com.hedworth.seshlog.claude.TranscriptParser.getAsJsonObjectOrNull
import com.hedworth.seshlog.model.*
import java.time.Instant

object ClaudeConversationEntries {
    fun parse(line: String, source: String): List<ConversationEntry> {
        val obj = TranscriptParser.parseObject(line) ?: return emptyList()
        if (obj.bool("isSidechain") || obj.bool("isMeta")) return emptyList()
        val role = when (obj.string("type")) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> return emptyList()
        }
        val time = obj.string("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val content = obj.getAsJsonObjectOrNull("message")?.get("content") ?: return emptyList()
        val identity = obj.string("uuid") ?: source
        if (!content.isJsonArray) return listOfNotNull(ConversationMessages.parseLine(line)?.let {
            ConversationEntry(it, identity)
        })
        return content.asJsonArray.mapIndexedNotNull { index, el ->
            val block = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val type = block.string("type")
            val kind = when (type) {
                "text" -> EntryKind.DIALOGUE
                "tool_use" -> EntryKind.TOOL_CALL
                "tool_result" -> EntryKind.TOOL_RESULT
                else -> return@mapIndexedNotNull null
            }
            val name = block.string("name")
            val text = when (kind) {
                EntryKind.TOOL_CALL -> listOfNotNull(name, block.get("input")?.let(::inputText)).joinToString("\n")
                EntryKind.TOOL_RESULT -> textualContent(block.get("content"))
                else -> block.string("text")
            }?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            if (kind == EntryKind.DIALOGUE && role == Role.USER && !TranscriptParser.isRealPrompt(text)) return@mapIndexedNotNull null
            ConversationEntry(ConversationMessage(role, text, time), "$identity:$index", kind,
                block.string(if (kind == EntryKind.TOOL_CALL) "id" else "tool_use_id"), name)
        }
    }

    /** Only recognized text blocks are included; image/base64 and unknown blocks never enter search. */
    fun textualContent(value: JsonElement?): String? = when {
        value == null || value.isJsonNull -> null
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
        value.isJsonArray -> value.asJsonArray.mapNotNull { el ->
            el.takeIf { it.isJsonObject }?.asJsonObject?.let {
                if (it.string("type") in setOf("text", "input_text", "output_text")) it.string("text") else null
            }
        }.joinToString("\n")
        else -> null
    }

    fun inputText(value: JsonElement): String? {
        fun clean(el: JsonElement): JsonElement? {
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                if (obj.string("type") in setOf("image", "image_url", "input_image", "audio", "file", "document") ||
                    obj.string("encoding") == "base64") return null
                val result = com.google.gson.JsonObject()
                obj.entrySet().forEach { (key, child) ->
                    if (key !in setOf("base64", "image_url", "image_data", "audio_data")) clean(child)?.let { result.add(key, it) }
                }
                return result
            }
            if (el.isJsonArray) return com.google.gson.JsonArray().apply { el.asJsonArray.forEach { clean(it)?.let(::add) } }
            return el.takeUnless { it.isJsonNull }
        }
        val cleaned = clean(value) ?: return null
        return if (cleaned.isJsonPrimitive && cleaned.asJsonPrimitive.isString) cleaned.asString
            else cleaned.toString()
    }
}
