package org.astrasec.launcher.weather

internal object WeatherDescriptions {
    fun fromWmoCode(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大致晴朗"
        2 -> "局部多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63 -> "小到中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71, 73 -> "小到中雪"
        75 -> "大雪"
        77 -> "米雪"
        80, 81 -> "阵雨"
        82 -> "强阵雨"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴伴冰雹"
        else -> "天气未知"
    }

    fun normalizeWttr(description: String, code: Int): String {
        if (description.any { it.code > 127 }) return description.trim()

        val normalized = description.lowercase()
        return when {
            "thunder" in normalized -> "雷暴"
            "blizzard" in normalized -> "暴风雪"
            "snow" in normalized -> "雪"
            "sleet" in normalized || "ice pellet" in normalized -> "雨夹雪"
            "freezing" in normalized -> "冻雨"
            "shower" in normalized -> "阵雨"
            "rain" in normalized -> "雨"
            "drizzle" in normalized -> "毛毛雨"
            "fog" in normalized || "mist" in normalized -> "雾"
            "overcast" in normalized -> "阴"
            "partly" in normalized -> "局部多云"
            "cloud" in normalized -> "多云"
            "sunny" in normalized || "clear" in normalized -> "晴"
            else -> fromWttrCode(code)
        }
    }

    private fun fromWttrCode(code: Int): String = when (code) {
        113 -> "晴"
        116 -> "局部多云"
        119 -> "多云"
        122 -> "阴"
        143, 248, 260 -> "雾"
        176, 263, 266, 281, 284 -> "毛毛雨"
        in 293..320, in 353..365 -> "雨"
        in 323..350, in 368..377 -> "雪"
        in 386..395, 200 -> "雷暴"
        else -> "天气未知"
    }
}

