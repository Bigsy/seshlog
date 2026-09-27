package com.hedworth.seshlog.terminal

import org.junit.Assert.*
import org.junit.Test

class ProcessInspectionTest {
    @Test fun `an Error from the inspector does not block subsequent ticks`() {
        val failures = mutableListOf<Throwable>()
        val inspection = ProcessInspection({ it() }, { it() }, { failures.add(it) })
        inspection.run({ throw AssertionError("synthetic inspector failure") }, {})
        assertFalse(inspection.isRunning)
        var applied = 0
        inspection.run({ 42 }, { applied = it })
        assertEquals(42, applied)
        assertEquals(1, failures.size)
    }

    @Test fun `only one tick runs until delivery finishes including failing delivery`() {
        val deliveries = mutableListOf<() -> Unit>()
        val inspection = ProcessInspection({ it() }, { deliveries.add(it) }, {})
        var inspections = 0
        inspection.run({ inspections++; 1 }, { throw AssertionError("delivery") })
        inspection.run({ inspections++; 2 }, {})
        assertEquals(1, inspections)
        assertTrue(inspection.isRunning)
        org.junit.Assert.assertThrows(AssertionError::class.java) { deliveries.removeAt(0)() }
        assertFalse(inspection.isRunning)
        inspection.run({ inspections++; 3 }, {})
        deliveries.removeAt(0)()
        assertEquals(2, inspections)
        assertFalse(inspection.isRunning)
    }
}
