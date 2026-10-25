package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.index.lexical.*
import com.hedworth.seshlog.model.*
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.time.Instant

class RankedPassageTest : BasePlatformTestCase() {
    private fun session(kind:AgentKind=AgentKind.CLAUDE_CODE)=Session(kind,"same-id","Title",Path.of("/synthetic"),null,null,Instant.EPOCH,null,false,null,null,1,true)
    fun `test winning tool passage expands and stale offsets are rejected`() {
        val entry=ConversationEntry(ConversationMessage(Role.ASSISTANT,"prefix 😺 needle suffix",null),"tool",EntryKind.TOOL_RESULT)
        val range=10..15
        val hit=SearchHit(session(),1.0,false,"needle","tool",true,range=range,entryHash=searchHash(entry.text))
        val target=validatedPassage(hit,listOf(entry))!!
        val panel=ConversationSearchPanel()
        panel.showEntries(listOf(entry),"needle",target)
        assertTrue(panel.editor.text.contains("[expanded]"))
        assertEquals("needle",panel.editor.text.substring(panel.editor.caretPosition,panel.editor.caretPosition+6))
        assertNull(validatedPassage(hit,listOf(entry.copy(message=entry.message.copy(text="changed source")))))
        panel.showEntries(listOf(entry),"needle",changed=true)
        assertTrue(panel.status.text.contains("Source changed"))
        assertEquals(0,panel.editor.caretPosition)
    }
    fun `test zero hit partial envelope and provider aware selection`() {
        val disposable=Disposer.newDisposable()
        try {
            val panel=SessionTreePanel(project,disposable)
            panel.renderSearchResults("absent",SearchResultSnapshot(emptyList(),SearchCoverage(2,1,1,0),1))
            assertTrue(panel.tree.emptyText.text.contains("Limited coverage"))
            val a=session();val b=session(AgentKind.CODEX)
            val hits=listOf(SearchHit(a,2.0,false,"a"),SearchHit(b,1.0,false,"b"))
            panel.renderSearchResults("needle",hits)
            val root=panel.tree.model.root as javax.swing.tree.DefaultMutableTreeNode
            val node=com.intellij.util.ui.tree.TreeUtil.findNodeWithObject(root,b)!!
            panel.tree.selectionPath=javax.swing.tree.TreePath(node.path)
            panel.renderSearchResults("needle",hits.reversed())
            assertEquals(AgentKind.CODEX,panel.selectedSession()!!.kind)
            assertEquals("b",panel.selectedSearchHit()!!.snippet)
        } finally { Disposer.dispose(disposable) }
    }
}
