package tech.capullo.telecloudradio.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tech.capullo.telecloudradio.data.db.AudioAnalysisDao
import tech.capullo.telecloudradio.data.db.MediaMessageDao
import tech.capullo.telecloudradio.data.db.MediaMessageDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MediaMessageDatabase =
        Room.databaseBuilder(context, MediaMessageDatabase::class.java, "telecloud_radio.db")
            .addMigrations(
                MediaMessageDatabase.MIGRATION_1_2,
                MediaMessageDatabase.MIGRATION_2_3,
                MediaMessageDatabase.MIGRATION_3_4,
                MediaMessageDatabase.MIGRATION_4_5,
            )
            // Wipes and recreates DB on downgrade (dev-time safety net for intermediate builds)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideMediaMessageDao(db: MediaMessageDatabase): MediaMessageDao = db.mediaMessageDao()

    @Provides
    fun provideAudioAnalysisDao(db: MediaMessageDatabase): AudioAnalysisDao = db.audioAnalysisDao()
}
