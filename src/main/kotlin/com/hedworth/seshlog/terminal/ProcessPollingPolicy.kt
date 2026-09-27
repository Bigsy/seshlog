package com.hedworth.seshlog.terminal

/** Pure cadence policy for terminal process polling. */
internal object ProcessPollingPolicy {
    data class State(val unchangedTicks: Int = 0)
    data class Decision(val delayMillis: Long, val state: State)

    fun next(
        state: State,
        frameActive: Boolean,
        changed: Boolean,
        fastMillis: Long = 2_000L,
        slowMillis: Long = 10_000L,
        unchangedBeforeBackoff: Int = 3,
    ): Decision {
        require(fastMillis > 0 && slowMillis >= fastMillis)
        require(unchangedBeforeBackoff > 0)
        val nextUnchanged = if (changed) 0 else state.unchangedTicks + 1
        val delay = if (frameActive && (changed || nextUnchanged < unchangedBeforeBackoff)) fastMillis else slowMillis
        return Decision(delay, State(nextUnchanged))
    }
}
