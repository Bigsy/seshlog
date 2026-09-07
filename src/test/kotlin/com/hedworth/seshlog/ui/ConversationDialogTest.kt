package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.*
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConversationDialogTest : BasePlatformTestCase() {
    fun testCopyActionsDisableWhileLoadingAndCopyTheCurrentMatch() {
        val gate = CountDownLatch(1)
        val copied = mutableListOf<String>()
        val entries = listOf(
            ConversationEntry(ConversationMessage(Role.USER, "question\n```\ncode\n```", null), "u"),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "TOOL_MATCH output", null), "tool", EntryKind.TOOL_RESULT))
        val dialog = ConversationDialog(project, session(), "TOOL_MATCH", loader = {
            check(gate.await(5, TimeUnit.SECONDS)); entries
        }, copy = { copied += it })
        try {
            assertFalse(dialog.copyMessageButton.isEnabled)
            assertFalse(dialog.copyDialogueButton.isEnabled)
            gate.countDown()
            await { dialog.copyMessageButton.isEnabled }
            assertTrue(dialog.copyDialogueButton.isEnabled)
            dialog.copyMessageButton.doClick()
            assertEquals(listOf("TOOL_MATCH output"), copied)
            dialog.copyDialogueButton.doClick()
            assertEquals("You:\nquestion\n```\ncode\n```", copied.last())
            // Ordinary selection is untouched by the additional copy actions.
            dialog.editor.select(0, 4)
            assertEquals(dialog.editor.text.substring(0, 4), dialog.editor.selectedText)
        } finally { gate.countDown(); Disposer.dispose(dialog.disposable) }
    }

    fun testEmptyAndFailedLoadsKeepCopyDisabled() {
        for (fail in listOf(false, true)) {
            val finished = CountDownLatch(1)
            val dialog = ConversationDialog(project, session(), "", loader = {
                try { if (fail) throw java.io.IOException("unreadable") else emptyList() }
                finally { finished.countDown() }
            }, copy = { fail("Nothing should be copied") })
            try {
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                assertFalse(dialog.copyMessageButton.isEnabled)
                assertFalse(dialog.copyDialogueButton.isEnabled)
            } finally { Disposer.dispose(dialog.disposable) }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun session() = Session(AgentKind.CLAUDE_CODE, "copy-test", "Synthetic", Paths.get("/synthetic"),
        null, null, Instant.EPOCH, null, false, null, null, 1, true)
}
