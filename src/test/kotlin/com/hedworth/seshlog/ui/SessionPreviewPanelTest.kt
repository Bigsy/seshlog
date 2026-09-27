package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths
import java.time.Instant

class SessionPreviewPanelTest : BasePlatformTestCase() {
    fun `test preview header uses the local session title`() {
        val organisation = SessionOrganisation.getInstance()
        val session = Session(
            AgentKind.CLAUDE_CODE, "preview-title", "Agent title", Paths.get("/synthetic"), null,
            null, Instant.EPOCH, null, false, null, null, 1, true,
        )
        val disposable = Disposer.newDisposable()
        try {
            organisation.edit(session.id) { it.title = "Local title" }
            assertEquals("Local title (fork)", com.hedworth.seshlog.terminal.TerminalTabs.forkTitle(session))
            val panel = SessionPreviewPanel(disposable)
            panel.showSession(session)
            assertTrue(panel.headerText.contains("Local title"))
            assertFalse(panel.headerText.contains("Agent title"))
        } finally {
            Disposer.dispose(disposable)
            organisation.loadState(SessionOrganisation.State())
        }
    }
}
