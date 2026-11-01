package com.hedworth.seshlog.selection

import com.hedworth.seshlog.terminal.BracketedPaste
import org.junit.Assert.*
import org.junit.Test

class EditorReferenceFormatTest {
    private val reference = EditorReference("/fictional/work tree/src/Example.kt", 12, 15, false)

    @Test fun `one reference preserves space for a message and has no Enter or source text`() {
        assertEquals("\"/fictional/work tree/src/Example.kt\":12-15 ", reference.text())
        assertEquals("\"/fictional/file.kt\":9 ", reference.copy(path = "/fictional/file.kt", firstLine = 9, lastLine = 9).text())
        assertFalse(reference.text().contains('\n'))
        assertFalse(reference.text().contains('\r'))
    }

    @Test fun `file names are quoted and controls cannot escape the paste`() {
        assertEquals("\"/fictional/a\\\"b\\\\c.kt\":12-15 ", reference.copy(path = "/fictional/a\"b\\c.kt").text())
        for (control in listOf('\u001b', '\r', '\n', '\t', '\u0000', '\u007f', '\u009b')) {
            assertThrows(IllegalArgumentException::class.java) { reference.copy(path = "/fictional/$control.kt").text() }
            assertThrows(IllegalArgumentException::class.java) { BracketedPaste.frame("file${if (control in "\r\n\t") '\u001b' else control}") }
        }
        assertEquals("\u001b[200~${reference.text()}\u001b[201~", BracketedPaste.frame(reference.text()))
        assertEquals("\u001b[200~a\nb\nc\u001b[201~", BracketedPaste.frame("a\r\nb\rc"))
    }
}
