package tech.capullo.telecloudradio.data.db

import androidx.room.Entity

@Entity(tableName = "audio_analysis", primaryKeys = ["chatId", "messageId"])
data class AudioAnalysisEntity(
    val chatId: Long,
    val messageId: Long,
    val peakDb: Float,
    val rmsDb: Float,
    val dynamicRange: Float,
    val spectralCutoffHz: Int,
    val nyquistHz: Int,
    val likelyTrueLossless: Boolean,
    val spectrumCsv: String,
    val spectrogramPath: String?,
    val analyzedAt: Long,
    val lufs: Float = -999f,
    val truePeakDb: Float = -999f,
    val clipping: Boolean = false,
    val totalSamples: Long = 0L,
    val channelStatsCsv: String? = null, // "p1,r1,dr1;p2,r2,dr2" per channel
)
