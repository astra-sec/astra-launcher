package org.astrasec.launcher.model

import kotlin.math.max

object GridSpanCalculator {
    fun fromWidth(availableWidthPx: Int, minimumColumnWidthPx: Int): Int {
        if (availableWidthPx <= 0 || minimumColumnWidthPx <= 0) return 1
        return max(1, availableWidthPx / minimumColumnWidthPx)
    }
}

