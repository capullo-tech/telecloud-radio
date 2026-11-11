package tech.capullo.telecloudradio.data.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Fixture: the credentials card of a real logged-in my.telegram.org/apps page (2026-11 redesign),
// with the api_id, api_hash and form hash replaced by fake values.
class MyTelegramOrgParserTest {

    private val appsPage = javaClass.classLoader!!
        .getResource("my-telegram-org/apps.html")!!
        .readText()

    @Test
    fun `reads api_id from the copy button and api_hash from the masked spoiler value`() {
        assertEquals(
            ApiCredentials(1234567, "0123456789abcdef0123456789abcdef"),
            MyTelegramOrgParser.parseCredentials(appsPage),
        )
    }

    @Test
    fun `intro text mentioning api_id and api_hash is not taken for credentials`() {
        val introOnly = appsPage.substringBefore("App api_id")
        assertNull(MyTelegramOrgParser.parseCredentials(introOnly))
    }

    @Test
    fun `page with an existing app has no create form`() {
        assertNull(MyTelegramOrgParser.parseCreateForm(appsPage))
    }
}
