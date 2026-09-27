package com.hedworth.seshlog.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionWatcherTest {
    private val roots = listOf("/home/u/.claude/projects", "/home/u/.local/share/opencode/opencode.db")

    @Test
    fun `a watched file and anything inside a watched directory is a match`() {
        assertTrue(SessionWatcher.isUnderRoots("/home/u/.claude/projects", roots))
        assertTrue(SessionWatcher.isUnderRoots("/home/u/.claude/projects/-home-u-work/abc.jsonl", roots))
        assertTrue(SessionWatcher.isUnderRoots("/home/u/.local/share/opencode/opencode.db", roots))
    }

    @Test
    fun `a sibling that only shares a root's prefix is not a match`() {
        // opencode.db-shm is the wal-index every SQLite reader touches, this plugin's own scan
        // included: matching it would make each scan schedule the next one forever.
        assertFalse(SessionWatcher.isUnderRoots("/home/u/.local/share/opencode/opencode.db-shm", roots))
        assertFalse(SessionWatcher.isUnderRoots("/home/u/.local/share/opencode/opencode.db-wal", roots))
        assertFalse(SessionWatcher.isUnderRoots("/home/u/.claude/projects-backup/abc.jsonl", roots))
        assertFalse(SessionWatcher.isUnderRoots("/home/u/.codex/sessions/abc.jsonl", roots))
    }

    @Test
    fun `ignored provider subpaths do not trigger a watch`() {
        val root = "/home/u/.claude/projects"
        assertTrue(SessionWatcher.isRelevantPath("$root/work/session/subagents/child.jsonl", listOf(root)).not())
        assertTrue(SessionWatcher.isRelevantPath("$root/memory/foo.jsonl", listOf(root)).not())
        assertTrue(SessionWatcher.isRelevantPath("$root/work/tool-results/result.json", listOf(root)).not())
        assertTrue(SessionWatcher.isRelevantPath("$root/work/session.jsonl", listOf(root)))
    }

    @Test
    fun `ignored names in a root parent do not suppress events below that root`() {
        val root = "/home/u/memory/projects"
        assertTrue(SessionWatcher.isRelevantPath("$root/session.jsonl", listOf(root)))
    }

    @Test
    fun `continuous events reach the maximum wait and fire repeatedly`() {
        var now = 0L
        val policy = SessionWatcherDebouncePolicy(clock = { now })
        var fires = 0
        for (tick in 0..22) {
            now = tick * 500L
            if (policy.consumeIfDue()) fires++
            policy.event()
        }
        assertTrue("events every 500 ms must not postpone forever", fires >= 2)
    }

    @Test
    fun `a quiet burst fires once after the debounce`() {
        var now = 0L
        val policy = SessionWatcherDebouncePolicy(clock = { now })
        policy.event()
        now = 1_499
        assertFalse(policy.consumeIfDue())
        now = 1_500
        assertTrue(policy.consumeIfDue())
        now = 10_000
        assertFalse(policy.consumeIfDue())
    }

    @Test
    fun `concurrent starts leave only the latest watch roots installed`() {
        val installed = mutableSetOf<String>()
        val registration = WatchRootRegistration<Int>(
            replace = { _, paths ->
                synchronized(installed) {
                    installed.clear()
                    installed += paths
                }
                setOf(paths.hashCode())
            },
            remove = {},
        )
        val first = registration.request(setOf("first"))
        val last = registration.request(setOf("last"))
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstDone = executor.submit<Boolean> { gate.await(); registration.install(first) }
            val lastDone = executor.submit<Boolean> { gate.await(); registration.install(last) }
            gate.countDown()
            assertFalse(firstDone.get(5, TimeUnit.SECONDS))
            assertTrue(lastDone.get(5, TimeUnit.SECONDS))
            assertEquals(setOf("last"), synchronized(installed) { installed.toSet() })
        } finally {
            executor.shutdownNow()
        }
    }
}
