package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
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
        val trace = SessionDiagnostics.trace("command", "tab" to DiagnosticRef.tab(terminal.content), "engine" to terminal.diagnosticEngine)
        trace.event("command_submitted")
        pending.mark(key)
        try { terminal.execute(command) }
        catch (t: Throwable) { trace.failure("command_failed", t); pending.observed(key); throw t }
    }

    fun observed(tab: Any) = pending.observed(tab)
}
