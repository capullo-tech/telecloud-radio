package tech.capullo.telecloudradio.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tech.capullo.source.telegram.data.telegram.TdLibTelegramClient
import tech.capullo.source.telegram.data.telegram.TelegramClient
import tech.capullo.telecloudradio.data.credentials.CredentialsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelegramModule {
    // The source library's TdLibTelegramClient is DI-free (no @Inject), so provide it explicitly.
    // CredentialsRepository is-a TelegramCredentials (apiId/apiHash + the at-rest DB encryption key).
    @Provides
    @Singleton
    fun provideTelegramClient(
        @ApplicationContext context: Context,
        credentials: CredentialsRepository,
    ): TelegramClient = TdLibTelegramClient(context, credentials)
}
