package com.hedworth.seshlog.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.awt.Component
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/** Also notices returning to an IDE window or revealing an already-loaded reply. No I/O. */
internal class CompletionViewObserver(parent: Disposable, private val check: () -> Unit) : Disposable {
    private val timer = Timer(1_000) { check() }
    init { Disposer.register(parent, this); timer.start() }
    override fun dispose() = timer.stop()

    companion object {
        fun isViewed(component: Component): Boolean =
            component.isShowing && component.width > 0 && component.height > 0 &&
                (component !is JComponent || !component.visibleRect.isEmpty) &&
                SwingUtilities.getWindowAncestor(component)?.isActive == true

        fun isLatestReplyVisible(document: ConversationDocument, editor: JTextComponent): Boolean {
            if (document.entries.any { it.truncated || !it.searchable }) return false
            val last = document.entries.indexOfLast {
                !it.isTool && it.message.role == com.hedworth.seshlog.model.Role.ASSISTANT && it.text.isNotBlank()
            }
            val end = document.messageRanges.getOrNull(last)?.last ?: return false
            return end >= 0 && editor.modelToView2D(end)?.intersects(editor.visibleRect) == true
        }
    }
}
