package com.hedworth.seshlog.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalStateTest {
    @Test fun `inspection failure is unknown rather than idle`() {
        assertEquals(TerminalState.UNKNOWN, TerminalState.inspect { error("Unsupported terminal") })
    }

    @Test fun `successful inspection distinguishes running and idle shells`() {
        assertEquals(TerminalState.BUSY, TerminalState.inspect { true })
        assertEquals(TerminalState.IDLE, TerminalState.inspect { false })
    }
}
