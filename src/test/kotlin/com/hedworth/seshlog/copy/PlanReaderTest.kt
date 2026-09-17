package com.hedworth.seshlog.copy

import com.google.gson.Gson
import com.hedworth.seshlog.claude.ClaudeCodeSessionProvider
import com.hedworth.seshlog.codex.CodexSessionProvider
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.pi.PiSessionProvider
import com.hedworth.seshlog.opencode.OpenCodeSessionProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class PlanReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val root get() = temp.root.toPath()
    private val claude get() = ClaudeCodeSessionProvider({ root }, { "claude" })
    private val codex get() = CodexSessionProvider({ root }, { "codex" })
    private fun fixture(name: String) = Path.of(javaClass.getResource("/fixtures/copy/$name")!!.toURI())
    private fun read(provider: SessionProvider, path: Path) = provider.latestPlan(LastAssistantTest.session(provider.kind, path))
    private fun transcript(text: String): Path = root.resolve("transcript.jsonl").also { Files.writeString(it, text) }
    private fun claudePlan(plan: Any?) = Gson().toJson(mapOf("type" to "attachment", "attachment" to
        mapOf("type" to "plan_file_reference", "planContent" to plan)))
    private fun codexPlan(plan: String) = Gson().toJson(mapOf("type" to "response_item", "payload" to
        mapOf("type" to "message", "role" to "assistant", "content" to listOf(mapOf("type" to "output_text", "text" to plan)))))

    @Test fun `latest explicit revisions preserve exact Markdown and ignore later nonplans`() {
        for ((provider, name) in listOf(claude to "claude-plan.jsonl", codex to "codex-plan.jsonl")) {
            assertEquals(CopyContent.Found(Files.readString(fixture("expected-plan.md"))), read(provider, fixture(name)))
        }
    }

    @Test fun `Claude file-only reference is scoped to transcript and reads current contents`() {
        val plan = root.resolve("linked.md")
        Files.writeString(plan, "# Linked\n")
        Files.writeString(root.resolve("newer-unrelated.md"), "not this one")
        val raw = Files.readString(fixture("claude-plan-reference.jsonl")).replace("@PLAN_PATH@", plan.toString())
        val path = transcript(raw)
        var result = read(claude, path) as CopyContent.Found
        assertEquals("# Linked\n", result.text)
        assertTrue(result.detail.contains("Current contents"))
        Files.writeString(plan, Files.readString(fixture("expected-plan.md")))
        result = read(claude, path) as CopyContent.Found
        assertEquals(Files.readString(fixture("expected-plan.md")), result.text)
        val other = root.resolve("other.jsonl")
        Files.writeString(other, "{\"type\":\"user\",\"message\":{\"content\":\"Please copy the latest plan\"}}")
        assertTrue(read(claude, other) is CopyContent.Absent)
        assertTrue(claude.latestPlan(LastAssistantTest.session(path = path, id = "other")) is CopyContent.Absent)
        Files.delete(plan)
        assertTrue(read(claude, path) is CopyContent.Failed)
        Files.createDirectory(plan)
        assertTrue(read(claude, path) is CopyContent.Failed)
    }

    @Test fun `Claude snapshot wins over mutable file in same record and tool results require matching call`() {
        val path = transcript(claudePlan("# Snapshot\n").dropLast(2) + ",\"planFilePath\":\"/missing/file.md\"}}")
        assertEquals(CopyContent.Found("# Snapshot\n"), read(claude, path))
        Files.writeString(path, """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"unrelated"}]},"toolUseResult":{"plan":"not a plan","filePath":"/missing"}}""")
        assertTrue(read(claude, path) is CopyContent.Absent)
    }

    @Test fun `Claude late results from older calls cannot replace newer plan sources`() {
        val records = Files.readString(fixture("claude-plan.jsonl")) +
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"first"}]},"toolUseResult":{"plan":"late older result","isAgent":false}}"""
        assertEquals(CopyContent.Found(Files.readString(fixture("expected-plan.md"))), read(claude, transcript(records)))
    }

    @Test fun `latest missing empty invalid or oversized plan never falls back`() {
        val good = claudePlan("# Older\n") + "\n"
        val path = transcript(good + Files.readString(fixture("claude-plan-reference.jsonl")).replace("@PLAN_PATH@", root.resolve("missing.md").toString()))
        assertTrue(read(claude, path) is CopyContent.Failed)
        Files.writeString(path, good + claudePlan(" \n"))
        assertTrue(read(claude, path) is CopyContent.Absent)
        Files.writeString(path, good + claudePlan(123))
        assertTrue(read(claude, path) is CopyContent.Failed)
        Files.writeString(path, good + claudePlan(listOf("invalid")))
        assertTrue(read(claude, path) is CopyContent.Failed)
        Files.writeString(path, good + claudePlan("x".repeat(PlanReader.MAX_PLAN_BYTES + 1)))
        assertTrue(read(claude, path) is CopyContent.Failed)
        Files.writeString(path, good + "x".repeat(PlanReader.MAX_RECORD_CHARS + 1))
        assertTrue(read(claude, path) is CopyContent.Failed)
        val file = root.resolve("oversized.md")
        Files.writeString(file, "x".repeat(PlanReader.MAX_PLAN_BYTES + 1))
        assertTrue(PlanReader.file(file.toString()) is CopyContent.Failed)
        assertTrue(PlanReader.file("relative.md") is CopyContent.Failed)
        Files.write(file, byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(PlanReader.file(file.toString()) is CopyContent.Failed)
    }

    @Test fun `Codex incomplete empty and oversized revisions do not copy earlier plans`() {
        val good = codexPlan("<proposed_plan>\nOlder\n</proposed_plan>") + "\n"
        val path = transcript(good + codexPlan("<proposed_plan>\nIncomplete"))
        assertTrue(read(codex, path) is CopyContent.Failed)
        Files.writeString(path, good + codexPlan("<proposed_plan>\n \n</proposed_plan>"))
        assertTrue(read(codex, path) is CopyContent.Absent)
        Files.writeString(path, good + codexPlan("<proposed_plan>\n" + "x".repeat(PlanReader.MAX_PLAN_BYTES) + "\n</proposed_plan>"))
        assertTrue(read(codex, path) is CopyContent.Failed)
        Files.writeString(path, codexPlan("An example:\n```\n<proposed_plan>\nnot official\n</proposed_plan>\n```"))
        assertTrue(read(codex, path) is CopyContent.Absent)
    }

    @Test fun `absent unreadable and unsupported outcomes remain distinct`() {
        for (provider in listOf(claude, codex)) {
            assertTrue(read(provider, transcript("bad data\n{}\n")) is CopyContent.Absent)
            assertTrue(read(provider, root.resolve("missing")) is CopyContent.Failed)
            assertTrue(read(provider, root) is CopyContent.Failed)
        }
        for (provider in listOf(PiSessionProvider({ root }, { "pi" }), OpenCodeSessionProvider({ root }, { "opencode" })))
            assertTrue(read(provider, root.resolve("missing")) is CopyContent.Unsupported)
    }
}
