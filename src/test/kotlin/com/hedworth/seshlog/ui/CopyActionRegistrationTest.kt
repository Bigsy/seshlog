package com.hedworth.seshlog.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CopyActionRegistrationTest : BasePlatformTestCase() {
    fun testCopyActionsAreDiscoverableWithOnlyAssistantCopyBoundToControlO() {
        val manager = ActionManager.getInstance()
        val menu = manager.getAction("Seshlog.ContextMenu") as ActionGroup
        val children = menu.getChildren(null).toSet()
        for ((id, name) in listOf(
            "Seshlog.CopyLastAssistantMessage" to "Seshlog: Copy Last Assistant Message",
            "Seshlog.CopyLatestPlan" to "Seshlog: Copy Latest Plan",
        )) {
            val action = manager.getAction(id)
            assertNotNull(action)
            assertEquals(name, action.templatePresentation.text)
            if (id == "Seshlog.CopyLastAssistantMessage") {
                val expected = com.intellij.openapi.actionSystem.KeyboardShortcut(
                    javax.swing.KeyStroke.getKeyStroke("ctrl O"), null)
                assertEquals(listOf(expected), action.shortcutSet.shortcuts.toList())
            } else assertTrue(action.shortcutSet.shortcuts.isEmpty())
            assertTrue(action in children)
        }
    }
}
