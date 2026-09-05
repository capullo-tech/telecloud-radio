package tech.capullo.telecloudradio.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementTemplateTest {

    @Test
    fun blankTemplateRendersDefaultByteIdenticalToOldHardcodedMessage() {
        // Regression guard: with no user template the posted text must be byte-identical to the
        // pre-template hardcoded string in PlaybackService.
        assertEquals(
            "🎙️ S is live!\n🎧 Listen from anywhere: u",
            renderAnnouncement("", "S", "u"),
        )
    }

    @Test
    fun blankStationFallsBackToTelecloudRadio() {
        assertEquals(
            "Telecloud Radio is live!\n🎧 Listen from anywhere: u",
            renderAnnouncement("{{station}} is live!\n🎧 Listen from anywhere: {{url}}", "", "u"),
        )
    }

    @Test
    fun whitespaceOnlyTemplateRendersDefault() {
        assertEquals(
            "🎙️ S is live!\n🎧 Listen from anywhere: u",
            renderAnnouncement(" \n\t ", "S", "u"),
        )
    }

    @Test
    fun missingUrlTokenAppendsUrlOnItsOwnLine() {
        assertEquals("hi S\nu", renderAnnouncement("hi {{station}}", "S", "u"))
    }

    @Test
    fun urlTokenSubstitutesWithoutAppend() {
        assertEquals("at u now", renderAnnouncement("at {{url}} now", "S", "u"))
    }

    @Test
    fun repeatedUrlTokensAllSubstituteWithSingleAppendNeverFiring() {
        assertEquals("u and u", renderAnnouncement("{{url}} and {{url}}", "S", "u"))
    }

    @Test
    fun unknownTokenSurvivesVerbatimAndAppendStillFires() {
        assertEquals("{{listnres}}\nu", renderAnnouncement("{{listnres}}", "S", "u"))
    }

    @Test
    fun uppercaseUrlIsNotATokenAndAppendFires() {
        assertEquals("{{URL}}\nu", renderAnnouncement("{{URL}}", "S", "u"))
    }
}
