package com.hedworth.seshlog.terminal

import org.junit.Assert.*
import org.junit.Test

class ResumeTerminalTest {
    private class Tab(val title: String = "Same title") { var commands = 0 }

    @Test fun `two same-title resumes preserve both registrations and rapid repeat sends once`() {
        val registry = TabRegistry<Tab>()
        val pending = PendingCommands<Tab> { 0L }
        val tabs = mutableListOf(Tab())
        fun send(tab: Tab) { pending.mark(tab); tab.commands++ }
        fun resume(id: String) = ResumeTerminal.resume(id, registry.tabFor(id), tabs,
            owns = { registry.sessionFor(it) != null }, idle = { !pending.contains(it) },
            execute = ::send, launch = { Tab().also { tabs += it; send(it) } },
            track = registry::register, focus = {})
        val first = resume("one")
        val second = resume("two")
        assertNotSame(first, second)
        assertSame(first, resume("one"))
        assertEquals(setOf("one", "two"), registry.sessionIds)
        assertEquals(1, first.commands)
        assertEquals(1, second.commands)
    }

    @Test fun `pending ends on observed process or after timeout`() {
        var now = 0L
        val pending = PendingCommands<Tab> { now }
        val tab = Tab()
        pending.mark(tab)
        now = 9_999
        assertTrue(pending.contains(tab))
        now = 10_000
        assertFalse(pending.contains(tab))
        pending.mark(tab)
        pending.observed(tab)
        assertFalse(pending.contains(tab))
    }

    @Test fun `the tab whose agent last ran the session is reused even after its title changed`() {
        val registry = TabRegistry<Tab>()
        val ended = Tab("Title before the agent renamed the session")
        val focused = ArrayList<Tab>()
        val resumed = ResumeTerminal.resume("s", null, emptyList(), ended = ended,
            owns = { registry.sessionFor(it) != null }, idle = { true }, execute = { it.commands++ },
            launch = { fail("opened a second tab"); Tab() }, track = registry::register, focus = { focused += it })
        assertSame(ended, resumed)
        assertEquals(1, ended.commands)
        assertSame(ended, registry.tabFor("s"))
        assertEquals("A reused tab is brought to the front", listOf(ended), focused)
        // Busy with something else, or since taken by another session: never borrowed.
        val busy = ResumeTerminal.resume("t", null, emptyList(), ended = Tab(),
            owns = { false }, idle = { false }, execute = { fail("typed into a busy tab") },
            launch = { Tab() }, track = registry::register, focus = {})
        assertEquals(0, busy.commands)
        val taken = Tab().also { registry.register("other", it) }
        assertNotSame(taken, ResumeTerminal.resume("u", null, emptyList(), ended = taken,
            owns = { registry.sessionFor(it) != null }, idle = { true }, execute = { fail("borrowed owned tab") },
            launch = { Tab() }, track = registry::register, focus = {}))
    }

    @Test fun `owned idle tab is never borrowed for another session`() {
        val registry = TabRegistry<Tab>()
        val first = Tab()
        registry.register("first", first)
        val second = ResumeTerminal.resume("second", null, listOf(first),
            owns = { registry.sessionFor(it) != null }, idle = { true }, execute = { fail("borrowed owned tab") },
            launch = { Tab() }, track = registry::register, focus = {})
        assertSame(first, registry.tabFor("first"))
        assertSame(second, registry.tabFor("second"))
    }
}
