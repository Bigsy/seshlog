package com.hedworth.seshlog.terminal

/**
 * The resume decision, injectable without constructing the Terminal tool window. [ended] is the tab
 * whose agent last ran the session; it is preferred over [candidates] matched by title.
 */
internal object ResumeTerminal {
    fun <T : Any> resume(
        id: String, existing: T?, candidates: List<T>, owns: (T) -> Boolean,
        idle: (T) -> Boolean, execute: (T) -> Unit, launch: () -> T,
        track: (String, T) -> Unit, focus: (T) -> Unit, ended: T? = null,
    ): T {
        if (existing != null) {
            if (idle(existing)) execute(existing)
            focus(existing)
            return existing
        }
        val reused = (listOfNotNull(ended) + candidates).firstOrNull { !owns(it) && idle(it) }?.also(execute)
        val tab = reused ?: launch()
        track(id, tab)
        // A launched tab takes focus itself; a reused one may sit behind the selected tab.
        if (reused != null) focus(tab)
        return tab
    }
}
