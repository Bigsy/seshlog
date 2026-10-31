package com.hedworth.seshlog.review

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Checkout
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths

class ReviewDiffExtensionTest : BasePlatformTestCase() {
    private val service get() = ReviewDraftService.getInstance()
    private val ids = mutableSetOf<String>()
    private val checkout = Checkout(Paths.get("/synthetic/review"), Paths.get("/synthetic/review/.git"),
        Paths.get("/synthetic/review/.git"), Checkout.Kind.MAIN, "topic", "a".repeat(40), directoryKey = "synthetic")
    private fun draft(): ReviewDraft = service.store.open(ReviewRecipient(AgentKind.CODEX, "synthetic-$name", checkout)).also { ids += it.id }
    override fun tearDown() {
        try { ids.forEach { service.store.discard(it) }; service.changed() } finally { super.tearDown() }
    }
    fun `test registered native extension maps keyboard and popup actions only in opted in diffs`() {
        val draft = draft()
        val file = ReviewFileSnapshot("file.kt", "a".repeat(40), "old\nkeep\n", "new\nkeep\n")
        service.store.load(draft.id, file)
        val selected = mutableListOf<Triple<ReviewSide, Int, Int>>()
        val processor = processor(request(file, ReviewDiffContext(draft.id, file.path, file.revision, { side, first, last -> selected += Triple(side, first, last) })))
        val viewer = processor.activeViewer as SimpleDiffViewer
        val left = viewer.editor1
        val right = viewer.editor2
        left.selectionModel.setSelection(0, left.document.getLineStartOffset(1))
        val local = ActionUtil.getActions(left.contentComponent).single { it.templateText == "Add Review Comment" }
        local.actionPerformed(AnActionEvent.createEvent(local, DataContext.EMPTY_CONTEXT, local.templatePresentation.clone(), "test", ActionUiKind.NONE, null))
        right.caretModel.moveToOffset(right.document.getLineStartOffset(1))
        ReviewDiffExtension.add(right, right.getUserData(ReviewDiffExtension.EDITOR_BINDING)!!)
        assertEquals(listOf(Triple(ReviewSide.BASE, 1, 1), Triple(ReviewSide.CURRENT, 2, 2)), selected)
        val markers = (left.markupModel.allHighlighters + right.markupModel.allHighlighters).filter { it.gutterIconRenderer?.tooltipText == "Add Review Comment" }
        assertTrue(markers.isNotEmpty())
        processor.show(request(file, null))
        assertNull(left.getUserData(ReviewDiffExtension.EDITOR_BINDING))
        assertTrue(markers.none { it.isValid })
        assertFalse(ActionUtil.getActions(left.contentComponent).contains(local))
        assertTrue((processor.activeViewer as SimpleDiffViewer).editors.all { it.getUserData(ReviewDiffExtension.EDITOR_BINDING) == null })
    }
    fun `test two native views share comment decorations and dispose independently`() {
        val draft = draft()
        val file = ReviewFileSnapshot("file.kt", "a".repeat(40), "old\n", "new\n")
        service.store.load(draft.id, file)
        val context = ReviewDiffContext(draft.id, file.path, file.revision, { _, _, _ -> })
        val first = processor(request(file, context))
        val second = processor(request(file, context))
        val editor = (second.activeViewer as SimpleDiffViewer).editor2
        service.store.add(draft.id, file.path, ReviewSide.CURRENT, 1, 1, "synthetic comment", file.revision)
        service.changed()
        assertTrue(editor.markupModel.allHighlighters.any { it.gutterIconRenderer?.tooltipText == "Review comment" })
        Disposer.dispose(first)
        assertNotNull(editor.getUserData(ReviewDiffExtension.EDITOR_BINDING))
        service.store.markStale(draft.id, setOf(file.path)); service.changed()
        assertTrue(editor.markupModel.allHighlighters.any { it.gutterIconRenderer?.tooltipText == "Review comment — Needs recheck" })
        Disposer.dispose(second)
        service.changed() // No disposed-editor listener may run.
        assertNull(editor.getUserData(ReviewDiffExtension.EDITOR_BINDING))
    }
    fun `test closing and reopening a review view retains draft and preview matches frozen payload`() {
        val draft = draft()
        val file = ReviewFileSnapshot("file.kt", "a".repeat(40), "old\n", "new\n")
        service.store.load(draft.id, file)
        service.store.add(draft.id, file.path, ReviewSide.CURRENT, 1, 1, "Keep note")
        val panel = ReviewCommentsPanel(project, draft.id) {}
        Disposer.dispose(panel)
        val reopened = service.store.open(draft.recipient)
        assertEquals(draft.id, reopened.id)
        assertEquals("Keep note", reopened.comments.single().note)
        val preview = service.store.preview(draft.id)
        val batch = service.store.prepare(draft.id)
        assertEquals(preview, batch.payload)
        service.store.finish(batch, ReviewDeliveryResult(ReviewDeliveryStatus.UNAVAILABLE, "synthetic"))
        val refreshed = file.copy(current = "changed\n")
        service.store.load(draft.id, refreshed)
        try {
            service.store.add(draft.id, file.path, ReviewSide.CURRENT, 1, 1, "Old child diff", file.revision)
            fail("A stale child diff must not anchor into a new file revision")
        } catch (_: IllegalStateException) {}
        assertEquals(1, service.store.get(draft.id).comments.size)
    }
    fun `test unsaved document capture is checkout scoped and does not save the buffer`() {
        val file = myFixture.addFileToProject("source.kt", "on disk")
        val manager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val document = manager.getDocument(file.virtualFile)!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.setText("unsaved edit") }
        val root = Paths.get(file.virtualFile.parent.path).toAbsolutePath().normalize()
        val recipient = ReviewRecipient(AgentKind.CODEX, "synthetic", checkout.copy(root = root))
        assertTrue(manager.isDocumentUnsaved(document))
        assertEquals(setOf("source.kt"), ReviewUnsavedBuffers.capture(recipient))
        assertTrue(manager.isDocumentUnsaved(document))
        assertTrue(ReviewUnsavedBuffers.capture(recipient.copy(checkout = checkout)).isEmpty())
    }
    private fun request(file: ReviewFileSnapshot, context: ReviewDiffContext?): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        return SimpleDiffRequest("Synthetic review", factory.create(project, file.before), factory.create(project, file.current), "Base", "On-disk snapshot")
            .apply { putUserData(ReviewDiffExtension.REVIEW_CONTEXT, context) }
    }
    private fun processor(request: DiffRequest) = Processor().also { Disposer.register(testRootDisposable, it); it.show(request) }
    private inner class Processor : DiffRequestProcessor(project) {
        override fun updateRequest(force: Boolean, scrollToChangePolicy: DiffUserDataKeysEx.ScrollToPolicy?) = Unit
        fun show(request: DiffRequest) = doApplyRequest(request)
    }
}
