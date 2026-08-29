package com.hedworth.seshlog.restore

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class RestoreCandidatesTest {
    private val root = Paths.get("/Users/tester/workspace/acme")

    /** Fake process tree: pid → parent pid. Absent pids are dead. */
    private class FakeTree(private val parents: Map<Long, Long>) : ProcessTree {
        override fun isAlive(pid: Long) = pid in parents || pid == 1L
        override fun parentPid(pid: Long) = parents[pid]
    }

    private fun session(id: String, pid: Long?) = Session(
        kind = AgentKind.CLAUDE_CODE, id = id, title = "T$id", cwd = root, gitBranch = null,
        startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = root.resolve("$id.jsonl"),
        isLive = pid != null, livePid = pid, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
    )

    // 100 = this IDE, 200 = another IDE; shells 110/120 belong to this IDE, 210 to the other.
    private val tree = FakeTree(mapOf(
        100L to 1L, 110L to 100L, 120L to 100L, 111L to 110L, 121L to 120L,
        200L to 1L, 210L to 200L, 211L to 210L,
        300L to 1L, // orphan: reparented to launchd
        401L to 400L, // parent 400 is dead
    ))

    @Test
    fun `process tree helpers`() {
        assertTrue(tree.isDescendantOf(111, setOf(110L, 120L)))
        assertTrue(tree.isDescendantOf(111, setOf(100L)))
        assertFalse(tree.isDescendantOf(211, setOf(110L, 120L)))
        assertFalse(tree.isDescendantOf(111, emptySet()))
        assertTrue(tree.isOrphan(300))
        assertTrue(tree.isOrphan(401))
        assertFalse(tree.isOrphan(111))
        assertFalse(tree.isOrphan(999)) // dead, not orphaned
    }

    @Test
    fun `snapshot keeps sessions under our shells or launched by us, not other IDEs`() {
        val ours = session("ours", 111)
        val oursToo = session("ours-too", 121)
        val launchedElsewhere = session("launched", 211)
        val other = session("other", 211)
        val dead = session("dead", null)
        val ids = RestoreCandidates.snapshot(listOf(other, ours, dead, launchedElsewhere, oursToo), setOf("launched", "dead"), setOf(110L, 120L), tree)
        assertEquals(listOf("ours", "launched", "ours-too"), ids)
    }

    @Test
    fun `plan restores dead and orphaned sessions, skips running and unknown ones`() {
        val dead = session("dead", null)
        val orphan = session("orphan", 300)
        val running = session("running", 211)
        val plan = RestoreCandidates.plan(listOf("orphan", "gone", "running", "dead", "orphan"), listOf(dead, orphan, running), tree)
        assertEquals(listOf("orphan", "dead"), plan.restore.map { it.id })
        assertEquals(listOf("orphan"), plan.orphans.map { it.id })
        assertEquals(listOf("running"), plan.running.map { it.id })
    }

    @Test
    fun `plan restores a remembered live session when its provider cannot expose a pid`() {
        val live = session("codex", null).copy(kind = AgentKind.CODEX, isLive = true)
        val plan = RestoreCandidates.plan(listOf("codex"), listOf(live), tree)
        assertEquals(listOf("codex"), plan.restore.map { it.id })
        assertTrue(plan.orphans.isEmpty())
        assertTrue(plan.running.isEmpty())
    }
}
