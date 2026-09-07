package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.model.Session
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class SessionCellRenderer : ColoredTreeCellRenderer() {

    /** Content-search hits by session id; empty when not searching. Set by the panel on the EDT. */
    var hits: Map<String, SearchHit> = emptyMap()
    var query: String = ""

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        val userObject = (value as? DefaultMutableTreeNode)?.userObject
        when (userObject) {
            is ProjectGroup -> renderGroup(userObject)
            is Session -> renderSession(userObject)
        }
    }

    private fun renderGroup(group: ProjectGroup) {
        icon = AllIcons.Nodes.Folder
        append(group.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${group.cwd}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${group.sessions.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        toolTipText = group.cwd.toString()
    }

    private fun renderSession(session: Session) {
        icon = if (session.isLive) AllIcons.Debugger.ThreadRunning else AllIcons.Vcs.History
        val titleAttrs = if (session.isLive) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        val organisation = com.hedworth.seshlog.settings.SessionOrganisation.getInstance()
        val metadata = organisation.metadata(session.id)
        if (metadata.pinned) append("★ ", titleAttrs)
        append(organisation.title(session), titleAttrs)
        if (metadata.hidden) append("  hidden", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${session.kind.displayName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        append("  " + DateFormatUtil.formatPrettyDateTime(session.lastActivityAt.toEpochMilli()), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        session.displayBranch?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        if (session.isLive) {
            append("  ● live", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, LIVE_COLOR))
        }
        val hit = hits[session.id]
        if (hit != null) {
            val label = if (hit.toolMatch) "  tool match" else if (hit.snippet != null) "  content match" else "  title/path match"
            append(label, SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, HIT_COLOR))
            if (hit.partial) append("  partial coverage", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            hit.snippet?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
        }
        toolTipText = tooltip(session, hit)
    }

    private fun tooltip(session: Session, hit: SearchHit? = null): String {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;") }
        val fmt = { i: java.time.Instant? -> i?.let { TS.format(it.atZone(ZoneId.systemDefault())) } ?: "–" }
        return buildString {
            append("<html><b>").append(esc(session.title)).append("</b><br>")
            append("Agent: ").append(session.kind.displayName).append("<br>")
            append("Session: ").append(session.id).append("<br>")
            append("Directory: ").append(esc(session.cwd.toString())).append("<br>")
            session.displayBranch?.let { append("Branch: ").append(esc(it)).append("<br>") }
            append("Started: ").append(fmt(session.startedAt)).append("<br>")
            append("Last activity: ").append(fmt(session.lastActivityAt)).append("<br>")
            append("Prompts: ").append(session.promptCount).append("<br>")
            if (session.isLive) append("Running, pid ").append(session.livePid).append("<br>")
            hit?.snippet?.let { append("Match: <i>").append(esc(it)).append("</i><br>") }
            session.transcriptPath?.let { append("<span style='color:gray'>").append(esc(it.toString())).append("</span>") }
            append("</html>")
        }
    }

    companion object {
        private val LIVE_COLOR: Color = JBColor(Color(0x2E8B57), Color(0x6CBF84))
        private val HIT_COLOR: Color = JBColor(Color(0x3574F0), Color(0x548AF7))
        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
