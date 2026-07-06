package tech.capullo.telecloudradio.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_analysis")
data class AudioAnalysisEntity(
    @PrimaryKey val messageId: Long,
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
