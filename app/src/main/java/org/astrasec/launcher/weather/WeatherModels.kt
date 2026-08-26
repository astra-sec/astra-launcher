package org.astrasec.launcher.weather

import kotlin.math.roundToInt

data class WeatherSnapshot(
    val locationName: String,
    val temperatureCelsius: Double,
    val condition: String,
    val provider: WeatherProvider,
    val updatedAtMillis: Long
) {
    val statusText: String
        get() = "$locationName · $condition · ${temperatureCelsius.roundToInt()}°C"
}

enum class WeatherProvider(
    val displayName: String,
    val attributionUrl: String
) {
    OPEN_METEO("Open-Meteo", "https://open-meteo.com/"),
    WTTR_IN("wttr.in", "https://wttr.in/")
}

internal data class ApproximateLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAtMillis: Long
)

