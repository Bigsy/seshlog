package com.hedworth.seshlog.terminal

import com.intellij.ui.content.Content

/** A terminal tab, independent of whether the IDE hosts a classic widget or a reworked view. */
interface TerminalHandle {
    val content: Content?
    fun shellPid(): Long?
    fun state(): TerminalState
    fun execute(command: String)
    fun rename(title: String) { content?.displayName = title }
}
