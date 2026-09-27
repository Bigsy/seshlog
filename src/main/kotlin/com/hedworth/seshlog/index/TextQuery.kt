package com.hedworth.seshlog.index

/** Literal, case-insensitive terms; offsets always refer to the original UTF-16 text. */
data class QueryTerm(val text: String, val phrase: Boolean) {
    /** One-to-one folding keeps every index aligned with the original UTF-16 source. */
    internal val folded: String = foldCase(text)

    fun ranges(source: String): List<IntRange> {
        return rangesFolded(foldCase(source))
    }

    internal fun rangesFolded(foldedSource: String): List<IntRange> {
        val result = ArrayList<IntRange>()
        var from = 0
        while (from <= foldedSource.length - folded.length) {
            val at = foldedSource.indexOf(folded, from)
            if (at < 0) break
            result += at until at + folded.length
            from = at + folded.length
        }
        return result
    }

    fun matches(source: String): Boolean = matchesFolded(foldCase(source))

    internal fun matchesFolded(foldedSource: String): Boolean = foldedSource.indexOf(folded) >= 0
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
            return TextQuery(terms.distinctBy { it.folded to it.phrase })
        }
    }
}

/** Simple one-to-one Unicode case folding; unlike String.lowercase(), it cannot change length. */
internal fun foldCase(value: String): String = buildString(value.length) {
    // The upper-then-lower form mirrors String.regionMatches(ignoreCase = true), including
    // pairs such as Greek sigma/final sigma, while retaining one UTF-16 unit per source unit.
    value.forEach { append(Character.toLowerCase(Character.toUpperCase(it))) }
}
