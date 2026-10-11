package org.astrasec.launcher.device

import org.junit.Assert.assertEquals
import org.junit.Test

class TvInputIntentPolicyTest {
    private val channel = "vnd.android.cursor.item/channel"

    @Test fun genericContentAndWildcardHandlersAreRejected() {
        assertEquals(0, tvInputHandlerPriority(emptyList(), emptyList(), channel, false))
        assertEquals(0, tvInputHandlerPriority(emptyList(), listOf("*", "*/*"), channel, true))
        assertEquals(0, tvInputHandlerPriority(listOf("*"), emptyList(), channel, true))
    }

    @Test fun specificTvHandlersOutrankSystemCursorFallback() {
        assertEquals(2, tvInputHandlerPriority(listOf("android.media.tv"), emptyList(), null, false))
        assertEquals(2, tvInputHandlerPriority(emptyList(), listOf(channel), channel, false))
        assertEquals(1, tvInputHandlerPriority(emptyList(), listOf("vnd.android.cursor.item"), channel, true))
        assertEquals(1, tvInputHandlerPriority(emptyList(), listOf("vnd.android.cursor.item/*"), channel, true))
    }

    @Test fun broadCursorTypesRequireSystemAppAndTvMimeType() {
        assertEquals(0, tvInputHandlerPriority(emptyList(), listOf("vnd.android.cursor.item"), channel, false))
        assertEquals(0, tvInputHandlerPriority(emptyList(), listOf("video"), "video/mp4", true))
        assertEquals(0, tvInputHandlerPriority(emptyList(), listOf("vnd.android.cursor.item"), null, true))
    }
}
