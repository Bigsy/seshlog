package com.hedworth.seshlog.terminal

import org.junit.Assert.*
import org.junit.Test

class ReviewLocalTargetTest {
    @Test fun `matching thread arguments alone cannot prove the receiving endpoint`() {
        // Two homes may contain the exact same thread and checkout. Neither argv nor cwd binds a socket.
        assertFalse(supportsLocalReviewTransport(arrayOf("resume", "synthetic-thread")))
        assertFalse(supportsLocalReviewTransport(emptyArray()))
    }
    @Test fun `only known default server arguments are eligible for local review delivery`() {
        assertFalse(supportsLocalReviewTransport(null, true))
        assertFalse(supportsLocalReviewTransport(arrayOf("--no-daemon"), true))
        assertFalse(supportsLocalReviewTransport(arrayOf("--remote", "unix://other"), true))
        assertFalse(supportsLocalReviewTransport(arrayOf("--remote=ws://127.0.0.1:5555"), true))
        assertTrue(supportsLocalReviewTransport(arrayOf("resume", "synthetic-thread"), true))
        assertTrue(supportsLocalReviewTransport(emptyArray(), true))
    }
}
