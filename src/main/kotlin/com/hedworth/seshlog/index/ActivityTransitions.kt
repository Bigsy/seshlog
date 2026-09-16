package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.Session

/** Pure comparison of two scans, for the "your session is waiting" notification. */
object ActivityTransitions {
    private val IDLE = setOf(Activity.WAITING, Activity.INTERRUPTED)

    /**
     * Sessions that were [Activity.WORKING] in [previous] and are now waiting or interrupted, and
     * whose process still runs according to [running]. A session first seen already waiting is not
     * a transition, so opening the IDE never produces a burst of notifications.
     */
    fun turnedWaiting(previous: Map<String, Session>, current: List<Session>, running: (Session) -> Boolean): List<Session> =
        current.filter { session ->
            session.activity in IDLE && previous[session.id]?.activity == Activity.WORKING && running(session)
        }
}
