package com.hedworth.seshlog.index.lexical

import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.model.ConversationEntry

/** Literal constraints never join entries or normalized passage windows. */
object LiteralEvidence {
    data class Match(val primary: SearchPrimaryMatch, val text: String, val fullExact: Boolean)

    data class Evidence(
        val allowed: Boolean,
        val exact: Boolean,
        val title: Boolean,
        val entry: ConversationEntry?,
        val range: IntRange?,
        val matches: List<Match>,
    )

    fun verify(
        query: RetrievalQuery,
        titles: List<String>,
        path: String,
        entries: List<ConversationEntry>,
        sources: SearchSources = SearchSources.DEFAULT,
    ): Evidence {
        val searchable = entries.filter { it.eligibleFor(sources) }
        val fields = titles + path + searchable.map { it.text }
        val required = query.phrases.all { phrase -> fields.any(phrase::matches) }
        val full = query.literal.terms.joinToString(" ") { it.text }
        val exactTerm = QueryTerm(full, false)
        val title = full.isNotBlank() && titles.any(exactTerm::matches)
        val exact = title ||
            (query.technical && full.isNotBlank() && fields.any(exactTerm::matches)) ||
            query.phrases.isNotEmpty()
        val pureTechnical = query.phrases.isEmpty() && query.literal.terms.all { LexicalTokens.technical(it.text) }
        val literalRequired = if (pureTechnical || query.tokens.isEmpty())
            query.literal.terms.any { term -> fields.any(term::matches) } else true
        val terms = (query.phrases + query.literal.terms +
            if (query.technical) emptyList() else query.tokens.map { QueryTerm(it, false) }).distinct()
        fun match(text: String, primary: SearchPrimaryMatch): Match? {
            val fullRange = if (full.isNotBlank()) exactTerm.ranges(text).firstOrNull() else null
            val protected = fullRange != null && (primary.source == SearchMatchSource.TITLE || query.technical || query.phrases.isNotEmpty())
            val range = if (protected) fullRange else terms.flatMap { it.ranges(text) }.minWithOrNull(compareBy({ it.first }, { it.last }))
            return range?.let { Match(primary.copy(range = it), text, protected) }
        }
        val matches = buildList {
            titles.forEachIndexed { i, text ->
                match(text, SearchPrimaryMatch(SearchMatchSource.TITLE, metadataVariant =
                    if (i == 0) SearchMetadataVariant.PROVIDER_TITLE else SearchMetadataVariant.LOCAL_TITLE))?.let(::add)
            }
            match(path, SearchPrimaryMatch(SearchMatchSource.PROJECT_PATH, metadataVariant = SearchMetadataVariant.PROJECT_PATH))?.let(::add)
            searchable.forEach { entry ->
                match(entry.text, SearchPrimaryMatch(entry.searchSource!!.matchSource, entry.sourceId,
                    toolName = entry.toolName))?.let(::add)
            }
        }.sortedWith(compareBy({ it.primary.source.ordinal }, { it.primary.entryId.orEmpty() }, { it.primary.range?.first ?: 0 }, { it.primary.range?.last ?: 0 }, { it.primary.metadataVariant?.ordinal ?: 0 }))
        val firstBody = matches.firstOrNull { it.primary.entryId != null }
        val entry = searchable.firstOrNull { it.sourceId == firstBody?.primary?.entryId }
        return Evidence(required && literalRequired, exact, title, entry, firstBody?.primary?.range, matches)
    }
}
