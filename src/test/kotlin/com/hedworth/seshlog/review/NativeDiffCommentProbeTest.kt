package com.hedworth.seshlog.review

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffExtension
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class NativeDiffCommentProbeTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        ExtensionTestUtil.maskExtensions(DiffExtension.EP_NAME, listOf(NativeDiffCommentProbe()), testRootDisposable)
    }

    fun `test native extension maps both sides and exclusive selection endpoints`() {
        val review = NativeDiffCommentProbe.Context()
        val processor = processor(request("old one\nold two\nold three\n", "new one\nnew two\nnew three\n", review))
        assertTrue(processor.activeViewer is SimpleDiffViewer)
        assertEquals(2, review.attachments.size)
        val left = review.attachments[0]
        left.editor.selectionModel.setSelection(0, left.editor.document.getLineStartOffset(2))
        left.marker.gutterIconRenderer!!.clickAction!!.actionPerformed(event(left))
        val right = review.attachments[1]
        right.editor.caretModel.moveToOffset(right.editor.document.getLineStartOffset(1))
        right.action.actionPerformed(event(right))
        assertEquals(listOf(NativeDiffCommentProbe.Selection(Side.LEFT, 1, 2),
            NativeDiffCommentProbe.Selection(Side.RIGHT, 2, 2)), review.selections)
    }

    fun `test keyboard action is editor scoped and disposed on request replacement`() {
        val review = NativeDiffCommentProbe.Context()
        val processor = processor(request("before\n", "after\n", review))
        val attached = review.attachments.toList()
        val right = attached[1]
        assertTrue(ActionUtil.getActions(right.editor.contentComponent).contains(right.action))
        assertEquals(NativeDiffCommentProbe.SHORTCUT.shortcuts.toList(), right.action.shortcutSet.shortcuts.toList())
        val key = KeyEvent(right.editor.contentComponent, KeyEvent.KEY_PRESSED, 0,
            InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_K, 'K')
        right.action.actionPerformed(AnActionEvent.createEvent(right.action, DataContext.EMPTY_CONTEXT,
            right.action.templatePresentation.clone(), "SeshlogD1Probe", ActionUiKind.NONE, key))
        assertEquals(listOf(NativeDiffCommentProbe.Selection(Side.RIGHT, 1, 1)), review.selections)
        processor.show(request("ordinary old", "ordinary new"))
        assertTrue(review.attachments.isEmpty())
        for (item in attached) {
            assertFalse(item.marker.isValid)
            assertFalse(ActionUtil.getActions(item.editor.contentComponent).contains(item.action))
        }
        assertTrue((processor.activeViewer as SimpleDiffViewer).editors.none { editor ->
            ActionUtil.getActions(editor.contentComponent).any { it.templateText == "Add Review Comment" }
        })
    }

    fun `test empty side cannot create a comment and concurrent views own their decorations`() {
        val review = NativeDiffCommentProbe.Context()
        val first = processor(request("", "added\n", review))
        val second = processor(request("deleted\n", "", review))
        val attachments = review.attachments.toList()
        attachments[0].action.actionPerformed(event(attachments[0]))
        attachments[3].action.actionPerformed(event(attachments[3]))
        assertTrue(review.selections.isEmpty())
        attachments[1].action.actionPerformed(event(attachments[1]))
        attachments[2].action.actionPerformed(event(attachments[2]))
        assertEquals(listOf(NativeDiffCommentProbe.Selection(Side.RIGHT, 1, 1),
            NativeDiffCommentProbe.Selection(Side.LEFT, 1, 1)), review.selections)
        Disposer.dispose(first)
        assertEquals(2, review.attachments.size)
        Disposer.dispose(second)
        assertTrue(review.attachments.isEmpty())
    }

    private fun event(item: NativeDiffCommentProbe.Attachment) =
        AnActionEvent.createEvent(item.action, DataContext.EMPTY_CONTEXT,
            item.action.templatePresentation.clone(), "SeshlogD1Probe", ActionUiKind.NONE, null)

    private fun request(before: String, current: String, review: NativeDiffCommentProbe.Context? = null): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        return SimpleDiffRequest("Fictional checkout changes", factory.create(project, before), factory.create(project, current),
            "Base", "Reviewed on-disk snapshot (all contributors)").apply {
            putUserData(NativeDiffCommentProbe.REVIEW, review)
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        }
    }

    private fun processor(request: DiffRequest): Processor = Processor().also {
        Disposer.register(testRootDisposable, it)
        it.show(request)
    }

    private inner class Processor : DiffRequestProcessor(project) {
        override fun updateRequest(force: Boolean, scrollToChangePolicy: DiffUserDataKeysEx.ScrollToPolicy?) = Unit
        fun show(request: DiffRequest) = doApplyRequest(request)
    }
}
