package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.index.lexical.SearchSessionKey
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.model.sameLocationEvidence
import com.hedworth.seshlog.ui.ActivityLabel
import java.time.Instant
import java.util.Locale

/** Captured on EDT; filtering/rendering never resolves paths or reads mutable IDE state. */
internal data class SwitcherRow(
    val session: Session,
    val title: String = session.title,
    val projectNames: List<String> = emptyList(),
    val hidden: Boolean = false,
    val associated: Boolean = false,
    val unread: Boolean = false,
    val observedCheckout: Checkout? = null,
) {
    val key = SearchSessionKey(session.kind, session.id)
    val checkout get() = session.checkout ?: observedCheckout
    val running get() = associated || session.isLive
    val locationProblem: String? get() = when {
        session.location?.uncertain == true -> "Location uncertain"
        session.recordedDirectoryAvailable == false || checkout?.available == false -> "Checkout unavailable"
        session.location == null && checkout == null -> "Checkout unverified"
        else -> null
    }
    val canResume get() = !running && locationProblem != "Location uncertain" && locationProblem != "Checkout unavailable"
    val status: String get() = when {
        associated -> "Enter: focus terminal"
        session.isLive -> "Running — terminal not associated"
        canResume -> "No tracked terminal — Resume explicitly"
        else -> "${locationProblem} — Resume unavailable"
    }
    fun attention(now: Instant): String = buildList {
        if (unread) add("● unread")
        if (running) add(ActivityLabel.badge(session.activity, session.activitySince, now)) else add("no tracked terminal")
        locationProblem?.let(::add)
    }.joinToString(" · ")
    fun details(): String = buildList {
        add(session.kind.displayName)
        addAll(projectNames)
        checkout?.branchLabel?.let(::add) ?: session.displayBranch?.let(::add)
        add(session.cwd.toString())
    }.joinToString(" · ")
}

internal object SwitcherSearch {
    const val LIMIT = 200
    const val MAX_QUERY = 4096
    fun find(rows: List<SwitcherRow>, query: String): List<SwitcherRow> {
        if (query.length > MAX_QUERY) return emptyList()
        val terms = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        return rows.asSequence().filterNot { it.hidden }.filter { row ->
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val s = row.session
            val fields = listOf(row.title, s.title, s.cwd.toString(), row.checkout?.root?.toString().orEmpty(),
                s.gitBranch.orEmpty(), row.checkout?.branch.orEmpty()) + row.projectNames
            val folded = fields.map { it.lowercase(Locale.ROOT) }
            terms.all { term -> folded.any { term in it } }
        }.sortedWith(compareByDescending<SwitcherRow> { it.session.lastActivityAt }
            .thenBy { it.session.kind.name }.thenBy { it.session.id }).toList()
    }
}

/** EDT-owned delivery/selection state. Existing matches stay put when activity timestamps change. */
internal class SwitcherResults {
    private var generation = 0L
    private var query = ""
    private var appliedQuery: String? = null
    private var closed = false
    var pending = false; private set
    var rows: List<SwitcherRow> = emptyList(); private set
    var selected: SearchSessionKey? = null
    fun begin(text: String): Long {
        query = text
        pending = true
        return ++generation
    }
    fun apply(ticket: Long, matches: List<SwitcherRow>): Boolean {
        if (closed || ticket != generation) return false
        val sameQuery = query == appliedQuery
        val wasEmpty = rows.isEmpty()
        val byKey = matches.associateBy { it.key }
        val eligible = matches.take(SwitcherSearch.LIMIT).mapTo(LinkedHashSet()) { it.key }
        // Keep the user's selected match even if its recency falls outside the current top 200.
        if (sameQuery && selected in byKey && selected !in eligible) {
            if (eligible.size == SwitcherSearch.LIMIT) eligible.remove(eligible.last())
            eligible.add(selected!!)
        }
        val retained = if (sameQuery) rows.mapNotNull { byKey[it.key]?.takeIf { row -> row.key in eligible } } else emptyList()
        val retainedKeys = retained.mapTo(HashSet()) { it.key }
        rows = (retained + matches.filter { it.key in eligible && it.key !in retainedKeys }).take(SwitcherSearch.LIMIT)
        selected = selected?.takeIf { key -> rows.any { it.key == key } }
            ?: if (!sameQuery || wasEmpty) rows.firstOrNull()?.key else null
        appliedQuery = query
        pending = false
        return true
    }
    fun close() { closed = true; generation++; pending = true }
}

/** Metadata identity, deliberately excluding title/activity changes while a request is in flight. */
internal fun sameSwitchTarget(selected: Session, current: Session?): Boolean = current != null &&
    selected.kind == current.kind && selected.id == current.id && selected.cwd == current.cwd &&
    selected.transcriptPath == current.transcriptPath && selected.startedAt == current.startedAt &&
    sameLocationEvidence(selected.location, current.location) && selected.recordedDirectoryAvailable == current.recordedDirectoryAvailable &&
    when (val checkout = selected.checkout) {
        null -> current.checkout == null
        else -> current.checkout?.let { checkout.sameIdentity(it) && checkout.available == it.available } == true
    }
