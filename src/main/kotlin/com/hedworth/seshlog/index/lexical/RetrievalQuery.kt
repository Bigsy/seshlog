package com.hedworth.seshlog.index.lexical

import com.hedworth.seshlog.index.QueryTerm
import com.hedworth.seshlog.index.TextQuery
import com.hedworth.seshlog.index.foldCase

/** Retrieval normalization is independent of literal viewer matching and UTF-16 offsets. */
data class RetrievalQuery(
    val raw: String,
    val literal: TextQuery,
    val tokens: List<String>,
    val technical: Boolean,
) {
    val phrases: List<QueryTerm>
        get() = literal.terms.filter { it.phrase }

    val verifyAll: Boolean
        get() = phrases.isNotEmpty() || technical || tokens.isEmpty() || raw.any { it.code > 127 }

    val match: String
        get() = tokens.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }

    companion object {
        const val MAX_CHARS = 4096
        const val MAX_TERMS = 128

        fun compile(raw: String): RetrievalQuery {
            require(raw.length <= MAX_CHARS) {
                "Query too long (maximum 4,096 characters or 128 terms)."
            }
            val literal = TextQuery.parse(raw)
            val tokens =
                LexicalTokens.queryTokens(literal.terms.joinToString(" ") { it.text }).distinct()
            require(literal.terms.size <= MAX_TERMS && tokens.size <= MAX_TERMS) {
                "Query too long (maximum 4,096 characters or 128 terms)."
            }
            return RetrievalQuery(
                raw,
                literal,
                tokens,
                literal.terms.any { !it.phrase && LexicalTokens.technical(it.text) },
            )
        }
    }
}

object LexicalTokens {
    const val VERSION = 1
    private val words = Regex("[\\p{L}\\p{N}_]+")
    private val camel = Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")
    private val stop =
        "a an the and or of to for in on at with from by is are was were be been it its that this i my we our how when where which about"
            .split(' ')
            .toSet()

    fun technical(text: String): Boolean =
        text.any { it.isDigit() || it in "_./\\:+-#@" } || camel.containsMatchIn(text)

    fun queryTokens(text: String): List<String> =
        words.findAll(text).map { foldCase(it.value) }.filter { it !in stop }.toList()

    fun tokens(text: String): List<String> =
        words
            .findAll(text)
            .flatMap { match ->
                val raw = foldCase(match.value)
                val split =
                    camel
                        .replace(match.value, " ")
                        .split(' ', '_')
                        .filter { it.isNotEmpty() }
                        .map(::foldCase)
                (listOf(raw) + split).distinct().filter { it !in stop }.asSequence()
            }
            .toList()
}
