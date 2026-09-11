package com.hedworth.seshlog.restore

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class RestoreSnapshotCollectorTest {
    private val workers = ArrayDeque<() -> Unit>()
    private val deliveries = ArrayDeque<() -> Unit>()
    private var phase = "ui"
    private var probes = 0
    private var saved = listOf("previous")
    private val tree = object : ProcessTree {
        override fun isAlive(pid: Long) = true
        override fun parentPid(pid: Long): Long? {
            assertEquals("worker", phase)
            return if (pid == 111L) 110L else null
        }
    }
    private val collector = RestoreSnapshotCollector<String>(workers::addLast, deliveries::addLast, {
        assertEquals("Terminal state must only be queried on the worker", "worker", phase)
        probes++
        it == "busy"
    }, tree)
    private val apply: (List<String>) -> Unit = {
        assertEquals("ui", phase)
        saved = it
    }

    private fun worker() {
        phase = "worker"
        try { workers.removeFirst()() } finally { phase = "ui" }
    }

    private fun deliver() { deliveries.removeFirst()() }

    private fun live(id: String, pid: Long) = Session(
        kind = AgentKind.CLAUDE_CODE, id = id, title = id, cwd = Paths.get("/synthetic"), gitBranch = null,
        startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = null,
        isLive = true, livePid = pid, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
    )

    @Test fun `process and terminal checks run in worker and persistence waits for UI delivery`() {
        collector.collect(listOf(live("ours", 111), live("other", 222)), emptySet(), listOf("starting"),
            setOf(110), mapOf("busy-session" to "busy", "idle-session" to "idle"), apply)
        assertEquals(0, probes)
        assertEquals(listOf("previous"), saved)
        worker()
        assertEquals(2, probes)
        assertEquals(listOf("previous"), saved)
        deliver()
        assertEquals(listOf("ours", "busy-session", "starting"), saved)
    }

    @Test fun `older delivery cannot replace newer scan`() {
        collector.collect(emptyList(), emptySet(), listOf("old"), emptySet(), emptyMap(), apply)
        worker()
        collector.collect(emptyList(), emptySet(), listOf("new"), emptySet(), emptyMap(), apply)
        worker()
        deliver()
        assertEquals(listOf("previous"), saved)
        deliver()
        assertEquals(listOf("new"), saved)
    }

    @Test fun `launch or stop invalidates an in flight snapshot`() {
        collector.collect(emptyList(), emptySet(), emptyList(), emptySet(), emptyMap(), apply)
        worker()
        saved = listOf("just-launched")
        collector.invalidate()
        deliver()
        assertEquals(listOf("just-launched"), saved)

        collector.collect(emptyList(), emptySet(), listOf("just-launched"), emptySet(), emptyMap(), apply)
        worker()
        saved = emptyList()
        collector.invalidate()
        deliver()
        assertTrue(saved.isEmpty())
    }

    @Test fun `shutdown preserves snapshot and cancels queued terminal probes`() {
        collector.collect(emptyList(), emptySet(), emptyList(), emptySet(), mapOf("tab" to "busy"), apply)
        collector.dispose()
        worker()
        assertEquals(0, probes)
        assertTrue(deliveries.isEmpty())
        assertEquals(listOf("previous"), saved)
    }

    @Test fun `shutdown prevents finished worker from clearing last known snapshot`() {
        collector.collect(emptyList(), emptySet(), emptyList(), emptySet(), emptyMap(), apply)
        worker()
        collector.dispose()
        deliver()
        assertEquals(listOf("previous"), saved)
    }
}
