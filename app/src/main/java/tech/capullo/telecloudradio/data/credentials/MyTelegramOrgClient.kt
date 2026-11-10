package tech.capullo.telecloudradio.data.credentials

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import javax.inject.Inject

class MyTelegramOrgException(message: String) : Exception(message)

data class ApiCredentials(val apiId: Int, val apiHash: String)

// Signs in to my.telegram.org with the user's phone + the code Telegram sends in-app, then reads (or
// creates) the account's API app so the user never copies the API ID/Hash by hand. my.telegram.org has
// no public API: this follows what its own page script (js/my-telegram.js) does, so a site redesign can
// break it - every failure surfaces as MyTelegramOrgException and the UI falls back to manual entry.
//
// One instance per sign-in attempt: the session cookie (stel_token) lives only in this client's
// in-memory cookie jar and dies with it. Owned and closed by AuthViewModel.
class MyTelegramOrgClient @Inject constructor() : Closeable {

    private val http = HttpClient(OkHttp) {
        install(HttpCookies)
        expectSuccess = false
    }

    // Asks Telegram to send a login code to the account's Telegram app. Returns the random_hash that
    // must accompany that code in [login].
    suspend fun sendCode(phone: String): String {
        val response = http.submitForm(
            "$BASE/auth/send_password",
            formParameters = parameters { append("phone", phone) },
        )
        val body = response.bodyAsText()
        val json = parseOrThrow(response, body) as? JsonObject
        return json?.get("random_hash")?.jsonPrimitive?.contentOrNull
            ?: throw MyTelegramOrgException(errorText(response, body))
    }

    suspend fun login(phone: String, randomHash: String, code: String) {
        val response = http.submitForm(
            "$BASE/auth/login",
            formParameters = parameters {
                append("phone", phone)
                append("random_hash", randomHash)
                append("password", code)
                append("remember", "0")
            },
        )
        // Success is the JSON literal `true`; anything else is the rejection text.
        val body = response.bodyAsText()
        val ok = (parseOrThrow(response, body) as? JsonPrimitive)?.booleanOrNull == true
        if (!ok) throw MyTelegramOrgException(errorText(response, body))
    }

    // Reads the API ID/Hash from /apps, creating the app first if the account has none.
    suspend fun fetchCredentials(): ApiCredentials {
        val page = getAppsPage()
        MyTelegramOrgParser.parseCredentials(page)?.let { return it }
        val createFields = MyTelegramOrgParser.parseCreateForm(page)
            ?: throw MyTelegramOrgException("Couldn't read your API credentials from my.telegram.org")
        val response = http.submitForm(
            "$BASE/apps/create",
            formParameters = parameters {
                createFields.forEach { (name, value) -> append(name, value) }
                append("app_title", APP_TITLE)
                append("app_shortname", APP_SHORTNAME)
                append("app_url", "")
                append("app_platform", APP_PLATFORM)
                append("app_desc", "")
            },
        )
        parseOrThrow(response, response.bodyAsText())
        return MyTelegramOrgParser.parseCredentials(getAppsPage())
            ?: throw MyTelegramOrgException(
                "my.telegram.org didn't create the API app. Create it at my.telegram.org/apps " +
                    "and enter the credentials manually.",
            )
    }

    override fun close() = http.close()

    private suspend fun getAppsPage(): String {
        val response = http.get("$BASE/apps")
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw MyTelegramOrgException(errorText(response, body))
        return body
    }

    // Mirrors the site's postForm(): a non-2xx reply is an error whose message is the JSON "text"
    // field or the raw body; a 2xx reply carries JSON (or nothing meaningful). Older deployments
    // answered errors as 200 + plain text, which callers catch by finding no expected JSON value.
    private fun parseOrThrow(response: HttpResponse, body: String): JsonElement? {
        val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        if (!response.status.isSuccess()) {
            val text = ((json as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
            throw MyTelegramOrgException(text ?: errorText(response, body))
        }
        return json
    }

    private fun errorText(response: HttpResponse, body: String): String {
        val text = body.trim()
        return when {
            text.isNotEmpty() && text.length < 200 && !text.startsWith("<") -> text
            else -> "my.telegram.org request failed (HTTP ${response.status.value})"
        }
    }

    private companion object {
        const val BASE = "https://my.telegram.org"
        const val APP_TITLE = "Telecloud Radio"
        const val APP_SHORTNAME = "telecloudradio"
        const val APP_PLATFORM = "android"
    }
}
