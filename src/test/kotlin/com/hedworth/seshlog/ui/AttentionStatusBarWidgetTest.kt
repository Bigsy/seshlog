package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SessionAttention
import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionStatusBarWidgetTest {
    @Test fun `status text follows attention counts`() {
        assertEquals("2 working · 1 unread", AttentionStatusBarWidget.textFor(SessionAttention.Counts(2, 1)))
        assertEquals("", AttentionStatusBarWidget.textFor(SessionAttention.Counts(0, 0)))
        assertEquals("3 unread", AttentionStatusBarWidget.textFor(SessionAttention.Counts(0, 3)))
    }
}
