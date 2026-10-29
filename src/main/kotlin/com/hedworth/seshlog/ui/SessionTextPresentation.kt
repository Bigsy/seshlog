package com.hedworth.seshlog.ui

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Color

/** Shared field palette and literal query emphasis for the main list and quick switcher. */
internal object SessionTextColors {
    val AGENT = JBColor(Color(0x2458A6), Color(0x8AB4F8))
    val BRANCH = JBColor(Color(0x26734D), Color(0x7EC699))
    val SECONDARY = JBColor(Color(0x6C707E), Color(0xA8ADB5))
}

internal class SessionRowText : SimpleColoredComponent() {
    val text: String get() = getCharSequence(false).toString()
    init { isOpaque = false; ipad = JBUI.emptyInsets() }
}

/** Native fragments keep user text literal and preserve UTF-16 offsets, including overlapping hits. */
internal fun SimpleColoredComponent.appendQueryText(
    text: String, terms: List<String>, color: Color, baseStyle: Int = SimpleTextAttributes.STYLE_PLAIN,
) {
    if (text.isEmpty()) return
    if (terms.isEmpty()) {
        append(text, SimpleTextAttributes(baseStyle, color))
        return
    }
    val matched = BooleanArray(text.length)
    for (term in terms) {
        if (term.isEmpty()) continue
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
        val style = baseStyle or if (matched[start]) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN
        append(text.substring(start, end), SimpleTextAttributes(style, color))
        start = end
    }
}
