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

    fun start(session: Session?, load: (Session) -> CopyContent) {
        val stale = scope.begin()
        if (disposed()) return
        if (session == null) { feedback("No known session in this context. Select a Seshlog session or focus an associated terminal."); return }
        background {
            val result = try { load(session) } catch (_: Exception) { CopyContent.Failed() }
            later {
                if (!stale() && !disposed()) when (result) {
                    is CopyContent.Found -> if (result.text.isNotBlank()) {
                        copy(result.text)
                        feedback("Copied from ${session.title}." + result.detail)
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
    fun <T> focusedContent(focus: java.awt.Component?, contents: List<T>, component: (T) -> java.awt.Component): T? =
        if (focus == null) null else contents.filter {
            javax.swing.SwingUtilities.isDescendingFrom(focus, component(it))
        }.singleOrNull()

    fun resolve(terminal: Boolean, terminalSession: Session?, contextSession: Session?): Session? =
        if (terminal) terminalSession else contextSession
}
