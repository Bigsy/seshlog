package com.hedworth.seshlog.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CopyActionRegistrationTest : BasePlatformTestCase() {
    fun testBothCopyActionsAreDiscoverableAndHaveNoDefaultShortcut() {
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
            assertTrue(action.shortcutSet.shortcuts.isEmpty())
            assertTrue(action in children)
        }
    }
}
