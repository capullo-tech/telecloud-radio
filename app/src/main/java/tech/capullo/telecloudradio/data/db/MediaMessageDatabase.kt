package tech.capullo.telecloudradio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaMessageEntity::class, AudioAnalysisEntity::class, TrackGainEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class MediaMessageDatabase : RoomDatabase() {
    abstract fun mediaMessageDao(): MediaMessageDao
    abstract fun audioAnalysisDao(): AudioAnalysisDao
    abstract fun trackGainDao(): TrackGainDao

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
                database.execSQL(
                    "ALTER TABLE audio_analysis ADD COLUMN lufs REAL NOT NULL DEFAULT -999",
                )
                database.execSQL(
                    "ALTER TABLE audio_analysis ADD COLUMN truePeakDb REAL NOT NULL DEFAULT -999",
                )
                database.execSQL(
                    "ALTER TABLE audio_analysis ADD COLUMN clipping INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE audio_analysis ADD COLUMN totalSamples INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL("ALTER TABLE audio_analysis ADD COLUMN channelStatsCsv TEXT")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS track_gain (
                        messageId INTEGER PRIMARY KEY NOT NULL,
                        gainLinear REAL NOT NULL,
                        measuredLufs REAL NOT NULL,
                        source TEXT NOT NULL,
                        computedAt INTEGER NOT NULL
                    )""",
                )
            }
        }

        // Tracks are keyed by (chatId, messageId): TDLib message ids repeat across chats, so a
        // messageId-only key dropped colliding tracks of a second station and let per-track data
        // leak between stations. SQLite can't alter a primary key, so each table is recreated and
        // copied. Up to v6 messageId was unique in media_messages, so the join that fills chatId
        // into audio_analysis/track_gain is unambiguous; rows without a track are dropped.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS media_messages_new (
                        messageId INTEGER NOT NULL,
                        chatId INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        senderId INTEGER,
                        senderUsername TEXT,
                        caption TEXT,
                        fileName TEXT,
                        fileUniqueId TEXT,
                        fileId INTEGER NOT NULL,
                        duration INTEGER,
                        performer TEXT,
                        title TEXT,
                        fileSize INTEGER,
                        mimeType TEXT,
                        station TEXT,
                        groupType TEXT,
                        reactions TEXT,
                        localPath TEXT,
                        PRIMARY KEY(chatId, messageId)
                    )""",
                )
                database.execSQL(
                    """INSERT INTO media_messages_new (
                        messageId, chatId, date, senderId, senderUsername, caption, fileName,
                        fileUniqueId, fileId, duration, performer, title, fileSize, mimeType,
                        station, groupType, reactions, localPath
                    )
                    SELECT
                        messageId, chatId, date, senderId, senderUsername, caption, fileName,
                        fileUniqueId, fileId, duration, performer, title, fileSize, mimeType,
                        station, groupType, reactions, localPath
                    FROM media_messages""",
                )
                database.execSQL("DROP TABLE media_messages")
                database.execSQL("ALTER TABLE media_messages_new RENAME TO media_messages")

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS audio_analysis_new (
                        chatId INTEGER NOT NULL,
                        messageId INTEGER NOT NULL,
                        peakDb REAL NOT NULL,
                        rmsDb REAL NOT NULL,
                        dynamicRange REAL NOT NULL,
                        spectralCutoffHz INTEGER NOT NULL,
                        nyquistHz INTEGER NOT NULL,
                        likelyTrueLossless INTEGER NOT NULL,
                        spectrumCsv TEXT NOT NULL,
                        spectrogramPath TEXT,
                        analyzedAt INTEGER NOT NULL,
                        lufs REAL NOT NULL,
                        truePeakDb REAL NOT NULL,
                        clipping INTEGER NOT NULL,
                        totalSamples INTEGER NOT NULL,
                        channelStatsCsv TEXT,
                        PRIMARY KEY(chatId, messageId)
                    )""",
                )
                database.execSQL(
                    """INSERT INTO audio_analysis_new (
                        chatId, messageId, peakDb, rmsDb, dynamicRange, spectralCutoffHz,
                        nyquistHz, likelyTrueLossless, spectrumCsv, spectrogramPath, analyzedAt,
                        lufs, truePeakDb, clipping, totalSamples, channelStatsCsv
                    )
                    SELECT
                        m.chatId, a.messageId, a.peakDb, a.rmsDb, a.dynamicRange,
                        a.spectralCutoffHz, a.nyquistHz, a.likelyTrueLossless, a.spectrumCsv,
                        a.spectrogramPath, a.analyzedAt, a.lufs, a.truePeakDb, a.clipping,
                        a.totalSamples, a.channelStatsCsv
                    FROM audio_analysis a
                    INNER JOIN media_messages m ON m.messageId = a.messageId""",
                )
                database.execSQL("DROP TABLE audio_analysis")
                database.execSQL("ALTER TABLE audio_analysis_new RENAME TO audio_analysis")

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS track_gain_new (
                        chatId INTEGER NOT NULL,
                        messageId INTEGER NOT NULL,
                        gainLinear REAL NOT NULL,
                        measuredLufs REAL NOT NULL,
                        source TEXT NOT NULL,
                        computedAt INTEGER NOT NULL,
                        PRIMARY KEY(chatId, messageId)
                    )""",
                )
                database.execSQL(
                    """INSERT INTO track_gain_new (
                        chatId, messageId, gainLinear, measuredLufs, source, computedAt
                    )
                    SELECT m.chatId, g.messageId, g.gainLinear, g.measuredLufs, g.source, g.computedAt
                    FROM track_gain g
                    INNER JOIN media_messages m ON m.messageId = g.messageId""",
                )
                database.execSQL("DROP TABLE track_gain")
                database.execSQL("ALTER TABLE track_gain_new RENAME TO track_gain")
            }
        }
    }
}
