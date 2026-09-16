package com.hedworth.seshlog.terminal

/**
 * User-supplied arguments appended to an agent's resume and fork commands (Settings | Tools |
 * Seshlog, "Additional arguments"). Inserted verbatim so the user can write shell syntax such as
 * `--model sonnet` or `--setting 'a b'`; only surrounding whitespace is trimmed.
 */
object ExtraArguments {
    fun append(command: String, extra: String): String {
        val trimmed = extra.trim()
        return if (trimmed.isEmpty()) command else "$command $trimmed"
    }
}
