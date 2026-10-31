package com.hedworth.seshlog.review

import com.hedworth.seshlog.model.AgentKind
import org.junit.Assert.*
import org.junit.Test

class ReviewDeliverySupportTest {
    @Test fun `preflight distinguishes unsupported platform and provider from unverified terminal`() {
        assertNull(ReviewDeliverySupport.unavailableReason(AgentKind.CODEX, "Mac OS X"))
        for (os in listOf("Linux", "Windows 11", "unknown")) {
            assertEquals("platform_unsupported", ReviewDeliverySupport.unavailableReason(AgentKind.CODEX, os))
            assertTrue(ReviewDeliverySupport.description(AgentKind.CODEX, os).contains("unavailable on $os"))
            assertTrue(ReviewDeliverySupport.description(AgentKind.CODEX, os).contains("Copy Review"))
        }
        for (kind in AgentKind.entries.filter { it != AgentKind.CODEX })
            assertEquals("provider_unsupported", ReviewDeliverySupport.unavailableReason(kind, "Mac OS X"))
    }
}
