package com.hedworth.seshlog.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreviewRendererTest {
    @Test
    fun `assistant markdown renders structure and escapes raw html`() {
        val html = MarkdownPreviewRenderer.render(
            "# Answer\n\n- first\n- second\n\n```kotlin\nval n = 1\n```\n\n<script>alert(1)</script>\n\n<scr\nipt src=\"https://example.test/evil.js\">bad</script>\n<link\n rel=\"stylesheet\" href=\"https://example.test/evil.css\">\n\n`<generic>`\n\n![secret](https://example.test/secret.png)\n\n![reference][logo]\n\n[logo]: file:///private/secret.png",
        )

        assertTrue(html.contains("<h1>Answer</h1>"))
        assertTrue(html.contains("<li>first</li>"))
        assertTrue(html.contains("val n = 1"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("secret.png"))
        assertTrue(html.contains("secret"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<link"))
        assertTrue(html.contains("&lt;generic&gt;"))
    }
    @Test
    fun `unmatched inline code cannot enable resource bearing html attributes`() {
        val html = MarkdownPreviewRenderer.render("` unmatched <body background='https://example.test/image'>text</body>")
        assertFalse(html.contains("<body background"))
        assertFalse(Regex("<[^>]+(?:src|href|background)\\s*=").containsMatchIn(html))
    }

}
