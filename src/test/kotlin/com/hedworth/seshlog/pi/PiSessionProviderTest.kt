package com.hedworth.seshlog.pi

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.terminal.ShellQuote
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException

class PiSessionProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun fixture(root: Path, name: String = "session.jsonl"): Path {
        Files.createDirectories(root)
        return root.resolve(name).also { path ->
            javaClass.getResourceAsStream("/fixtures/pi/branch.jsonl")!!.use { Files.copy(it, path) }
        }
    }

    @Test fun `scan uses qualified identity branch metadata and no live detection`() {
        val root = tmp.newFolder().toPath()
        val path = fixture(root.resolve("project"))
        val provider = PiSessionProvider({ root }, { "pi" })
        val session = provider.scan(emptyMap()).single()
        assertEquals(AgentKind.PI, session.kind)
        assertEquals("pi:synthetic-pi", session.id)
        assertEquals("Named session", session.title)
        assertTrue(session.hasExplicitTitle)
        assertFalse(session.isLive)
        assertFalse(provider.detectsLiveSessions)
        assertNull(session.gitBranch)
        assertEquals(path, session.transcriptPath)
        assertEquals(listOf("First prompt", "Selected reply"), provider.conversationText(session))
        assertEquals(listOf("Selected reply"), provider.lastMessages(session, 1).map { it.text })
        assertEquals(emptyList<Any>(), provider.lastMessages(session, 0))
    }

    @Test fun `cache reuses unchanged files persists only metadata and invalidates append rename delete and root changes`() {
        var root = tmp.newFolder().toPath()
        var parses = 0
        val cache = tmp.root.toPath().resolve("cache.json")
        val provider = PiSessionProvider({ root }, { "pi" }, cache) { parses++; PiTranscriptParser.parse(it) }
        val path = fixture(root)
        provider.scan(emptyMap())
        provider.scan(emptyMap())
        assertEquals(1, parses)
        val disk = Files.readString(cache)
        assertFalse(disk.contains("Selected reply"))
        assertFalse(disk.contains("Abandoned reply"))
        val reloaded = PiSessionProvider({ root }, { "pi" }, cache) { error("Unchanged file must use persisted cache") }
        assertEquals(1, reloaded.scan(emptyMap()).size)
        val stamp = provider.contentStamp(provider.scan(emptyMap()).single())
        Files.writeString(path, """{"type":"session_info","id":"rename","parentId":"name","name":"New title"}""" + "\n", java.nio.file.StandardOpenOption.APPEND)
        val updated = provider.scan(emptyMap()).single()
        assertEquals("New title", updated.title)
        assertNotEquals(stamp, provider.contentStamp(updated))
        Files.move(path, root.resolve("renamed.jsonl"))
        assertEquals("renamed.jsonl", provider.scan(emptyMap()).single().transcriptPath!!.fileName.toString())
        Files.delete(root.resolve("renamed.jsonl"))
        assertTrue(provider.scan(emptyMap()).isEmpty())
        root = tmp.newFolder().toPath()
        fixture(root)
        assertEquals(1, provider.scan(emptyMap()).size)
        root = root.resolve("missing")
        assertFalse(provider.isAvailable())
        assertTrue(provider.scan(emptyMap()).isEmpty())
    }

    @Test fun `bad or disappearing file does not discard other sessions and content IO errors propagate`() {
        val root = tmp.newFolder().toPath()
        fixture(root, "good.jsonl")
        fixture(root, "gone.jsonl")
        Files.writeString(root.resolve("bad.jsonl"), "invalid")
        val provider = PiSessionProvider({ root }, { "pi" }) { path ->
            if (path.fileName.toString() == "gone.jsonl") { Files.delete(path); throw IOException("gone") }
            PiTranscriptParser.parse(path)
        }
        val session = provider.scan(emptyMap()).single()
        assertNotNull(provider.scanProblem)
        Files.delete(session.transcriptPath!!)
        assertThrows(IOException::class.java) { provider.conversationText(session) }
    }

    @Test fun `deduplication is deterministic and traversal bounded without symlinks`() {
        val root = tmp.newFolder().toPath()
        fixture(root, "z.jsonl")
        val first = fixture(root, "a.jsonl")
        val outside = tmp.newFolder().toPath()
        fixture(outside)
        Files.createSymbolicLink(root.resolve("link"), outside)
        fixture(root.resolve("too/deep"))
        val provider = PiSessionProvider({ root }, { "pi" })
        assertEquals(first, provider.scan(emptyMap()).single().transcriptPath)
        Files.delete(first)
        Files.delete(root.resolve("z.jsonl"))
        assertTrue(provider.scan(emptyMap()).isEmpty())
    }

    @Test fun `commands quote exact file executable and fork destination`() {
        val root = tmp.newFolder("space ' dollar $ semicolon ;").toPath()
        val path = fixture(root)
        val executable = "/some path/pi's;$(false)"
        val provider = PiSessionProvider({ root }, { executable })
        val session = provider.scan(emptyMap()).single()
        assertEquals("${ShellQuote.quote(executable)} --session ${ShellQuote.quote(path.toString())}", provider.resumeCommand(session))
        assertEquals("${ShellQuote.quote(executable)} --fork ${ShellQuote.quote(path.toString())} --session-dir ${ShellQuote.quote(root.toString())}", provider.forkCommand(session))
    }
}
