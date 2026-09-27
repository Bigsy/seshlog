package com.hedworth.seshlog.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

/** Extracts changed-file operations from the structured tool-call entries already read on demand. */
object ChangedFileExtractor {
    fun fromEntries(entries: List<ConversationEntry>, cwd: Path): List<ChangedFile> =
        entries.asSequence()
            .filter { it.kind == EntryKind.TOOL_CALL }
            .flatMap { parseToolCall(it, cwd).asSequence() }
            .toList()

    private fun parseToolCall(entry: ConversationEntry, cwd: Path): List<ChangedFile> {
        val tool = entry.toolName?.trim().orEmpty()
        val normalized = tool.substringAfterLast('.').lowercase().replace("_", "").replace("-", "")
        val payload = entry.text.substringAfter('\n', "").trim()
        val obj = payload.takeIf { it.startsWith("{") }?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        return when {
            normalized in setOf("write", "filewrite", "writefile") -> write(entry, tool, obj, cwd)
            normalized in setOf("edit", "multiedit", "fileedit", "editfile") -> edit(entry, tool, obj, cwd)
            normalized in setOf("applypatch", "patch", "applydiff") -> patch(entry, tool, payload, obj, cwd)
            normalized in setOf("move", "rename", "movefile", "filerename") -> move(entry, tool, obj, cwd)
            else -> emptyList()
        }
    }

    private fun write(entry: ConversationEntry, tool: String, obj: JsonObject?, cwd: Path): List<ChangedFile> {
        val path = obj?.path() ?: return emptyList()
        val content = obj.stringAny("content") ?: return emptyList()
        val resolved = resolve(cwd, path) ?: return emptyList()
        return listOf(ChangedFile(resolved, ChangedFileOperation.WRITE, content, content, entry.sourceId, tool))
    }

    private fun edit(entry: ConversationEntry, tool: String, obj: JsonObject?, cwd: Path): List<ChangedFile> {
        val path = obj?.path() ?: return emptyList()
        val resolved = resolve(cwd, path) ?: return emptyList()
        val edits = obj.get("edits")?.takeIf { it.isJsonArray }
        val payload = if (edits != null) edits.toString() else obj.toString()
        return listOf(ChangedFile(resolved, ChangedFileOperation.EDIT, null, payload, entry.sourceId, tool))
    }

    private fun patch(entry: ConversationEntry, tool: String, payload: String, obj: JsonObject?, cwd: Path): List<ChangedFile> {
        val raw = obj?.firstString("patch", "diff", "patchText", "patch_text") ?: payload
        val headers = PATCH_PATH.findAll(raw).toList()
        return headers.mapIndexedNotNull { index, match ->
            val operation = when (match.groupValues[1]) {
                "Add" -> ChangedFileOperation.WRITE
                "Delete" -> ChangedFileOperation.DELETE
                else -> ChangedFileOperation.PATCH
            }
            // An invalid path drops only this patch header; it never becomes cwd (or ".").
            resolve(cwd, match.groupValues[2])?.let { path ->
                val blockEnd = headers.getOrNull(index + 1)?.range?.first ?: raw.length
                val move = MOVE_PATH.find(raw.substring(match.range.last + 1, blockEnd))
                    ?.groupValues?.get(1)?.let { resolve(cwd, it) }
                if (move != null && operation == ChangedFileOperation.PATCH) {
                    ChangedFile(move, ChangedFileOperation.MOVE, null, raw, entry.sourceId, tool, path)
                } else ChangedFile(path, operation, null, raw, entry.sourceId, tool)
            }
        }.distinctBy { it.path to it.operation }
    }

    private fun move(entry: ConversationEntry, tool: String, obj: JsonObject?, cwd: Path): List<ChangedFile> {
        val source = obj?.firstString("source", "from", "old_path", "oldPath", "file_path", "filePath") ?: return emptyList()
        val target = obj?.firstString("target", "destination", "to", "new_path", "newPath") ?: return emptyList()
        val old = resolve(cwd, source) ?: return emptyList()
        val next = resolve(cwd, target) ?: return emptyList()
        return listOf(ChangedFile(next, ChangedFileOperation.MOVE, null, "${old} -> ${next}", entry.sourceId, tool, old))
    }

    private fun resolve(cwd: Path, raw: String): Path? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val path = runCatching { Path.of(trimmed) }.getOrNull() ?: return null
        return if (path.isAbsolute) path.normalize() else cwd.resolve(path).normalize()
    }

    private fun JsonObject.path(): String? = listOf("file_path", "filePath", "path", "filename", "file").firstNotNullOfOrNull { stringAny(it) }

    private fun JsonObject.firstString(vararg names: String): String? = names.firstNotNullOfOrNull { stringAny(it) }

    private fun JsonObject.stringAny(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    // Claude apply_patch and Codex apply_patch both use `*** Update File: path` headers.
    private val MOVE_PATH = Regex("^\\*\\*\\* Move to:[ \\t]+(.+?)[ \\t]*$", RegexOption.MULTILINE)
    private val PATCH_PATH = Regex("^\\*\\*\\* (Update|Add|Delete) File:?[ \\t]+(.+?)\\s*$", RegexOption.MULTILINE)
}
