package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class ActivityTransitionsTest {
    private fun session(id: String, activity: Activity, live: Boolean = true) = Session(
        kind = AgentKind.CLAUDE_CODE, id = id, title = id, cwd = Paths.get("/p"), gitBranch = null, startedAt = null,
        lastActivityAt = Instant.EPOCH, transcriptPath = null, isLive = live, livePid = null, promptTitle = null,
        promptCount = 1, hasExplicitTitle = true, activity = activity,
    )

    @Test
    fun `only running sessions that were working and now wait or are interrupted count`() {
        val before = listOf(
            session("a", Activity.WORKING), session("b", Activity.WORKING), session("c", Activity.WAITING),
            session("d", Activity.WORKING), session("e", Activity.UNKNOWN), session("f", Activity.WORKING),
        ).associateBy { it.id }
        val now = listOf(
            session("a", Activity.WAITING),            // finished its turn
            session("b", Activity.INTERRUPTED),        // stopped by the user
            session("c", Activity.WAITING),            // was already waiting
            session("d", Activity.WORKING),            // still busy
            session("e", Activity.WAITING),            // state unknown before: not a transition
            session("f", Activity.WAITING, live = false), // process gone
            session("g", Activity.WAITING),            // first seen
        )
        val turned = ActivityTransitions.turnedWaiting(before, now) { it.isLive }
        assertEquals(listOf("a", "b"), turned.map { it.id })
    }

    @Test
    fun `an empty previous scan never notifies`() {
        val now = listOf(session("a", Activity.WAITING), session("b", Activity.INTERRUPTED))
        assertEquals(emptyList<Session>(), ActivityTransitions.turnedWaiting(emptyMap(), now) { true })
    }
}
