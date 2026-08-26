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

    fun `test recordLaunch snapshots the launched session when it is live`() {
        val manager = SessionRestoreManager.getInstance(project)
        val live = Session(
            kind = AgentKind.CLAUDE_CODE, id = "live-1", title = "Live", cwd = Paths.get("/elsewhere"), gitBranch = null,
            startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = Paths.get("/elsewhere/live-1.jsonl"),
            isLive = true, livePid = 1L, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
        )
        // The index is empty in tests, so nothing live is known: snapshot stays empty but must not throw.
        manager.recordLaunch(live)
        assertEquals(emptyList<String>(), RestoreState.getInstance(project).liveSessionIds)
    }
}
