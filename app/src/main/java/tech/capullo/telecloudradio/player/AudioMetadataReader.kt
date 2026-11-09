package tech.capullo.telecloudradio.player

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ReplayGain(val gainDb: Float, val peak: Float?)

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
     * REPLAYGAIN_TRACK_GAIN (dB) and, when present, REPLAYGAIN_TRACK_PEAK - the gain a tagger already
     * computed, so normalization can skip decoding the whole file. Null when absent or unparseable,
     * which is common for Telegram uploads; AudioAnalyzer.measureLoudness is the fallback. See
     * [replayGainFrom] for how each tag format stores the keys.
     */
    fun readReplayGain(path: String): ReplayGain? = runCatching {
        AudioFileIO.read(File(path)).tag?.let(::replayGainFrom)
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

/**
 * REPLAYGAIN_TRACK_GAIN / _PEAK from whatever tag format the file uses. Tag.getFirst(String) only
 * finds these in Vorbis comments (FLAC, Ogg, Opus): in ID3v2 the string is matched against the
 * frame id, so the TXXX frame whose DESCRIPTION is the key is never found, and in MP4 the key is
 * the descriptor of a reverse-DNS ("----") atom. Walk every field and match by the key each format
 * actually stores, case-insensitively (taggers write both cases).
 */
internal fun replayGainFrom(tag: Tag): ReplayGain? {
    var gain: Float? = null
    var peak: Float? = null
    for (field in tag.fields) {
        val (key, value) = when (field) {
            is AbstractID3v2Frame -> (field.body as? FrameBodyTXXX)?.let { it.description to it.firstTextValue } ?: continue
            is Mp4TagReverseDnsField -> field.descriptor to field.content
            is TagTextField -> field.id to field.content
            else -> continue
        }
        when (key?.uppercase()) {
            "REPLAYGAIN_TRACK_GAIN" -> if (gain == null) gain = value?.let(::parseReplayGainNumber)
            "REPLAYGAIN_TRACK_PEAK" -> if (peak == null) peak = value?.let(::parseReplayGainNumber)
        }
    }
    // Peak is linear full scale ("0.987654"); optional, and only used to cap a boost.
    return gain?.let { ReplayGain(it, peak) }
}

// Values vary by tagger: "-6.5 dB", "-6.5dB", "-6.5".
private fun parseReplayGainNumber(raw: String): Float? = raw.trim().removeSuffix("dB").removeSuffix("db").removeSuffix("DB").trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
