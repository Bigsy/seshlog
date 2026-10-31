package com.hedworth.seshlog.terminal

import org.junit.Assert.*
import org.junit.Test

class ReviewLocalTargetTest {
    @Test fun `only known default server arguments are eligible for local review delivery`() {
        assertFalse(supportsLocalReviewTransport(null))
        assertFalse(supportsLocalReviewTransport(arrayOf("--no-daemon")))
        assertFalse(supportsLocalReviewTransport(arrayOf("--remote", "unix://other")))
        assertFalse(supportsLocalReviewTransport(arrayOf("--remote=ws://127.0.0.1:5555")))
        assertTrue(supportsLocalReviewTransport(arrayOf("resume", "synthetic-thread")))
        assertTrue(supportsLocalReviewTransport(emptyArray()))
    }
}
