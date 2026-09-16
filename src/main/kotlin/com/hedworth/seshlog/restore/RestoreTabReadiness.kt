package com.hedworth.seshlog.restore

import com.hedworth.seshlog.terminal.TerminalState

/** Do not interpret an incomplete tab list as permission to create another terminal. */
internal object RestoreTabReadiness {
    fun <T> ready(restored: Boolean, matching: () -> List<T>, state: (T) -> TerminalState, show: (T) -> Unit): Boolean {
        if (!restored) return false
        val tabs = matching()
        if (tabs.isEmpty() || tabs.any { state(it) == TerminalState.IDLE }) return true
        tabs.firstOrNull { state(it) == TerminalState.UNKNOWN }?.let(show)
        return false
    }
}
