package org.astrasec.launcher.weather

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class WeatherRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun cachedWeather(): WeatherSnapshot? {
        if (!preferences.contains(KEY_WEATHER_UPDATED_AT)) return null
        val provider = runCatching {
            WeatherProvider.valueOf(preferences.getString(KEY_WEATHER_PROVIDER, null).orEmpty())
        }.getOrNull() ?: return null
        val locationName = preferences.getString(KEY_WEATHER_LOCATION, null)
            ?.takeIf(String::isNotBlank) ?: return null
        val condition = preferences.getString(KEY_WEATHER_CONDITION, null)
            ?.takeIf(String::isNotBlank) ?: return null

        return WeatherSnapshot(
            locationName = locationName,
            temperatureCelsius = Double.fromBits(
                preferences.getLong(KEY_WEATHER_TEMPERATURE, Double.NaN.toBits())
            ),
            condition = condition,
            provider = provider,
            updatedAtMillis = preferences.getLong(KEY_WEATHER_UPDATED_AT, 0L)
        ).takeIf { it.temperatureCelsius.isFinite() }
    }

    fun refreshWeather(): WeatherSnapshot? {
        val now = System.currentTimeMillis()
        val cachedWeather = cachedWeather()
        if (cachedWeather != null && now - cachedWeather.updatedAtMillis < WEATHER_REFRESH_MILLIS) {
            return cachedWeather
        }

        val cachedLocation = cachedLocation()
        val location = when {
            cachedLocation != null && now - cachedLocation.updatedAtMillis < LOCATION_REFRESH_MILLIS -> {
                cachedLocation
            }
            else -> fetchIpWhoisLocation(now)?.also(::saveLocation) ?: cachedLocation
        }

        if (location != null) {
            fetchOpenMeteo(location, now)?.let { weather ->
                saveWeather(weather)
                return weather
            }
            fetchWttr(location, now)?.let { result ->
                saveWeather(result.weather)
                return result.weather
            }
        }

        if (location == null) {
            fetchWttr(location = null, now)?.let { result ->
                result.location?.let(::saveLocation)
                saveWeather(result.weather)
                return result.weather
            }
        }

        return cachedWeather
    }

    private fun fetchIpWhoisLocation(now: Long): ApproximateLocation? = runCatching {
        val root = getJson(IPWHOIS_URL)
        if (!root.optBoolean("success", false)) return@runCatching null
        val latitude = root.optDouble("latitude", Double.NaN)
        val longitude = root.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return@runCatching null

        ApproximateLocation(
            name = locationLabel(
                root.optString("city"),
                root.optString("region"),
                root.optString("country")
            ) ?: return@runCatching null,
            latitude = latitude,
            longitude = longitude,
            updatedAtMillis = now
        )
    }.getOrNull()

    private fun fetchOpenMeteo(
        location: ApproximateLocation,
        now: Long
    ): WeatherSnapshot? = runCatching {
        val coordinates = formattedCoordinates(location)
        val root = getJson(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${coordinates.first}&longitude=${coordinates.second}" +
                "&current=temperature_2m,weather_code&timezone=auto&forecast_days=1"
        )
        val current = root.optJSONObject("current") ?: return@runCatching null
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        val weatherCode = current.optInt("weather_code", Int.MIN_VALUE)
        if (!temperature.isFinite() || weatherCode == Int.MIN_VALUE) return@runCatching null

        WeatherSnapshot(
            locationName = location.name,
            temperatureCelsius = temperature,
            condition = WeatherDescriptions.fromWmoCode(weatherCode),
            provider = WeatherProvider.OPEN_METEO,
            updatedAtMillis = now
        )
    }.getOrNull()

    private fun fetchWttr(location: ApproximateLocation?, now: Long): WttrResult? = runCatching {
        val locationPath = location?.let {
            val coordinates = formattedCoordinates(it)
            "${coordinates.first},${coordinates.second}"
        }.orEmpty()
        val root = getJson("https://wttr.in/$locationPath?format=j1&lang=zh")
        val current = root.optJSONArray("current_condition")
            ?.optJSONObject(0) ?: return@runCatching null
        val temperature = current.optString("temp_C").toDoubleOrNull()
            ?: return@runCatching null
        val weatherCode = current.optString("weatherCode").toIntOrNull() ?: -1
        val description = current.optJSONArray("weatherDesc")
            ?.optJSONObject(0)?.optString("value").orEmpty()

        val wttrLocation = parseWttrLocation(root, now)
        val locationName = location?.name ?: wttrLocation?.name ?: return@runCatching null
        WttrResult(
            weather = WeatherSnapshot(
                locationName = locationName,
                temperatureCelsius = temperature,
                condition = WeatherDescriptions.normalizeWttr(description, weatherCode),
                provider = WeatherProvider.WTTR_IN,
                updatedAtMillis = now
            ),
            location = wttrLocation
        )
    }.getOrNull()

    private fun parseWttrLocation(root: JSONObject, now: Long): ApproximateLocation? {
        val area = root.optJSONArray("nearest_area")?.optJSONObject(0) ?: return null
        val latitude = area.optString("latitude").toDoubleOrNull() ?: return null
        val longitude = area.optString("longitude").toDoubleOrNull() ?: return null
        val name = locationLabel(
            area.optJSONArray("areaName")?.optJSONObject(0)?.optString("value").orEmpty(),
            area.optJSONArray("region")?.optJSONObject(0)?.optString("value").orEmpty(),
            area.optJSONArray("country")?.optJSONObject(0)?.optString("value").orEmpty()
        ) ?: return null
        return ApproximateLocation(name, latitude, longitude, now)
    }

    private fun cachedLocation(): ApproximateLocation? {
        if (!preferences.contains(KEY_LOCATION_UPDATED_AT)) return null
        val name = preferences.getString(KEY_LOCATION_NAME, null)
            ?.takeIf(String::isNotBlank) ?: return null
        val latitude = Double.fromBits(
            preferences.getLong(KEY_LOCATION_LATITUDE, Double.NaN.toBits())
        )
        val longitude = Double.fromBits(
            preferences.getLong(KEY_LOCATION_LONGITUDE, Double.NaN.toBits())
        )
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return ApproximateLocation(
            name,
            latitude,
            longitude,
            preferences.getLong(KEY_LOCATION_UPDATED_AT, 0L)
        )
    }

    private fun saveLocation(location: ApproximateLocation) {
        preferences.edit()
            .putString(KEY_LOCATION_NAME, location.name)
            .putLong(KEY_LOCATION_LATITUDE, location.latitude.toBits())
            .putLong(KEY_LOCATION_LONGITUDE, location.longitude.toBits())
            .putLong(KEY_LOCATION_UPDATED_AT, location.updatedAtMillis)
            .apply()
    }

    private fun saveWeather(weather: WeatherSnapshot) {
        preferences.edit()
            .putString(KEY_WEATHER_LOCATION, weather.locationName)
            .putLong(KEY_WEATHER_TEMPERATURE, weather.temperatureCelsius.toBits())
            .putString(KEY_WEATHER_CONDITION, weather.condition)
            .putString(KEY_WEATHER_PROVIDER, weather.provider.name)
            .putLong(KEY_WEATHER_UPDATED_AT, weather.updatedAtMillis)
            .apply()
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) error("HTTP $responseCode")
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readLimited(MAX_RESPONSE_CHARS)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun BufferedReader.readLimited(maxChars: Int): String {
        val result = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (result.length + count > maxChars) error("Response too large")
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private fun formattedCoordinates(location: ApproximateLocation): Pair<String, String> =
        String.format(Locale.US, "%.4f", location.latitude) to
            String.format(Locale.US, "%.4f", location.longitude)

    private fun locationLabel(vararg candidates: String): String? {
        val parts = candidates.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(2)
            .toList()
        return parts.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
    }

    private data class WttrResult(
        val weather: WeatherSnapshot,
        val location: ApproximateLocation?
    )

    private companion object {
        const val PREFERENCES_NAME = "weather_cache"
        const val KEY_LOCATION_NAME = "location_name"
        const val KEY_LOCATION_LATITUDE = "location_latitude"
        const val KEY_LOCATION_LONGITUDE = "location_longitude"
        const val KEY_LOCATION_UPDATED_AT = "location_updated_at"
        const val KEY_WEATHER_LOCATION = "weather_location"
        const val KEY_WEATHER_TEMPERATURE = "weather_temperature"
        const val KEY_WEATHER_CONDITION = "weather_condition"
        const val KEY_WEATHER_PROVIDER = "weather_provider"
        const val KEY_WEATHER_UPDATED_AT = "weather_updated_at"

        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 8_000
        const val MAX_RESPONSE_CHARS = 300_000
        const val WEATHER_REFRESH_MILLIS = 30 * 60 * 1_000L
        const val LOCATION_REFRESH_MILLIS = 24 * 60 * 60 * 1_000L
        const val USER_AGENT = "AstraLauncher/0.1 (Android; org.astrasec.launcher)"
        const val IPWHOIS_URL =
            "https://ipwho.is/?fields=success,city,region,country,latitude,longitude"
    }
}
