package tech.capullo.telecloudradio.util

import tech.capullo.source.telegram.data.telegram.TelegramException

// The shipped announcement text; also what a blank user template falls back to. Stored in the
// pref only when the user actually edits - the pref itself stays raw, so changing this constant
// still reaches users who never touched the editor.
const val DEFAULT_ANNOUNCEMENT_TEMPLATE = "🎙️ {{station}} is live!\n🎧 Listen from anywhere: {{url}}"

// Stand-in link shown in the Settings preview and the "Send test message" post - shaped like a
// real quick-tunnel URL so the message renders exactly as a live one would.
const val ANNOUNCEMENT_SAMPLE_URL = "https://sample-words-go-here.trycloudflare.com"

/**
 * Expands [template] into the message posted when a broadcast's public link comes up.
 *
 * Two tokens, both literal and case-sensitive: `{{station}}` ([station], falling back to
 * "Telecloud Radio" when blank - the pre-template behavior) and `{{url}}` ([url]). Both
 * are substituted in a single pass, so substituted values are never re-scanned: a chat
 * title containing `{{url}}` (or any `{{...}}`) lands in the message verbatim. Any other
 * `{{...}}` sequence is a typo the user will want to see, so it survives verbatim. When
 * the template never mentions `{{url}}`, the link is appended as `\n` + the bare URL on
 * its own line: an announcement without the link is useless, and the editor warns about
 * the append. A blank (empty or whitespace-only) template means "use the default".
 */

// Single token pattern - both tokens in ONE pass so substituted values are inert: a chat
// title containing "{{url}}" (or any {{...}}) is never re-scanned by a later replacement.
private val TOKEN = Regex("""\{\{(station|url)\}\}""")

fun renderAnnouncement(template: String, station: String, url: String): String {
    val resolved = template.ifBlank { DEFAULT_ANNOUNCEMENT_TEMPLATE }
    val hasUrlToken = "{{url}}" in resolved
    val rendered = TOKEN.replace(resolved) { m ->
        if (m.groupValues[1] == "station") station.ifBlank { "Telecloud Radio" } else url
    }
    return if (hasUrlToken) rendered else "$rendered\n$url"
}

/**
 * One message for a failed Telegram post, shared by the live announcement
 * (PlaybackService) and Settings' "Send test message" so the wording can't drift.
 * [what] names the payload: "the public link" / "the test message".
 */
fun postFailureMessage(error: Throwable, channel: String, what: String): String = if (error is TelegramException &&
    error.message.contains("administrator rights", ignoreCase = true)
) {
    "No permission to post in $channel - make the app account a channel admin"
} else {
    "Couldn't post $what to $channel: ${error.message ?: "unknown error"}"
}
