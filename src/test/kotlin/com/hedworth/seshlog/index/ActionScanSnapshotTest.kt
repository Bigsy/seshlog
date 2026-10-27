package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ProviderHealth
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class ActionScanSnapshotTest {
    @Test fun `timeout failure and discarded scan cannot provide a successful action receipt`() {
        val timedOut = CompletableFuture<ActionScanSnapshot?>()
        assertNull(ActionScanSnapshot.await(1) { timedOut })
        assertTrue(timedOut.isCancelled)
        val failed = CompletableFuture<ActionScanSnapshot?>().apply { completeExceptionally(IllegalStateException("scan failed")) }
        assertNull(ActionScanSnapshot.await(100) { failed })
        assertNull(ActionScanSnapshot.await(100) { CompletableFuture.completedFuture(null) })
        assertNull(ActionScanSnapshot.await(100) { error("executor unavailable") })
    }

    @Test fun `only the successfully refreshed relevant provider permits action validation`() {
        val result = ActionScanSnapshot.await(100) { CompletableFuture.completedFuture(ActionScanSnapshot(mapOf(
            AgentKind.CLAUDE_CODE to ProviderHealth.READY, AgentKind.CODEX to ProviderHealth.ERROR,
            AgentKind.OPENCODE to ProviderHealth.MISSING))) }!!
        assertTrue(result.ready(AgentKind.CLAUDE_CODE))
        assertFalse(result.ready(AgentKind.CODEX))
        assertFalse(result.ready(AgentKind.OPENCODE))
        assertFalse(result.ready(AgentKind.PI))
    }
}
