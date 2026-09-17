package com.hedworth.seshlog.copy

import com.google.gson.Gson
import com.hedworth.seshlog.claude.ClaudeCodeSessionProvider
import com.hedworth.seshlog.codex.CodexSessionProvider
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.pi.PiSessionProvider
import com.hedworth.seshlog.opencode.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import org.sqlite.JDBC

class LastAssistantTest {
    @get:Rule val temp = TemporaryFolder()
    private val root get() = temp.root.toPath()
    private fun providers() = listOf(
        ClaudeCodeSessionProvider({ root }, { "claude" }),
        CodexSessionProvider({ root }, { "codex" }),
        PiSessionProvider({ root }, { "pi" }),
    )
    private fun fixture(name: String) = Path.of(javaClass.getResource("/fixtures/copy/$name")!!.toURI())
    private fun source(provider: SessionProvider) = fixture(when (provider.kind) {
        AgentKind.CLAUDE_CODE -> "claude.jsonl"; AgentKind.CODEX -> "codex.jsonl"; else -> "pi.jsonl"
    })

    @Test fun `all file providers skip trailing user tools blank and malformed records with exact Markdown`() {
        for (provider in providers()) assertEquals(CopyContent.Found(Files.readString(fixture("expected.md"))),
            provider.lastAssistantMessage(session(provider.kind, source(provider))))
    }

    @Test fun `all file providers read fresh long replies past preview limits`() {
        val text = "```\n" + "héllo 👋\n".repeat(50000) + "```\n"
        for (provider in providers()) {
            val path = root.resolve("${provider.kind}.jsonl")
            val input = Files.readString(source(provider)).replace(Gson().toJson(Files.readString(fixture("expected.md"))), Gson().toJson(text))
            Files.writeString(path, input)
            assertEquals(CopyContent.Found(text), provider.lastAssistantMessage(session(provider.kind, path)))
            Files.writeString(path, "")
            assertTrue(provider.lastAssistantMessage(session(provider.kind, path)) is CopyContent.Absent)
            Files.delete(path)
            assertTrue(provider.lastAssistantMessage(session(provider.kind, path)) is CopyContent.Failed)
        }
    }

    @Test fun `reverse reader crosses arbitrarily many trailing prompts and rejects oversized records`() {
        val provider = providers().first()
        val path = root.resolve("many.jsonl")
        Files.writeString(path, Files.readString(source(provider)) +
            "{\"type\":\"user\",\"message\":{\"content\":\"later\"}}\n".repeat(10000))
        assertEquals(CopyContent.Found(Files.readString(fixture("expected.md"))), provider.lastAssistantMessage(session(provider.kind, path)))
        Files.writeString(path, "x".repeat(LastAssistantReader.MAX_RECORD_BYTES + 1))
        assertTrue(provider.lastAssistantMessage(session(provider.kind, path)) is CopyContent.Failed)
    }

    @Test fun `Pi follows the persisted branch instead of last physical assistant`() {
        val path = Path.of(javaClass.getResource("/fixtures/pi/branch.jsonl")!!.toURI())
        val provider = providers().last()
        val expected = provider.conversationMessages(session(provider.kind, path)).last { it.role == Role.ASSISTANT }.text
        assertEquals(CopyContent.Found(expected), provider.lastAssistantMessage(session(provider.kind, path)))
    }

    @Test fun `opencode complete latest text skips bad rows tools blanks and other sessions`() {
        val db = OpenCodeFixture.create(root)
        val provider = OpenCodeSessionProvider({ root }, { "opencode" })
        val target = session(AgentKind.OPENCODE, null).copy(id = "copy")
        val text = Files.readString(fixture("expected.md")) + "long\n".repeat(10000)
        JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { conn ->
            fun add(id: String, time: Int, role: String, raw: String, owner: String = "copy") {
                conn.prepareStatement("INSERT INTO message VALUES (?, ?, ?, ?, ?)").use {
                    it.setString(1, id); it.setString(2, owner); it.setInt(3, time); it.setInt(4, time)
                    it.setString(5, "{\"role\":\"$role\"}"); it.executeUpdate()
                }
                conn.prepareStatement("INSERT INTO part VALUES (?, ?, ?, ?, ?, ?)").use {
                    it.setString(1, id); it.setString(2, id); it.setString(3, owner); it.setInt(4, time); it.setInt(5, time)
                    it.setString(6, raw); it.executeUpdate()
                }
            }
            add("copy1", 1, "assistant", Gson().toJson(mapOf("type" to "text", "text" to text)))
            add("copy2", 2, "assistant", "{bad")
            add("copy3", 3, "assistant", "{\"type\":\"text\",\"text\":\" \\n\"}")
            add("copy4", 4, "assistant", "{\"type\":\"reasoning\",\"text\":\"hidden\"}")
            add("copy5", 5, "user", "{\"type\":\"text\",\"text\":\"later\"}")
            add("copy6", 6, "assistant", "{\"type\":\"text\",\"text\":\"other\"}", "another")
        }
        assertEquals(CopyContent.Found(text), provider.lastAssistantMessage(target))
        assertTrue(provider.lastAssistantMessage(target.copy(id = "missing")) is CopyContent.Absent)
        Files.delete(db)
        assertTrue(provider.lastAssistantMessage(target) is CopyContent.Failed)
    }

    companion object {
        fun session(kind: AgentKind = AgentKind.CLAUDE_CODE, path: Path? = null, id: String = "one") = Session(
            kind, id, id, Path.of("/same/directory"), null, null, Instant.EPOCH, path, false, null, null, 0, false)
    }
}
