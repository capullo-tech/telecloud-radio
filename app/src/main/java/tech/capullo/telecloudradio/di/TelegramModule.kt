package tech.capullo.telecloudradio.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tech.capullo.telecloudradio.data.telegram.TdLibTelegramClient
import tech.capullo.telecloudradio.data.telegram.TelegramClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TelegramModule {
    @Binds
    @Singleton
    abstract fun bindTelegramClient(impl: TdLibTelegramClient): TelegramClient
}
