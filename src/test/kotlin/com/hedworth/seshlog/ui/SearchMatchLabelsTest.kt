package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.index.lexical.SearchSessionKey
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode

class SearchMatchLabelsTest : BasePlatformTestCase() {
    fun `test rows tooltips and accessibility describe every actual primary source`() {
        val session = Session(AgentKind.CLAUDE_CODE, "labels", "Provider needle", Path.of("/synthetic"), null,
            null, Instant.EPOCH, null, false, null, null, 1, true)
        val renderer = SessionCellRenderer()
        val tree = com.intellij.ui.treeStructure.Tree()
        val organisation = SessionOrganisation.getInstance()
        try {
            organisation.edit(session.id) { it.title = "Local display" }
            for (source in SearchMatchSource.entries) {
                val primary = SearchPrimaryMatch(source, toolName = "shell".takeIf { source.entrySource in listOf(SearchEntrySource.TOOL_CALL, SearchEntrySource.TOOL_RESULT) },
                    metadataVariant = SearchMetadataVariant.PROVIDER_TITLE.takeIf { source == SearchMatchSource.TITLE })
                renderer.hits = mapOf(SearchSessionKey(session.kind, session.id) to SearchHit(session, 1.0, false, "Provider needle", primary = primary))
                renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(session), false, false, true, 0, false)
                assertTrue(renderer.matchLabel.text.startsWith(source.label))
                assertTrue(renderer.toolTipText.contains(source.label))
                assertTrue(renderer.accessibleContext.accessibleDescription.contains("Matched ${source.label}"))
                assertTrue(renderer.accessibleContext.accessibleName.contains(source.label))
                assertTrue(renderer.titleLabel.text.contains("Local display"))
                if (source == SearchMatchSource.TITLE) assertTrue(renderer.toolTipText.contains("provider title"))
                if (source == SearchMatchSource.TOOL_RESULT) assertTrue(renderer.matchLabel.text.contains("shell"))
            }
        } finally { organisation.loadState(SessionOrganisation.State()) }
    }
}
