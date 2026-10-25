package com.hedworth.seshlog.index.lexical

import com.hedworth.seshlog.index.QueryTerm
import com.hedworth.seshlog.model.ConversationEntry

/** Literal constraints never join entries or normalized passage windows. */
object LiteralEvidence {
    data class Evidence(
        val allowed: Boolean,
        val exact: Boolean,
        val title: Boolean,
        val entry: ConversationEntry?,
        val range: IntRange?,
    )

    fun verify(
        query: RetrievalQuery,
        titles: List<String>,
        path: String,
        entries: List<ConversationEntry>,
    ): Evidence {
        val searchable = entries.filter { it.searchable }
        val fields = titles + path + searchable.map { it.text }
        val required = query.phrases.all { phrase -> fields.any(phrase::matches) }
        val full = query.literal.terms.joinToString(" ") { it.text }
        val exactTerm = QueryTerm(full, false)
        val title = full.isNotBlank() && titles.any(exactTerm::matches)
        val exact =
            title ||
                (query.technical && full.isNotBlank() && fields.any(exactTerm::matches)) ||
                query.phrases.isNotEmpty()
        val pureTechnical =
            query.phrases.isEmpty() && query.literal.terms.all { LexicalTokens.technical(it.text) }
        val literalRequired =
            if (pureTechnical || query.tokens.isEmpty())
                query.literal.terms.any { term -> fields.any(term::matches) }
            else true
        val terms = (query.phrases + query.literal.terms).distinct()
        val entry = searchable.firstOrNull { e -> terms.any { it.matches(e.text) } }
        val range =
            entry?.let { e ->
                terms
                    .flatMap { it.ranges(e.text) }
                    .minWithOrNull(compareBy({ it.first }, { it.last }))
            }
        return Evidence(required && literalRequired, exact, title, entry, range)
    }
}
