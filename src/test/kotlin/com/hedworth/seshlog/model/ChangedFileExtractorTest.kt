package com.hedworth.seshlog.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ChangedFileExtractorTest {
    private val cwd = Path.of("/workspace/project")

    private fun call(name: String, payload: String, id: String = name) = ConversationEntry(
        ConversationMessage(Role.ASSISTANT, "$name\n$payload", null), id,
        EntryKind.TOOL_CALL, toolName = name,
    )

    private fun fixture(name: String): List<ConversationEntry> =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().useLines { lines ->
                lines.filter(String::isNotBlank).mapIndexed { index, line ->
                    val obj = JsonParser.parseString(line).asJsonObject
                    val input = obj.get("input")
                    call(obj.get("tool").asString,
                        if (input.isJsonPrimitive) input.asString else input.toString(), "fixture:$index")
                }.toList()
            }

    @Test
    fun `Claude Write records normalized path and complete in-memory content`() {
        val files = ChangedFileExtractor.fromEntries(listOf(call("Write", "{\"file_path\":\"src/Main.kt\",\"content\":\"fun main() {}\"}")), cwd)
        assertEquals(1, files.size)
        assertEquals(cwd.resolve("src/Main.kt"), files.single().path)
        assertEquals(ChangedFileOperation.WRITE, files.single().operation)
        assertEquals("fun main() {}", files.single().historicalContent)
    }

    @Test
    fun `Claude Edit and MultiEdit retain recorded payload without fabricating snapshot`() {
        val files = ChangedFileExtractor.fromEntries(listOf(
            call("Edit", "{\"file_path\":\"a.txt\",\"old_string\":\"old\",\"new_string\":\"new\"}"),
            call("MultiEdit", "{\"file_path\":\"b.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"}]}", "multi"),
        ), cwd)
        assertEquals(listOf(cwd.resolve("a.txt"), cwd.resolve("b.txt")), files.map { it.path })
        assertTrue(files.all { it.operation == ChangedFileOperation.EDIT })
        assertTrue(files.all { it.historicalContent == null && it.recordedText.isNotBlank() })
    }

    @Test
    fun `Codex apply_patch extracts every synthetic fixture path`() {
        val patch = """*** Begin Patch
*** Update File: src/one.kt
@@
-old
+new
*** Add File: src/two.kt
+new file
*** Delete File: src/three.kt
*** End Patch"""
        val files = ChangedFileExtractor.fromEntries(listOf(call("apply_patch", patch)), cwd)
        assertEquals(listOf("src/one.kt", "src/two.kt", "src/three.kt"), files.map { cwd.relativize(it.path).toString() })
        assertTrue(files.all { it.historicalContent == null })
    }

    @Test
    fun `OpenCode write and edit tool shapes use filePath`() {
        val files = ChangedFileExtractor.fromEntries(listOf(
            call("write", "{\"filePath\":\"app.ts\",\"content\":\"export {}\"}"),
            call("edit", "{\"filePath\":\"app.ts\",\"oldString\":\"old\",\"newString\":\"new\"}"),
        ), cwd)
        assertEquals(listOf(cwd.resolve("app.ts"), cwd.resolve("app.ts")), files.map { it.path })
        assertEquals(ChangedFileOperation.WRITE, files[0].operation)
        assertEquals(ChangedFileOperation.EDIT, files[1].operation)
    }

    @Test
    fun `namespaced apply_patch and Move retain both paths`() {
        val files = ChangedFileExtractor.fromEntries(listOf(
            call("codex.apply_patch", "*** Update File: src/a.kt\n@@\n-old\n+new"),
            call("Move", "{\"source\":\"src/a.kt\",\"destination\":\"src/b.kt\"}"),
        ), cwd)
        assertEquals(ChangedFileOperation.PATCH, files[0].operation)
        assertEquals(ChangedFileOperation.MOVE, files[1].operation)
        assertEquals(cwd.resolve("src/a.kt"), files[1].previousPath)
        assertEquals(cwd.resolve("src/b.kt"), files[1].path)
    }

    @Test
    fun `synthetic Claude fixture yields expected paths`() {
        val files = ChangedFileExtractor.fromEntries(fixture("changed_files_claude.jsonl"), cwd)
        assertEquals(listOf("src/Claude.kt", "src/Claude.kt", "src/Other.kt"), files.map { cwd.relativize(it.path).toString() })
    }

    @Test
    fun `synthetic Codex fixture yields expected paths`() {
        val files = ChangedFileExtractor.fromEntries(fixture("changed_files_codex.jsonl"), cwd)
        assertEquals(listOf("src/Codex.kt", "src/new.kt", "src/after.kt"), files.map { cwd.relativize(it.path).toString() })
        assertEquals(cwd.resolve("src/before.kt"), files.last().previousPath)
        assertEquals(ChangedFileOperation.MOVE, files.last().operation)
    }

    @Test
    fun `synthetic OpenCode fixture yields expected paths`() {
        val files = ChangedFileExtractor.fromEntries(fixture("changed_files_opencode.jsonl"), cwd)
        assertEquals(listOf("src/OpenCode.ts", "src/OpenCode.ts"), files.map { cwd.relativize(it.path).toString() })
    }

    @Test
    fun `unrelated and malformed tool calls are ignored`() {
        val files = ChangedFileExtractor.fromEntries(listOf(
            call("Bash", "{\"command\":\"printf hi\"}"),
            call("Write", "not json"),
            call("Write", "{\"file_path\":\"bad\\u0000path\",\"content\":\"x\"}"),
        ), cwd)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `invalid patch path is skipped instead of resolving to cwd`() {
        val invalidPath = "bad${'\u0000'}path"
        val files = ChangedFileExtractor.fromEntries(
            listOf(call("apply_patch", "*** Update File: $invalidPath\n-old\n+new")), cwd,
        )
        assertTrue(files.isEmpty())
    }
}
