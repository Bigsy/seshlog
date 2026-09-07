package com.hedworth.seshlog.opencode

import com.hedworth.seshlog.claude.TranscriptParser
import com.hedworth.seshlog.claude.TranscriptParser.string
import com.hedworth.seshlog.claude.TranscriptParser.bool
import com.hedworth.seshlog.claude.TranscriptParser.getAsJsonObjectOrNull
import com.hedworth.seshlog.claude.ClaudeConversationEntries
import com.hedworth.seshlog.model.*
import java.time.Instant

object OpenCodeConversationEntries {
    fun parse(raw: String, sourceId: String, role: Role, time: Instant?): List<ConversationEntry> {
        val obj = TranscriptParser.parseObject(raw) ?: return emptyList()
        if (obj.bool("synthetic")) return emptyList()
        if (obj.string("type") == "text") return listOfNotNull(obj.string("text")?.takeIf { it.isNotBlank() }?.let {
            ConversationEntry(ConversationMessage(role, it, time), sourceId)
        })
        if (obj.string("type") != "tool") return emptyList()
        val state = obj.getAsJsonObjectOrNull("state") ?: return emptyList()
        val name = obj.string("tool")
        val callId = obj.string("callID")
        val entries = ArrayList<ConversationEntry>()
        val input = listOfNotNull(name, state.get("input")?.let(ClaudeConversationEntries::inputText)).joinToString("\n")
        if (input.isNotBlank()) entries += ConversationEntry(ConversationMessage(role, input, time),
            "$sourceId:call", EntryKind.TOOL_CALL, callId, name)
        val output = ClaudeConversationEntries.textualContent(state.get("output"))
            ?: state.string("error")
        if (!output.isNullOrBlank()) entries += ConversationEntry(ConversationMessage(role, output, time),
            "$sourceId:result", EntryKind.TOOL_RESULT, callId, name)
        return entries
    }
}
