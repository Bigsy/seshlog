package com.hedworth.seshlog.terminal

/** The resume decision, injectable without constructing the Terminal tool window. */
internal object ResumeTerminal {
    fun <T : Any> resume(
        id: String, existing: T?, candidates: List<T>, owns: (T) -> Boolean,
        idle: (T) -> Boolean, execute: (T) -> Unit, launch: () -> T,
        track: (String, T) -> Unit, focus: (T) -> Unit,
    ): T {
        if (existing != null) {
            if (idle(existing)) execute(existing)
            focus(existing)
            return existing
        }
        val tab = candidates.firstOrNull { !owns(it) && idle(it) }
            ?.also(execute) ?: launch()
        track(id, tab)
        return tab
    }
}
