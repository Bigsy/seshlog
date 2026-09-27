package com.hedworth.seshlog.ui

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Renders assistant Markdown with the bundled parser. The small fallback handles a damaged or
 * unavailable parser without making preview loading fail. Raw HTML is treated as text in both paths.
 */
internal object MarkdownPreviewRenderer {
    fun render(markdown: String): String {
        val safe = escapeImages(escapeRawHtml(markdown))
        return runCatching { bundledMarkdown(safe) }.getOrElse { fallback(safe) }
    }

    private fun bundledMarkdown(markdown: String): String {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return sanitizeHtml(HtmlGenerator(markdown, tree, flavour).generateHtml())
    }

    // The Swing HTML renderer can fetch resources from attributes beyond <img>, including
    // body backgrounds. Preserve only structural tags, with no resource-bearing attributes.
    private fun sanitizeHtml(html: String): String = Regex("<[^>]*>").replace(html) { match ->
        val tag = Regex("<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9]*)\\b").find(match.value)
        val name = tag?.groupValues?.get(2)?.lowercase()
        if (name in SAFE_TAGS) "<${tag!!.groupValues[1]}$name>" else esc(match.value)
    }.replace(Regex("<[^>]*$")) { esc(it.value) }

    private val SAFE_TAGS = setOf(
        "html", "body", "div", "span", "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
        "pre", "code", "blockquote", "ul", "ol", "li", "em", "strong", "b", "i", "s", "del",
        "table", "thead", "tbody", "tr", "th", "td", "a",
    )

    private fun fallback(markdown: String): String = buildString {
        var inCode = false
        markdown.lines().forEach { line ->
            when {
                line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~") -> {
                    if (inCode) append("</code></pre>") else append("<pre><code>")
                    inCode = !inCode
                }
                inCode -> append(escPreservingEntities(line)).append('\n')
                line.startsWith("### ") -> append("<h3>").append(escPreservingEntities(line.drop(4))).append("</h3>")
                line.startsWith("## ") -> append("<h2>").append(escPreservingEntities(line.drop(3))).append("</h2>")
                line.startsWith("# ") -> append("<h1>").append(escPreservingEntities(line.drop(2))).append("</h1>")
                line.startsWith("- ") -> append("<ul><li>").append(inline(line.drop(2))).append("</li></ul>")
                line.isBlank() -> Unit
                else -> append("<p>").append(inline(line)).append("</p>")
            }
        }
        if (inCode) append("</code></pre>")
    }

    private fun inline(text: String): String =
        escPreservingEntities(text).replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    /** Escape tags outside fenced or inline code, including tags whose attributes span lines. */
    private fun escapeRawHtml(markdown: String): String = buildString {
        var inCode = false
        var inTag = false
        val lines = markdown.lines()
        lines.forEachIndexed { index, line ->
            val fence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
            if (fence) {
                inCode = !inCode
                append(line)
            } else if (inCode) {
                append(line)
            } else {
                var inlineCode = false
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    if (c == '`' && !inTag) {
                        inlineCode = !inlineCode
                        append(c)
                    } else if (c == '<' && !inlineCode && !inTag && line.getOrNull(i + 1)?.let { it.isLetter() || it == '/' || it == '!' || it == '?' } == true) {
                        append("&lt;")
                        inTag = true
                    } else if (c == '>' && inTag) {
                        append("&gt;")
                        inTag = false
                    } else {
                        append(c)
                    }
                    i++
                }
            }
            if (index < lines.lastIndex) append('\n')
        }
    }

    /** Images are displayed as alt text so rendering cannot trigger a local or remote fetch. */
    private fun escapeImages(markdown: String): String =
        markdown
            .replace(Regex("!\\[([^]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("!\\[([^]]*)\\]\\s*\\[[^]]*]"), "$1")

    private fun esc(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escPreservingEntities(value: String): String = value
        .replace(Regex("&(?!#\\d+;|#x[0-9a-fA-F]+;|[A-Za-z][A-Za-z0-9]+;)"), "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;")
}
