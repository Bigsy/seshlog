package com.hedworth.seshlog.copy

import org.junit.Assert.*
import org.junit.Test

class CopyRequestTest {
    private val one = LastAssistantTest.session(id = "one")
    private val two = LastAssistantTest.session(id = "two")

    @Test fun `focus resolves the actual split pane and never the last active pane`() {
        val first = javax.swing.JPanel()
        val second = javax.swing.JPanel()
        val input = javax.swing.JTextArea()
        second.add(input)
        assertSame(second, CopyTarget.focusedContent(input, listOf(first, second)) { it })
        assertNull(CopyTarget.focusedContent(javax.swing.JTextArea(), listOf(first, second)) { it })
        assertNull(CopyTarget.focusedContent(null, listOf(first, second)) { it })
    }

    @Test fun `focused tracked terminal takes priority over stale tree selection even in same directory`() {
        assertEquals(one.cwd, two.cwd)
        assertEquals(one, CopyTarget.resolve(true, one, two))
        assertNull(CopyTarget.resolve(true, null, two))
        assertEquals(two, CopyTarget.resolve(false, one, two))
        assertNull(CopyTarget.resolve(false, one, null))
    }

    @Test fun `captured target survives tab switching and newer requests suppress old results`() {
        val background = arrayListOf<() -> Unit>()
        val later = arrayListOf<() -> Unit>()
        var clipboard = "original"
        var disposed = false
        val feedback = arrayListOf<String>()
        val request = CopyRequest({ background.add(it) }, { later.add(it) }, { disposed }, { clipboard = it }, feedback::add)
        var selected = one
        request.start(selected) { CopyContent.Found(it.id) }
        selected = two
        background.removeAt(0)(); later.removeAt(0)()
        assertEquals("one", clipboard)
        request.start(one) { CopyContent.Found(it.id) }
        request.start(selected) { CopyContent.Found(it.id) }
        background.removeAt(1)(); background.removeAt(0)()
        later.forEach { it() }; later.clear()
        assertEquals("two", clipboard)
        for (result in listOf(CopyContent.Absent(), CopyContent.Failed(), CopyContent.Unsupported(), CopyContent.Found(" "))) {
            request.start(one) { result }; background.removeAt(0)(); later.removeAt(0)()
            assertEquals("two", clipboard)
        }
        request.start(one) { error("read failure") }; background.removeAt(0)(); later.removeAt(0)()
        assertEquals("two", clipboard)
        request.start(one) { CopyContent.Found("stale") }
        request.start(null) { error("must not read") }
        background.removeAt(0)(); later.removeAt(0)()
        assertEquals("two", clipboard)
        request.start(one) { CopyContent.Found("disposed") }
        disposed = true
        background.removeAt(0)(); later.removeAt(0)()
        assertEquals("two", clipboard)
        assertTrue(feedback.any { it.contains("could not be read") })
    }
}
