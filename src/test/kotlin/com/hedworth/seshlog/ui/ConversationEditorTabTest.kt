package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.model.EntryKind
import com.hedworth.seshlog.settings.SeshlogSettings
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
    fun `test full editor opens the ranked tool passage and rejects a reclassified target`() {
        val session = Session(AgentKind.CLAUDE_CODE, "ranked-tool-editor", "Synthetic", Paths.get("/synthetic"),
            null, null, Instant.EPOCH, null, false, null, null, 1, true)
        val tool = ConversationEntry(ConversationMessage(Role.USER, "third needle tool", null), "t", EntryKind.TOOL_RESULT)
        val primary = SearchPrimaryMatch(SearchMatchSource.TOOL_RESULT, "t", 6..11,
            com.hedworth.seshlog.index.lexical.searchHash(tool.text))
        val hit = SearchHit(session, 1.0, false, "needle tool", "t", range = primary.range,
            entryHash = primary.entryHash, primary = primary, sources = SearchSources.ALL)
        for (reclassified in listOf(false, true)) {
            val entries = listOf(ConversationEntry(ConversationMessage(Role.USER, "first needle user", null), "u"),
                if (reclassified) tool.copy(kind = EntryKind.DIALOGUE) else tool)
            val file = ConversationEditorTabs.open(project, session, "needle", loader = { entries }, hit = hit)
            try {
                await { String(file.contentsToByteArray(), StandardCharsets.UTF_8).contains("third needle tool") }
                val editor = FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>().first().editor
                if (reclassified) {
                    assertTrue(editor.document.text.contains("Source changed"))
                    assertEquals(0, editor.caretModel.offset)
                } else assertEquals(editor.document.text.indexOf("needle tool"), editor.caretModel.offset)
            } finally { FileEditorManager.getInstance(project).closeFile(file) }
        }
    }
    fun `test full editor captures source context and navigates original entry bodies only`() {
        val session = Session(AgentKind.CLAUDE_CODE, "editor-sources", "needle title", Paths.get("/synthetic"),
            null, null, Instant.EPOCH, null, false, null, null, 1, true)
        val entries = listOf(
            ConversationEntry(ConversationMessage(Role.USER, "first needle user", null), "u"),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "second needle assistant", null), "a"),
            ConversationEntry(ConversationMessage(Role.USER, "third needle tool", null), "t", EntryKind.TOOL_RESULT, toolName = "needle"),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "needle coverage", null), "c", EntryKind.COVERAGE),
        )
        val settings = SeshlogSettings.getInstance()
        val previous = settings.searchSources
        val file = ConversationEditorTabs.open(project, session, "needle", loader = { entries }, sources = SearchSources(true, false, false))
        try {
            await { String(file.contentsToByteArray(), StandardCharsets.UTF_8).contains("third needle tool") }
            val editor = FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>().first().editor
            val text = editor.document.text
            assertTrue(text.contains("Search in: Your messages · 1 body matches"))
            val expected = text.indexOf("needle user")
            assertEquals(expected, editor.caretModel.offset)
            settings.searchSources = SearchSources.ALL
            repeat(3) {
                assertTrue(ConversationEditorTabs.navigate(project, true))
                assertEquals(expected, editor.caretModel.offset)
            }
            assertTrue(ConversationEditorTabs.navigate(project, false))
            assertEquals(expected, editor.caretModel.offset)
            assertTrue(editor.markupModel.allHighlighters.any { it.startOffset == expected && it.endOffset == expected + 6 })
            assertFalse(editor.markupModel.allHighlighters.any { it.startOffset == text.indexOf("needle tool") })
            assertFalse(file.isWritable)
        } finally { FileEditorManager.getInstance(project).closeFile(file); settings.searchSources = previous }
    }

    fun `test metadata primary starts at the explanation and explicit navigation visits eligible body matches`() {
        val session = Session(AgentKind.CLAUDE_CODE, "metadata-editor", "needle title", Paths.get("/synthetic"),
            null, null, Instant.EPOCH, null, false, null, null, 1, true)
        val entries = listOf(ConversationEntry(ConversationMessage(Role.USER, "body needle", null), "u"))
        val hit = SearchHit(session, 1.0, true, "needle title", primary = SearchPrimaryMatch(SearchMatchSource.TITLE,
            metadataVariant = SearchMetadataVariant.PROVIDER_TITLE))
        val file = ConversationEditorTabs.open(project, session, "needle", loader = { entries }, hit = hit)
        try {
            await { String(file.contentsToByteArray(), StandardCharsets.UTF_8).contains("body needle") }
            val editor = FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>().first().editor
            assertTrue(editor.document.text.contains("Matched session title"))
            assertEquals(0, editor.caretModel.offset)
            assertTrue(ConversationEditorTabs.navigate(project, true))
            assertEquals(editor.document.text.indexOf("needle", editor.document.text.indexOf("body needle")), editor.caretModel.offset)
        } finally { FileEditorManager.getInstance(project).closeFile(file) }
    }
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
