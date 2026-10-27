package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.worktree.OpenProjectSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class SwitcherMetadataTest {
    @Test fun `Pi and opencode rows search observed branches without inventing agent location provenance`() {
        for (kind in listOf(AgentKind.PI, AgentKind.OPENCODE)) {
            val original = switchSession()
            val session = original.copy(kind = kind, gitBranch = null, checkout = null, location = null)
            val paths = ResolvedPaths.resolve(setOf(session.cwd), resolver = { it }, repositoryResolver = { null },
                checkoutResolver = { _, _ -> original.checkout })
            val rows = switcherRows(listOf(session), paths, mapOf(original.checkout!!.root to listOf("Engine")),
                emptySet(), emptySet(), { it.title }, { false })
            val match = SwitcherSearch.find(rows, "observed-branch Engine").single()
            assertEquals(original.checkout, match.observedCheckout)
            assertNull(match.session.location)
            assertNull(match.session.checkout)
            assertTrue(match.details().contains("observed-branch"))
        }
    }

    @Test fun `nested non-Git directories choose the most specific open project`() {
        val root = Path.of("/synthetic/project")
        val nested = root.resolve("nested")
        val projects = OpenProjectSnapshot(setOf("parent", "nested", "other"),
            mapOf("parent" to root, "nested" to nested, "other" to Path.of("/other")))
        assertEquals("parent", switcherDestination(root.resolve("src"), null, projects))
        assertEquals("nested", switcherDestination(nested.resolve("src"), null, projects))
        assertNull(switcherDestination(Path.of("/synthetic/project-other/src"), null, projects))
    }

    @Test fun `verified checkout membership prevents matching an ancestor or sibling worktree`() {
        val checkout = switchSession().checkout!!
        val parent = Path.of("/synthetic/engine")
        val projects = OpenProjectSnapshot(setOf("parent", "checkout"),
            mapOf("parent" to parent, "checkout" to checkout.root),
            mapOf("parent" to parent, "checkout" to checkout.root.resolve("src")))
        assertEquals("checkout", switcherDestination(checkout.root.resolve("src/deep"), checkout, projects))
        assertNull(switcherDestination(checkout.root.resolve("elsewhere"), checkout, projects))
        val main = checkout.copy(root = parent, kind = Checkout.Kind.MAIN)
        assertEquals("parent", switcherDestination(parent.resolve("src"), main, projects))
    }

    @Test fun `continuations collapse before hiding and retain independently associated predecessors`() {
        val old = switchSession("old").copy(kind = AgentKind.CLAUDE_CODE, continuationId = "new")
        val new = old.copy(id = "new", continuationId = null)
        fun rows(hidden: Set<String> = emptySet(), associated: Set<String> = emptySet()) =
            switcherRows(listOf(old, new), ResolvedPaths.EMPTY, emptyMap(), associated, emptySet(),
                { it.title }, { it.id in hidden })
        assertEquals(listOf("new"), rows().map { it.session.id })
        assertTrue(SwitcherSearch.find(rows(hidden = setOf("new")), "").isEmpty())
        assertEquals(listOf("old", "new"), rows(associated = setOf("old")).map { it.session.id })
        assertFalse(rows(associated = setOf("old")).first().session.isLive) // Original evidence stays original.
        assertEquals(listOf(old), switcherSessions(listOf(old), emptySet())) // Missing successor.
        assertEquals(2, switcherSessions(listOf(old, new.copy(continuationId = "old")), emptySet()).size)
    }
}
