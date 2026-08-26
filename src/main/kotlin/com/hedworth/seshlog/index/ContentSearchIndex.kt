package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Session
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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
)

/**
 * In-memory full-text index over transcript conversation text, kept free of IntelliJ types so it
 * is unit-testable. Entries are keyed by `(size, mtime)` and rebuilt lazily: [search] indexes any
 * transcript that is new or changed before matching against it, so the first query pays the
 * extraction cost and later ones are pure string scans.
 *
 * Matching is a case-insensitive substring search — the `rg`-style "find the phrase" the plan asks
 * for, without a query language.
 *
 * @param extractor reads one transcript into its list of message texts (never throws)
 */
class ContentSearchIndex(private val extractor: (Path) -> List<String>) {

    private class Entry(val size: Long, val mtimeMillis: Long, val texts: List<String>, val lower: List<String>)

    private val entries = ConcurrentHashMap<Path, Entry>()

    /** Number of transcripts currently indexed (for tests and diagnostics). */
    val size: Int get() = entries.size

    /**
     * Search [sessions] for [query]. [isCancelled] is polled between transcripts so a superseded
     * query can bail out early; when cancelled, the (partial) result is still returned.
     * Results are ranked best first.
     */
    fun search(query: String, sessions: List<Session>, isCancelled: () -> Boolean = { false }): List<SearchHit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        for (session in sessions) {
            if (isCancelled()) break
            val entry = entryFor(session.transcriptPath)
            val titleMatch = session.title.lowercase().contains(needle)
            var count = 0
            var snippet: String? = null
            if (entry != null) {
                for (i in entry.lower.indices) {
                    val c = countOccurrences(entry.lower[i], needle)
                    if (c == 0) continue
                    if (snippet == null) snippet = snippet(entry.texts[i], entry.lower[i].indexOf(needle), needle.length)
                    count += c
                }
            }
            if (count == 0 && !titleMatch) continue
            hits += SearchHit(session, count + if (titleMatch) TITLE_BONUS else 0, titleMatch, snippet)
        }
        hits.sortWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.session.lastActivityAt })
        return hits
    }

    /** Drop entries for transcripts that are no longer in [live]. */
    fun retainOnly(live: Collection<Path>) {
        entries.keys.retainAll(live.toSet())
    }

    private fun entryFor(path: Path): Entry? {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        } catch (_: Exception) {
            entries.remove(path)
            return null
        }
        val size = attrs.size()
        val mtime = attrs.lastModifiedTime().toMillis()
        entries[path]?.let { if (it.size == size && it.mtimeMillis == mtime) return it }
        val texts = try {
            extractor(path)
        } catch (_: Exception) {
            emptyList()
        }
        return Entry(size, mtime, texts, texts.map { it.lowercase() }).also { entries[path] = it }
    }

    companion object {
        /** A title match outranks a handful of incidental content matches. */
        const val TITLE_BONUS = 10
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
