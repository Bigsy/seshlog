package com.hedworth.seshlog.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
