package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentFilterState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.KeyStroke
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JLabel

class SessionSwitcherUiTest : BasePlatformTestCase() {
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
        val title = component.components.filterIsInstance<JPanel>().single().components.filterIsInstance<JLabel>().first()
        assertEquals("<html>literal title", title.text)
        assertEquals(true, title.getClientProperty("html.disable"))
    }
}
