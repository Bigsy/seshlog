package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class SessionContinuationsTest {
    private fun session(id: String, next: String? = null) = Session(
        AgentKind.CLAUDE_CODE, id, "Same title", Path.of("/project"), "main",
        null, Instant.EPOCH, null, false, null, null, 2, true, continuationId = next,
    )

    @Test
    fun `follows chains regardless of timestamps and retains all earlier history`() {
        val first = session("first", "second").copy(lastActivityAt = Instant.EPOCH.plusSeconds(100))
        val second = session("second", "third")
        val third = session("third")
        val fork = session("fork").copy(forkedFromId = first.id)
        val graph = SessionContinuations(listOf(third, first, fork, second))
        assertEquals(listOf(third, fork), graph.current())
        assertEquals(third, graph.latest(first))
        assertEquals(listOf(second, first), graph.history(third))
    }

    @Test
    fun `missing successor keeps last available session until it is scanned`() {
        val first = session("first", "second")
        val second = session("second", "third")
        assertEquals(listOf(first), SessionContinuations(listOf(first)).current())
        assertEquals(listOf(second), SessionContinuations(listOf(first, second)).current())
    }

    @Test
    fun `cycles and self links cannot hide sessions or loop history traversal`() {
        val a = session("a", "b")
        val b = session("b", "a")
        val self = session("self", "self")
        val all = listOf(a, b, self)
        val graph = SessionContinuations(all)
        assertEquals(all, graph.current())
        assertEquals(listOf(b), graph.history(a))
        assertEquals(emptyList<Session>(), graph.history(self))
    }

    @Test
    fun `links never merge different providers or suppress a live predecessor`() {
        val first = session("first", "second")
        val otherAgent = session("second").copy(kind = AgentKind.CODEX)
        assertEquals(listOf(first, otherAgent), SessionContinuations(listOf(first, otherAgent)).current())
        val live = first.copy(isLive = true, livePid = 123)
        val second = session("second")
        assertEquals(listOf(live, second), SessionContinuations(listOf(live, second)).current())
    }
}
