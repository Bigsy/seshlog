package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.settings.SessionAttentionState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Instant

class ConversationEditorTabTest : BasePlatformTestCase() {
    fun testOpeningConversationCreatesReadOnlyInMemoryMarkdown() {
        val session = Session(
            AgentKind.CLAUDE_CODE, "editor-tab", "Synthetic", Paths.get("/synthetic"),
            null, null, Instant.EPOCH, null, false, null, null, 1, true,
        )
        val entries = listOf(
            ConversationEntry(ConversationMessage(Role.USER, "question", null), "u"),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "answer", null), "a"),
        )
        val file = ConversationEditorTabs.open(project, session, loader = { entries })
        try {
            await { String(file.contentsToByteArray(), StandardCharsets.UTF_8).contains("answer") }
            assertFalse(file.isWritable)
            FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>()
                .firstOrNull()?.editor?.document?.let { assertFalse(it.isWritable) }
            assertTrue(String(file.contentsToByteArray(), StandardCharsets.UTF_8).contains("## Assistant"))
        } finally {
            FileEditorManager.getInstance(project).closeFile(file)
        }
    }

    fun testViewingLatestReplyClearsItsUnreadReceipt() {
        val session = Session(
            AgentKind.CLAUDE_CODE, "editor-unread", "Synthetic", Paths.get("/synthetic"),
            null, null, Instant.ofEpochSecond(2), null, false, null, null, 1, true,
            activity = com.hedworth.seshlog.model.Activity.WAITING,
            activitySince = Instant.ofEpochSecond(2),
        )
        val attention = SessionAttentionState.getInstance()
        val receipt = SessionAttention.Completion(session.activitySince.toString(), session.lastActivityAt.toString())
        attention.loadState(SessionAttentionState.State().also { it.unread[session.id] = receipt })
        try {
            val file = ConversationEditorTabs.open(project, session, loader = {
                listOf(ConversationEntry(ConversationMessage(Role.ASSISTANT, "latest reply", null), "a"))
            })
            await { String(file.contentsToByteArray(), StandardCharsets.UTF_8).contains("latest reply") }
            attention.viewed(session.id, attention.receipt(session))
            assertFalse(attention.unreadIds.contains(session.id))
            FileEditorManager.getInstance(project).closeFile(file)
        } finally {
            attention.loadState(SessionAttentionState.State())
        }
    }

    fun testConversationTabKeepsMarkdownAndLabelsEachEntry() {
        val markdown = ConversationEditorTabs.markdown(
            "Synthetic session",
            listOf(
                ConversationEntry(ConversationMessage(Role.USER, "Please fix **this**", null), "u"),
                ConversationEntry(ConversationMessage(Role.ASSISTANT, "```kotlin\nval answer = 42\n```", null), "a"),
            ),
        )

        assertTrue(markdown.startsWith("# Synthetic session"))
        assertTrue(markdown.contains("## You\n\nPlease fix **this**"))
        assertTrue(markdown.contains("## Assistant\n\n```kotlin\nval answer = 42"))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
