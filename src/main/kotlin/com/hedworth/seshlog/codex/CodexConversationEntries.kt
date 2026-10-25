package com.hedworth.seshlog.codex

import com.hedworth.seshlog.claude.ClaudeConversationEntries
import com.hedworth.seshlog.codex.CodexTranscriptParser.objectValue
import com.hedworth.seshlog.codex.CodexTranscriptParser.string
import com.hedworth.seshlog.model.*

object CodexConversationEntries {
    fun withDirectories(): (String, String) -> List<ConversationEntry> = DirectoryContextEntries(::parse) { line, old ->
        val obj = CodexTranscriptParser.parseObject(line)
        if (obj?.string("type") in setOf("session_meta", "turn_context")) {
            com.hedworth.seshlog.claude.TranscriptParser.absoluteDirectory(obj?.objectValue("payload")?.string("cwd"))
                ?.let(java.nio.file.Path::of) ?: old
        } else old
    }::parse

    fun parse(line: String, source: String): List<ConversationEntry> {
        val obj = CodexTranscriptParser.parseObject(line) ?: return emptyList()
        if (obj.string("type") != "response_item") return emptyList()
        val payload = obj.objectValue("payload") ?: return emptyList()
        val kind = when (payload.string("type")) {
            "function_call", "custom_tool_call" -> EntryKind.TOOL_CALL
            "function_call_output", "custom_tool_call_output" -> EntryKind.TOOL_RESULT
            "message" -> return listOfNotNull(CodexConversationMessages.parseLine(line)?.let {
                ConversationEntry(it, payload.string("id") ?: source)
            })
            else -> return emptyList()
        }
        val name = payload.string("name")
        val text = if (kind == EntryKind.TOOL_CALL) {
            listOfNotNull(name, (payload.get("arguments") ?: payload.get("input"))?.let(ClaudeConversationEntries::inputText)).joinToString("\n")
        } else ClaudeConversationEntries.textualContent(payload.get("output"))
        if (text.isNullOrBlank()) return emptyList()
        return listOf(ConversationEntry(
            ConversationMessage(Role.ASSISTANT, text, CodexTranscriptParser.instant(obj.string("timestamp"))),
            payload.string("id") ?: source, kind, payload.string("call_id"), name))
    }
}
