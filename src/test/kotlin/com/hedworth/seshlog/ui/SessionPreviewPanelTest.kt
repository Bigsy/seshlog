package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths
import java.time.Instant

class SessionPreviewPanelTest : BasePlatformTestCase() {
    fun `test identical query and session with new source context invalidates the preview load`() {
        val owner = Disposer.newDisposable()
        val settings = com.hedworth.seshlog.settings.SeshlogSettings.getInstance()
        val previous = settings.previewCollapsed
        try {
            settings.previewCollapsed = false
            val file = Paths.get(javaClass.getResource("/fixtures/claude_tools.jsonl")!!.toURI())
            val session = Session(AgentKind.CLAUDE_CODE, "preview-context", "Fixture", file.parent, null,
                null, Instant.EPOCH, file, false, null, null, 1, true)
            val panel = SessionPreviewPanel(owner)
            panel.showSession(session, "TOOL_ONLY", sources = com.hedworth.seshlog.index.SearchSources.ALL)
            val count = panel.loadRequestCount
            panel.showSession(session, "TOOL_ONLY", sources = com.hedworth.seshlog.index.SearchSources.DEFAULT)
            assertEquals(count + 1, panel.loadRequestCount)
            assertEquals(com.hedworth.seshlog.index.SearchSources.DEFAULT, panel.searchSources)
            assertEquals("", panel.conversation.editor.text)
        } finally { Disposer.dispose(owner); settings.previewCollapsed = previous }
    }
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
