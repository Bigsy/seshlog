package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.model.Session

internal fun inboxSessions(sessions: List<Session>, unread: Set<String>, hidden: Set<String>): List<Session> =
    sessions.filter { it.id in unread && it.id !in hidden }
        .sortedWith(compareByDescending<Session> { it.lastActivityAt }.thenBy { it.kind.name }.thenBy { it.id })

/** A receipt belongs to the delivered preview, never to whichever reply happens to be latest later. */
internal class InboxPreviewState {
    data class Request(val generation: Long, val session: Session, val receipt: SessionAttention.Completion?)
    private var generation = 0L
    var delivered: Request? = null
        private set
    fun begin(session: Session, receipt: SessionAttention.Completion?): Request {
        delivered = null
        return Request(++generation, session, receipt?.copy())
    }
    fun clear() { generation++; delivered = null }
    fun accept(request: Request, current: Session?): Boolean {
        if (request.generation != generation || current?.id != request.session.id ||
            current.kind != request.session.kind || current.lastActivityAt != request.session.lastActivityAt ||
            current.activitySince != request.session.activitySince || current.transcriptPath != request.session.transcriptPath) return false
        delivered = request
        return true
    }
    companion object {
        fun text(messages: List<ConversationMessage>): String? = messages.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }
            ?.text?.let { if (it.length <= 6000) it else it.take(6000) + "\n\n[Preview shortened — open the conversation for more]" }
    }
}
