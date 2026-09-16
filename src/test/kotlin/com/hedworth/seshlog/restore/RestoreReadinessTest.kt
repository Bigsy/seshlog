package com.hedworth.seshlog.restore

import com.hedworth.seshlog.terminal.TerminalState
import org.junit.Assert.*
import org.junit.Test

class RestoreReadinessTest {
    @Test fun `incomplete restoration must not mistake missing tabs for permission to launch`() {
        assertFalse(RestoreTabReadiness.ready<String>(false,
            { fail("Must wait for tab enumeration"); emptyList() },
            { fail("No tab yet"); TerminalState.UNKNOWN },
            { fail("No tab yet") }))
        assertTrue(RestoreTabReadiness.ready<String>(true, { emptyList() },
            { TerminalState.UNKNOWN }, { fail("No tab") }))
    }

    @Test fun `matching lazy shell is shown and reused only after it becomes idle`() {
        var state = TerminalState.UNKNOWN
        var shown = 0
        fun ready() = RestoreTabReadiness.ready(true, { listOf("restored") }, { state }, { shown++ })
        assertFalse(ready())
        assertEquals(1, shown)
        state = TerminalState.IDLE
        assertTrue(ready())
        assertEquals(1, shown)
        state = TerminalState.BUSY
        assertFalse(ready())
    }

    @Test fun `waits for restored tabs and shell readiness before launching once`() {
        val queue = ArrayDeque<() -> Unit>()
        var ready = false
        var launches = 0
        RestoreReadiness(queue::addLast, { false }).await({ ready }, { launches++ }, { fail("Timed out") })
        assertEquals(0, launches)
        queue.removeFirst()()
        assertEquals(0, launches)
        ready = true
        queue.removeFirst()()
        assertEquals(1, launches)
        assertTrue(queue.isEmpty())
    }

    @Test fun `timeout never launches into an unknown tab or creates a duplicate`() {
        val queue = ArrayDeque<() -> Unit>()
        var time = 0L
        var timedOut = false
        RestoreReadiness(queue::addLast, { false }, { time }, 10).await(
            { false }, { fail("Must preserve pending restore") }, { timedOut = true })
        time = 10
        queue.removeFirst()()
        assertTrue(timedOut)
        assertTrue(queue.isEmpty())
    }

    @Test fun `closing project cancels queued restore even if terminal becomes ready`() {
        val queue = ArrayDeque<() -> Unit>()
        var closed = false
        RestoreReadiness(queue::addLast, { closed }).await(
            { closed }, { fail("Project closed") }, { fail("Project closed") })
        closed = true
        queue.removeFirst()()
        assertTrue(queue.isEmpty())
    }
}
