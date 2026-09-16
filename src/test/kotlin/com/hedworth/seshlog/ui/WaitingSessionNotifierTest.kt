package com.hedworth.seshlog.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class WaitingSessionNotifierTest {
    @Test
    fun `sessions are matched to a project by literal path prefix`() {
        val roots = listOf(Paths.get("/Users/tester/workspace/acme"), Paths.get("/srv/other"))
        assertTrue(WaitingSessionNotifier.isUnder(Paths.get("/Users/tester/workspace/acme"), roots))
        assertTrue(WaitingSessionNotifier.isUnder(Paths.get("/Users/tester/workspace/acme/api/../api"), roots))
        assertTrue(WaitingSessionNotifier.isUnder(Paths.get("/srv/other/deep"), roots))
        assertFalse(WaitingSessionNotifier.isUnder(Paths.get("/Users/tester/workspace/acme-2"), roots))
        assertFalse(WaitingSessionNotifier.isUnder(Paths.get("/Users/tester"), roots))
        assertFalse(WaitingSessionNotifier.isUnder(Paths.get("/x"), emptyList()))
    }
}
