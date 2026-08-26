package com.hedworth.seshlog.terminal

/** POSIX single-quote escaping; safe for bash, zsh and fish. PowerShell is out of scope. */
object ShellQuote {
    private val SAFE = Regex("^[A-Za-z0-9_./:=@%+,-]+$")

    fun quote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (SAFE.matches(arg)) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}
