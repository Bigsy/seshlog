package com.hedworth.seshlog.pi

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hedworth.seshlog.claude.TranscriptParser
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

data class PiTranscriptInfo(
    val sessionId: String?, val cwd: String?, val explicitTitle: String?, val promptTitle: String?,
    val startedAt: Instant?, val lastActivityAt: Instant?, val promptCount: Int,
)

data class PiTranscript(val info: PiTranscriptInfo, val messages: List<ConversationMessage>)

/** Read-only history view of the persisted leaf, including original history before compaction. */
object PiTranscriptParser {
    private val LOG = logger<PiTranscriptParser>()
    private data class Entry(val id: String, val parent: String?, val message: ConversationMessage?)

    fun parse(path: Path): PiTranscriptInfo = read(path).info
    fun read(path: Path): PiTranscript = Files.newBufferedReader(path).use { parseLines(it.lineSequence()) }

    fun parseLines(lines: Sequence<String>): PiTranscript {
        var header: JsonObject? = null
        var linear = true
        var name: String? = null
        var activity: Instant? = null
        val entries = linkedMapOf<String, Entry>()
        val ambiguous = hashSetOf<String>()
        var leaf: String? = null
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val obj = try { JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject }
                catch (_: Exception) { null }
            if (obj == null) {
                LOG.debug("Skipping malformed Pi transcript record")
                return@forEach
            }
            val type = obj.string("type") ?: return@forEach
            if (type == "session") {
                if (header == null) {
                    header = obj
                    linear = (obj.get("version")?.let { runCatching { it.asInt }.getOrNull() } ?: 1) < 2
                }
                return@forEach
            }
            if (header == null) return@forEach
            val id = if (linear) entries.size.toString() else obj.string("id")?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val parent = if (linear) leaf else {
                val value = obj.get("parentId") ?: return@forEach
                if (value.isJsonNull) null else obj.string("parentId")?.takeIf { it.isNotBlank() } ?: return@forEach
            }
            if (type == "session_info") obj.string("name")?.let { name = it.takeIf(String::isNotBlank) }
            val message = if (type == "message") obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject else null
            val role = when (message?.string("role")) { "user" -> Role.USER; "assistant" -> Role.ASSISTANT; else -> null }
            val time = if (role != null) instant(obj.get("timestamp")) ?: instant(message?.get("timestamp")) else null
            if (time != null && (activity == null || time > activity)) activity = time
            val text = message?.get("content")?.let(::text)
            val visible = if (role != null && text != null && (role != Role.USER || realPrompt(text)))
                ConversationMessage(role, text, time) else null
            if (entries.containsKey(id)) {
                ambiguous += id
                LOG.debug("Skipping ambiguous Pi entry ID")
            } else entries[id] = Entry(id, parent, visible)
            leaf = id
        }
        val branch = arrayListOf<ConversationMessage>()
        val visited = hashSetOf<String>()
        var cursor = leaf
        while (cursor != null) {
            if (cursor in ambiguous || !visited.add(cursor)) break
            val entry = entries[cursor] ?: break
            entry.message?.let(branch::add)
            cursor = entry.parent
        }
        branch.reverse()
        val prompts = branch.filter { it.role == Role.USER }
        val id = header?.string("id")?.takeIf { it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")) }
        val cwd = header?.string("cwd")?.takeIf { it.isNotBlank() && runCatching { Path.of(it).isAbsolute }.getOrDefault(false) }
        return PiTranscript(PiTranscriptInfo(id, cwd, name, prompts.firstOrNull()?.text?.let(TranscriptParser::promptToTitle),
            instant(header?.get("timestamp")), activity, prompts.size), branch)
    }

    private fun realPrompt(text: String) = TranscriptParser.isRealPrompt(text) &&
        !text.trim().matches(Regex("/[\\w:-]+"))

    private fun text(value: JsonElement): String? {
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString.takeIf(String::isNotBlank)
        if (!value.isJsonArray) return null
        return value.asJsonArray.mapNotNull { block ->
            val obj = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (obj.string("type") == "text") obj.string("text")?.takeIf(String::isNotBlank) else null
        }.joinToString("\n").takeIf(String::isNotBlank)
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString

    private fun instant(value: JsonElement?): Instant? = runCatching {
        if (value == null || !value.isJsonPrimitive) null
        else if (value.asJsonPrimitive.isNumber) Instant.ofEpochMilli(value.asLong)
        else Instant.parse(value.asString)
    }.getOrNull()
}
