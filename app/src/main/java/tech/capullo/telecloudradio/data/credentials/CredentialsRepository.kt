package tech.capullo.telecloudradio.data.credentials

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialsRepository @Inject constructor(private val prefs: SharedPreferences) {

    val apiId: Int
        get() = prefs.getInt("telegram_api_id", 0)

    val apiHash: String
        get() = prefs.getString("telegram_api_hash", "") ?: ""

    fun hasCredentials() = apiId != 0 && apiHash.isNotEmpty()

    fun save(apiId: Int, apiHash: String) {
        prefs.edit()
            .putInt("telegram_api_id", apiId)
            .putString("telegram_api_hash", apiHash)
            .apply()
    }
}
