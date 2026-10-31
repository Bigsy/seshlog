package com.hedworth.seshlog.review

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.worktree.ReviewFile
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.awt.Container
import javax.swing.JList
import javax.swing.JTabbedPane
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath

class ReviewIdeIntegrationTest : BasePlatformTestCase() {
    private val root = Path.of("/synthetic/review")
    private val service get() = ReviewDraftService.getInstance()
    private val ids = mutableListOf<String>()
    private fun draft(): ReviewDraft {
        val checkout = Checkout(root, root.resolve(".git"), root.resolve(".git"), Checkout.Kind.MAIN, "main", "a".repeat(40), directoryKey = "synthetic")
        return service.store.open(ReviewRecipient(AgentKind.CODEX, "synthetic-$name", checkout)).also { ids += it.id }
    }
    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
            val host = ChangesViewContentManager.getInstance(project)
            host.findContents { it.component is ReviewCommentsPanel }.forEach(host::removeContent)
            ids.forEach { service.store.discard(it) }; service.changed()
        } finally { super.tearDown() }
    }
    fun `test native Changes browser opens only selected metadata without commit candidates`() {
        val opened = mutableListOf<ReviewFile>()
        val browser = ReviewChangesBrowser(project, root, opened::add)
        val file = ReviewFile("src/retry.kt", "M")
        browser.setFiles(listOf(file, ReviewFile("added.txt", "?")))
        val nodes = browser.viewer.root.traverse().toList()
        val node = nodes.firstOrNull { (it.userObject as? FilePath)?.path == root.resolve(file.path).toString() }
        assertNotNull(nodes.joinToString { "${it.userObject?.javaClass?.name}:${it.userObject}" }, node)
        node!!
        browser.viewer.selectionPath = TreePath(node.path)
        assertTrue(browser.canShowDiff())
        browser.showDiff()
        assertEquals(listOf(file), opened)
        assertTrue(browser.viewer.root.allChangesUnder.isEmpty()) // Never added to a commit/change list.
        browser.setFiles(emptyList())
        assertFalse(browser.canShowDiff())
    }
    fun `test reopening a review selects the same Commit content and closing preserves notes`() {
        val draft = draft()
        service.store.load(draft.id, ReviewFileSnapshot("file.kt", "a".repeat(40), "old\n", "new\n"))
        service.store.add(draft.id, "file.kt", ReviewSide.CURRENT, 1, 1, "Preserve this note")
        val first = ReviewToolWindow.content(project, draft.id)
        assertSame(first, ReviewToolWindow.content(project, draft.id))
        assertEquals(true, first.getUserData(ChangesViewContentManager.IS_IN_COMMIT_TOOLWINDOW_KEY))
        val host = ChangesViewContentManager.getInstance(project)
        assertEquals(1, host.findContents { it.component is ReviewCommentsPanel }.size)
        host.removeContent(first)
        val next = ReviewToolWindow.content(project, draft.id)
        assertNotSame(first, next)
        assertEquals("Preserve this note", service.store.get(draft.id).comments.single().note)
    }
    fun `test unavailable VCS window reports a reason without silently creating invisible content`() {
        val draft = draft()
        try {
            ReviewToolWindow.open(project, draft.id)
            fail("The lightweight project has no VCS window")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Commit/Changes tool window is unavailable")) }
        assertTrue(ChangesViewContentManager.getInstance(project).findContents { it.component is ReviewCommentsPanel }.isEmpty())
        assertEquals(draft.id, service.store.get(draft.id).id)
    }
    fun `test comments and send controls remain reachable in a narrow short docked view`() {
        val draft = draft()
        service.store.load(draft.id, ReviewFileSnapshot("file.kt", "a".repeat(40), "old\n", "new\n"))
        service.store.add(draft.id, "file.kt", ReviewSide.CURRENT, 1, 1, "A note in a small tool window")
        val panel = ReviewCommentsPanel(project, draft.id) {}
        Disposer.register(testRootDisposable, panel)
        fun descendants(value: Container): List<java.awt.Component> = value.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
        val controls = descendants(panel)
        val tabs = controls.filterIsInstance<JTabbedPane>().single()
        tabs.selectedIndex = (0 until tabs.tabCount).single { tabs.getTitleAt(it).startsWith("Comments") }
        panel.setSize(360, 580)
        fun layout(value: Container) { value.doLayout(); value.components.filterIsInstance<Container>().forEach(::layout) }
        layout(panel)
        val list = controls.filterIsInstance<JList<*>>().single { it.accessibleContext.accessibleName == "Review comments across files" }
        val viewport = list.parent
        val visible = SwingUtilities.convertRectangle(viewport.parent, viewport.bounds, panel)
        assertTrue("Comment list must have usable height: $visible", visible.height >= 35)
        assertTrue(panel.bounds.contains(visible))
        val send = controls.filterIsInstance<JButton>().single { it.accessibleContext.accessibleName.startsWith("Send 1 comment to") }
        assertTrue(panel.bounds.contains(SwingUtilities.convertRectangle(send.parent, send.bounds, panel)))
    }
    fun `test unsupported platforms disable Send and explain platform support before target checks`() {
        val draft = draft()
        service.store.load(draft.id, ReviewFileSnapshot("file.kt", "base", "old\n", "new\n"))
        service.store.add(draft.id, "file.kt", ReviewSide.CURRENT, 1, 1, "Keep my note")
        for (os in listOf("Linux", "Windows 11")) {
            val panel = ReviewCommentsPanel(project, draft.id, osName = os) {}
            Disposer.register(testRootDisposable, panel)
            fun descendants(value: Container): List<java.awt.Component> = value.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
            val controls = descendants(panel)
            val send = controls.filterIsInstance<JButton>().single { it.accessibleContext.accessibleName.startsWith("Send 1 comment to") }
            assertFalse("Direct send must be disabled on $os", send.isEnabled)
            assertTrue(controls.filterIsInstance<javax.swing.JTextArea>().any { it.text.contains("unavailable on $os") && it.text.contains("Copy Review") })
        }
        assertEquals("Keep my note", service.store.get(draft.id).comments.single().note)
    }
    fun `test native editor reuses snapshot tab and navigation reselects either side`() {
        val draft = draft()
        val file = ReviewFileSnapshot("file.kt", "a".repeat(40), "old\nbase\n", "new\ncurrent\n")
        service.store.load(draft.id, file)
        val base = service.store.add(draft.id, file.path, ReviewSide.BASE, 2, 2, "Keep base").comments.last()
        val current = service.store.add(draft.id, file.path, ReviewSide.CURRENT, 1, 1, "Keep current").comments.last()
        val opened = mutableMapOf<ReviewDiffVirtualFile, DiffEditorViewerFileEditor>()
        fun open(focus: ReviewComment): ReviewDiffVirtualFile {
            val context = ReviewDiffContext(draft.id, file.path, file.revision, { _, _, _ -> }, focus)
            return ReviewDiffEditors.open(project, context, { before, current ->
                SimpleDiffRequest("Synthetic review editor", before, current, "Base", "Snapshot").apply { putUserData(ReviewDiffExtension.REVIEW_CONTEXT, context) }
            }, { opened.keys.toList() }, { tab ->
                arrayOf(opened.getOrPut(tab) {
                    val processor = tab.createProcessor(project)
                    processor.updateRequest()
                    DiffEditorViewerFileEditor(project, tab, processor).also { Disposer.register(testRootDisposable, it) }
                })
            })
        }
        val first = open(base)
        val second = open(current)
        assertSame(first, second)
        val editors = listOf(opened.getValue(first))
        assertEquals(1, editors.size)
        val viewer = (editors.single().editorViewer as DiffRequestProcessor).activeViewer as SimpleDiffViewer
        assertEquals("new", viewer.editor2.selectionModel.selectedText)
        open(base)
        assertEquals("base", viewer.editor1.selectionModel.selectedText)
        Disposer.dispose(editors.single())
        assertNull(viewer.editor1.getUserData(ReviewDiffExtension.EDITOR_BINDING))
    }
    fun `test force refreshing a live native diff preserves both sides and its tab`() {
        val draft = draft()
        val file = ReviewFileSnapshot("refresh.kt", "base", "old\n", "new\n")
        service.store.load(draft.id, file)
        val context = ReviewDiffContext(draft.id, file.path, file.revision, { _, _, _ -> })
        lateinit var processor: DiffRequestProcessor
        val tab = ReviewDiffEditors.open(project, context, { before, current -> SimpleDiffRequest("Synthetic refresh", before, current, "Base", "Snapshot") }, { emptyList() }, { tab ->
            processor = tab.createProcessor(project)
            processor.updateRequest()
            arrayOf(DiffEditorViewerFileEditor(project, tab, processor).also { Disposer.register(testRootDisposable, it) })
        })
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        assertEquals(2, (tab.request as SimpleDiffRequest).contents.size)
        processor.updateRequest(true)
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        assertEquals("Presentation refresh must not erase the request", 2, (tab.request as SimpleDiffRequest).contents.size)
        assertTrue(tab.isValid)
        val viewer = processor.activeViewer as SimpleDiffViewer
        assertEquals("old\n", viewer.editor1.document.text)
        assertEquals("new\n", viewer.editor2.document.text)
    }
    fun `test an open native diff counts against snapshot refresh memory until its editor closes`() {
        val draft = draft()
        val file = ReviewFileSnapshot("large.txt", "a".repeat(40), "", "x".repeat(7 * 1024 * 1024))
        service.store.load(draft.id, file)
        val context = ReviewDiffContext(draft.id, file.path, file.revision, { _, _, _ -> })
        val editors = mutableListOf<DiffEditorViewerFileEditor>()
        val tab = ReviewDiffEditors.open(project, context, { before, current -> SimpleDiffRequest("Synthetic bounded diff", before, current, "Base", "Snapshot") }, { emptyList() }, { tab ->
            Array(2) {
                val processor = tab.createProcessor(project)
                processor.updateRequest()
                DiffEditorViewerFileEditor(project, tab, processor).also { editors += it; Disposer.register(testRootDisposable, it) }
            }
        })
        val refreshed = file.copy(current = "y".repeat(10 * 1024 * 1024))
        try {
            service.store.load(draft.id, refreshed)
            fail("The open old revision must still count towards the 32 MiB bound")
        } catch (_: IllegalStateException) {}
        assertEquals(file.revision, service.store.get(draft.id).files.getValue(file.path).revision)
        Disposer.dispose(editors[0])
        assertFalse("The other split still retains the snapshot", (tab.request as SimpleDiffRequest).contents.isEmpty())
        try { service.store.load(draft.id, refreshed); fail("Closing one split must not release the live second split") }
        catch (_: IllegalStateException) {}
        Disposer.dispose(editors[1])
        assertTrue("Closed native tabs must release their retained text", (tab.request as SimpleDiffRequest).contents.isEmpty())
        assertEquals(refreshed.revision, service.store.load(draft.id, refreshed).files.getValue(file.path).revision)
    }
    fun `test a five MiB file on each side opens within the shared review text budget`() {
        val draft = draft()
        val size = 5 * 1024 * 1024
        val file = ReviewFileSnapshot("permitted.txt", "base", "base\n" + "a".repeat(size - 5), "next\n" + "b".repeat(size - 5))
        service.store.load(draft.id, file)
        service.store.add(draft.id, file.path, ReviewSide.CURRENT, 1, 1, "Keep this reviewable")
        val context = ReviewDiffContext(draft.id, file.path, file.revision, { _, _, _ -> })
        fun open() = ReviewDiffEditors.open(project, context, { before, current -> SimpleDiffRequest("Permitted file", before, current, "Base", "Snapshot") }, { emptyList() }, { tab ->
            val processor = tab.createProcessor(project)
            processor.updateRequest()
            arrayOf(DiffEditorViewerFileEditor(project, tab, processor).also { Disposer.register(testRootDisposable, it) })
        })
        val tab = open()
        val other = open() // Independent native tabs, as in two project windows.
        assertEquals(2, tab.request.contents.size)
        tab.request.contents.map { it as com.intellij.diff.contents.DocumentContent }.forEachIndexed { index, content ->
            assertFalse(content.document.isWritable)
            assertSame(file.content(if (index == 0) ReviewSide.BASE else ReviewSide.CURRENT), content.document.immutableCharSequence)
        }
        val firstDocument = (tab.request.contents[1] as com.intellij.diff.contents.DocumentContent).document
        val otherDocument = (other.request.contents[1] as com.intellij.diff.contents.DocumentContent).document
        assertNotSame(firstDocument, otherDocument)
        assertSame(firstDocument.immutableCharSequence, otherDocument.immutableCharSequence)
        assertSame(file, service.store.load(draft.id, file.copy()).files[file.path])
        assertTrue(tab.isValid)
        assertEquals("Keep this reviewable", service.store.get(draft.id).comments.single().note)
    }
}
