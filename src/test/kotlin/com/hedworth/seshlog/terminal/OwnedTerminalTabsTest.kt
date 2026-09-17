package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.copy.CopyTarget
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentUI
import java.nio.file.Path
import java.time.Instant
import javax.swing.JPanel

/** Exercise the ownership service as well as the observer: activity polls run during drags. */
class OwnedTerminalTabsTest : BasePlatformTestCase() {
    private fun manager(): ContentManager {
        val ui = object : ContentUI {
            private val panel = JPanel()
            override fun getComponent() = panel
            override fun setManager(manager: ContentManager) = Unit
            override fun isSingleSelection() = true
            override fun isToSelectAddedContent() = true
            override fun canBeEmptySelection() = true
            override fun canChangeSelectionTo(content: Content, implicit: Boolean) = true
            override fun getCloseActionName() = "Close"
            override fun getCloseAllButThisActionName() = "Close others"
            override fun getPreviousContentActionName() = "Previous"
            override fun getNextContentActionName() = "Next"
        }
        return ContentFactory.getInstance().createContentManager(ui, true, project).also {
            Disposer.register(testRootDisposable, it)
        }
    }

    private fun track(owned: OwnedTerminalTabs, manager: ContentManager, id: String): Content {
        val content = ContentFactory.getInstance().createContent(JPanel(), id, false)
        manager.addContent(content)
        val session = Session(AgentKind.CODEX, id, id, Path.of("/same/project"), null, null,
            Instant.EPOCH, null, false, null, null, 0, false)
        owned.track(session, object : TerminalHandle {
            override val content = content
            override fun shellPid(): Long? = null
            override fun state() = TerminalState.UNKNOWN
            override fun execute(command: String) = Unit
        })
        return content
    }

    fun testActivityLookupsDuringDragDoNotEraseAssociationAndRealClosureDoes() {
        val owned = OwnedTerminalTabs(project)
        Disposer.register(testRootDisposable, owned)
        val left = manager()
        val right = manager()
        val a = track(owned, left, "a")
        val b = track(owned, left, "b")
        repeat(3) {
            val from = requireNotNull(b.manager)
            val to = if (from === left) right else left
            from.removeContent(b, false)
            assertNull(b.manager)
            assertFalse("Cannot close a tab while it is detached", owned.close("b"))
            assertFalse(owned.focus("b"))
            // Exactly the terminal lookup used by the two-second activity and restore polls.
            repeat(3) { assertNull(owned.terminalFor("b")) }
            assertTrue("Detachment is not closure", owned.owns("b"))
            assertEquals("b", owned.sessionFor(b))
            to.addContent(b)
            val focus = javax.swing.JTextArea().also { b.component.add(it) }
            val selected = CopyTarget.focusedContent(focus, listOf(a, b)) { it.component }
            assertEquals("b", owned.sessionFor(requireNotNull(selected)))
            assertEquals(setOf("a", "b"), owned.sessionIds)
        }
        // A temporarily unavailable widget in an attached pane must also retain ownership.
        assertNull(owned.terminalFor("b"))
        assertTrue(owned.owns("b"))
        requireNotNull(b.manager).removeContent(b, true)
        assertNull(owned.terminalFor("b"))
        assertFalse("Actual disposal clears ownership", owned.owns("b"))
        assertTrue(owned.owns("a"))
        assertTrue(owned.close("a"))
        assertFalse(owned.owns("a"))
        assertTrue(owned.close("already-closed"))
    }
}
