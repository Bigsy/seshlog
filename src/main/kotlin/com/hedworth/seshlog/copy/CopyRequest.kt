package com.hedworth.seshlog.copy

import com.hedworth.seshlog.index.SearchRequestScope
import com.hedworth.seshlog.model.Session

/** A new invocation supersedes pending clipboard writes, even when its target is unknown. */
class CopyRequest(
    private val background: (() -> Unit) -> Unit,
    private val later: (() -> Unit) -> Unit,
    private val disposed: () -> Boolean,
    private val copy: (String) -> Unit,
    private val feedback: (String) -> Unit,
) {
    private val scope = SearchRequestScope()

    fun start(session: Session?, resolve: (() -> Session?)? = null, load: (Session) -> CopyContent) {
        val stale = scope.begin()
        if (disposed()) return
        if (session == null && resolve == null) { feedback("No known session in this context. Select a Seshlog session or focus an associated terminal."); return }
        background {
            var resolved = session
            val result = try {
                if (resolve != null) resolved = resolve()
                resolved?.let(load) ?: CopyContent.Absent("No known session in this context. Select a Seshlog session or focus an associated terminal.")
            } catch (_: Exception) { CopyContent.Failed() }
            later {
                if (!stale() && !disposed()) when (result) {
                    is CopyContent.Found -> if (result.text.isNotBlank()) {
                        copy(result.text)
                        feedback("Copied from ${resolved?.title}." + result.detail)
                    } else feedback("No text to copy.")
                    is CopyContent.Absent -> feedback(result.reason)
                    is CopyContent.Failed -> feedback(result.reason)
                    is CopyContent.Unsupported -> feedback(result.reason)
                }
            }
        }
    }
}

/** Terminal context wins even when it has no known association. Never infer from cwd. */
object CopyTarget {
    /** A keyboard action may expose a wrapper as its context component; use its actual source. */
    fun invocationComponent(context: java.awt.Component?, keySource: java.awt.Component?, focus: java.awt.Component?): java.awt.Component? {
        if (keySource != null) return keySource
        if (context == null) return focus
        return focus?.takeIf { javax.swing.SwingUtilities.isDescendingFrom(it, context) } ?: context
    }

    fun <T> focusedContent(focus: java.awt.Component?, contents: List<T>, component: (T) -> java.awt.Component): T? {
        // Recursive enumeration can include a containing pane as well as its terminal. The
        // nearest ancestor identifies the actual terminal; equal-depth ambiguity stays unknown.
        var ancestor = focus
        while (ancestor != null) {
            val matches = contents.filter { component(it) === ancestor }
            if (matches.isNotEmpty()) return matches.singleOrNull()
            ancestor = ancestor.parent
        }
        return null
    }

    fun resolve(terminal: Boolean, terminalSession: Session?, contextSession: Session?): Session? =
        if (terminal) terminalSession else contextSession
}
