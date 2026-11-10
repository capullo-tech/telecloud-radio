package tech.capullo.telecloudradio.data.credentials

// Reads the my.telegram.org/apps page. Anchored on the app_id / app_hash field names and validated by
// value shape (numeric id, 32-hex hash) instead of exact markup, so cosmetic redesigns don't break it
// and a wrong match can't slip through as plausible credentials.
object MyTelegramOrgParser {

    private const val WINDOW = 3000

    // Field labels: "App api_id" / "App api_hash" text on the current site, for="app_id" /
    // for="app_hash" on the pre-2026 one. Not bare "api_id": the page intro text mentions it too.
    private val ID_ANCHOR = Regex("""(?i)(?:\bapp\s+api_|for="app_)id\b""")
    private val HASH_ANCHOR = Regex("""(?i)(?:\bapp\s+api_|for="app_)hash\b""")
    private val ID_ATTR = Regex("""data-(?:copy|value)="(\d{3,12})"""")
    private val ID_TEXT = Regex(""">\s*(\d{3,12})\s*<""")
    private val HASH_VALUE = Regex("""(?<![0-9a-fA-F])([0-9a-f]{32})(?![0-9a-fA-F])""")
    private val CREATE_FORM = Regex("""<form\b[^>]*\bid="app_create_form"[^>]*>(.*?)</form>""", RegexOption.DOT_MATCHES_ALL)
    private val INPUT = Regex("""<input\b[^>]*>""")
    private val NAME = Regex("""\bname="([^"]*)"""")
    private val VALUE = Regex("""\bvalue="([^"]*)"""")

    fun parseCredentials(html: String): ApiCredentials? {
        val id = ID_ANCHOR.findAll(html).firstNotNullOfOrNull { anchor ->
            val window = html.windowAfter(anchor.range.first)
            (ID_ATTR.find(window) ?: ID_TEXT.find(window))?.groupValues?.get(1)?.toIntOrNull()
        } ?: return null
        val hash = HASH_ANCHOR.findAll(html).firstNotNullOfOrNull { anchor ->
            HASH_VALUE.find(html.windowAfter(anchor.range.first))?.groupValues?.get(1)
        } ?: return null
        return ApiCredentials(id, hash)
    }

    // Hidden inputs of the "create app" form (its CSRF-style hash etc.), or null when the page has no
    // such form. The user-facing fields are filled in by the caller.
    fun parseCreateForm(html: String): List<Pair<String, String>>? {
        val form = CREATE_FORM.find(html)?.groupValues?.get(1) ?: return null
        return INPUT.findAll(form)
            .map { it.value }
            .filter { """type="hidden"""" in it }
            .mapNotNull { tag ->
                val name = NAME.find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                name to (VALUE.find(tag)?.groupValues?.get(1)?.unescapeHtml() ?: "")
            }
            .toList()
    }

    private fun String.windowAfter(start: Int) = substring(start, minOf(length, start + WINDOW))

    private fun String.unescapeHtml() = replace("&quot;", "\"").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&")
}
