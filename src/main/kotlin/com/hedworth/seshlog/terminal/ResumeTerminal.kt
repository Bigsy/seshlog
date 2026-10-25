package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.diagnostics.DiagnosticTrace
/**
 * The resume decision, injectable without constructing the Terminal tool window. [ended] is the tab
 * whose agent last ran the session; it is preferred over [candidates] matched by title.
 */
internal object ResumeTerminal {
    fun <T : Any> resume(
        id: String, existing: T?, candidates: List<T>, owns: (T) -> Boolean,
        idle: (T) -> Boolean, execute: (T) -> Unit, launch: () -> T,
        track: (String, T) -> Unit, focus: (T) -> Unit, ended: T? = null,
        trace: DiagnosticTrace = DiagnosticTrace.NONE,
    ): T {
        if (existing != null) {
            val restart = idle(existing)
            trace.event("resume_decision", "target" to "owned", "execute" to restart)
            if (restart) execute(existing)
            focus(existing)
            return existing
        }
        val reused = (listOfNotNull(ended) + candidates).firstOrNull { !owns(it) && idle(it) }
        trace.event("resume_decision", "target" to when { reused == null -> "new"; reused == ended -> "ended"; else -> "idle_title_match" }, "execute" to true)
        reused?.let(execute)
        val tab = reused ?: launch()
        track(id, tab)
        // A launched tab takes focus itself; a reused one may sit behind the selected tab.
        if (reused != null) focus(tab)
        return tab
    }
}
