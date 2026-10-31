package com.hedworth.seshlog.review

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.worktree.ReviewFile
import com.hedworth.seshlog.worktree.WorktreeReviewSnapshot
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.util.DiffNotificationProvider
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Pair
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.*

internal class ReviewCommentsDialog(private val project: Project, private val draftId: String) : DialogWrapper(project, false) {
    private val service = ReviewDraftService.getInstance()
    private val store = service.store
    private val recipient = store.get(draftId).recipient
    private val base = JTextField(store.get(draftId).base ?: "HEAD", 22)
    private val recorded = JCheckBox("Files mentioned in this session's recorded edits (tool records only)")
    private val fileModel = DefaultListModel<ReviewFile>()
    private val files = JBList(fileModel)
    private val commentModel = DefaultListModel<ReviewComment>()
    private val comments = JBList(commentModel)
    private val status = JTextArea(2, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
    private val count = JLabel()
    private val send = JButton("Send review").apply { isEnabled = false; toolTipText = "Verified delivery adapter is being connected" }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "seshlog-review-view").apply { isDaemon = true } }
    private var job: Future<*>? = null
    private var generation = 0L
    private var closed = false
    private var scan: WorktreeReviewSnapshot? = null
    private var recordedFiles: ReviewSnapshots.RecordedFiles? = null
    private val subscription: AutoCloseable

    init {
        title = "Review Checkout Changes and Comment"
        setModal(false)
        setOKButtonText("Close")
        files.selectionMode = ListSelectionModel.SINGLE_SELECTION
        comments.selectionMode = ListSelectionModel.SINGLE_SELECTION
        files.accessibleContext.accessibleName = "Checkout changes (all contributors)"
        comments.accessibleContext.accessibleName = "Review comments across files"
        base.accessibleContext.accessibleName = "Local comparison base (fixed after first load)"
        files.cellRenderer = textRenderer { value -> (value as ReviewFile).let { "${it.status}  ${it.path}" } }
        comments.cellRenderer = textRenderer { value -> (value as ReviewComment).let {
            "${if (it.validation == ReviewValidation.NEEDS_RECHECK) "Needs recheck · " else ""}${it.path}:${it.firstLine}–${it.lastLine} (${it.side.name.lowercase()}) · ${it.note.lineSequence().first().take(90)}"
        } }
        files.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) openFile() } })
        comments.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) navigate() } })
        bindEnter(files, ::openFile)
        bindEnter(comments, ::navigate)
        recorded.addActionListener { showFiles() }
        subscription = service.listen { if (!closed) renderDraft() }
        init()
        renderDraft()
        reload()
    }

    private fun renderDraft() {
        val draft = runCatching { store.get(draftId) }.getOrNull() ?: run { close(OK_EXIT_CODE); return }
        val selected = comments.selectedValue?.id
        commentModel.clear()
        draft.comments.forEach(commentModel::addElement)
        draft.comments.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { comments.selectedIndex = it }
        count.text = "Send ${draft.comments.size} comments to “${recipientTitle()}” · ${draft.sent.size} sent batches"
        base.isEnabled = draft.base == null
        draft.base?.let { base.text = it }
    }
    private fun recipientTitle(): String = SessionIndex.getInstance().sessions.firstOrNull {
        it.kind == recipient.kind && it.id == recipient.sessionId
    }?.let { SessionOrganisation.getInstance().title(it) } ?: "Closed or unindexed session"

    private data class Refresh(val scan: WorktreeReviewSnapshot, val files: List<ReviewFileSnapshot>, val failed: Set<String>, val recorded: ReviewSnapshots.RecordedFiles)
    private fun reload() {
        val draft = store.get(draftId)
        val ref = draft.base ?: base.text.trim()
        val expected = draft.generation
        read("Refreshing on-disk snapshots…", {
            val next = ReviewSnapshots.scan(recipient, ref)
            val loaded = mutableListOf<ReviewFileSnapshot>()
            val failed = linkedSetOf<String>()
            draft.files.values.forEach { old ->
                val file = next.files.firstOrNull { it.path == old.path } ?: ReviewFile(old.path, old.gitStatus)
                runCatching { ReviewSnapshots.read(next, file) }.fold(loaded::add) { failed += old.path }
            }
            val session = SessionIndex.getInstance().sessions.firstOrNull { it.kind == recipient.kind && it.id == recipient.sessionId }
            val provenance = runCatching { session?.let { ReviewSnapshots.recorded(recipient, SessionIndex.getInstance().providerFor(it).changedFileScan(it)) } }
                .getOrNull() ?: ReviewSnapshots.RecordedFiles(emptySet(), true)
            Refresh(next, loaded, failed, provenance)
        }) { result ->
            if (store.get(draftId).generation != expected) { status.text = "The draft changed during refresh; refresh again"; return@read }
            scan = result.scan
            result.files.forEach { store.load(draftId, it) }
            if (result.failed.isNotEmpty()) store.markStale(draftId, result.failed)
            recordedFiles = result.recorded
            showFiles()
            status.text = "${result.scan.files.size} changed files · ${if (result.recorded.partial) "partial recorded-file coverage" else "recognized tool operations only"} · Refresh never confirms changed anchors."
            service.changed()
        }
    }
    private fun showFiles() {
        val selected = files.selectedValue?.path
        fileModel.clear()
        scan?.files?.filter { !recorded.isSelected || it.path in recordedFiles?.paths.orEmpty() }?.forEach(fileModel::addElement)
        (0 until fileModel.size).firstOrNull { fileModel[it].path == selected }?.let { files.selectedIndex = it }
        if (files.selectedIndex < 0 && !fileModel.isEmpty) files.selectedIndex = 0
    }
    private fun openFile() {
        val snapshot = scan ?: return
        val file = files.selectedValue ?: return
        val expected = store.get(draftId).generation
        read("Reading reviewed snapshot…", { ReviewSnapshots.read(snapshot, file) }) { content ->
            store.load(draftId, content, expected)
            service.changed()
            showDiff(content)
        }
    }
    private fun navigate() {
        val comment = comments.selectedValue ?: return
        store.get(draftId).files[comment.path]?.let { showDiff(it, comment) }
    }
    private fun showDiff(file: ReviewFileSnapshot, focus: ReviewComment? = null) {
        val factory = DiffContentFactory.getInstance()
        val unsaved = file.path in ReviewUnsavedBuffers.capture(recipient)
        lateinit var context: ReviewDiffContext
        context = ReviewDiffContext(draftId, file.path, file.revision, { side, first, last ->
            ReviewCommentUi.add(project, context, side, first, last)
        }, focus)
        val request = SimpleDiffRequest("Checkout changes — ${file.path} — ${recipient.kind.displayName}: ${recipientTitle()}",
            factory.create(project, file.before), factory.create(project, file.current),
            "Base ${file.base.take(12)}", "Reviewed on-disk snapshot (all contributors)")
        request.putUserData(ReviewDiffExtension.REVIEW_CONTEXT, context)
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        request.putUserData(DiffUserDataKeys.NOTIFICATION_PROVIDERS, listOf(DiffNotificationProvider { viewer ->
            when {
                viewer !is SimpleDiffViewer -> JLabel("Seshlog comments require a side-by-side text diff. Switch presentation to add comments.")
                unsaved -> JLabel("An unsaved IDE buffer differs from this on-disk snapshot. Save and refresh before sending comments for this file.")
                else -> null
            }
        }))
        focus?.let { request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE,
            Pair(if (it.side == ReviewSide.BASE) Side.LEFT else Side.RIGHT, it.firstLine - 1)) }
        DiffManager.getInstance().showDiff(project, request)
    }
    private fun edit() {
        val comment = comments.selectedValue ?: return
        ReviewNoteDialog(project, "Edit Review Comment", comment.selected, comment.note) {
            store.edit(draftId, comment.id, it); service.changed()
        }.show()
    }
    private fun overall() {
        val note = store.get(draftId).overallNote
        // An empty overall note is allowed; the dedicated editor retains input on validation failure.
        val field = JTextArea(note, 8, 55).apply { lineWrap = true; wrapStyleWord = true }
        val dialog = object : DialogWrapper(project) {
            init { title = "Overall Review Note"; init() }
            override fun createCenterPanel(): JComponent = JBScrollPane(field)
            override fun doOKAction() {
                try { store.overall(draftId, field.text); service.changed(); super.doOKAction() }
                catch (e: Exception) { setErrorText(e.message ?: "Could not save note") }
            }
        }
        dialog.show()
    }
    private fun preview(copy: Boolean = false, sent: ReviewSubmission? = null) {
        val expected = store.get(draftId).generation
        read("Formatting review…", { sent?.payload ?: store.preview(draftId) }) { payload ->
            if (sent == null && store.get(draftId).generation != expected) { status.text = "Draft changed; preview again"; return@read }
            if (copy) {
                CopyPasteManager.getInstance().setContents(StringSelection(payload))
                status.text = "Review copied. Direct delivery has not occurred."
            } else object : DialogWrapper(project, false) {
                init { title = if (sent == null) "Exact Outgoing Review (current draft)" else "Sent Review — read only"; setModal(false); setOKButtonText("Close"); init() }
                override fun createCenterPanel(): JComponent = JBScrollPane(JTextArea(payload).apply { isEditable = false; caretPosition = 0 }).apply { preferredSize = Dimension(850, 600) }
                override fun createActions(): Array<Action> = arrayOf(okAction)
            }.show()
        }
    }
    private fun sent() {
        val batches = store.get(draftId).sent
        if (batches.isEmpty()) { status.text = "No accepted or queued batches in this review"; return }
        val selected = Messages.showEditableChooseDialog("Choose an accepted/queued batch (read only)", "Sent Reviews", null,
            batches.map { it.id }.toTypedArray(), batches.last().id, null)
        batches.firstOrNull { it.id == selected }?.let { preview(sent = it) }
    }
    private fun confirm() {
        val comment = comments.selectedValue ?: return
        if (Messages.showYesNoDialog(project, "Open the refreshed diff and inspect this anchor first. Confirm that its meaning and selected text are still correct?",
                "Confirm Review Anchor", Messages.getQuestionIcon()) != Messages.YES) return
        read("Checking selected text…", { store.confirm(draftId, comment.id) }) { service.changed() }
    }
    private fun discard() {
        if (Messages.showYesNoDialog(project, "Discard this draft and its in-memory sent batches?", "Discard Review", Messages.getQuestionIcon()) != Messages.YES) return
        try { store.discard(draftId); service.changed(); close(OK_EXIT_CODE) } catch (e: Exception) { status.text = e.message }
    }
    private fun <T> read(message: String, work: () -> T, deliver: (T) -> Unit) {
        val request = ++generation
        job?.cancel(true)
        status.text = message
        job = worker.submit {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater({
                if (!closed && !project.isDisposed && request == generation) result.fold({
                    try { deliver(it) } catch (e: Exception) { status.text = e.message ?: "Review changed; refresh and try again" }
                }, { status.text = it.message ?: "Could not read review" })
            }, ModalityState.any())
        }
    }
    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(8, 8)).apply {
        preferredSize = Dimension(1100, 680)
        add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Recipient: ${recipient.kind.displayName} · ${recipientTitle()} · ${recipient.checkout.root}"))
            add(JLabel("Checkout changes include all contributors. Recorded tool operations do not establish authorship or complete file history."))
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JLabel("Local base:")); add(base); add(button("Refresh", ::reload)); add(recorded) })
        }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JPanel(BorderLayout()).apply {
            add(JBScrollPane(files), BorderLayout.CENTER); add(button("Show Diff", ::openFile), BorderLayout.SOUTH)
        }, JPanel(BorderLayout()).apply {
            add(JBScrollPane(comments), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(button("Go to Comment", ::navigate)); add(button("Edit", ::edit));
                add(button("Remove") { comments.selectedValue?.let { store.remove(draftId, it.id); service.changed() } }); add(button("Confirm Anchor", ::confirm)) }, BorderLayout.SOUTH)
        }).apply { resizeWeight = 0.42 }, BorderLayout.CENTER)
        add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(count)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(button("Overall Note", ::overall)); add(button("Preview", { preview() })); add(button("Copy Review", { preview(copy = true) }));
                add(button("Sent Batches", ::sent)); add(button("Discard Review", ::discard)); add(send) })
            add(status)
            add(JLabel("Limits: 50 comments, 4,000 characters per note, 128 KiB per message. Drafts and sent batches live in memory until IDE exit."))
            add(JLabel("Explicit send lets the agent store and process the review under its normal settings."))
        }, BorderLayout.SOUTH)
    }
    override fun createActions(): Array<Action> = arrayOf(okAction)
    override fun dispose() { closed = true; generation++; job?.cancel(true); worker.shutdownNow(); subscription.close(); super.dispose() }
    private fun bindEnter(component: JComponent, action: () -> Unit) {
        component.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "review-open")
        component.actionMap.put("review-open", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) = action() })
    }
    private fun textRenderer(text: (Any) -> String) = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean) =
            super.getListCellRendererComponent(list, value?.let(text).orEmpty(), index, selected, focus)
    }
}
