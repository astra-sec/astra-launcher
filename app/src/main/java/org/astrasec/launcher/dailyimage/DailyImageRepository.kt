package org.astrasec.launcher.dailyimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.text.Html
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DailyImageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val imageDirectory = File(appContext.filesDir, IMAGE_DIRECTORY_NAME)
    private val imageFile = File(imageDirectory, IMAGE_FILE_NAME)

    fun cachedImage(): DailyImageSnapshot? {
        if (!imageFile.isFile || imageFile.length() <= 0L) return null
        val provider = runCatching {
            DailyImageProvider.valueOf(preferences.getString(KEY_PROVIDER, null).orEmpty())
        }.getOrNull() ?: return null
        val date = preferences.getString(KEY_DATE, null)?.takeIf(String::isNotBlank) ?: return null
        val title = preferences.getString(KEY_TITLE, null)?.takeIf(String::isNotBlank) ?: return null
        return DailyImageSnapshot(
            metadata = DailyImageMetadata(
                provider = provider,
                date = date,
                title = title,
                credit = preferences.getString(KEY_CREDIT, "").orEmpty(),
                sourceUrl = preferences.getString(KEY_SOURCE_URL, "").orEmpty(),
                license = preferences.getString(KEY_LICENSE, "").orEmpty(),
                updatedAtMillis = preferences.getLong(KEY_UPDATED_AT, 0L)
            ),
            imageFile = imageFile
        )
    }

    fun refreshDailyImage(): DailyImageSnapshot? {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val cached = cachedImage()
        if (cached?.metadata?.date == today) return cached

        val now = System.currentTimeMillis()
        val lastAttempt = preferences.getLong(KEY_LAST_ATTEMPT_AT, 0L)
        val retryDelay = if (cached == null) EMPTY_CACHE_RETRY_MILLIS else CACHED_RETRY_MILLIS
        if (now - lastAttempt in 0 until retryDelay) return cached
        preferences.edit().putLong(KEY_LAST_ATTEMPT_AT, now).apply()
        for (provider in DAILY_IMAGE_PROVIDER_ORDER) {
            val candidate = runCatching {
                when (provider) {
                    DailyImageProvider.BING -> fetchBing(today, now)
                    DailyImageProvider.WIKIMEDIA -> fetchWikimedia(today, now)
                    DailyImageProvider.NASA_APOD -> fetchNasaApod(today, now)
                }
            }.getOrNull() ?: continue
            if (!downloadAndCommit(candidate)) continue
            saveMetadata(candidate.metadata)
            return cachedImage()
        }
        return cached
    }

    fun decodeForScreen(snapshot: DailyImageSnapshot, widthPx: Int, heightPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(snapshot.imageFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val targetWidth = widthPx.coerceAtLeast(MIN_DECODE_WIDTH)
        val targetHeight = heightPx.coerceAtLeast(MIN_DECODE_HEIGHT)
        while (
            bounds.outWidth / (sampleSize * 2) >= targetWidth &&
            bounds.outHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            snapshot.imageFile.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    private fun fetchBing(today: String, now: Long): DailyImageCandidate? {
        val root = getJson(BING_ARCHIVE_URL)
        val image = root.optJSONArray("images")?.optJSONObject(0) ?: return null
        val relativeUrl = image.optString("url").takeIf(String::isNotBlank) ?: return null
        val imageUrl = when {
            relativeUrl.startsWith("https://") -> relativeUrl
            relativeUrl.startsWith("//") -> "https:$relativeUrl"
            else -> "https://www.bing.com$relativeUrl"
        }
        val copyright = image.optString("copyright").trim()
        return DailyImageCandidate(
            metadata = DailyImageMetadata(
                provider = DailyImageProvider.BING,
                date = apiDate(image.optString("enddate")) ?: today,
                title = image.optString("title").trim().ifBlank { copyright.ifBlank { "每日图片" } },
                credit = copyright,
                sourceUrl = image.optString("copyrightlink").trim().ifBlank { BING_HOME_URL },
                license = "图片版权归相应作者或机构所有",
                updatedAtMillis = now
            ),
            imageUrl = imageUrl
        )
    }

    private fun fetchWikimedia(today: String, now: Long): DailyImageCandidate? {
        val template = "{{Potd/$today}}"
        val expanded = getJson(
            queryUrl(
                WIKIMEDIA_API_URL,
                "action" to "expandtemplates",
                "format" to "json",
                "formatversion" to "2",
                "prop" to "wikitext",
                "text" to template
            )
        )
        val fileName = expanded.optJSONObject("expandtemplates")
            ?.optString("wikitext")
            ?.trim()
            ?.removePrefix("File:")
            ?.takeIf { name -> name.isNotBlank() && !name.startsWith("[") }
            ?: return null

        val details = getJson(
            queryUrl(
                WIKIMEDIA_API_URL,
                "action" to "query",
                "format" to "json",
                "formatversion" to "2",
                "prop" to "imageinfo",
                "titles" to "File:$fileName",
                "iiprop" to "url|extmetadata",
                "iiurlwidth" to "1920",
                "iiextmetadatalanguage" to "zh-hans",
                "iiextmetadatafilter" to "Artist|LicenseShortName|LicenseUrl|Credit|ImageDescription"
            )
        )
        val page = details.optJSONObject("query")
            ?.optJSONArray("pages")
            ?.optJSONObject(0) ?: return null
        val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
        val imageUrl = info.optString("thumburl").ifBlank { info.optString("url") }
            .takeIf(String::isNotBlank) ?: return null
        val metadata = info.optJSONObject("extmetadata")
        val artist = metadata.metadataValue("Artist")
        val license = metadata.metadataValue("LicenseShortName")
        return DailyImageCandidate(
            metadata = DailyImageMetadata(
                provider = DailyImageProvider.WIKIMEDIA,
                date = today,
                title = fileName.substringBeforeLast('.').replace('_', ' '),
                credit = artist,
                sourceUrl = info.optString("descriptionurl").trim().ifBlank {
                    "https://commons.wikimedia.org/wiki/File:${encodePath(fileName)}"
                },
                license = license,
                updatedAtMillis = now
            ),
            imageUrl = imageUrl
        )
    }

    private fun fetchNasaApod(today: String, now: Long): DailyImageCandidate? {
        val root = getJson("$NASA_APOD_URL?api_key=DEMO_KEY")
        if (root.optString("media_type") != "image") return null
        val imageUrl = root.optString("url").takeIf(String::isNotBlank) ?: return null
        val apiDate = root.optString("date").takeIf(String::isNotBlank) ?: today
        return DailyImageCandidate(
            metadata = DailyImageMetadata(
                provider = DailyImageProvider.NASA_APOD,
                date = apiDate,
                title = root.optString("title").trim().ifBlank { "NASA 天文每日图片" },
                credit = root.optString("copyright").trim().ifBlank { "NASA/APOD" },
                sourceUrl = nasaPageUrl(apiDate),
                license = "请参阅 NASA 图片与媒体使用规则",
                updatedAtMillis = now
            ),
            imageUrl = imageUrl
        )
    }

    private fun downloadAndCommit(candidate: DailyImageCandidate): Boolean = runCatching {
        imageDirectory.mkdirs()
        val temporary = File.createTempFile("candidate_", ".image", imageDirectory)
        try {
            downloadImage(candidate.imageUrl, temporary)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            check(bounds.outWidth >= MIN_IMAGE_WIDTH && bounds.outHeight >= MIN_IMAGE_HEIGHT)
            replaceImageAtomically(temporary)
        } finally {
            temporary.delete()
        }
    }.isSuccess

    private fun downloadImage(url: String, destination: File) {
        val connection = openConnection(url, "image/*")
        try {
            val declaredLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                @Suppress("DEPRECATION")
                connection.contentLength.toLong()
            }
            if (declaredLength > MAX_IMAGE_BYTES) error("Image too large")
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) error("Image too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun replaceImageAtomically(temporary: File) {
        try {
            Files.move(
                temporary.toPath(),
                imageFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                imageFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun saveMetadata(metadata: DailyImageMetadata) {
        preferences.edit()
            .putString(KEY_PROVIDER, metadata.provider.name)
            .putString(KEY_DATE, metadata.date)
            .putString(KEY_TITLE, metadata.title)
            .putString(KEY_CREDIT, metadata.credit)
            .putString(KEY_SOURCE_URL, metadata.sourceUrl)
            .putString(KEY_LICENSE, metadata.license)
            .putLong(KEY_UPDATED_AT, metadata.updatedAtMillis)
            .apply()
    }

    private fun getJson(url: String): JSONObject {
        val connection = openConnection(url, "application/json")
        try {
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readLimited(MAX_JSON_CHARS)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.instanceFollowRedirects = true
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP $responseCode")
        }
        return connection
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

    private fun JSONObject?.metadataValue(key: String): String = this
        ?.optJSONObject(key)
        ?.optString("value")
        ?.let(::plainText)
        .orEmpty()

    private fun plainText(html: String): String = Html.fromHtml(
        html,
        Html.FROM_HTML_MODE_LEGACY
    ).toString().trim()

    private fun apiDate(value: String): String? {
        if (value.length != 8 || value.any { char -> !char.isDigit() }) return null
        return "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
    }

    private fun nasaPageUrl(date: String): String {
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return NASA_APOD_PAGE_URL
        return "https://apod.nasa.gov/apod/ap${parsed.format(DateTimeFormatter.ofPattern("yyMMdd"))}.html"
    }

    private fun queryUrl(base: String, vararg parameters: Pair<String, String>): String =
        base + "?" + parameters.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun encodePath(value: String): String = urlEncode(value).replace("%2F", "/")

    private companion object {
        const val PREFERENCES_NAME = "daily_image_cache"
        const val IMAGE_DIRECTORY_NAME = "daily_image"
        const val IMAGE_FILE_NAME = "current.image"
        const val KEY_PROVIDER = "provider"
        const val KEY_DATE = "date"
        const val KEY_TITLE = "title"
        const val KEY_CREDIT = "credit"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_LICENSE = "license"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"

        const val CONNECT_TIMEOUT_MILLIS = 6_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val MAX_JSON_CHARS = 1_000_000
        const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L
        const val MIN_IMAGE_WIDTH = 640
        const val MIN_IMAGE_HEIGHT = 360
        const val MIN_DECODE_WIDTH = 1024
        const val MIN_DECODE_HEIGHT = 600
        const val EMPTY_CACHE_RETRY_MILLIS = 30 * 60 * 1_000L
        const val CACHED_RETRY_MILLIS = 2 * 60 * 60 * 1_000L
        const val USER_AGENT = "AstraLauncher/0.1 (Android; org.astrasec.launcher)"

        const val BING_ARCHIVE_URL =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"
        const val BING_HOME_URL = "https://www.bing.com/"
        const val WIKIMEDIA_API_URL = "https://commons.wikimedia.org/w/api.php"
        const val NASA_APOD_URL = "https://api.nasa.gov/planetary/apod"
        const val NASA_APOD_PAGE_URL = "https://apod.nasa.gov/apod/astropix.html"
    }
}
