package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.TextQuery
import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.index.SearchMetadataVariant
import com.hedworth.seshlog.index.lexical.SearchSessionKey
import com.hedworth.seshlog.index.lexical.RetrievalQuery
import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.font.TextAttribute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

class SessionCellRenderer : JPanel(BorderLayout()), TreeCellRenderer {

    /** Content-search hits by session id; empty when not searching. Set by the panel on the EDT. */
    var hits: Map<SearchSessionKey, SearchHit> = emptyMap()
    private var queryTerms: List<String> = emptyList()
    var query: String = ""
        set(value) {
            if (field == value) return
            field = value
            queryTerms = if (value.length > RetrievalQuery.MAX_CHARS) emptyList() else
                TextQuery.parse(value).terms.takeIf { it.size <= RetrievalQuery.MAX_TERMS }?.map { it.text }.orEmpty()
        }
    var showFolder: Boolean = false
    var activeSessionId: String? = null
    /** Sessions of agents without a pid file whose Seshlog-owned tab still runs them. Set by the panel on the EDT. */
    var runningOwned: Set<String> = emptySet()
    var unread: Set<String> = emptySet()

    internal val titleLabel = SessionRowText()
    internal val activityLabel = plainLabel()
    internal val folderLabel = SessionRowText()
    internal val detailsLabel = SessionRowText()
    internal val matchLabel = SessionRowText()
    private val heading = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
        isOpaque = false
        add(titleLabel, BorderLayout.CENTER)
        add(activityLabel, BorderLayout.EAST)
    }
    private val details = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        isOpaque = false
        add(folderLabel, BorderLayout.NORTH)
        add(detailsLabel, BorderLayout.CENTER)
        add(matchLabel, BorderLayout.SOUTH)
    }

    init {
        isOpaque = false
        add(heading, BorderLayout.NORTH)
        add(details, BorderLayout.CENTER)
    }

    // A renderer is painted through CellRendererPane, outside the normal component layout cycle.
    override fun validate() {
        doLayout()
        heading.doLayout()
        details.doLayout()
    }

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ): Component {
        val foreground = if (selected) UIUtil.getTreeSelectionForeground(tree.hasFocus()) else tree.foreground
        val secondary = if (selected) foreground else SessionTextColors.SECONDARY
        titleLabel.font = tree.font
        titleLabel.foreground = foreground
        activityLabel.font = tree.font.deriveFont((tree.font.size2D - 1).coerceAtLeast(1f))
        folderLabel.font = activityLabel.font
        folderLabel.foreground = secondary
        detailsLabel.font = activityLabel.font
        matchLabel.font = activityLabel.font
        detailsLabel.foreground = secondary
        matchLabel.foreground = secondary
        titleLabel.clear()
        titleLabel.icon = null
        activityLabel.text = ""
        activityLabel.isVisible = false
        folderLabel.clear()
        folderLabel.isVisible = false
        detailsLabel.clear()
        matchLabel.clear()
        matchLabel.isVisible = false
        details.isVisible = false
        toolTipText = null
        getAccessibleContext().accessibleDescription = null
        border = JBUI.Borders.empty(4, 0, 4, 8)

        when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
            is ProjectGroup -> {
                titleLabel.icon = AllIcons.Nodes.Folder
                titleLabel.font = tree.font.deriveFont(Font.BOLD)
                titleLabel.appendQueryText(item.displayName, queryTerms, foreground, SimpleTextAttributes.STYLE_BOLD)
                activityLabel.text = "${item.sessions.size} " + if (item.sessions.size == 1) "session" else "sessions"
                val summary = SessionAttention.summary(item.sessions, runningOwned, unread)
                if (summary.isNotEmpty()) activityLabel.text += "  ·  $summary"
                activityLabel.foreground = secondary
                activityLabel.isVisible = true
                toolTipText = item.cwd.toString()
            }
            is Session -> renderSession(item, selected, tree.font, foreground)
        }
        getAccessibleContext().accessibleName = listOf(titleLabel.text, activityLabel.text, folderLabel.text, detailsLabel.text, matchLabel.text)
            .filter { it.isNotEmpty() }.joinToString(", ")
        return this
    }

    private fun renderSession(session: Session, selected: Boolean, font: Font, foreground: Color) {
        val organisation = SessionOrganisation.getInstance()
        val metadata = organisation.metadata(session.id)
        titleLabel.icon = if (isRunning(session)) AllIcons.Debugger.ThreadRunning else AllIcons.Vcs.History
        if (session.id == activeSessionId) {
            titleLabel.font = font.deriveFont(Font.BOLD).let {
                if (selected) it.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON)) else it
            }
            if (!selected) titleLabel.foreground = ACTIVE_COLOR
        }
        val titleStyle = if (session.id == activeSessionId) SimpleTextAttributes.STYLE_BOLD or
            (if (selected) SimpleTextAttributes.STYLE_UNDERLINE else 0) else SimpleTextAttributes.STYLE_PLAIN
        if (metadata.pinned) titleLabel.append("★ ", SimpleTextAttributes(titleStyle, titleLabel.foreground))
        titleLabel.appendQueryText(organisation.title(session), queryTerms, titleLabel.foreground, titleStyle)
        if (isRunning(session)) {
            activityLabel.text = ActivityLabel.badge(session.activity, session.activitySince, Instant.now())
            activityLabel.foreground = when {
                selected -> foreground
                ActivityLabel.isIdle(session.activity) -> WAITING_COLOR
                else -> LIVE_COLOR
            }
            activityLabel.isVisible = true
        }
        if (session.id in unread) {
            activityLabel.text = "● unread" + if (activityLabel.isVisible) "  ·  ${activityLabel.text}" else ""
            activityLabel.foreground = if (selected) foreground else HIT_COLOR
            activityLabel.isVisible = true
        }
        val secondary = if (selected) foreground else SessionTextColors.SECONDARY
        val agentColor = if (selected) foreground else SessionTextColors.AGENT
        val branchColor = if (selected) foreground else SessionTextColors.BRANCH
        fun field(component: SessionRowText, text: String, color: Color, highlight: Boolean = true) {
            if (component.text.isNotEmpty()) component.append("  ·  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondary))
            component.appendQueryText(text, if (highlight) queryTerms else emptyList(), color)
        }
        field(detailsLabel, session.kind.displayName, agentColor, false)
        field(detailsLabel, DateFormatUtil.formatPrettyDateTime(session.lastActivityAt.toEpochMilli()), secondary, false)
        if (session.forkedFromId != null) field(detailsLabel, "fork", secondary, false)
        if (metadata.hidden) field(detailsLabel, "hidden", secondary, false)
        if (session.continuationId != null) field(detailsLabel, "continued", secondary, false)
        if (!showFolder) WorktreePresentation.details(session)?.let { field(detailsLabel, it, branchColor) }
        if (showFolder) {
            field(folderLabel, session.cwd.fileName?.toString() ?: session.cwd.toString(), foreground)
            (session.checkout?.branchLabel ?: session.displayBranch)?.let { field(folderLabel, it, branchColor) }
            if (session.checkout?.available == false || session.recordedDirectoryAvailable == false)
                field(folderLabel, "unavailable", secondary, false)
            if (session.location?.uncertain == true) field(folderLabel, "location uncertain", secondary, false)
            field(folderLabel, session.cwd.toString(), secondary)
            folderLabel.isVisible = true
        }
        details.border = JBUI.Borders.empty(2, titleLabel.icon.iconWidth + titleLabel.iconTextGap, 0, 0)
        details.isVisible = true
        val hit = hits[SearchSessionKey(session.kind,session.id)]
        if (hit != null) {
            field(matchLabel, hit.primary.source.label, agentColor, false)
            if (hit.toolMatch) hit.primary.toolName?.let { field(matchLabel, it.replace(WHITESPACE, " ").take(80), secondary, false) }
            if (hit.partial) field(matchLabel, "partial coverage", secondary, false)
            hit.snippet?.let { field(matchLabel, it.replace(WHITESPACE, " ").trim().take(280), foreground) }
            matchLabel.isVisible = true
        }
        toolTipText = tooltip(session, hit)
        getAccessibleContext().accessibleDescription = (listOf("Folder: ${session.cwd}") + WorktreePresentation.description(session) +
            listOfNotNull(hit?.let { "Matched ${it.primary.source.label}. ${it.snippet.orEmpty().take(1024)}" })).joinToString(" ")
    }

    override fun toString(): String = listOf(titleLabel.text, activityLabel.text, folderLabel.text, detailsLabel.text, matchLabel.text)
        .filter { it.isNotEmpty() }.joinToString(" ")

    private fun tooltip(session: Session, hit: SearchHit? = null): String {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;") }
        val fmt = { i: Instant? -> i?.let { TS.format(it.atZone(ZoneId.systemDefault())) } ?: "–" }
        val localTitle = SessionOrganisation.getInstance().title(session)
        return buildString {
            append("<html><b>").append(esc(localTitle)).append("</b><br>")
            if (localTitle != session.title) append("Agent title: ").append(esc(session.title)).append("<br>")
            if (session.id == activeSessionId) append("Active terminal<br>")
            if (session.id in unread) append("Finished turn not yet viewed<br>")
            append("Agent: ").append(session.kind.displayName).append("<br>")
            append("Session: ").append(esc(session.id)).append("<br>")
            session.forkedFromId?.let { append("Forked from: ").append(esc(it)).append("<br>") }
            if (session.subagentTranscriptPaths.isNotEmpty()) {
                append("Subagent activity: ").append(session.subagentTranscriptPaths.size).append(" transcript")
                if (session.subagentTranscriptPaths.size != 1) append('s')
                append("<br>")
            }
            session.continuationId?.let { append("Continues in: ").append(esc(it)).append("<br>") }
            append("Directory: ").append(esc(session.cwd.toString())).append("<br>")
            if (session.checkout == null) session.displayBranch?.let { append("Session-reported branch: ").append(esc(it)).append("<br>") }
            WorktreePresentation.description(session).forEach { append(esc(it)).append("<br>") }
            append("Started: ").append(fmt(session.startedAt)).append("<br>")
            append("Last activity: ").append(fmt(session.lastActivityAt)).append("<br>")
            append("Prompts: ").append(session.promptCount).append("<br>")
            if (isRunning(session)) {
                append(ActivityLabel.describe(session.activity))
                if (session.activity != Activity.UNKNOWN) session.activitySince?.let { append(" since ").append(fmt(it)) }
                append("<br>")
            }
            if (session.isLive) append("Running, pid ").append(session.livePid).append("<br>")
            hit?.let {
                append(esc(it.primary.source.label))
                when (it.primary.metadataVariant) {
                    SearchMetadataVariant.PROVIDER_TITLE -> append(" (provider title)")
                    SearchMetadataVariant.LOCAL_TITLE -> append(" (local override)")
                    else -> {}
                }
                if (it.toolMatch) it.primary.toolName?.let { name -> append(" · ").append(esc(name.take(80))) }
                it.snippet?.let { snippet -> append(": <i>").append(esc(snippet.take(1024))).append("</i>") }
                append("<br>")
            }
            session.transcriptPath?.let { append("<span style='color:gray'>").append(esc(it.toString())).append("</span>") }
            append("</html>")
        }
    }

    /** Live by the agent's own pid file, or still running in a tab Seshlog owns. */
    private fun isRunning(session: Session): Boolean = session.isLive || session.id in runningOwned

    companion object {
        // Treat agent-provided text literally; JLabel otherwise interprets strings starting with <html>.
        private fun plainLabel() = JLabel().apply { putClientProperty("html.disable", true) }

        private val WHITESPACE = Regex("\\s+")
        private val ACTIVE_COLOR: Color = JBColor(Color(0x2458A6), Color(0x8AB4F8))
        private val LIVE_COLOR: Color = JBColor(Color(0x2E8B57), Color(0x6CBF84))
        private val WAITING_COLOR: Color = JBColor(Color(0xB86E00), Color(0xE8A33D))
        private val HIT_COLOR: Color = JBColor(Color(0x3574F0), Color(0x548AF7))
        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
