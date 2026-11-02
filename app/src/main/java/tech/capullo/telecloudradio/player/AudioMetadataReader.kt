package tech.capullo.telecloudradio.player

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AudioMetadata(
    val codec: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    // ID3/tag fields - override DB values if present
    val tagTitle: String?,
    val tagArtist: String?,
)

@Singleton
class AudioMetadataReader @Inject constructor() {

    init {
        runCatching {
            java.util.logging.Logger.getLogger("org.jaudiotagger").level =
                java.util.logging.Level.OFF
        }
    }

    fun read(path: String): AudioMetadata {
        val file = File(path)

        // Primary source: read actual bitstream headers (works regardless of filename/extension)
        val extracted = extractFromBitstream(path)
        val codec = extracted.codec ?: extensionToCodec(file.extension)
        var bitrateKbps: Int? = extracted.bitrateKbps
        var sampleRateHz: Int? = extracted.sampleRateHz
        var channels: Int? = extracted.channels
        var bitDepth: Int? = null
        var tagTitle: String? = null
        var tagArtist: String? = null

        // MediaMetadataRetriever - more accurate bitrate reading
        runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(path)
                val mmrBitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()?.div(1000)?.toInt()
                if (mmrBitrate != null && mmrBitrate > 0) bitrateKbps = mmrBitrate
                tagTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }
                tagArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }
            }
        }

        // JAudioTagger - most accurate for lossless (FLAC, AIFF); overrides bitstream values
        runCatching {
            val audioFile = AudioFileIO.read(file)
            val header = audioFile.audioHeader
            header.sampleRateAsNumber.takeIf { it > 0 }?.let { sampleRateHz = it }
            header.bitsPerSample.takeIf { it > 0 }?.let { bitDepth = it }
            parseChannels(header.channels)?.let { channels = it }
            header.bitRateAsNumber.toInt().takeIf { it > 0 }?.let {
                if (bitrateKbps == null) bitrateKbps = it
            }
            audioFile.tag?.let { tag ->
                tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)?.takeIf { it.isNotBlank() }?.let {
                    tagTitle =
                        it
                }
                tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)?.takeIf {
                    it.isNotBlank()
                }?.let {
                    tagArtist =
                        it
                }
            }
        }

        return AudioMetadata(
            codec,
            bitrateKbps,
            sampleRateHz,
            bitDepth,
            channels,
            tagTitle,
            tagArtist,
        )
    }

    /**
     * REPLAYGAIN_TRACK_GAIN tag (Vorbis comment / ID3 TXXX / MP4 freeform), in dB - the gain a
     * tagger already computed, so volume normalization can use it directly instead of decoding
     * and analyzing the whole file. Null when absent or unparseable, which is common for
     * Telegram-sourced uploads (not curated rips) - AudioAnalyzer.computeIntegratedLufs is the
     * fallback for those. jaudiotagger 3.0.1 has no FieldKey.REPLAYGAIN_TRACK_GAIN (its FieldKey
     * enum predates wide ReplayGain support), so this reads the raw custom-field key directly -
     * the same lookup Tag.getFirst(FieldKey) does internally for mapped fields, just for an
     * unmapped one; works uniformly across Vorbis comment (FLAC/Opus/OGG), ID3 TXXX (MP3), and
     * MP4 freeform atoms. Format of the value varies by tagger ("-6.5 dB", "-6.5dB", "-6.5") -
     * strip any trailing unit before parsing.
     */
    fun readReplayGainDb(path: String): Float? = runCatching {
        val audioFile = AudioFileIO.read(File(path))
        val raw = audioFile.tag?.getFirst("REPLAYGAIN_TRACK_GAIN")?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        raw.trim().removeSuffix("dB").removeSuffix("db").removeSuffix("DB").trim().toFloatOrNull()
    }.getOrNull()

    private data class ExtractorResult(
        val codec: String?,
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitrateKbps: Int?,
    )

    private fun extractFromBitstream(path: String): ExtractorResult {
        runCatching {
            val extractor = MediaExtractor()
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val sr = runCatching {
                        fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }.getOrNull()?.takeIf {
                        it >
                            0
                    }
                    val ch = runCatching {
                        fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }.getOrNull()?.takeIf {
                        it >
                            0
                    }
                    val br = runCatching { fmt.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
                        ?.let { it / 1000 }?.takeIf { it > 0 }
                    extractor.release()
                    // audio/raw means the demuxer decoded to PCM; codec identity
                    // is unknown from MIME alone - fall back to file extension
                    val codec = if (mime == "audio/raw") null else mimeToCodec(mime)
                    return ExtractorResult(codec, sr, ch, br)
                }
            }
            extractor.release()
        }
        // Fallback to extension only if MediaExtractor can't parse the file
        return ExtractorResult(extensionToCodec(File(path).extension), null, null, null)
    }

    private fun mimeToCodec(mime: String): String = when {
        mime.contains("flac") -> "FLAC"
        mime.contains("mpeg") -> "MP3"
        mime.contains("aac") || mime.contains("mp4a") -> "AAC"
        mime.contains("vorbis") -> "Vorbis"
        mime.contains("opus") -> "Opus"
        mime.contains("wav") || mime.contains("lpcm") || mime.contains("raw") -> "PCM"
        mime.contains("aiff") -> "AIFF"
        else -> mime.substringAfterLast("/").uppercase()
    }

    private fun parseChannels(s: String): Int? {
        s.trim().toIntOrNull()?.let { return it }
        return when {
            s.lowercase().contains("mono") -> 1
            s.lowercase().contains("stereo") -> 2
            else -> null
        }
    }

    private fun extensionToCodec(ext: String): String? = when (ext.lowercase()) {
        "flac" -> "FLAC"
        "mp3" -> "MP3"
        "m4a", "aac" -> "AAC"
        "ogg" -> "Vorbis"
        "opus" -> "Opus"
        "wav", "wave" -> "PCM"
        "aiff", "aif" -> "AIFF"
        else -> ext.uppercase().takeIf { ext.isNotBlank() }
    }
}
