package tech.capullo.telecloudradio.data.credentials

import android.content.SharedPreferences
import android.util.Base64
import tech.capullo.source.telegram.data.telegram.TelegramCredentials
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialsRepository @Inject constructor(
    private val prefs: SharedPreferences,
) : TelegramCredentials {

    override val apiId: Int
        get() = prefs.getInt("telegram_api_id", 0)

    override val apiHash: String
        get() = prefs.getString("telegram_api_hash", "") ?: ""

    // 32-byte key for TDLib's local database (login session + cached chats), so it is encrypted at
    // rest. Generated once and persisted in the (already EncryptedSharedPreferences-backed) prefs;
    // transparent to the user, not a password. Losing it means the local DB can't be opened -> the
    // user re-authenticates.
    override val databaseEncryptionKey: ByteArray
        get() {
            prefs.getString(DB_KEY_PREF, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            return TelegramCredentials.newDatabaseEncryptionKey().also { key ->
                prefs.edit().putString(DB_KEY_PREF, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
            }
        }

    override fun hasCredentials() = apiId != 0 && apiHash.isNotEmpty()

    fun save(apiId: Int, apiHash: String) {
        prefs.edit()
            .putInt("telegram_api_id", apiId)
            .putString("telegram_api_hash", apiHash)
            .apply()
    }

    companion object {
        private const val DB_KEY_PREF = "telegram_db_encryption_key"
    }
}
