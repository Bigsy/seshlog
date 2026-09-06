package com.hedworth.seshlog.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class SessionOrganisationTest : BasePlatformTestCase() {
    fun `test metadata survives XML roundtrip without transcript content`() {
        val store = SessionOrganisation()
        store.edit("synthetic") { it.pinned = true; it.title = "Local title"; it.hidden = true }
        val copy = SessionOrganisation()
        copy.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), SessionOrganisation.State::class.java))
        assertTrue(copy.metadata("synthetic").pinned)
        assertTrue(copy.metadata("synthetic").hidden)
        assertEquals("Local title", copy.metadata("synthetic").title)
        assertFalse(copy.metadata("other").hidden)
    }
}
