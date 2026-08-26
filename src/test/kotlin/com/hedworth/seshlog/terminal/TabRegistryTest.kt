package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class TabRegistryTest {
    private val root = Paths.get("/Users/tester/workspace/acme")

    /** Fake process tree: pid → parent pid. Absent pids are dead. */
    private class FakeTree(private val parents: Map<Long, Long>) : ProcessTree {
        override fun isAlive(pid: Long) = pid in parents || pid == 1L
        override fun parentPid(pid: Long) = parents[pid]
    }

    /** A tab handle with a mutable title, standing in for a `Content`. */
    private class Tab(val name: String, var title: String) {
        override fun toString() = name
    }

    private fun session(id: String, pid: Long?, title: String = "T$id") = Session(
        kind = AgentKind.CLAUDE_CODE, id = id, title = title, cwd = root, gitBranch = null,
        startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = root.resolve("$id.jsonl"),
        isLive = pid != null, livePid = pid, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
    )

    // 100 = this IDE with shells 110/120; 111 and 121 are claude processes under them (121 via a
    // wrapper 125); 200 = another IDE with shell 210 running claude 211.
    private val tree = FakeTree(mapOf(
        100L to 1L, 110L to 100L, 120L to 100L, 111L to 110L, 125L to 120L, 121L to 125L,
        200L to 1L, 210L to 200L, 211L to 210L,
    ))

    private val tabA = Tab("A", "Ta")
    private val tabB = Tab("B", "Tb")

    @Test
    fun `process tree finds the nearest matching ancestor`() {
        assertEquals(120L, tree.firstAncestorIn(121, setOf(110L, 120L, 100L)))
        assertEquals(100L, tree.firstAncestorIn(121, setOf(100L)))
        assertNull(tree.firstAncestorIn(211, setOf(110L, 120L)))
        assertNull(tree.firstAncestorIn(111, emptySet()))
    }

    @Test
    fun `register, forget and owns`() {
        val registry = TabRegistry<Tab>()
        registry.register("a", tabA)
        registry.register("a2", tabA)
        registry.register("b", tabB)
        assertTrue(registry.owns("a"))
        assertEquals(tabB, registry.tabFor("b"))
        registry.forget(tabA)
        assertFalse(registry.owns("a"))
        assertFalse(registry.owns("a2"))
        assertEquals(setOf("b"), registry.sessionIds)
    }

    @Test
    fun `sessionFor is the reverse lookup of register`() {
        val registry = TabRegistry<Tab>()
        assertNull(registry.sessionFor(tabA))
        registry.register("a", tabA)
        assertEquals("a", registry.sessionFor(tabA))
        assertNull(registry.sessionFor(tabB))
        registry.forget(tabA)
        assertNull(registry.sessionFor(tabA))
    }

    @Test
    fun `sessionFor follows adoption in sync`() {
        val registry = TabRegistry<Tab>()
        registry.sync(listOf(session("a", 111)), mapOf(tabA to 110L, tabB to 120L), tree) { it.title }
        assertEquals("a", registry.sessionFor(tabA))
        assertNull(registry.sessionFor(tabB))
    }

    @Test
    fun `sync adopts live sessions running under an open tab's shell`() {
        val registry = TabRegistry<Tab>()
        val retitles = registry.sync(
            listOf(session("a", 111), session("b", 121), session("other", 211), session("dead", null)),
            mapOf(tabA to 110L, tabB to 120L), tree,
        ) { it.title }
        assertEquals(tabA, registry.tabFor("a"))
        assertEquals(tabB, registry.tabFor("b"))
        assertFalse(registry.owns("other"))
        assertFalse(registry.owns("dead"))
        assertTrue("titles already match, nothing to retitle", retitles.isEmpty())
    }

    @Test
    fun `sync reports a retitle only when the session title differs from the tab title`() {
        val registry = TabRegistry<Tab>()
        registry.register("a", tabA)
        val same = registry.sync(listOf(session("a", 111, title = "Ta")), mapOf(tabA to 110L), tree) { it.title }
        assertTrue(same.isEmpty())

        val changed = registry.sync(listOf(session("a", 111, title = "Fix the flaky test")), mapOf(tabA to 110L), tree) { it.title }
        assertEquals(listOf(TabRegistry.Retitle(tabA, "Fix the flaky test")), changed)

        // Not live yet (just launched): no retitle, entry kept.
        val pending = registry.sync(listOf(session("a", null, title = "Later")), mapOf(tabA to 110L), tree) { it.title }
        assertTrue(pending.isEmpty())
        assertTrue(registry.owns("a"))
    }

    @Test
    fun `sync drops a session that turns out to run under another shell, keeps one when the tab shell is unknown`() {
        val registry = TabRegistry<Tab>()
        registry.register("elsewhere", tabA)
        registry.register("unknown", tabB)
        registry.sync(listOf(session("elsewhere", 211), session("unknown", 211)), mapOf(tabA to 110L, tabB to null), tree) { it.title }
        assertFalse(registry.owns("elsewhere"))
        assertEquals(tabB, registry.tabFor("unknown"))
    }

    @Test
    fun `sync moves a session to the tab its process actually runs in`() {
        val registry = TabRegistry<Tab>()
        registry.register("a", tabB) // stale: the user re-ran it in tab A
        registry.sync(listOf(session("a", 111)), mapOf(tabA to 110L, tabB to 120L), tree) { it.title }
        assertEquals(tabA, registry.tabFor("a"))
    }
}
