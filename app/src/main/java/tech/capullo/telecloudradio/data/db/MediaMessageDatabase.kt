package tech.capullo.telecloudradio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaMessageEntity::class, AudioAnalysisEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class MediaMessageDatabase : RoomDatabase() {
    abstract fun mediaMessageDao(): MediaMessageDao
    abstract fun audioAnalysisDao(): AudioAnalysisDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE media_messages ADD COLUMN localPath TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS audio_analysis (
                        messageId INTEGER PRIMARY KEY NOT NULL,
                        peakDb REAL NOT NULL,
                        rmsDb REAL NOT NULL,
                        dynamicRange REAL NOT NULL,
                        spectralCutoffHz INTEGER NOT NULL,
                        nyquistHz INTEGER NOT NULL,
                        likelyTrueLossless INTEGER NOT NULL,
                        spectrumCsv TEXT NOT NULL,
                        analyzedAt INTEGER NOT NULL
                    )""",
                )
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE audio_analysis ADD COLUMN spectrogramPath TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE audio_analysis ADD COLUMN lufs REAL NOT NULL DEFAULT -999")
                database.execSQL("ALTER TABLE audio_analysis ADD COLUMN truePeakDb REAL NOT NULL DEFAULT -999")
                database.execSQL("ALTER TABLE audio_analysis ADD COLUMN clipping INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE audio_analysis ADD COLUMN totalSamples INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE audio_analysis ADD COLUMN channelStatsCsv TEXT")
            }
        }
    }
}
