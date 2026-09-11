package com.hedworth.seshlog.restore

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths
import java.time.Instant

class RestoreStateTest : BasePlatformTestCase() {

    fun `test state round-trips through workspace serialisation`() {
        val state = RestoreState.getInstance(project)
        state.liveSessionIds = listOf("one", "two")
        val copy = RestoreState()
        copy.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(
            com.intellij.util.xmlb.XmlSerializer.serialize(state.state), RestoreState.State::class.java))
        assertEquals(listOf("one", "two"), copy.liveSessionIds)
        state.liveSessionIds = emptyList()
    }

    fun `test restored session is remembered before the first live scan and quick shutdown`() {
        val manager = SessionRestoreManager.getInstance(project)
        val restored = Session(
            kind = AgentKind.CLAUDE_CODE, id = "restored", title = "Restored", cwd = Paths.get("/synthetic"), gitBranch = null,
            startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = null,
            isLive = false, livePid = null, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
        )
        var launched = false
        manager.resumeRestored(restored) { launched = true }
        assertTrue(launched)
        assertEquals(listOf("restored"), RestoreState.getInstance(project).liveSessionIds)
        manager.snapshotAtClose(emptyList())
        assertEquals(listOf("restored"), RestoreState.getInstance(project).liveSessionIds)
        RestoreState.getInstance(project).liveSessionIds = emptyList()
    }

    fun `test failed restore does not remember an unlaunched session`() {
        val manager = SessionRestoreManager.getInstance(project)
        val restored = Session(
            kind = AgentKind.CLAUDE_CODE, id = "failed", title = "Failed", cwd = Paths.get("/synthetic"), gitBranch = null,
            startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = null,
            isLive = false, livePid = null, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
        )
        try {
            manager.resumeRestored(restored) { throw IllegalStateException("Synthetic launch failure") }
            fail("Expected launch failure")
        } catch (_: IllegalStateException) {
            assertTrue(RestoreState.getInstance(project).liveSessionIds.isEmpty())
        }
    }

    fun `test recordLaunch remembers the session before the asynchronous live scan`() {
        val manager = SessionRestoreManager.getInstance(project)
        val live = Session(
            kind = AgentKind.CLAUDE_CODE, id = "live-1", title = "Live", cwd = Paths.get("/elsewhere"), gitBranch = null,
            startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = Paths.get("/elsewhere/live-1.jsonl"),
            isLive = true, livePid = 1L, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
        )
        // The application index is still empty, exactly as it is immediately after launching a
        // terminal command. The id must survive until the provider observes its live marker.
        manager.recordLaunch(live)
        assertEquals(listOf("live-1"), RestoreState.getInstance(project).liveSessionIds)
        RestoreState.getInstance(project).liveSessionIds = emptyList()
    }
}
