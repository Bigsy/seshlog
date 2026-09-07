package com.hedworth.seshlog.index

/** Literal, case-insensitive terms; offsets always refer to the original UTF-16 text. */
data class QueryTerm(val text: String, val phrase: Boolean) {
    fun ranges(source: String): List<IntRange> {
        val result = ArrayList<IntRange>()
        var from = 0
        while (from <= source.length - text.length) {
            val at = source.indexOf(text, from, ignoreCase = true)
            if (at < 0) break
            result += at until at + text.length
            from = at + text.length
        }
        return result
    }
    fun matches(source: String): Boolean = source.contains(text, ignoreCase = true)
}

data class TextQuery(val terms: List<QueryTerm>) {
    fun matches(fields: List<String>): Boolean =
        terms.isNotEmpty() && terms.all { term -> fields.any(term::matches) }

    /** Navigation finds each term, even when the remaining terms occur in other fields. */
    fun ranges(text: String): List<IntRange> =
        terms.flatMap { it.ranges(text) }.distinct().sortedWith(compareBy({ it.first }, { it.last }))

    companion object {
        const val HINT = "All words must match; use \"quoted phrases\" for exact text."
        fun parse(input: String): TextQuery {
            val terms = ArrayList<QueryTerm>()
            var i = 0
            while (i < input.length) {
                if (input[i].isWhitespace()) { i++; continue }
                val phrase = input[i] == '"'
                if (phrase) i++
                val start = i
                while (i < input.length && if (phrase) input[i] != '"' else !input[i].isWhitespace() && input[i] != '"') i++
                if (i > start) terms += QueryTerm(input.substring(start, i), phrase)
                if (phrase && i < input.length) i++
            }
            return TextQuery(terms.distinctBy { it.text.lowercase() to it.phrase })
        }
    }
}
