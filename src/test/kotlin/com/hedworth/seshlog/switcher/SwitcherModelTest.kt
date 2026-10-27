package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

internal fun switchSession(id: String = "one", seconds: Long = 1): Session = Session(
    AgentKind.CODEX, id, "Fix parser", Path.of("/synthetic/engine/topic/src"), "recorded-branch", null,
    Instant.ofEpochSecond(seconds), null, false, null, null, 1, true,
    location = SessionLocation(LocationSource.CODEX_CONTEXT), recordedDirectoryAvailable = true,
    checkout = Checkout(Path.of("/synthetic/engine/topic"), Path.of("/synthetic/engine/.git/worktrees/topic"),
        Path.of("/synthetic/engine/.git"), Checkout.Kind.LINKED, "observed-branch", null, directoryKey = "original"))

class SwitcherModelTest {
    @Test fun `metadata search covers both titles project aliases paths and both branches across agents`() {
        val row = SwitcherRow(switchSession(), title = "Local label", projectNames = listOf("Engine Project"))
        for (query in listOf("local LABEL", "parser", "Engine Project", "topic/src", "recorded-branch", "observed-branch", "label engine observed"))
            assertEquals(query, listOf(row), SwitcherSearch.find(listOf(row), query))
        assertTrue(SwitcherSearch.find(listOf(row), "label absent").isEmpty())
        val pi = SwitcherRow(switchSession().copy(kind = AgentKind.PI, cwd = Path.of("/elsewhere")))
        assertEquals(2, SwitcherSearch.find(listOf(pi, row), "parser").size)
        assertEquals(listOf(pi), SwitcherSearch.find(listOf(row.copy(hidden = true), pi), ""))
        assertTrue(SwitcherSearch.find(listOf(row), "x".repeat(SwitcherSearch.MAX_QUERY + 1)).isEmpty())
    }

    @Test fun `recent order is deterministic and refresh preserves selection and existing order`() {
        val old = SwitcherRow(switchSession("old", 1))
        val recent = SwitcherRow(switchSession("recent", 2))
        val state = SwitcherResults()
        state.apply(state.begin(""), SwitcherSearch.find(listOf(old, recent), ""))
        assertEquals(listOf(recent.key, old.key), state.rows.map { it.key })
        state.selected = old.key
        val updated = old.copy(session = old.session.copy(lastActivityAt = Instant.ofEpochSecond(100)))
        val new = SwitcherRow(switchSession("new", 200))
        state.apply(state.begin(""), SwitcherSearch.find(listOf(updated, recent, new), ""))
        assertEquals(listOf(recent.key, old.key, new.key), state.rows.map { it.key })
        assertEquals(old.key, state.selected)
        assertEquals(updated, state.rows[1])
        state.apply(state.begin(""), listOf(recent, new))
        assertNull(state.selected) // A disappearing selected row must not silently select a different agent.
        state.apply(state.begin("parser"), listOf(new, recent))
        assertEquals(new.key, state.selected)
    }

    @Test fun `stale queries metadata refreshes and closed popups cannot publish`() {
        val state = SwitcherResults()
        val a = state.begin("a")
        val b = state.begin("b")
        assertFalse(state.apply(a, listOf(SwitcherRow(switchSession()))))
        assertTrue(state.pending)
        assertTrue(state.apply(b, emptyList()))
        val beforeHide = state.begin("b")
        val hidden = state.begin("b")
        assertFalse(state.apply(beforeHide, listOf(SwitcherRow(switchSession()))))
        state.close()
        assertFalse(state.apply(hidden, emptyList()))
    }

    @Test fun `limit applies after filtering and provider identity retains the correct selected row`() {
        val rows = (0..300).map { SwitcherRow(switchSession("id-$it", it.toLong()), title = if (it == 0) "unique-oldest" else "title $it") }
        assertEquals(rows.first(), SwitcherSearch.find(rows, "unique-oldest").single())
        val state = SwitcherResults()
        state.apply(state.begin(""), SwitcherSearch.find(rows, ""))
        assertEquals(SwitcherSearch.LIMIT, state.rows.size)
        val codex = SwitcherRow(switchSession("same"))
        val pi = codex.copy(session = codex.session.copy(kind = AgentKind.PI))
        state.apply(state.begin("same"), listOf(codex, pi))
        state.selected = pi.key
        state.apply(state.begin("same"), listOf(pi, codex))
        assertEquals(pi.key, state.selected)
        assertEquals(listOf(codex, pi), state.rows)
    }

    @Test fun `availability distinguishes stopped uncertain missing external live and associated sessions`() {
        val row = SwitcherRow(switchSession())
        assertTrue(row.canResume)
        assertFalse(row.copy(associated = true).canResume)
        assertTrue(row.copy(associated = true).status.contains("Enter"))
        assertFalse(row.copy(session = row.session.copy(isLive = true)).canResume)
        assertTrue(row.copy(session = row.session.copy(isLive = true)).status.contains("not associated"))
        assertFalse(row.copy(session = row.session.copy(recordedDirectoryAvailable = false)).canResume)
        assertFalse(row.copy(session = row.session.copy(location = row.session.location!!.copy(uncertain = true))).canResume)
        assertFalse(row.copy(session = row.session.copy(checkout = row.session.checkout!!.unavailable("removed"))).canResume)
        val unknown = row.copy(session = row.session.copy(checkout = null, location = null))
        assertEquals("Checkout unverified", unknown.locationProblem)
        assertTrue(unknown.canResume) // Validated on the worker on explicit Resume.
        val attention = row.copy(associated = true, unread = true).attention(Instant.EPOCH)
        assertTrue(attention.contains("unread")); assertTrue(attention.contains("live"))
        assertTrue(row.details().contains("observed-branch"))
    }

    @Test fun `first scan after an empty index selects the first recent session`() {
        val state = SwitcherResults()
        state.apply(state.begin(""), emptyList())
        val row = SwitcherRow(switchSession())
        state.apply(state.begin(""), listOf(row))
        assertEquals(row.key, state.selected)
    }
}
