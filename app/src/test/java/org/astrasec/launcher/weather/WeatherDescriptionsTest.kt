package org.astrasec.launcher.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherDescriptionsTest {
    @Test
    fun mapsCommonWmoCodesToChineseDescriptions() {
        assertEquals("晴", WeatherDescriptions.fromWmoCode(0))
        assertEquals("局部多云", WeatherDescriptions.fromWmoCode(2))
        assertEquals("大雨", WeatherDescriptions.fromWmoCode(65))
        assertEquals("雷暴伴冰雹", WeatherDescriptions.fromWmoCode(99))
    }

    @Test
    fun normalizesEnglishWttrDescriptions() {
        assertEquals("局部多云", WeatherDescriptions.normalizeWttr("Partly cloudy", 116))
        assertEquals("雷暴", WeatherDescriptions.normalizeWttr("Patchy light rain with thunder", 386))
    }

    @Test
    fun preservesLocalizedWttrDescriptions() {
        assertEquals("小雨", WeatherDescriptions.normalizeWttr("小雨", 296))
    }
}

