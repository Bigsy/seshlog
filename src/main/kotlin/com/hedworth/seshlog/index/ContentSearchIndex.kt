package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Session
import java.util.concurrent.ConcurrentHashMap

/** One session that matched a content search. */
data class SearchHit(
    val session: Session,
    /** Number of occurrences of the query in the conversation text (title match adds a bonus). */
    val score: Int,
    /** True when the title itself contains the query. */
    val titleMatch: Boolean,
    /** Short context around the first content match, or null if only the title matched. */
    val snippet: String?,
    val entryId: String? = null,
    val toolMatch: Boolean = false,
    val partial: Boolean = false,
)

/**
 * In-memory full-text index over session conversation text, kept free of IntelliJ types so it is
 * unit-testable. Entries are keyed by session id and stamped with the provider's [contentStamp];
 * [search] re-extracts any session whose stamp is new or changed before matching against it, so the
 * first query pays the extraction cost and later ones are pure string scans.
 *
 * Matching uses literal AND terms and quoted phrases shared with conversation navigation.
 *
 * @param extractor    reads one session into its list of message texts (may throw: skipped and retried on the next search)
 * @param contentStamp cheap change token for a session; null means "unknown", which disables caching for it
 */
class ContentSearchIndex(
    private val extractor: (Session) -> List<String>,
    private val contentStamp: (Session) -> Any?,
    private val localTitle: (Session) -> String = { "" },
    private val entryExtractor: ((Session) -> List<com.hedworth.seshlog.model.ConversationEntry>)? = null,
) {

    private class Entry(val stamp: Any?, val content: List<com.hedworth.seshlog.model.ConversationEntry>) {
        val searchable = content.filter { it.searchable }
        val texts = searchable.map { it.text }
        /** Folded one-to-one with [texts], so indices can be used against the original text. */
        val foldedTexts = texts.map(::foldCase)
        val partial = content.any { it.truncated || !it.searchable }
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Number of sessions currently indexed (for tests and diagnostics). */
    val size: Int get() = entries.size

    /**
     * Search [sessions] for [query]. [isCancelled] is polled between sessions so a superseded
     * query can bail out early; when cancelled, the (partial) result is still returned.
     * Results are ranked best first.
     */
    fun search(query: String, sessions: List<Session>, isCancelled: () -> Boolean = { false }): List<SearchHit> {
        val parsed = TextQuery.parse(query)
        if (parsed.terms.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        for (session in sessions) {
            if (isCancelled()) break
            val entry = entryFor(session)
            val titles = listOf(session.title, localTitle(session))
            val texts = entry?.texts.orEmpty()
            val foldedTitles = titles.map(::foldCase)
            val foldedCwd = foldCase(session.cwd.toString())
            val termMatched = BooleanArray(parsed.terms.size)
            val phraseMatched = BooleanArray(parsed.terms.size)
            parsed.terms.forEachIndexed { index, term ->
                termMatched[index] = foldedTitles.any(term::matchesFolded) || term.matchesFolded(foldedCwd)
            }
            val titleTerms = parsed.terms.indices.count { index -> foldedTitles.any(parsed.terms[index]::matchesFolded) }
            var count = 0
            var snippet: String? = null
            var matchedEntry: com.hedworth.seshlog.model.ConversationEntry? = null
            for ((i, text) in texts.withIndex()) {
                val foldedText = entry!!.foldedTexts[i]
                val seenRanges = HashSet<Long>()
                var firstMatchAt: Int? = null
                var firstMatchLength = 0
                parsed.terms.forEachIndexed { termIndex, term ->
                    var at = foldedText.indexOf(term.folded)
                    if (at < 0) return@forEachIndexed
                    termMatched[termIndex] = true
                    if (term.phrase) phraseMatched[termIndex] = true
                    if (firstMatchAt == null || at < firstMatchAt!! ||
                        (at == firstMatchAt && term.folded.length < firstMatchLength)) {
                        firstMatchAt = at
                        firstMatchLength = term.folded.length
                    }
                    while (count < 9 && at >= 0) {
                        val key = (at.toLong() shl 32) xor term.folded.length.toLong()
                        if (seenRanges.add(key)) count++
                        at = foldedText.indexOf(term.folded, at + term.folded.length)
                    }
                }
                if (snippet == null && firstMatchAt != null) {
                    matchedEntry = entry.searchable[i]
                    snippet = (if (matchedEntry?.isTool == true) "${matchedEntry!!.label}: " else "") +
                        snippet(text, firstMatchAt!!, firstMatchLength)
                }
            }
            if (termMatched.any { !it }) continue
            val phraseBonus = parsed.terms.indices.count { phraseMatched[it] } * 20
            hits += SearchHit(session, titleTerms * TITLE_BONUS + phraseBonus + count,
                titleTerms > 0, snippet, matchedEntry?.sourceId, matchedEntry?.isTool == true, entry?.partial == true)
        }
        hits.sortWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.session.lastActivityAt })
        return hits
    }

    /** Drop entries for sessions whose ids are no longer in [live]. */
    fun retainOnly(live: Collection<String>) {
        entries.keys.retainAll(live.toSet())
    }

    private fun entryFor(session: Session): Entry? {
        val stamp = contentStamp(session)
        if (stamp != null) entries[session.id]?.let { if (it.stamp == stamp) return it }
        val texts = try {
            entryExtractor?.invoke(session) ?: extractor(session).mapIndexed { i, text ->
                com.hedworth.seshlog.model.ConversationEntry(
                    com.hedworth.seshlog.model.ConversationMessage(com.hedworth.seshlog.model.Role.ASSISTANT, text, null), "message:$i")
            }
        } catch (_: Exception) {
            entries.remove(session.id)
            return null
        }
        val entry = Entry(stamp, texts)
        // No stamp means we cannot tell when the content changes: use the extraction once, never cache it.
        if (stamp == null) entries.remove(session.id) else entries[session.id] = entry
        return entry
    }

    companion object {
        /** A title match outranks a handful of incidental content matches. */
        const val TITLE_BONUS = 100
        private const val SNIPPET_CONTEXT = 60

        internal fun countOccurrences(haystack: String, needle: String): Int {
            var count = 0
            var from = 0
            while (true) {
                val i = haystack.indexOf(needle, from)
                if (i < 0) return count
                count++
                from = i + needle.length
            }
        }

        /** One line of context around the match, whitespace collapsed, ellipses where cut. */
        internal fun snippet(text: String, matchIndex: Int, matchLength: Int): String {
            val start = (matchIndex - SNIPPET_CONTEXT).coerceAtLeast(0)
            val end = (matchIndex + matchLength + SNIPPET_CONTEXT).coerceAtMost(text.length)
            val core = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
            return (if (start > 0) "…" else "") + core + (if (end < text.length) "…" else "")
        }
    }
}
