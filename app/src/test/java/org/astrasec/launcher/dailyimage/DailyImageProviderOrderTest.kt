package org.astrasec.launcher.dailyimage

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyImageProviderOrderTest {
    @Test
    fun `uses the agreed fallback order`() {
        assertEquals(
            listOf(
                DailyImageProvider.BING,
                DailyImageProvider.WIKIMEDIA,
                DailyImageProvider.NASA_APOD
            ),
            DAILY_IMAGE_PROVIDER_ORDER
        )
    }
}
