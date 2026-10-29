package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Session

/** Main search ordering, applied during retrieval before the presentation limit. */
enum class SearchSort(val label: String) {
    BEST_MATCH("Best match"),
    RECENTLY_ACTIVE("Recently active");

    val comparator: Comparator<SearchHit> get() = when (this) {
        BEST_MATCH -> compareByDescending<SearchHit> { it.exact }
            .thenByDescending { it.score }
            .thenByDescending { it.session.lastActivityAt }
            .thenBy { it.session.kind.name }.thenBy { it.session.id }
        RECENTLY_ACTIVE -> Comparator { a, b -> recentSessions.compare(a.session, b.session) }
    }

    companion object {
        val recentSessions: Comparator<Session> = compareByDescending<Session> { it.lastActivityAt }
            .thenBy { it.kind.name }.thenBy { it.id }
    }
}
