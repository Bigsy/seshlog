package com.hedworth.seshlog.terminal

/** Unknown terminals must never receive commands through reuse. */
enum class TerminalState {
    BUSY, IDLE, UNKNOWN;

    companion object {
        fun inspect(hasRunningCommands: () -> Boolean): TerminalState = try {
            if (hasRunningCommands()) BUSY else IDLE
        } catch (_: Throwable) {
            UNKNOWN
        }
    }
}
