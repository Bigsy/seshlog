package com.hedworth.seshlog.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessPollingPolicyTest {
    @Test
    fun `unchanged inactive polling backs off after several ticks`() {
        var state = ProcessPollingPolicy.State()
        val delays = (1..4).map {
            val decision = ProcessPollingPolicy.next(state, frameActive = false, changed = false,
                fastMillis = 2, slowMillis = 10, unchangedBeforeBackoff = 3)
            state = decision.state
            decision.delayMillis
        }
        assertEquals(listOf(10L, 10L, 10L, 10L), delays)
    }

    @Test
    fun `changes reset active polling while inactive frames stay slow`() {
        var state = ProcessPollingPolicy.State(9)
        var decision = ProcessPollingPolicy.next(state, frameActive = true, changed = true,
            fastMillis = 2, slowMillis = 10, unchangedBeforeBackoff = 3)
        assertEquals(2L, decision.delayMillis)
        state = decision.state
        decision = ProcessPollingPolicy.next(state, frameActive = false, changed = true,
            fastMillis = 2, slowMillis = 10, unchangedBeforeBackoff = 3)
        assertEquals(10L, decision.delayMillis)
        assertEquals(0, decision.state.unchangedTicks)
    }

    @Test
    fun `active unchanged polling backs off after the threshold`() {
        var state = ProcessPollingPolicy.State()
        val delays = (1..4).map {
            val decision = ProcessPollingPolicy.next(state, frameActive = true, changed = false,
                fastMillis = 2, slowMillis = 10, unchangedBeforeBackoff = 3)
            state = decision.state
            decision.delayMillis
        }
        assertEquals(listOf(2L, 2L, 10L, 10L), delays)
    }
}
