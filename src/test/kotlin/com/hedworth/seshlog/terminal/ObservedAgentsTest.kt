package com.hedworth.seshlog.terminal

import org.junit.Assert.*
import org.junit.Test

class ObservedAgentsTest {
    private class Process(val name: String) { var alive = true }

    /** One liveness check: snapshot on the EDT, check on the worker, reconcile on the EDT. */
    private fun cycle(agents: ObservedAgents<Process>, running: Map<String, Process> = emptyMap()): Set<String> {
        val watched = agents.snapshot()
        val exited = watched.filterValues { !it.alive }.keys
        running.forEach(agents::observe)
        return agents.ended(watched, exited)
    }

    @Test fun `Claude exiting detaches its tab before Codex has a session, keeping it for copy`() {
        val registry = TabRegistry<String>()
        val agents = ObservedAgents<Process>()
        val claude = Process("claude")
        registry.register("claude-session", "tab")
        assertTrue(cycle(agents, mapOf("claude-session" to claude)).isEmpty())
        claude.alive = false
        // Codex now runs in the same shell but has no rollout until its first prompt.
        val ended = cycle(agents)
        assertEquals(setOf("claude-session"), ended)
        val last = ended.single().takeIf { registry.release(it, "tab") }
        assertNull(registry.sessionFor("tab"))
        assertEquals("claude-session", last)
        // Copy: an unidentified Codex must not return Claude's reply; an idle shell may.
        assertNull(SessionProcess.Discovery(emptySet(), hasAgent = true).copySession(last))
        assertEquals(last, SessionProcess.Discovery(emptySet(), hasAgent = false).copySession(last))
        assertTrue("ended once", cycle(agents).isEmpty())
    }

    @Test fun `tabs registered before their agent starts or re-run in time keep their association`() {
        val agents = ObservedAgents<Process>()
        // Resume registers the tab before the agent process exists: never observed, never ended.
        assertTrue(cycle(agents).isEmpty())
        val first = Process("first")
        cycle(agents, mapOf("s" to first))
        first.alive = false
        // The same session resumed again in that tab before the check reconciled.
        assertTrue(cycle(agents, mapOf("s" to Process("second"))).isEmpty())
    }

    @Test fun `release only ends the association it was observed for`() {
        val registry = TabRegistry<String>()
        registry.register("s", "moved-to")
        assertFalse(registry.release("s", "original"))
        assertEquals("s", registry.sessionFor("moved-to"))
        assertTrue(registry.release("s", "moved-to"))
        assertFalse(registry.owns("s"))
    }
}
