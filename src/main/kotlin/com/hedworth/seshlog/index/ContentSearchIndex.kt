package com.hedworth.seshlog.index

import com.hedworth.seshlog.index.lexical.*
import com.hedworth.seshlog.model.*
import java.nio.file.Path

/**
 * Scores are internal ranking values, never confidence percentages. All ranges use source UTF-16.
 */
data class SearchHit(
    val session: Session,
    val score: Double,
    val titleMatch: Boolean,
    val snippet: String?,
    val entryId: String? = null,
    val partial: Boolean = false,
    val range: IntRange? = null,
    val entryHash: String? = null,
    val exact: Boolean = false,
    val primary: SearchPrimaryMatch = SearchPrimaryMatch(SearchMatchSource.ASSISTANT),
    val sources: SearchSources = SearchSources.DEFAULT,
) {
    val toolMatch: Boolean get() = primary.source == SearchMatchSource.TOOL_CALL || primary.source == SearchMatchSource.TOOL_RESULT
    val key: SearchSessionKey
        get() = SearchSessionKey(session.kind, session.id)
}

data class SearchCoverage(val eligible: Int, val indexed: Int, val skipped: Int, val limited: Int) {
    val partial: Boolean
        get() = skipped > 0 || limited > 0
}

data class SearchResultSnapshot(
    val hits: List<SearchHit>,
    val coverage: SearchCoverage,
    val corpusRevision: Long,
    val availability: String? = null,
    val resultLimited: Boolean = false,
    val request: Long = 0,
    val candidateRevision: Int = 0,
    val context: SearchContext? = null,
)

/** Durable postings with bounded, disposable source buffers. Only workers call this class. */
class ContentSearchIndex(
    private val extractor: (Session) -> List<String>,
    private val contentStamp: (Session) -> Any?,
    private val localTitle: (Session) -> String = { "" },
    private val entryExtractor: ((Session) -> List<ConversationEntry>)? = null,
    private val characterBudget: Long = DEFAULT_CHARACTER_BUDGET,
    private val storeFactory: () -> LexicalSearchStore = { LexicalSearchStore.open() },
) : AutoCloseable {
    private var storage: LexicalSearchStore? = null
    private var storageFailure: String? = null
    private val stampHashes =
        object : LinkedHashMap<SearchSessionKey, Pair<Any, String>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<SearchSessionKey, Pair<Any, String>>?
            ): Boolean = size > 10_000
        }
    private val buffers = LinkedHashMap<SearchSessionKey, Buffer>(16, 0.75f, true)

    private data class Buffer(val stamp: String?, val entries: List<ConversationEntry>) {
        val cost = entries.sumOf { it.text.length.toLong() }
        val partial = entries.any { it.truncated || !it.searchable }
    }

    var revision: Long = 0
        private set

    val size: Int
        get() = store().sourceCount

    val retainedCharacters: Long
        get() = buffers.values.sumOf { it.cost }

    init {
        require(characterBudget >= 0)
    }

    private fun store(): LexicalSearchStore =
        storage
            ?: try {
                storeFactory().also { storage = it }
            } catch (failure: Exception) {
                storageFailure = (failure as? IncompatibleLexicalSchema)?.explanation ?:
                    "Saved search index unavailable; using a temporary local index. Retry in Search settings."
                LexicalSearchStore.open().also { storage = it }
            }

    fun search(
        query: String,
        sessions: List<Session>,
        sources: SearchSources = SearchSources.DEFAULT,
        sort: SearchSort = SearchSort.BEST_MATCH,
        isCancelled: () -> Boolean = { false },
    ): List<SearchHit> = searchSnapshot(query, sessions, sources, sort, isCancelled).hits

    fun searchSnapshot(
        query: String,
        sessions: List<Session>,
        sources: SearchSources = SearchSources.DEFAULT,
        sort: SearchSort = SearchSort.BEST_MATCH,
        isCancelled: () -> Boolean = { false },
    ): SearchResultSnapshot {
        return try {
            searchInStore(query, sessions, sources, sort, isCancelled).copy(context = SearchContext(query, sources, sort))
        } catch (failure: java.sql.SQLException) {
            if (isCancelled())
                return SearchResultSnapshot(
                    emptyList(),
                    SearchCoverage(sessions.size, 0, sessions.size, 0),
                    revision,
                )
            try {
                storage?.close()
            } catch (_: Exception) {}
            storage = LexicalSearchStore.open()
            storageFailure =
                "Saved search index could not be updated; using a temporary local index. Retry in Search settings."
            searchInStore(query, sessions, sources, sort, isCancelled).copy(context = SearchContext(query, sources, sort))
        }
    }

    private fun searchInStore(
        query: String,
        sessions: List<Session>,
        sources: SearchSources,
        sort: SearchSort,
        isCancelled: () -> Boolean,
    ): SearchResultSnapshot {
        val plan =
            try {
                RetrievalQuery.compile(query)
            } catch (_: IllegalArgumentException) {
                return SearchResultSnapshot(
                    emptyList(),
                    SearchCoverage(sessions.size, 0, 0, 0),
                    revision,
                    "Query too long (maximum 4,096 characters or 128 terms).",
                )
            }
        if (plan.literal.terms.isEmpty())
            return SearchResultSnapshot(
                emptyList(),
                SearchCoverage(sessions.size, 0, 0, 0),
                revision,
            )
        val store = store()
        val candidates = sessions.distinctBy { SearchSessionKey(it.kind, it.id) }
        val savedSources = store.sources()
        val ready = HashSet<SearchSessionKey>()
        val validatedStamps = HashMap<SearchSessionKey, String?>()
        val partial = HashSet<SearchSessionKey>()
        for (session in candidates) {
            if (isCancelled()) break
            val key = SearchSessionKey(session.kind, session.id)
            val metadata =
                searchHash(session.title + "\u0000" + localTitle(session) + "\u0000" + session.cwd)
            val stamp = stamp(session)
            val old = savedSources[key]
            fun metadataOnly() {
                if (old == null && store.sourceCount >= LexicalSearchStore.MAX_SOURCES) return
                if (old == null || old.metadata != metadata) {
                    store.replace(key, old?.stamp, old?.fingerprint, metadata, old?.partial ?: false,
                        session.title, localTitle(session), session.cwd.toString(), null)
                    revision++
                }
            }
            if (sources == SearchSources.METADATA_ONLY) {
                metadataOnly()
                continue
            }
            var reuse = old != null && stamp != null && old.stamp == stamp
            // Import/moved roots: hash only discovered local files; never follow a stored path.
            if (!reuse && old?.fingerprint != null && stamp != null) {
                reuse =
                    old.fingerprint == sourceFingerprint(paths(session), isCancelled) &&
                        stamp == stamp(session)
                if (reuse) store.validateStamp(key, stamp)
            }
            if (reuse) {
                ready += key
                validatedStamps[key] = stamp
                if (old!!.partial) partial += key
                if (old.metadata != metadata) {
                    store.replace(
                        key,
                        stamp,
                        old.fingerprint,
                        metadata,
                        old.partial,
                        session.title,
                        localTitle(session),
                        session.cwd.toString(),
                        null,
                    )
                    revision++
                }
                continue
            }
            if (isCancelled()) break
            if (old == null && store.sourceCount >= LexicalSearchStore.MAX_SOURCES) continue
            val loaded = read(session, stamp)
            if (loaded == null || stamp != stamp(session) || isCancelled()) {
                buffers.remove(key)
                if (!isCancelled()) metadataOnly()
                continue
            }
            val passages = passages(loaded.entries, isCancelled)
            if (isCancelled()) break
            val room = store.hasRoom(passages.size, key) || old != null && passages.size <= 0
            val limited = loaded.partial || !room || stamp == null
            val fingerprint = sourceFingerprint(paths(session), isCancelled)
            if (stamp != stamp(session) || isCancelled()) continue
            store.replace(
                key,
                stamp,
                fingerprint,
                metadata,
                limited,
                session.title,
                localTitle(session),
                session.cwd.toString(),
                if (room) passages else emptyList(),
            ) {
                check(!isCancelled()) { "Source changed during indexing." }
            }
            revision++
            ready += key
            validatedStamps[key] = stamp
            if (limited) partial += key
        }
        val metadataOnly = sources == SearchSources.METADATA_ONLY
        val metadataKeys = candidates.map { SearchSessionKey(it.kind, it.id) }.filter { metadataOnly || it !in ready }
        val metadataScores = if (metadataKeys.isEmpty()) emptyList() else
            store.scores(plan, metadataKeys, SearchSources.METADATA_ONLY, isCancelled)
        val scores = (metadataScores + store.scores(plan, ready, sources, isCancelled)).associateBy { it.key }
        // scores() sets the scope used by the literal candidate lane.
        val substringCandidates = store.substringCandidates(plan, sources, isCancelled)
        val hits = ArrayList<SearchHit>()
        val failed = if (metadataOnly) HashSet<SearchSessionKey>() else metadataKeys.toHashSet()
        // Sorting needs only the protected title tier. Build full metadata excerpts lazily for
        // verified candidates, rather than allocating ranges/descriptors for the entire corpus.
        val fullQuery = QueryTerm(plan.literal.terms.joinToString(" ") { it.text }, false)
        val exactTitles = if (plan.verifyAll) emptySet() else candidates.filter {
            fullQuery.matches(it.title) || fullQuery.matches(localTitle(it))
        }.mapTo(HashSet()) { SearchSessionKey(it.kind, it.id) }
        val verificationOrder = if (sort == SearchSort.RECENTLY_ACTIVE) candidates.sortedWith(SearchSort.recentSessions)
        else if (plan.verifyAll) candidates else candidates.sortedWith(
            compareByDescending<Session> { SearchSessionKey(it.kind, it.id) in exactTitles }
                .thenByDescending { scores[SearchSessionKey(it.kind, it.id)]?.score ?: 1e-6 }
                .thenByDescending { it.lastActivityAt }.thenBy { it.kind.name }.thenBy { it.id })
        for (session in verificationOrder) {
            if (isCancelled() || !plan.verifyAll && hits.size > 100) break
            val key = SearchSessionKey(session.kind, session.id)
            val score = scores[key]
            val metadataSignal = plan.tokens.any { token ->
                listOf(session.title, localTitle(session), session.cwd.toString()).any { QueryTerm(token, false).matches(it) }
            }
            if (score == null && !plan.verifyAll && !metadataSignal && key !in substringCandidates) continue
            val metadataEvidence = LiteralEvidence.verify(plan, listOf(session.title, localTitle(session)),
                session.cwd.toString(), emptyList(), sources)
            val stamp = stamp(session)
            if (!metadataOnly && stamp != null && key in ready && validatedStamps[key] != stamp) {
                failed += key
                continue
            }
            // A metadata hit can satisfy eligibility without proving that an eligible body lacks
            // protected full-query evidence. Metadata is conclusive only when it already has
            // that evidence (and therefore wins the deterministic source tie), or when this
            // query cannot protect a body match at all.
            val metadataConclusive = metadataEvidence.allowed && metadataEvidence.matches.isNotEmpty() &&
                (!(plan.technical || plan.phrases.isNotEmpty()) || metadataEvidence.matches.any { it.fullExact })
            val needsSource = !metadataOnly && (score?.entry != null || key in substringCandidates ||
                plan.verifyAll && !metadataConclusive)
            val loaded = if (needsSource && key in ready) read(session, stamp) else null
            if (loaded == null && needsSource && key in ready) failed += key
            if (!metadataOnly && stamp != stamp(session)) {
                failed += key
                buffers.remove(key)
                continue
            }
            val evidence = if (loaded != null) LiteralEvidence.verify(plan,
                listOf(session.title, localTitle(session)), session.cwd.toString(), loaded.entries, sources) else metadataEvidence
            if (!evidence.allowed) continue
            if (score == null && !metadataSignal && evidence.entry == null && !evidence.title && plan.tokens.isNotEmpty()) continue
            // Protected full-query evidence wins. Otherwise explain the actual SQL field winner.
            val protected = evidence.matches.firstOrNull { it.fullExact }
            val ranked = evidence.matches.firstOrNull { match ->
                match.primary.source == score?.source && match.primary.metadataVariant == score.variant &&
                    match.primary.entryId == score.entry
            }
            var selected = protected ?: ranked ?: evidence.matches.firstOrNull()
            if (selected == null) continue
            if (protected == null && ranked != null && score?.entry != null) {
                val entry = loaded?.entries?.firstOrNull { it.sourceId == score.entry && it.eligibleFor(sources) }
                if (entry == null || searchHash(entry.text) != score.hash) {
                    failed += key
                    continue
                }
                val terms = plan.literal.terms + if (plan.technical) emptyList() else plan.tokens.map { QueryTerm(it, false) }
                val range = terms.flatMap { it.ranges(entry.text) }
                    .filter { it.first >= score.start && it.last < score.end }
                    .minWithOrNull(compareBy({ it.first }, { it.last }))
                // A window containing only a normalized neighbour cannot claim literal evidence.
                if (range == null) {
                    selected = evidence.matches.firstOrNull { it.primary.entryId != score.entry } ?: continue
                } else selected = ranked.copy(primary = ranked.primary.copy(range = range))
            }
            val primary = selected.primary.copy(entryHash = selected.primary.entryId?.let { searchHash(selected.text) })
            val range = primary.range!!
            hits += SearchHit(session, score?.score ?: 1e-6, evidence.title,
                snippet(selected.text, range.first, range.last - range.first + 1),
                primary.entryId,
                !metadataOnly && (key in partial || key !in ready || key in failed),
                range.takeIf { primary.entryId != null }, primary.entryHash, evidence.exact,
                primary.copy(range = range.takeIf { primary.entryId != null }), sources)
        }
        val sorted =
            hits
                .filter { it.score.isFinite() && it.score > 0 }
                .sortedWith(sort.comparator)
        return SearchResultSnapshot(
            sorted.take(100),
            SearchCoverage(candidates.size, if (metadataOnly) candidates.size else ready.size, failed.size, if (metadataOnly) 0 else partial.size),
            revision,
            storageFailure,
            sorted.size > 100,
        )
    }

    private fun stamp(session: Session): String? =
        try {
            val value = contentStamp(session)
            val key = SearchSessionKey(session.kind, session.id)
            if (value == null) null
            else {
                val cached = stampHashes[key]
                if (cached?.first == value) cached.second
                else searchHash(value.toString()).also { stampHashes[key] = value to it }
            }
        } catch (_: Exception) {
            null
        }

    private fun paths(session: Session): List<Path> =
        listOfNotNull(session.transcriptPath) + session.subagentTranscriptPaths

    private fun read(session: Session, stamp: String?): Buffer? {
        val key = SearchSessionKey(session.kind, session.id)
        buffers[key]?.let { if (stamp != null && it.stamp == stamp) return it }
        val entries =
            try {
                val collector = ConversationLimits.Collector()
                (entryExtractor?.invoke(session)
                        ?: extractor(session).mapIndexed { i, text ->
                            ConversationEntry(
                                ConversationMessage(Role.ASSISTANT, text, null),
                                "message:$i",
                            )
                        })
                    .forEach(collector::add)
                collector.finish()
            } catch (_: Exception) {
                buffers.remove(key)
                return null
            }
        val buffer = Buffer(stamp, entries)
        buffers.remove(key)
        if (stamp != null && buffer.cost <= characterBudget) {
            buffers[key] = buffer
            while (retainedCharacters > characterBudget) buffers.remove(buffers.keys.first())
        }
        return buffer
    }

    private fun passages(
        entries: List<ConversationEntry>,
        cancelled: () -> Boolean,
    ): List<LexicalPassage> = buildList {
        for (entry in entries) {
            if (cancelled()) break
            if (!entry.searchable) continue
            val hash = searchHash(entry.text)
            var start = 0
            while (start < entry.text.length) {
                if (cancelled()) break
                var end = minOf(entry.text.length, start + 2048)
                if (end < entry.text.length && Character.isLowSurrogate(entry.text[end])) end--
                add(
                    LexicalPassage(
                        entry.sourceId,
                        start,
                        end,
                        hash,
                        entry.text.substring(start, end),
                        entry.searchSource!!,
                    )
                )
                if (end == entry.text.length) break
                start = end - 128
                if (Character.isLowSurrogate(entry.text[start])) start++
            }
        }
    }

    /** Absence from a scan is not proof of deletion (provider roots may be unavailable). */
    fun retainOnly(live: Collection<SearchSessionKey>, completeKinds: Set<AgentKind> = emptySet()) {
        val keep = live.toSet()
        buffers.keys.retainAll(keep)
        if (completeKinds.isNotEmpty()) {
            store().pruneMissing(keep, completeKinds)
            revision++
        }
    }

    fun refresh() {
        buffers.clear()
        revision++
    }

    fun clear() {
        store().clear()
        refresh()
    }

    fun rebuildSavedIndex(reset: () -> Unit) {
        storage?.close()
        storage = null
        reset()
        storageFailure = null
        refresh()
    }

    fun retry() {
        storage?.close()
        storage = null
        storageFailure = null
        refresh()
    }

    fun export(path: Path) = store().export(path)

    fun importSnapshot(path: Path, cancelled: () -> Boolean = { false }): Int {
        refresh()
        return store().importSnapshot(path, cancelled)
    }

    override fun close() {
        storage?.close()
        storage = null
        buffers.clear()
    }

    companion object {
        const val DEFAULT_CHARACTER_BUDGET = 32_000_000L

        internal fun snippet(text: String, matchIndex: Int, matchLength: Int): String {
            val start = (matchIndex - 60).coerceAtLeast(0)
            val end = (matchIndex + matchLength + 60).coerceAtMost(text.length)
            return (if (start > 0) "…" else "") +
                text.substring(start, end).replace(Regex("\\s+"), " ").trim() +
                (if (end < text.length) "…" else "")
        }
    }
}
