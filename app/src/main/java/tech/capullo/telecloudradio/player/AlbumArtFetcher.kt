package tech.capullo.telecloudradio.player

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches album art for tracks without an embedded picture: Deezer first,
 * iTunes Search as fallback (both keyless). Results - including "nothing
 * found" - are cached on disk per messageId and evicted together with the
 * track file.
 */
@Singleton
class AlbumArtFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cacheDir: File get() = File(context.filesDir, "artcache")

    private fun artFile(messageId: Long) = File(cacheDir, "art_$messageId.jpg")
    private fun noneMarker(messageId: Long) = File(cacheDir, "art_$messageId.none")

    fun cached(messageId: Long): ByteArray? =
        artFile(messageId).takeIf { it.exists() }?.readBytes()

    fun evict(messageId: Long) {
        artFile(messageId).delete()
        noneMarker(messageId).delete()
    }

    fun clearAll() {
        cacheDir.deleteRecursively()
    }

    suspend fun fetch(
        messageId: Long,
        artist: String?,
        title: String?,
        fileName: String?,
    ): ByteArray? = withContext(Dispatchers.IO) {
        cached(messageId)?.let { return@withContext it }
        if (noneMarker(messageId).exists()) return@withContext null

        val query = resolveQuery(artist, title, fileName)
        if (query == null) {
            markNone(messageId)
            return@withContext null
        }
        // Plain full-text attempts (field-qualified queries break on reversed
        // "Title - Artist" naming); second attempt drops "(French Mix)"-style tags
        val primary = listOfNotNull(query.first, query.second).joinToString(" ")
        val stripped = primary
            .replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val attempts = if (stripped.isNotBlank() && stripped != primary) {
            listOf(primary, stripped)
        } else listOf(primary)

        // Only write the negative-cache marker when a source definitively answered
        // "no results" - a network failure must stay retryable
        var definitiveMiss = false
        for (text in attempts) {
            for (source in listOf(::fetchITunes, ::fetchDeezer)) {
                try {
                    val bytes = source(text)
                    if (bytes != null) {
                        cacheDir.mkdirs()
                        artFile(messageId).writeBytes(bytes)
                        Log.d("TeleCloud", "art fetched for $messageId (${bytes.size} bytes, q=\"$text\")")
                        return@withContext bytes
                    }
                    definitiveMiss = true
                } catch (e: Exception) {
                    Log.d("TeleCloud", "art source error for \"$text\": ${e.message}")
                }
            }
        }
        if (definitiveMiss) markNone(messageId)
        null
    }

    private fun markNone(messageId: Long) {
        cacheDir.mkdirs()
        noneMarker(messageId).writeBytes(ByteArray(0))
    }

    // ---- query building ----

    // Returns artist (nullable) to search text; null when nothing usable exists
    private fun resolveQuery(
        artist: String?,
        title: String?,
        fileName: String?,
    ): Pair<String?, String>? {
        if (!title.isNullOrBlank()) {
            return (artist?.takeIf { it.isNotBlank() }) to title
        }
        val base = cleanFilename(fileName) ?: return null
        val dashSplit = base.split(" - ", limit = 2)
        return if (dashSplit.size == 2 && dashSplit[0].isNotBlank() && dashSplit[1].isNotBlank()) {
            dashSplit[0].trim() to dashSplit[1].trim()
        } else {
            null to base
        }
    }

    private val audioExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "aiff", "aif", "alac", "ape",
    )
    private val usernameRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]{4,31}$")
    private val youtubeIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    // "12 - The_Frightnrs - Gotta Find a Way.mimarido11.m4a" → "The Frightnrs - Gotta Find a Way"
    private fun cleanFilename(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val parts = fileName.split(".").toMutableList()
        // Strip trailing extension(s), uploader tags and YouTube-id tokens
        while (parts.size > 1) {
            val last = parts.last().trim()
            if (last.lowercase() in audioExtensions ||
                usernameRegex.matches(last) ||
                youtubeIdRegex.matches(last)
            ) {
                parts.removeAt(parts.size - 1)
            } else break
        }
        var base = parts.joinToString(".").replace('_', ' ').trim()
        base = base.replace(Regex("^\\d{1,3}[\\s.\\-]+"), "").trim() // leading track number
        return base.takeIf { it.isNotBlank() }
    }

    // ---- sources ----
    // Return null = definitive "no results"; throw = network/availability problem
    // (iTunes first: Deezer is geo-blocked in some regions and just errors there)

    private fun fetchITunes(text: String): ByteArray? {
        val json = httpGetString(
            "https://itunes.apple.com/search?media=music&limit=1&term=${URLEncoder.encode(text, "UTF-8")}",
        )
        val root = JSONObject(json)
        if (root.optInt("resultCount") == 0) return null
        val url = root.getJSONArray("results").getJSONObject(0).optString("artworkUrl100")
        if (url.isBlank()) return null
        return httpGetBytes(url.replace("100x100", "600x600"))
    }

    private fun fetchDeezer(text: String): ByteArray? {
        val json = httpGetString(
            "https://api.deezer.com/search?limit=1&q=${URLEncoder.encode(text, "UTF-8")}",
        )
        val data = JSONObject(json).optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        val album = data.getJSONObject(0).optJSONObject("album") ?: return null
        val url = album.optString("cover_xl").ifBlank { album.optString("cover_big") }
        if (url.isBlank()) return null
        return httpGetBytes(url)
    }

    // ---- http (throws on any failure - callers decide retryability) ----

    private fun httpGetString(url: String): String =
        httpGetBytes(url).toString(Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", "TelecloudRadio/0.1 (Android)")
            connection.instanceFollowRedirects = true
            if (connection.responseCode != 200) {
                throw java.io.IOException("HTTP ${connection.responseCode} for $url")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
