package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.settings.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.time.Instant
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem

class SearchSourcesControlTest : BasePlatformTestCase() {
    fun `test typing away and back reruns a query whose previous generation was cancelled`() {
        val owner = Disposer.newDisposable()
        try {
            val panel = SessionTreePanel(project, owner)
            panel.searchField.text = "needle"
            fun await(done: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 5000
                while (!done() && System.currentTimeMillis() < deadline) {
                    com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                    Thread.sleep(10)
                }
                assertTrue(done())
            }
            await { panel.searchRequestCount >= 1 }
            val before = panel.searchRequestCount
            panel.searchField.text = "different"
            panel.searchField.text = "needle"
            await { panel.searchRequestCount > before }
            val submitted = panel.searchRequestCount
            panel.searchField.text = ""
            SeshlogSettings.getInstance().searchSources.let { current ->
                SeshlogSettings.getInstance().searchSources = current.copy(toolActivity = !current.toolActivity)
                assertEquals(submitted, panel.searchRequestCount)
                SeshlogSettings.getInstance().searchSources = current
            }
        } finally { Disposer.dispose(owner) }
    }
    fun `test all menu states persist across panels without broad settings or scan events`() {
        val settings = SeshlogSettings.getInstance()
        val previous = settings.searchSources
        val ownerA = Disposer.newDisposable()
        val ownerB = Disposer.newDisposable()
        try {
            settings.searchSources = SearchSources.DEFAULT
            val a = SessionTreePanel(project, ownerA)
            val b = SessionTreePanel(project, ownerB)
            var broadEvents = 0
            var scans = 0
            ApplicationManager.getApplication().messageBus.connect(ownerA).subscribe(SeshlogSettingsListener.TOPIC,
                object : SeshlogSettingsListener { override fun settingsChanged() { broadEvents++ } })
            ApplicationManager.getApplication().messageBus.connect(ownerA).subscribe(SessionIndex.TOPIC,
                object : SessionIndex.SessionIndexListener {
                    override fun sessionsUpdated(sessions: List<Session>) { scans++ }
                    override fun scanStateChanged(scanning: Boolean) { scans++ }
                })
            for (bits in 0..7) {
                val expected = SearchSources(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
                settings.searchSources = expected
                assertEquals(expected, a.activeSources)
                assertEquals(expected, b.activeSources)
                assertTrue(a.sourcesButton.text.contains(expected.summary))
                assertEquals(expected.description, b.sourcesButton.accessibleContext.accessibleDescription)
                val menu = a.createSourcesMenu()
                val checks = menu.components.filterIsInstance<JCheckBoxMenuItem>()
                assertEquals(listOf("Your messages", "Assistant replies", "Tool activity"), checks.map { it.text })
                assertEquals(listOf(expected.userMessages, expected.assistantReplies, expected.toolActivity), checks.map { it.isSelected })
                assertTrue(menu.components.filterIsInstance<JMenuItem>().any { it.text == "Session titles and project paths are always included." })
            }
            a.searchField.text = "x"
            val checks = a.createSourcesMenu().components.filterIsInstance<JCheckBoxMenuItem>()
            checks[0].doClick(0)
            checks[1].doClick(0)
            checks[2].doClick(0)
            assertEquals(SearchSources.METADATA_ONLY, settings.searchSources)
            assertEquals("x", a.searchField.text)
            assertEquals("", a.activeQuery)
            val reset = a.createSourcesMenu().components.filterIsInstance<JMenuItem>().last()
            reset.doClick(0)
            assertEquals(SearchSources.DEFAULT, b.activeSources)
            assertEquals("x", a.searchField.text)
            assertEquals(0, broadEvents)
            assertEquals(0, scans)
            Disposer.dispose(ownerA)
            settings.searchSources = SearchSources.ALL
            assertEquals(SearchSources.ALL, b.activeSources)
        } finally {
            if (!Disposer.isDisposed(ownerA)) Disposer.dispose(ownerA)
            Disposer.dispose(ownerB)
            settings.searchSources = previous
        }
    }

    fun `test toggling invalidates displayed hits immediately and rejects late source envelopes`() {
        val settings = SeshlogSettings.getInstance()
        val previous = settings.searchSources
        val owner = Disposer.newDisposable()
        try {
            settings.searchSources = SearchSources.ALL
            val panel = SessionTreePanel(project, owner)
            panel.searchField.text = "needle"
            val session = Session(AgentKind.CLAUDE_CODE, "toggle-synthetic", "Title", Path.of("/synthetic"), null,
                null, Instant.EPOCH, null, false, null, null, 1, true)
            val old = SearchResultSnapshot(listOf(SearchHit(session, 1.0, false, "tool needle", sources = SearchSources.ALL)),
                SearchCoverage(1, 1, 0, 0), 1, context = SearchContext("needle", SearchSources.ALL))
            panel.renderSearchResults("needle", old)
            assertEquals(1, panel.visibleSessions.size)
            val root = panel.tree.model.root as javax.swing.tree.DefaultMutableTreeNode
            val selected = com.intellij.util.ui.tree.TreeUtil.findNodeWithObject(root, session)!!
            panel.tree.selectionPath = javax.swing.tree.TreePath(selected.path)
            settings.searchSources = SearchSources.DEFAULT
            assertTrue(panel.visibleSessions.isEmpty())
            assertNull(panel.selectedSearchHit())
            assertTrue(panel.tree.emptyText.text.contains("Searching"))
            panel.renderSearchResults("needle", old)
            assertTrue(panel.visibleSessions.isEmpty())
            assertEquals("needle", panel.searchField.text)
            panel.renderSearchResults("needle", old.copy(hits = old.hits.map { it.copy(sources = SearchSources.DEFAULT) }, context = SearchContext("needle")))
            assertEquals(session.id, panel.selectedSession()!!.id)
            settings.searchSources = SearchSources.METADATA_ONLY
            panel.searchField.text = ""
            assertEquals(SearchSources.METADATA_ONLY, panel.activeSources)
            assertEquals(SearchSources.METADATA_ONLY, settings.searchSources)
        } finally { Disposer.dispose(owner); settings.searchSources = previous }
    }

    fun `test unrelated settings reset and apply preserve a source change made while settings are open`() {
        val settings = SeshlogSettings.getInstance()
        val previous = settings.searchSources
        val configurable = SeshlogConfigurable()
        try {
            val form = configurable.createComponent() as com.intellij.openapi.ui.DialogPanel
            configurable.reset()
            settings.searchSources = SearchSources.METADATA_ONLY
            // Exercise the same bindings as Settings Apply without initiating a provider scan.
            form.apply()
            assertEquals(SearchSources.METADATA_ONLY, settings.searchSources)
            configurable.reset()
            assertEquals(SearchSources.METADATA_ONLY, settings.searchSources)
        } finally { configurable.disposeUIResources(); settings.searchSources = previous }
    }
}
