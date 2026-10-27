package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.terminal.TabRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class SwitcherRequestTest {
    private class Harness {
        val selected = switchSession()
        var current: Session? = selected
        var hidden = false
        var ready = true
        var active = true
        var problem: String? = null
        val workers = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val effects = mutableListOf<Session>()
        val reports = mutableListOf<String>()
        val request = SwitcherRequest({ current }, { hidden }, { workers += it }, { ui += it }, { active }, { reports += it })
        fun start() = request.run(selected, { problem }, { ready }, { effects += it })
        fun deliver() { while (workers.isNotEmpty()) workers.removeFirst()(); while (ui.isNotEmpty()) ui.removeFirst()() }
    }

    @Test fun `effects run only after worker evidence and UI revalidation`() {
        val h = Harness()
        h.start()
        assertTrue(h.effects.isEmpty())
        h.workers.removeFirst()()
        assertTrue(h.effects.isEmpty())
        h.current = h.selected.copy(title = "new title")
        h.deliver()
        assertEquals(listOf(h.current), h.effects)
    }

    @Test fun `changed source provider location checkout and disappeared session reject delayed navigation`() {
        val selected = switchSession()
        val changed = listOf(null, selected.copy(kind = AgentKind.PI), selected.copy(cwd = Path.of("/other")),
            selected.copy(transcriptPath = Path.of("/other.jsonl")),
            selected.copy(location = selected.location!!.copy(uncertain = true)),
            selected.copy(checkout = selected.checkout!!.copy(directoryKey = "replacement")),
            selected.copy(checkout = selected.checkout.unavailable("gone")),
            selected.copy(recordedDirectoryAvailable = false))
        for (replacement in changed) for (stage in 0..2) {
            val h = Harness()
            if (stage == 0) h.current = replacement
            h.start()
            if (stage == 1) h.current = replacement
            if (stage == 2) { h.workers.removeFirst()(); h.current = replacement }
            h.deliver()
            assertTrue(h.effects.isEmpty())
            assertEquals(1, h.reports.size)
        }
    }

    @Test fun `hide tab reassignment checkout failure and popup disposal block queued effects`() {
        for (case in 0..3) {
            val h = Harness()
            if (case == 2) h.problem = "Checkout replaced"
            h.start(); h.workers.removeFirst()()
            when (case) { 0 -> h.hidden = true; 1 -> h.ready = false; 3 -> h.active = false }
            h.deliver()
            assertTrue(h.effects.isEmpty())
            assertEquals(if (case == 3) 0 else 1, h.reports.size)
        }
    }

    @Test fun `terminal generation rejects same-tab same-id reuse even after identity returns`() {
        val registry = TabRegistry<String>()
        registry.register("one", "tab")
        val expected = registry.association("one")!!
        assertTrue(registry.matches(expected))
        registry.register("two", "tab")
        assertFalse(registry.matches(expected))
        registry.register("one", "tab")
        assertFalse(registry.matches(expected))
        val fresh = registry.association("one")!!
        registry.forget("tab")
        assertFalse(registry.matches(fresh))
    }
}
