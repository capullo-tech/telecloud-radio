package tech.capullo.telecloudradio.player

import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.id3.valuepair.TextEncoding
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each tag format stores the ReplayGain keys differently; all must be found. */
class ReplayGainTagTest {

    private fun txxx24(desc: String, value: String) = ID3v24Frame("TXXX").apply {
        body = FrameBodyTXXX(TextEncoding.ISO_8859_1, desc, value)
    }

    private fun assertGain(gain: Float, peak: Float?, tag: Tag) {
        val rg = replayGainFrom(tag)!!
        assertEquals(gain, rg.gainDb, 1e-4f)
        if (peak == null) assertNull(rg.peak) else assertEquals(peak, rg.peak!!, 1e-6f)
    }

    @Test
    fun `MP3 ID3v2_4 TXXX frames, matched by description`() {
        val tag = ID3v24Tag().apply {
            addField(txxx24("replaygain_track_gain", "-7.20 dB"))
            addField(txxx24("replaygain_track_peak", "0.988525"))
        }
        assertGain(-7.2f, 0.988525f, tag)
    }

    @Test
    fun `MP3 ID3v2_3 TXXX frame, upper-case description, no peak`() {
        val tag = ID3v23Tag().apply {
            addField(ID3v23Frame("TXXX").apply { body = FrameBodyTXXX(TextEncoding.ISO_8859_1, "REPLAYGAIN_TRACK_GAIN", "+2.5 dB") })
        }
        assertGain(2.5f, null, tag)
    }

    @Test
    fun `MP4 reverse-DNS atoms`() {
        val tag = Mp4Tag().apply {
            addField(Mp4TagReverseDnsField("----:com.apple.iTunes:replaygain_track_gain", "com.apple.iTunes", "replaygain_track_gain", "-3.10 dB"))
            addField(Mp4TagReverseDnsField("----:com.apple.iTunes:replaygain_track_peak", "com.apple.iTunes", "replaygain_track_peak", "0.5"))
        }
        assertGain(-3.1f, 0.5f, tag)
    }

    @Test
    fun `Vorbis comments (FLAC, Ogg, Opus)`() {
        val tag = VorbisCommentTag.createNewTag().apply {
            addField("REPLAYGAIN_TRACK_GAIN", "-1.5dB")
            addField("REPLAYGAIN_TRACK_PEAK", "0.75")
        }
        assertGain(-1.5f, 0.75f, tag)
    }

    @Test
    fun `album gain alone is not a track gain`() {
        val tag = ID3v24Tag().apply { addField(txxx24("replaygain_album_gain", "-5 dB")) }
        assertNull(replayGainFrom(tag))
    }
}
