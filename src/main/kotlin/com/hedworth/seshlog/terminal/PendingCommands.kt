package com.hedworth.seshlog.terminal

import java.util.WeakHashMap

/** Bridges the interval between sending a command and observing its agent process. */
internal class PendingCommands<T : Any>(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val deadlines = WeakHashMap<T, Long>()

    @Synchronized fun mark(tab: T) { deadlines[tab] = clock() + 10_000 }
    @Synchronized fun observed(tab: T) { deadlines.remove(tab) }
    @Synchronized fun contains(tab: T): Boolean {
        val deadline = deadlines[tab] ?: return false
        if (clock() < deadline) return true
        deadlines.remove(tab)
        return false
    }
}

internal object TerminalCommands {
    private val pending = PendingCommands<Any>()
    private fun key(terminal: TerminalHandle): Any = terminal.content ?: terminal
    fun state(terminal: TerminalHandle): TerminalState =
        if (pending.contains(key(terminal))) TerminalState.BUSY else terminal.state()

    fun execute(terminal: TerminalHandle, command: String) {
        val key = key(terminal)
        pending.mark(key)
        try { terminal.execute(command) }
        catch (t: Throwable) { pending.observed(key); throw t }
    }

    fun observed(tab: Any) = pending.observed(tab)
}
