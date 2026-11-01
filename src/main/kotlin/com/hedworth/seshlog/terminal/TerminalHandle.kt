package com.hedworth.seshlog.terminal

import com.intellij.ui.content.Content

/** A terminal tab, independent of whether the IDE hosts a classic widget or a reworked view. */
interface TerminalHandle {
    val content: Content?
    val diagnosticEngine: String get() = "unknown"
    fun shellPid(): Long?
    /** The title the running program last set with an escape sequence, regardless of the tab's name. */
    fun applicationTitle(): String? = null
    fun state(): TerminalState
    fun execute(command: String)
    /** Insert at the input cursor without Enter or clearing input; false means unavailable. */
    fun paste(text: String): Boolean = false
    fun rename(title: String) { content?.displayName = title }
}
