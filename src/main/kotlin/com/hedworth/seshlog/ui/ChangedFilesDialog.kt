package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.ChangedFile
import com.hedworth.seshlog.model.ChangedFileScan
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.ui.actions.SessionAction
import com.hedworth.seshlog.ui.actions.notify
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

private const val MAX_CURRENT_FILE_BYTES = 8 * 1024 * 1024

private sealed class CurrentFileRead {
    data class Found(val text: String) : CurrentFileRead()
    data object Missing : CurrentFileRead()
    data object TooLarge : CurrentFileRead()
    data class Error(val message: String) : CurrentFileRead()
}

/** In-memory changed-file list; transcript extraction happens only when this dialog is opened. */
class ChangedFilesDialog(
    private val project: Project,
    private val session: Session,
    private val files: List<ChangedFile>,
    private val partial: Boolean = false,
) : DialogWrapper(project, false) {
    private val listModel = DefaultListModel<ChangedFile>()
    private val list = JBList(listModel)
    private val open = JButton("Open", AllIcons.Actions.MenuOpen)
    private val compare = JButton("Compare with Current", AllIcons.Actions.Diff)

    init {
        title = "Changed Files — ${SessionOrganisation.getInstance().title(session)}"
        files.forEach(listModel::addElement)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
            ): Component {
                val file = value as ChangedFile
                return super.getListCellRendererComponent(list, "${file.operation}: ${file.path}", index, selected, focus)
            }
        }
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addListSelectionListener { updateButtons() }
        open.addActionListener { selected()?.let(::openFile) }
        compare.addActionListener { selected()?.let(::compareFile) }
        updateButtons()
        init()
    }

    private fun selected(): ChangedFile? = list.selectedValue

    private fun updateButtons() {
        val file = selected()
        open.isEnabled = file != null
        compare.isEnabled = file != null
    }

    private fun openFile(file: ChangedFile) {
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file.path)
        if (virtual != null) OpenFileDescriptor(project, virtual).navigate(true)
        else notify(project, "File not found: ${file.path}", NotificationType.WARNING)
    }

    private fun compareFile(file: ChangedFile) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val current = readCurrentFile(file.path)
            app.invokeLater {
                if (!project.isDisposed) showChangedFileDiff(project, file, current)
            }
        }
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(850, 480)
        val coverageText = if (partial) {
            "Partial coverage: the transcript reader reached its in-memory limit."
        } else {
            "On-demand scan: recognized file operations only; edits and patches retain their recorded payload."
        }
        add(JLabel(coverageText), BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            border = JBUI.Borders.emptyTop(6)
            add(open); add(compare)
        }, BorderLayout.SOUTH)
    }
}

private fun readCurrentFile(path: Path): CurrentFileRead {
    if (Files.notExists(path)) return CurrentFileRead.Missing
    if (!Files.exists(path)) return CurrentFileRead.Error("could not determine whether the path exists")
    if (!Files.isRegularFile(path)) return CurrentFileRead.Error("path is not a regular file")
    return try {
        Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(MAX_CURRENT_FILE_BYTES + 1)
            if (bytes.size > MAX_CURRENT_FILE_BYTES) CurrentFileRead.TooLarge
            else CurrentFileRead.Found(String(bytes, StandardCharsets.UTF_8))
        }
    } catch (error: Exception) {
        CurrentFileRead.Error(error.message ?: error.javaClass.simpleName)
    }
}

/** Opens the native IDE diff, preserving the distinction between a snapshot and a patch. */
private fun showChangedFileDiff(project: Project, file: ChangedFile, current: CurrentFileRead) {
    val leftTitle = if (file.historicalContent != null) "Recorded file content" else "Recorded patch (not historical file)"
    val right = when (current) {
        is CurrentFileRead.Found -> current.text to "Current file"
        CurrentFileRead.Missing -> "(File does not exist.)" to "Current file (missing)"
        CurrentFileRead.TooLarge -> "(Current file exceeds the 8 MiB comparison limit.)" to "Current file (too large)"
        is CurrentFileRead.Error -> "(Could not read current file: ${current.message})" to "Current file (read error)"
    }
    val factory = DiffContentFactory.getInstance()
    val request = SimpleDiffRequest(
        "${file.path.fileName} — recorded change vs current",
        factory.create(project, file.historicalContent ?: file.recordedText),
        factory.create(project, right.first),
        leftTitle,
        right.second,
    )
    DiffManager.getInstance().showDiff(project, request)
}

/** Action implementation is kept separate so the UI registration can add it to the session menu. */
class InspectChangedFilesAction : SessionAction(
    "Inspect Changed Files", "List files changed by this session", AllIcons.Actions.Preview,
) {
    override fun perform(project: Project, session: Session) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val scan = runCatching { SessionIndex.getInstance().providerFor(session).changedFileScan(session) }
                .getOrElse { ChangedFileScan(emptyList(), partial = false) }
            app.invokeLater {
                if (!project.isDisposed) ChangedFilesDialog(project, session, scan.files, scan.partial).show()
            }
        }
    }
}
