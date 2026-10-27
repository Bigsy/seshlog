package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentFilterState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JList
import javax.swing.JPanel

class SessionSwitcherUiTest : BasePlatformTestCase() {
    fun `test keyboard moves between enabled worktree and resume buttons without launching`() {
        val focused = mutableListOf<JButton>()
        val traversed = mutableListOf<Pair<JButton, Boolean>>()
        val panel = SessionSwitcherPanel(project, live = false,
            focusButton = { focused += it }, traverseFocus = { button, forward -> traversed += button to forward })
        try {
            val row = SwitcherRow(switchSession())
            panel.results.apply(panel.results.begin(""), listOf(row))
            panel.render()
            assertTrue(panel.resume.isEnabled)
            assertTrue(panel.open.isEnabled)
            var launched = 0
            panel.resume.addActionListener { launched++ }
            panel.open.addActionListener { launched++ }

            fun press(button: JButton, key: String) {
                val binding = button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key))
                assertNotNull("No binding for $key", binding)
                val action = button.actionMap.get(binding)
                assertNotNull("No action for $key", action)
                action!!.actionPerformed(ActionEvent(button, ActionEvent.ACTION_PERFORMED, key))
            }

            panel.enter()
            assertEquals(listOf(panel.resume), focused)
            press(panel.resume, "LEFT")
            press(panel.open, "RIGHT")
            press(panel.resume, "shift TAB")
            press(panel.open, "TAB")
            assertEquals(listOf(panel.resume, panel.open, panel.resume, panel.open, panel.resume), focused)
            for (button in listOf(panel.open, panel.resume)) {
                assertTrue(button.focusTraversalKeysEnabled)
                assertFalse(KeyStroke.getKeyStroke("TAB") in button.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS))
                assertFalse(KeyStroke.getKeyStroke("shift TAB") in button.getFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS))
            }
            assertEquals(0, launched)

            press(panel.open, "shift TAB")
            press(panel.resume, "TAB")
            assertEquals(listOf(panel.open to false, panel.resume to true), traversed)

            val noWorktree = row.copy(session = row.session.copy(checkout = null))
            panel.results.apply(panel.results.begin("other"), listOf(noWorktree))
            panel.render()
            assertFalse(panel.open.isEnabled)
            focused.clear()
            traversed.clear()
            press(panel.resume, "LEFT")
            press(panel.resume, "shift TAB")
            assertTrue(focused.isEmpty())
            assertEquals(listOf(panel.resume to false), traversed)
            assertEquals(0, launched)
        } finally { Disposer.dispose(panel) }
    }

    fun `test default shortcuts are registered for macOS and other platforms`() {
        assertNotNull(ActionManager.getInstance().getAction("Seshlog.SwitchSession"))
        val keymaps = KeymapManager.getInstance()
        val common = KeyboardShortcut(KeyStroke.getKeyStroke("control alt shift P"), null)
        val mac = KeyboardShortcut(KeyStroke.getKeyStroke("meta alt S"), null)
        assertTrue(common in keymaps.getKeymap("\$default")!!.getShortcuts("Seshlog.SwitchSession"))
        for (name in listOf("Mac OS X", "Mac OS X 10.5+")) {
            val shortcuts = keymaps.getKeymap(name)!!.getShortcuts("Seshlog.SwitchSession")
            assertTrue("$name should use the macOS shortcut", mac in shortcuts)
            assertFalse("$name should not inherit the other platform shortcut", common in shortcuts)
        }
    }

    fun `test registered keyboard action and stopped enter offer explicit resume without changing filters`() {
        assertNotNull(ActionManager.getInstance().getAction("Seshlog.SwitchSession"))
        val filter = AgentFilterState.getInstance(project)
        val before = filter.mode
        val panel = SessionSwitcherPanel(project, live = false)
        try {
            filter.mode = AgentFilterMode.Only(com.hedworth.seshlog.model.AgentKind.CLAUDE_CODE)
            val row = SwitcherRow(switchSession())
            panel.results.apply(panel.results.begin(""), listOf(row))
            panel.render()
            assertEquals(row, panel.list.selectedValue)
            assertTrue(panel.resume.isEnabled)
            panel.enter()
            assertTrue(panel.status.text.contains("Choose Resume"))
            assertEquals(AgentFilterMode.Only(com.hedworth.seshlog.model.AgentKind.CLAUDE_CODE), filter.mode)
            panel.results.begin("changed")
            panel.enter()
            assertTrue(panel.status.text.contains("Choose Resume")) // Pending results never act.
        } finally { Disposer.dispose(panel); filter.mode = before }
    }

    fun `test refresh keeps provider selection and missing selected row clears action targets`() {
        val panel = SessionSwitcherPanel(project, live = false)
        try {
            val a = SwitcherRow(switchSession("a"))
            val b = SwitcherRow(switchSession("b"))
            panel.results.apply(panel.results.begin(""), listOf(a, b)); panel.render()
            panel.list.selectedIndex = 1
            panel.results.apply(panel.results.begin(""), listOf(b.copy(unread = true), a)); panel.render()
            assertEquals(b.key, panel.list.selectedValue.key)
            assertEquals(1, panel.list.selectedIndex)
            assertTrue(panel.list.selectedValue.unread)
            panel.results.apply(panel.results.begin(""), listOf(a)); panel.render()
            assertNull(panel.list.selectedValue)
            assertFalse(panel.resume.isEnabled)
            assertFalse(panel.open.isEnabled)
        } finally { Disposer.dispose(panel) }
    }

    fun `test renderer treats titles literally and exposes agent path branch and attention to accessibility`() {
        val row = SwitcherRow(switchSession().copy(title = "<html>literal title"), associated = true, unread = true)
        val renderer = SwitcherRenderer()
        val component = renderer.getListCellRendererComponent(JList(), row, 0, true, true) as JPanel
        assertTrue(component.accessibleContext.accessibleName.contains("observed-branch"))
        assertTrue(component.accessibleContext.accessibleName.contains("unread"))
        assertTrue(component.accessibleContext.accessibleName.contains("/synthetic/engine/topic/src"))
        assertEquals("<html>literal title", renderer.title.getCharSequence(false).toString())
        assertEquals(row.details(), renderer.details.getCharSequence(false).toString())
    }

    fun `test renderer colours fields and bolds case insensitive query words across visible metadata`() {
        var query = "DEMO uncertain TOPIC observed"
        val renderer = SwitcherRenderer { query }
        val list = JList<SwitcherRow>()
        val row = SwitcherRow(switchSession().copy(title = "Demo Uncertain location"),
            projectNames = listOf("Demo Engine"))
        renderer.getListCellRendererComponent(list, row, 0, false, false)
        assertEquals(listOf("Demo", "Uncertain"), boldFragments(renderer.title))
        assertEquals(listOf("Demo", "observed", "topic"), boldFragments(renderer.details))
        val details = fragments(renderer.details)
        val agent = details.first { it.first == "Codex" }.second.fgColor
        val branch = details.first { it.first == "observed" }.second.fgColor
        val path = details.first { it.first.startsWith("/synthetic/") }.second.fgColor
        assertEquals(3, setOf(agent, branch, path).size)

        // Selection outlines the row without changing field colours or moving the text.
        val title = fragments(renderer.title)
        val insets = renderer.insets
        val size = renderer.preferredSize
        renderer.getListCellRendererComponent(list, row, 0, true, true)
        assertEquals(list.background, renderer.background)
        assertEquals(title, fragments(renderer.title))
        assertEquals(details, fragments(renderer.details))
        assertEquals(insets, renderer.insets)
        assertEquals(size, renderer.preferredSize)
        assertEquals(listOf("Demo", "Uncertain"), boldFragments(renderer.title))

        query = ""
        renderer.getListCellRendererComponent(list, row, 0, false, false)
        assertTrue(boldFragments(renderer.title).isEmpty())
        assertTrue(boldFragments(renderer.details).isEmpty())
        renderer.getListCellRendererComponent(list, SwitcherRow(switchSession().copy(title = "Another row")), 1, false, false)
        assertEquals("Another row", renderer.title.getCharSequence(false).toString())
        assertFalse(renderer.details.getCharSequence(false).contains("Demo Engine"))
    }

    fun `test renderer highlights overlapping repeated words and literal markup without shifting unicode offsets`() {
        val renderer = SwitcherRenderer { "ana İSTANBUL <b>" }
        val row = SwitcherRow(switchSession().copy(title = "İstanbul banana BANANA <b>literal</b>"))
        renderer.getListCellRendererComponent(JList(), row, 0, false, false)
        assertEquals(row.title, renderer.title.getCharSequence(false).toString())
        assertEquals(listOf("İstanbul", "anana", "ANANA", "<b>"), boldFragments(renderer.title))
    }

    private fun fragments(component: SimpleColoredComponent): List<Pair<String, SimpleTextAttributes>> = buildList {
        val iterator = component.iterator()
        while (iterator.hasNext()) add(iterator.next() to iterator.textAttributes)
    }

    private fun boldFragments(component: SimpleColoredComponent): List<String> = fragments(component)
        .filter { (_, attributes) -> attributes.fontStyle and Font.BOLD != 0 }.map { it.first }
}
