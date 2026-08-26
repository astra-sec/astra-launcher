package org.astrasec.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GridSpanCalculatorTest {
    @Test
    fun `uses all columns that fit the available width`() {
        assertEquals(4, GridSpanCalculator.fromWidth(availableWidthPx = 600, minimumColumnWidthPx = 140))
        assertEquals(6, GridSpanCalculator.fromWidth(availableWidthPx = 1000, minimumColumnWidthPx = 160))
    }

    @Test
    fun `always keeps at least one column`() {
        assertEquals(1, GridSpanCalculator.fromWidth(availableWidthPx = 100, minimumColumnWidthPx = 140))
        assertEquals(1, GridSpanCalculator.fromWidth(availableWidthPx = 0, minimumColumnWidthPx = 0))
    }
}
