package com.hedworth.seshlog.review

import com.hedworth.seshlog.worktree.ReviewFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserBase
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.vcsUtil.VcsUtil
import java.nio.file.Path
import javax.swing.tree.DefaultTreeModel

/** Native VCS tree/grouping/diff actions; entries are metadata, never live commit candidates. */
internal class ReviewChangesBrowser(
    project: Project, private val root: Path, private val open: (ReviewFile) -> Unit,
) : ChangesBrowserBase(project, false, false) {
    private var entries = emptyList<ReviewFile>()
    init {
        init()
        viewer.accessibleContext.accessibleName = "Checkout changes (all contributors)"
        viewer.emptyText.text = "No changed files in this comparison"
        hideViewerBorder()
    }
    fun setFiles(files: List<ReviewFile>) {
        entries = files.toList()
        viewer.rebuildTree()
        if (viewer.selectionCount == 0 && viewer.rowCount > 0) viewer.setSelectionRow(0)
    }
    private fun selected(): ReviewFile? {
        val node = viewer.selectionPath?.lastPathComponent as? ChangesBrowserNode<*> ?: return null
        val path = node.userObject as? FilePath ?: return null
        val selected = path.ioFile.toPath().toAbsolutePath().normalize()
        return entries.firstOrNull { root.resolve(it.path).toAbsolutePath().normalize() == selected }
    }
    override fun canShowDiff() = selected() != null
    override fun showDiff() { selected()?.let(open) }
    override fun onDoubleClick() = showDiff()
    override fun buildTreeModel(): DefaultTreeModel {
        val builder = TreeModelBuilder(myProject, grouping)
        entries.forEach { entry ->
            val path = VcsUtil.getFilePath(root.resolve(entry.path).toString(), false)
            val status = when {
                entry.status.contains('?') -> FileStatus.UNKNOWN
                entry.status.contains('A') -> FileStatus.ADDED
                entry.status.contains('D') -> FileStatus.DELETED
                else -> FileStatus.MODIFIED
            }
            builder.insertChangeNode(path, builder.myRoot, ChangesBrowserNode.createFilePath(path, status))
        }
        return builder.build()
    }
}
