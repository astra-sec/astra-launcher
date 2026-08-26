package org.astrasec.launcher.dailyimage

import java.io.File

enum class DailyImageProvider(val displayName: String) {
    BING("Bing 每日图片"),
    WIKIMEDIA("Wikimedia Commons 每日图片"),
    NASA_APOD("NASA 天文每日图片")
}

internal val DAILY_IMAGE_PROVIDER_ORDER = listOf(
    DailyImageProvider.BING,
    DailyImageProvider.WIKIMEDIA,
    DailyImageProvider.NASA_APOD
)

data class DailyImageMetadata(
    val provider: DailyImageProvider,
    val date: String,
    val title: String,
    val credit: String,
    val sourceUrl: String,
    val license: String,
    val updatedAtMillis: Long
)

data class DailyImageSnapshot(
    val metadata: DailyImageMetadata,
    val imageFile: File
)

internal data class DailyImageCandidate(
    val metadata: DailyImageMetadata,
    val imageUrl: String
)
