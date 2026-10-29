package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.ui.SessionTextColors
import com.hedworth.seshlog.ui.appendQueryText
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** Literal, styled text fragments: no HTML parsing, transcript access or filesystem work. */
internal class SwitcherRenderer(private val query: () -> String = { "" }) :
    JPanel(BorderLayout()), ListCellRenderer<SwitcherRow> {
    internal val title = textComponent()
    internal val details = textComponent()
    private val attention = JLabel().apply { putClientProperty("html.disable", true) }
    private val heading = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
        isOpaque = false; add(title, BorderLayout.CENTER); add(attention, BorderLayout.EAST)
    }
    private var lastQuery: String? = null
    private var terms: List<String> = emptyList()
    private val rowBorder = JBUI.Borders.empty(5, 8)
    private val selectedBorder = BorderFactory.createCompoundBorder(
        JBUI.Borders.customLine(OUTLINE_COLOR, 2), JBUI.Borders.empty(3, 6))

    init { border = rowBorder; add(heading, BorderLayout.NORTH); add(details, BorderLayout.SOUTH) }

    override fun validate() { doLayout(); heading.doLayout() }

    override fun getListCellRendererComponent(list: JList<out SwitcherRow>, value: SwitcherRow,
                                              index: Int, selected: Boolean, focus: Boolean): Component {
        val text = query()
        if (text != lastQuery) {
            lastQuery = text
            terms = if (text.length > SwitcherSearch.MAX_QUERY) emptyList()
                else text.split(Regex("\\s+")).filter(String::isNotBlank).distinct()
        }
        background = list.background
        border = if (selected) selectedBorder else rowBorder
        val foreground = list.foreground
        val secondary = SessionTextColors.SECONDARY
        title.clear(); details.clear()
        title.font = list.font.deriveFont(Font.PLAIN)
        details.font = title.font.deriveFont((list.font.size2D - 1).coerceAtLeast(1f))
        attention.font = details.font
        attention.foreground = secondary
        title.appendQueryText(value.title, terms, foreground)
        details.append(value.session.kind.displayName, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, SessionTextColors.AGENT))
        fun field(text: String, color: Color) {
            details.append(" · ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondary))
            details.appendQueryText(text, terms, color)
        }
        value.projectNames.forEach { field(it, foreground) }
        (value.checkout?.branchLabel ?: value.session.displayBranch)?.let { field(it, SessionTextColors.BRANCH) }
        field(value.session.cwd.toString(), secondary)
        attention.text = value.attention(Instant.now())
        getAccessibleContext().accessibleName = "${value.title}, ${value.details()}, ${attention.text}, ${value.status}"
        toolTipText = null
        preferredSize = Dimension(JBUI.scale(700), title.preferredSize.height + details.preferredSize.height + JBUI.scale(10))
        return this
    }

    private companion object {
        val OUTLINE_COLOR = JBColor.namedColor("Component.focusColor", JBColor(Color(0x3574F0), Color(0x548AF7)))
        fun textComponent() = SimpleColoredComponent().apply { isOpaque = false; ipad = JBUI.emptyInsets() }
    }
}
