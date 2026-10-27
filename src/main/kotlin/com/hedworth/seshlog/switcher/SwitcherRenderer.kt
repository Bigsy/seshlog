package com.hedworth.seshlog.switcher

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
        val secondary = SECONDARY_COLOR
        title.clear(); details.clear()
        title.font = list.font.deriveFont(Font.PLAIN)
        details.font = title.font.deriveFont((list.font.size2D - 1).coerceAtLeast(1f))
        attention.font = details.font
        attention.foreground = secondary
        title.appendHighlighted(value.title, foreground)
        details.append(value.session.kind.displayName, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, AGENT_COLOR))
        fun field(text: String, color: Color) {
            details.append(" · ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondary))
            details.appendHighlighted(text, color)
        }
        value.projectNames.forEach { field(it, foreground) }
        (value.checkout?.branchLabel ?: value.session.displayBranch)?.let { field(it, BRANCH_COLOR) }
        field(value.session.cwd.toString(), secondary)
        attention.text = value.attention(Instant.now())
        getAccessibleContext().accessibleName = "${value.title}, ${value.details()}, ${attention.text}, ${value.status}"
        toolTipText = null
        preferredSize = Dimension(JBUI.scale(700), title.preferredSize.height + details.preferredSize.height + JBUI.scale(10))
        return this
    }

    private fun SimpleColoredComponent.appendHighlighted(text: String, color: Color) {
        val matched = BooleanArray(text.length)
        for (term in terms) {
            var start = text.indexOf(term, ignoreCase = true)
            while (start >= 0) {
                matched.fill(true, start, start + term.length)
                start = text.indexOf(term, start + 1, ignoreCase = true)
            }
        }
        var start = 0
        while (start < text.length) {
            var end = start + 1
            while (end < text.length && matched[end] == matched[start]) end++
            val style = if (matched[start]) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN
            append(text.substring(start, end), SimpleTextAttributes(style, color))
            start = end
        }
    }

    private companion object {
        val AGENT_COLOR = JBColor(Color(0x2458A6), Color(0x8AB4F8))
        val BRANCH_COLOR = JBColor(Color(0x26734D), Color(0x7EC699))
        val SECONDARY_COLOR = JBColor(Color(0x6C707E), Color(0xA8ADB5))
        val OUTLINE_COLOR = JBColor.namedColor("Component.focusColor", JBColor(Color(0x3574F0), Color(0x548AF7)))
        fun textComponent() = SimpleColoredComponent().apply { isOpaque = false; ipad = JBUI.emptyInsets() }
    }
}
