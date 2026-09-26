package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.Session

/** Metadata only. First observation establishes a baseline, never an unread backlog. */
class SessionAttention(val unread: MutableMap<String, Completion> = linkedMapOf()) {
    data class Completion(var activityAt: String = "", var contentAt: String = "")
    private var previous = emptyMap<String, Session>()
    private var previouslyRunning = emptySet<String>()

    fun observe(sessions: List<Session>, running: Set<String>) {
        for (session in sessions) {
            val before = previous[session.id]
            if (session.activity == Activity.WORKING || session.activity == Activity.INTERRUPTED) {
                unread.remove(session.id)
            } else if (session.activity == Activity.WAITING && before != null &&
                (session.isLive || session.id in running || before.isLive || session.id in previouslyRunning) &&
                (before.activity == Activity.WORKING ||
                    (before.activity == Activity.WAITING && before.activitySince != null &&
                        session.activitySince != null && session.activitySince > before.activitySince))) {
                unread[session.id] = Completion(revision(session), session.lastActivityAt.toString())
            }
        }
        previous = sessions.associateBy { it.id }
        previouslyRunning = running.toSet()
    }

    /** Capture before loading a reply; a late delivery must not acknowledge a newer completion. */
    fun receipt(session: Session): Completion? = unread[session.id]?.takeIf {
        it.activityAt == revision(session) ||
            // Claude's live status disappears on exit, but its saved reply can still be viewed.
            (session.activity == Activity.UNKNOWN && it.contentAt == session.lastActivityAt.toString())
    }?.copy()

    fun viewed(id: String, receipt: Completion?): Boolean =
        receipt != null && unread.remove(id, receipt)

    companion object {
        private fun revision(session: Session) = (session.activitySince ?: session.lastActivityAt).toString()

        /** Follow the displayed tree order, wrap, and never change the user's filters. */
        fun next(sessions: List<Session>, unread: Set<String>, current: String?): Session? {
            val start = sessions.indexOfFirst { it.id == current }
            return (1..sessions.size).asSequence().map { sessions[(start + it) % sessions.size] }
                .firstOrNull { it.id in unread }
        }

        fun summary(sessions: List<Session>, running: Set<String>, unread: Set<String>): String {
            val working = sessions.count { it.activity == Activity.WORKING && (it.isLive || it.id in running) }
            val unseen = sessions.count { it.id in unread }
            return listOfNotNull(if (working > 0) "$working working" else null,
                if (unseen > 0) "$unseen unread" else null).joinToString(" · ")
        }
    }
}
