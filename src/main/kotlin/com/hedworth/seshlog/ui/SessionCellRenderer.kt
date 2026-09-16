package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.Session
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import java.awt.Color
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class SessionCellRenderer : ColoredTreeCellRenderer() {

    /** Content-search hits by session id; empty when not searching. Set by the panel on the EDT. */
    var hits: Map<String, SearchHit> = emptyMap()
    var query: String = ""
    var activeSessionId: String? = null
    /** Sessions of agents without a pid file whose Seshlog-owned tab still runs them. Set by the panel on the EDT. */
    var runningOwned: Set<String> = emptySet()

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        val userObject = (value as? DefaultMutableTreeNode)?.userObject
        when (userObject) {
            is ProjectGroup -> renderGroup(userObject)
            is Session -> renderSession(userObject, selected)
        }
    }

    private fun renderGroup(group: ProjectGroup) {
        icon = AllIcons.Nodes.Folder
        append(group.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${group.cwd}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${group.sessions.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        toolTipText = group.cwd.toString()
    }

    private fun renderSession(session: Session, selected: Boolean) {
        icon = if (session.isLive) AllIcons.Debugger.ThreadRunning else AllIcons.Vcs.History
        val titleAttrs = when {
            session.id == activeSessionId && !selected -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ACTIVE_COLOR)
            session.id == activeSessionId -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_UNDERLINE, null)
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        val organisation = com.hedworth.seshlog.settings.SessionOrganisation.getInstance()
        val metadata = organisation.metadata(session.id)
        if (metadata.pinned) append("★ ", titleAttrs)
        append(organisation.title(session), titleAttrs)
        if (session.forkedFromId != null) append(" (fork)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (metadata.hidden) append("  hidden", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${session.kind.displayName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        append("  " + DateFormatUtil.formatPrettyDateTime(session.lastActivityAt.toEpochMilli()), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        session.displayBranch?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        if (isRunning(session)) {
            val color = if (ActivityLabel.isIdle(session.activity)) WAITING_COLOR else LIVE_COLOR
            append("  " + ActivityLabel.badge(session.activity, session.activitySince, Instant.now()),
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
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
            if (session.id == activeSessionId) append("Active terminal<br>")
            append("Agent: ").append(session.kind.displayName).append("<br>")
            append("Session: ").append(session.id).append("<br>")
            session.forkedFromId?.let { append("Forked from: ").append(esc(it)).append("<br>") }
            append("Directory: ").append(esc(session.cwd.toString())).append("<br>")
            session.displayBranch?.let { append("Branch: ").append(esc(it)).append("<br>") }
            append("Started: ").append(fmt(session.startedAt)).append("<br>")
            append("Last activity: ").append(fmt(session.lastActivityAt)).append("<br>")
            append("Prompts: ").append(session.promptCount).append("<br>")
            if (isRunning(session)) {
                append(ActivityLabel.describe(session.activity))
                if (session.activity != Activity.UNKNOWN) session.activitySince?.let { append(" since ").append(fmt(it)) }
                append("<br>")
            }
            if (session.isLive) append("Running, pid ").append(session.livePid).append("<br>")
            hit?.snippet?.let { append("Match: <i>").append(esc(it)).append("</i><br>") }
            session.transcriptPath?.let { append("<span style='color:gray'>").append(esc(it.toString())).append("</span>") }
            append("</html>")
        }
    }

    /** Live by the agent's own pid file, or still running in a tab Seshlog owns. */
    private fun isRunning(session: Session): Boolean = session.isLive || session.id in runningOwned

    companion object {
        private val ACTIVE_COLOR: Color = JBColor(Color(0x2458A6), Color(0x8AB4F8))
        private val LIVE_COLOR: Color = JBColor(Color(0x2E8B57), Color(0x6CBF84))
        private val WAITING_COLOR: Color = JBColor(Color(0xB86E00), Color(0xE8A33D))
        private val HIT_COLOR: Color = JBColor(Color(0x3574F0), Color(0x548AF7))
        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
