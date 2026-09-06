package com.hedworth.seshlog.model

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ProviderScanTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `missing empty unreadable and recovered storage have different states`() {
        val root = tmp.root.toPath()
        assertEquals(ProviderHealth.MISSING, ProviderScan.read(root.resolve("absent"), true, { error("must not scan") }).health)
        assertEquals(ProviderHealth.READY, ProviderScan.read(root, true, { emptyList() }).health)
        val failed = ProviderScan.read(root, true, { throw java.io.IOException("Locked") })
        assertEquals(ProviderHealth.ERROR, failed.health)
        assertTrue(failed.problem!!.contains("Locked"))
        assertEquals(ProviderHealth.READY, ProviderScan.read(root, true, { emptyList() }).health)
        assertEquals(ProviderHealth.ERROR, ProviderScan.read(root, false, { emptyList() }).health)
        assertEquals(ProviderHealth.ERROR, ProviderScan.read(root, true, { emptyList() }, { "Unreadable child directory" }).health)
    }
}
