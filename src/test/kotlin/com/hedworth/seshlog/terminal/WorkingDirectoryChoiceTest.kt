package com.hedworth.seshlog.terminal

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class WorkingDirectoryChoiceTest {
    @Test fun `replacement is remembered and missing replacements fall back or request a choice`() {
        val original = Paths.get("/old")
        val replacement = Paths.get("/new")
        assertEquals(replacement, WorkingDirectoryChoice.available(original, "/new") { it == replacement })
        assertEquals(original, WorkingDirectoryChoice.available(original, "/gone") { it == original })
        assertNull(WorkingDirectoryChoice.available(original, "/gone") { false })
        assertNull(WorkingDirectoryChoice.available(original, "\u0000") { false })
    }
}
