package com.hedworth.seshlog.opencode

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

class OpenCodeSessionProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun provider(showArchived: Boolean = false): OpenCodeSessionProvider {
        OpenCodeFixture.create(tmp.root.toPath())
        return OpenCodeSessionProvider({ tmp.root.toPath() }, { "opencode" }, { showArchived })
    }

    @Test
    fun `scan lists top-level unarchived sessions with titles, prompt stats and no transcript file`() {
        val provider = provider()
        assertTrue(provider.isAvailable())
        val sessions = provider.scan(emptyMap())
        assertEquals(setOf("ses_a", "ses_c", "ses_e"), sessions.map { it.id }.toSet())

        val titled = sessions.single { it.id == "ses_a" }
        assertEquals(AgentKind.OPENCODE, titled.kind)
        assertEquals("Add opencode support", titled.title)
        assertTrue(titled.hasExplicitTitle)
        assertEquals("Add opencode as a third provider.", titled.promptTitle)
        // Neither the file-only user message nor the synthetic tool-call echo counts as a prompt.
        assertEquals(2, titled.promptCount)
        assertEquals(Paths.get("/Users/tester/workspace/acme"), titled.cwd)
        assertNull(titled.gitBranch)
        assertEquals(Instant.ofEpochMilli(1788000000000), titled.startedAt)
        assertEquals(Instant.ofEpochMilli(1788000600000), titled.lastActivityAt)
        assertNull(titled.transcriptPath)
        assertFalse(titled.isLive)
        assertNull(titled.livePid)
        assertFalse(provider.detectsLiveSessions)

        val placeholder = sessions.single { it.id == "ses_c" }
        assertFalse(placeholder.hasExplicitTitle)
        assertEquals("What does the watcher do?", placeholder.title)
        assertEquals(1, placeholder.promptCount)

        val empty = sessions.single { it.id == "ses_e" }
        assertFalse(empty.hasExplicitTitle)
        assertEquals("Untitled session", empty.title)
        assertNull(empty.promptTitle)
        assertEquals(0, empty.promptCount)
    }

    @Test
    fun `archived sessions appear only when asked for`() {
        assertEquals(
            setOf("ses_a", "ses_c", "ses_d", "ses_e"),
            provider(showArchived = true).scan(emptyMap()).map { it.id }.toSet(),
        )
    }

    @Test
    fun `prompt stats are reused from the previous scan while time_updated is unchanged`() {
        val provider = provider()
        val first = provider.scan(emptyMap()).single { it.id == "ses_a" }
        val marker = first.copy(promptCount = 99, promptTitle = "cached")
        val unchanged = provider.scan(mapOf("ses_a" to marker)).single { it.id == "ses_a" }
        assertEquals(99, unchanged.promptCount)
        assertEquals("cached", unchanged.promptTitle)
        assertEquals("Add opencode support", unchanged.title) // the title is always read fresh

        val stale = marker.copy(lastActivityAt = Instant.ofEpochMilli(1))
        val recomputed = provider.scan(mapOf("ses_a" to stale)).single { it.id == "ses_a" }
        assertEquals(2, recomputed.promptCount)
    }

    @Test
    fun `conversation text and last messages are ordered per message with roles mapped`() {
        val provider = provider()
        val session = provider.scan(emptyMap()).single { it.id == "ses_a" }
        assertEquals(
            listOf(
                "Add opencode as a third provider.\nKeep the SQL small.",
                "Starting with the schema.",
                "Also hide archived sessions.",
                "Archived sessions are hidden by default.",
            ),
            provider.conversationText(session),
        )
        assertTrue(provider.conversationText(session).none { it.contains("Called the Read tool") })
        val last = provider.lastMessages(session, 2)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), last.map { it.role })
        // The synthetic file echo on the same message is left out, and the synthetic-only message never appears.
        assertEquals(listOf("Also hide archived sessions.", "Archived sessions are hidden by default."), last.map { it.text })
        // The message's own time, not its part's (which the fixture sets 50s later).
        assertEquals(Instant.ofEpochMilli(1788000300000), last[1].timestamp)
        // Four messages have visible text: the blank-only assistant turn is not one of them.
        assertEquals(4, provider.lastMessages(session, 50).size)
        assertEquals(emptyList<Any>(), provider.lastMessages(session, 0))
        assertEquals(session.lastActivityAt, provider.contentStamp(session))
    }

    @Test
    fun `resume and fork commands quote executable and id`() {
        val provider = OpenCodeSessionProvider({ tmp.root.toPath() }, { "/opt/open code/opencode" })
        val session = Session(
            kind = AgentKind.OPENCODE, id = "ses;echo nope", title = "t", cwd = tmp.root.toPath(), gitBranch = null,
            startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = null, isLive = false, livePid = null,
            promptTitle = null, promptCount = 1, hasExplicitTitle = false,
        )
        assertEquals("'/opt/open code/opencode' --session 'ses;echo nope'", provider.resumeCommand(session))
        assertEquals("'/opt/open code/opencode' --session 'ses;echo nope' --fork", provider.forkCommand(session))
    }

    @Test
    fun `missing or unreadable database is unavailable and scans to nothing without throwing`() {
        val root = tmp.root.toPath()
        val provider = OpenCodeSessionProvider({ root }, { "opencode" })
        assertFalse(provider.isAvailable())
        assertEquals(emptyList<Session>(), provider.scan(emptyMap()))
        // The database and its WAL, never the data directory: reading the database touches
        // opencode.db-shm, so a directory watch would make every scan trigger the next one.
        assertEquals(listOf(root.resolve("opencode.db"), root.resolve("opencode.db-wal")), provider.watchRoots())

        Files.writeString(root.resolve(OpenCodeSessionProvider.DATABASE_FILE), "this is not a database")
        assertTrue(provider.isAvailable())
        assertEquals(emptyList<Session>(), provider.scan(emptyMap()))
    }

    @Test
    fun `placeholder titles are recognised`() {
        assertTrue(OpenCodeSessionProvider.isPlaceholderTitle("New session - 2026-09-02T09:00:00.000Z"))
        assertFalse(OpenCodeSessionProvider.isPlaceholderTitle("New session handling"))
    }
}
