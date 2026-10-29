package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.worktree.ReviewFile
import com.hedworth.seshlog.worktree.WorktreeReview
import com.hedworth.seshlog.worktree.WorktreeReviewSnapshot
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.*

internal class WorktreeReviewDialog(private val project: Project, private val checkout: Checkout) : DialogWrapper(project, false) {
    private val base = JTextField("HEAD", 24)
    private val refresh = JButton("Refresh")
    private val compare = JButton("Show Diff")
    private val status = JLabel(" ")
    private val model = DefaultListModel<ReviewFile>()
    private val list = JBList(model)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "seshlog-worktree-review").apply { isDaemon = true } }
    private var job: Future<*>? = null
    private var generation = 0L
    private var closed = false
    private var snapshot: WorktreeReviewSnapshot? = null

    init {
        title = "Review Worktree Changes — ${checkout.root.fileName}"
        setModal(false)
        setOKButtonText("Close")
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                val file = value as ReviewFile
                return super.getListCellRendererComponent(l, "${file.status}  ${file.path}", index, selected, focus)
            }
        }
        list.addListSelectionListener { compare.isEnabled = list.selectedValue != null }
        list.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) compare() } })
        list.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "diff")
        list.actionMap.put("diff", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) = compare() })
        refresh.addActionListener { reload() }
        base.addActionListener { reload() }
        compare.addActionListener { compare() }
        base.accessibleContext.accessibleName = "Local base branch, tag or commit"
        list.accessibleContext.accessibleName = "Checkout-wide changed files"
        init()
        reload()
    }

    private fun reload() {
        snapshot = null
        model.clear()
        compare.isEnabled = false
        val ref = base.text.trim()
        submit("Reading changes…", { WorktreeReview.scan(checkout, ref) }) { result ->
            snapshot = result
            result.files.forEach(model::addElement)
            status.text = "${result.files.size} files · base ${result.base.take(8)} · ignored files excluded"
            if (!model.isEmpty) list.selectedIndex = 0
        }
    }

    private fun compare() {
        val scan = snapshot ?: return
        val file = list.selectedValue ?: return
        submit("Reading ${file.path}…", { WorktreeReview.content(scan, file) }) { content ->
            val factory = DiffContentFactory.getInstance()
            DiffManager.getInstance().showDiff(project, SimpleDiffRequest(file.path,
                factory.create(project, content.before), factory.create(project, content.current),
                "Base ${scan.base.take(8)}", "Current checkout (all contributors)"))
            status.text = "${scan.files.size} files · base ${scan.base.take(8)} · refresh to discover newer changes"
        }
    }

    private fun <T> submit(message: String, read: () -> T, deliver: (T) -> Unit) {
        val request = ++generation
        job?.cancel(true)
        status.text = message
        job = worker.submit {
            val result = runCatching(read)
            ApplicationManager.getApplication().invokeLater({
                if (!closed && !project.isDisposed && request == generation) {
                    result.fold(deliver) { status.text = it.message ?: "Could not read changes" }
                }
            }, ModalityState.any())
        }
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(8, 8)).apply {
        preferredSize = Dimension(850, 480)
        add(JPanel(BorderLayout()).apply {
            add(JLabel("Checkout-wide changes, including staged and untracked files; not attributed to one session."), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JLabel("Compare current files with:")); add(base); add(refresh) }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { add(status, BorderLayout.CENTER); add(compare, BorderLayout.EAST) }, BorderLayout.SOUTH)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
    override fun dispose() { closed = true; generation++; job?.cancel(true); worker.shutdownNow(); super.dispose() }
}
