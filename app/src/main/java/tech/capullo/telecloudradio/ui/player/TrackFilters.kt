package tech.capullo.telecloudradio.ui.player

import org.json.JSONArray
import org.json.JSONObject
import tech.capullo.telecloudradio.data.db.MediaMessageEntity
import tech.capullo.telecloudradio.util.PerfTrace
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal val telegramUsernameRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]{4,31}$")

internal fun extractUploader(caption: String?): String? {
    if (caption.isNullOrBlank()) return null
    return caption.trim().split(Regex("\\s+"))
        .lastOrNull { word ->
            val stripped = word.trimEnd('.', ',', '!', '?')
            stripped.startsWith("@") && stripped.length > 1 &&
                telegramUsernameRegex.matches(stripped.substring(1))
        }
        ?.trimEnd('.', ',', '!', '?')
}

internal fun extractUploaderFromFilename(fileName: String?): String? {
    if (fileName.isNullOrBlank()) return null
    val parts = fileName.split(".")
    if (parts.size < 3) return null
    val candidate = parts[parts.size - 2]
    return if (telegramUsernameRegex.matches(candidate)) "@$candidate" else null
}

// Same chain as the now-playing label, plus Telegram sender as a last resort
internal fun uploaderKey(track: MediaMessageEntity): String? = PerfTrace.section("Filters.uploaderKey") {
    extractUploader(track.caption)
        ?: extractUploaderFromFilename(track.fileName)
        ?: track.senderUsername?.let { "@$it" }
        ?: track.senderId?.let { "user $it" }
}

internal fun extensionKey(track: MediaMessageEntity): String? = track.fileName?.substringAfterLast('.', "")?.lowercase()
    ?.takeIf { it.isNotBlank() && it.length <= 5 && it.none(Char::isWhitespace) }
    ?: track.mimeType?.substringAfterLast('/')?.lowercase()

// SimpleDateFormat is not thread-safe, so keep one per thread via ThreadLocal rather than a
// plain shared val (matches() stays on main today, but this keeps monthKey safe anywhere).
private val monthFormat = ThreadLocal.withInitial { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }

internal fun monthKey(dateStr: String?): String? = PerfTrace.section("Filters.monthKey") {
    if (dateStr.isNullOrBlank()) return@section null
    try {
        val epochSeconds = dateStr.toLong()
        monthFormat.get().format(Date(epochSeconds * 1000))
    } catch (_: Exception) {
        null
    }
}

// Relative date presets shown at the top of the Date filter dropdown
internal const val DATE_TODAY = "Today"
internal const val DATE_YESTERDAY = "Yesterday"
internal const val DATE_THIS_WEEK = "This week"
internal const val DATE_LAST_TWO_WEEKS = "Last 2 weeks"
internal val datePresets = listOf(DATE_TODAY, DATE_YESTERDAY, DATE_THIS_WEEK, DATE_LAST_TWO_WEEKS)

private const val DAY_SECONDS = 86_400L

private fun startOfTodayEpoch(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

// key is either a relative preset or a "MMM yyyy" month bucket; todayEpoch is start-of-today
// in epoch seconds, computed once per QueueFilters.matches call and passed down.
internal fun matchesDateKey(dateStr: String?, key: String, todayEpoch: Long): Boolean {
    val epoch = dateStr?.toLongOrNull() ?: return false
    return when (key) {
        DATE_TODAY -> epoch >= todayEpoch
        DATE_YESTERDAY -> epoch >= todayEpoch - DAY_SECONDS && epoch < todayEpoch
        DATE_THIS_WEEK -> epoch >= todayEpoch - 6 * DAY_SECONDS // rolling 7 days
        DATE_LAST_TWO_WEEKS -> epoch >= todayEpoch - 13 * DAY_SECONDS
        else -> monthKey(dateStr) == key
    }
}

// Active queue filters; empty set / blank search = dimension not filtered
data class QueueFilters(
    val uploaders: Set<String> = emptySet(),
    val months: Set<String> = emptySet(),
    val extensions: Set<String> = emptySet(),
    val search: String = "",
) {
    val isActive: Boolean
        get() = uploaders.isNotEmpty() || months.isNotEmpty() ||
            extensions.isNotEmpty() || search.isNotBlank()

    fun matches(track: MediaMessageEntity): Boolean = PerfTrace.section("Filters.matches") {
        val q = search.trim().lowercase()
        // Computed once per matches() call instead of per track per month key
        val todayEpoch = if (months.isEmpty()) 0L else startOfTodayEpoch()
        (uploaders.isEmpty() || (uploaderKey(track) ?: "") in uploaders) &&
            (months.isEmpty() || months.any { matchesDateKey(track.date, it, todayEpoch) }) &&
            (extensions.isEmpty() || (extensionKey(track) ?: "") in extensions) &&
            (
                q.isEmpty() ||
                    track.title?.lowercase()?.contains(q) == true ||
                    track.performer?.lowercase()?.contains(q) == true ||
                    track.fileName?.lowercase()?.contains(q) == true
                )
    }

    fun toJson(): String = JSONObject().apply {
        put("uploaders", JSONArray(uploaders.toList()))
        put("months", JSONArray(months.toList()))
        put("extensions", JSONArray(extensions.toList()))
        put("search", search)
    }.toString()

    companion object {
        fun fromJson(json: String?): QueueFilters {
            if (json.isNullOrBlank()) return QueueFilters()
            return runCatching {
                val o = JSONObject(json)
                fun set(name: String): Set<String> {
                    val a = o.optJSONArray(name) ?: return emptySet()
                    return (0 until a.length()).map(a::getString).toSet()
                }
                QueueFilters(
                    set("uploaders"),
                    set("months"),
                    set("extensions"),
                    o.optString("search"),
                )
            }.getOrDefault(QueueFilters())
        }
    }
}
