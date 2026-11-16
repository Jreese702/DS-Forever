package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MAIL ENVELOPE ON HOME (the user, 2026-11-16: "we need to add an icon somewhere to the home menu for when the player has mail"):
 * the calendar document's `mail` (Calendar c15) read as [HomeMail], and the envelope's place beside the Calendar button
 * ([HomeMailButton]). The map module at rest is 608 px wide (HomeCalendarButton's own tests).
 */
class HomeMailTest {
    private fun doc(mail: String) = JSONObject("""{"type": "calendar", "c": {"v": "c15", "mail": $mail}}""")

    @Test fun readsTheDocumentsMail() {
        assertEquals(HomeMail(true, listOf("Brannoc")), HomeMail.parse(doc("""{"on": true, "from": ["Brannoc"]}""")))
        assertEquals(HomeMail(true, listOf("Brannoc", "Auction House", "Thalia")),
            HomeMail.parse(doc("""{"on": true, "from": ["Brannoc", "Auction House", "Thalia", "Fourth"]}""")))   // three at most
        assertEquals(HomeMail(true), HomeMail.parse(doc("""{"on": true}""")))                                         // the game named nobody
        assertEquals(HomeMail(false), HomeMail.parse(doc("""{"on": false}""")))
        assertEquals(HomeMail(false), HomeMail.parse(doc("""{"on": false, "from": ["Stale"]}""")))                   // no names without mail
        assertEquals(HomeMail(true, listOf("Brannoc")), HomeMail.parse(doc("""{"on": true, "from": ["", "  ", "Brannoc"]}""")))
    }

    @Test fun nothingWithoutMail() {
        assertNull(HomeMail.parse(doc("""{"off": true}""")))                                 // the game's rule: no icon in the game, none here
        assertNull(HomeMail.parse(JSONObject("""{"type": "calendar", "c": {"v": "c14"}}""")))  // an older add-on
        assertNull(HomeMail.parse(null))
        // the `c` object itself is read the same
        assertEquals(HomeMail(true, listOf("Brannoc")), HomeMail.parse(JSONObject("""{"mail": {"on": true, "from": ["Brannoc"]}}""")))
    }

    @Test fun theCardsHeadingIsTerse() {
        assertEquals("Unread mail from", HomeMail(true, listOf("Brannoc")).heading)
        assertEquals("Unread mail", HomeMail(true).heading)
    }

    @Test fun aLogLineNeverCarriesAName() {
        val s = HomeMail(true, listOf("Brannoc", "Thalia")).toString()
        assertFalse(s, s.contains("Brannoc") || s.contains("Thalia"))
        assertTrue(s, s.contains("2"))
    }

    @Test fun theEnvelopeStandsLeftOfTheCalendarButton() {
        // the Calendar button (536, 8) - (600, 72); the envelope one button and its gap to the left, the same size
        assertArrayEquals(floatArrayOf(536f, 8f, 600f, 72f), HomeCalendarButton.box(608f, 0f), 0.01f)
        assertArrayEquals(floatArrayOf(466f, 8f, 530f, 72f), HomeMailButton.box(608f, 0f), 0.01f)
        val m = HomeMailButton.box(608f, 0f); val c = HomeCalendarButton.box(608f, 0f)
        assertEquals(HomeMailButton.GAP, c[0] - m[2], 0.01f)
        assertEquals(HomeCalendarButton.SIZE, m[2] - m[0], 0.01f); assertEquals(HomeCalendarButton.SIZE, m[3] - m[1], 0.01f)
        // on the band's strip only: the buff row starts at BTN_TOP (80) and the quest card under the band (72)
        assertTrue(m[3] <= 72f)
        // on screen (the map's left at 624, its top at 234 at rest)
        assertArrayEquals(floatArrayOf(1090f, 242f, 1154f, 306f), HomeMailButton.box(624f + 608f, 234f), 0.01f)
    }

    @Test fun theBandIsOneButtonShorterWhileItShows() {
        assertEquals(HomeCalendarButton.bandRight(608f), HomeMailButton.bandRight(608f, mail = false), 0.01f)
        assertEquals(460f, HomeMailButton.bandRight(608f, mail = true), 0.01f)                     // 6 px before the envelope
        assertEquals(HomeMailButton.box(608f, 0f)[0] - HomeMailButton.GAP, HomeMailButton.bandRight(608f, mail = true), 0.01f)
        // most of the band is still the name's (452 of the 592 px)
        val full = 608f - 8f - 8f
        assertTrue((HomeMailButton.bandRight(608f, true) - 8f) / full > 0.75f)
    }

    @Test fun theCornersFingerBoxIsSplitBetweenTheTwo() {
        assertEquals(533f, HomeMailButton.split(608f), 0.01f)                                     // the middle of the 6 px gap
        assertArrayEquals(floatArrayOf(442f, 0f, 533f, 78f), HomeMailButton.hit(608f), 0.01f)
        assertArrayEquals(floatArrayOf(533f, 0f, 608f, 78f), HomeMailButton.calHit(608f), 0.01f)
        // every point of each button is its own button's, never the other's
        val m = HomeMailButton.box(608f, 0f); val c = HomeCalendarButton.box(608f, 0f)
        for (x in listOf(m[0], m[0] + 32f, m[2])) for (y in listOf(m[1], m[3])) {
            assertTrue(HomeMailButton.inHit(x, y, 608f)); assertFalse(HomeMailButton.inCalHit(x, y, 608f))
        }
        for (x in listOf(c[0], c[0] + 32f, c[2])) for (y in listOf(c[1], c[3])) {
            assertTrue(HomeMailButton.inCalHit(x, y, 608f)); assertFalse(HomeMailButton.inHit(x, y, 608f))
        }
        // the split point is the Calendar's (one owner per pixel)
        assertFalse(HomeMailButton.inHit(533f, 40f, 608f)); assertTrue(HomeMailButton.inCalHit(533f, 40f, 608f))
        // the band's middle is the map's touch; the buff row (80) is the auras'
        assertFalse(HomeMailButton.inHit(400f, 40f, 608f))
        assertFalse(HomeMailButton.inHit(500f, 82f, 608f)); assertFalse(HomeMailButton.inCalHit(570f, 82f, 608f))
    }

    @Test fun theOpenMapsStopIsLeftOfTheCalendars() {
        val cal = HomeCalendarButton.fullBox(1240f, 150f, 16f)
        val mail = HomeMailButton.fullBox(1240f, 150f, 16f)
        assertArrayEquals(floatArrayOf(1026f, 16f, 1090f, 80f), cal, 0.01f)
        assertArrayEquals(floatArrayOf(956f, 16f, 1020f, 80f), mail, 0.01f)
        assertEquals(1023f, HomeMailButton.fullSplit(1240f, 150f), 0.01f)
        assertEquals(HomeCalendarButton.FULL_SHIFT, HomeMailButton.SHIFT, 0.01f)
    }

    @Test fun theCardHangsUnderTheEnvelope() {
        // one name: the heading's line and one name's, the padding round them
        assertEquals(22f * 2 + 40f + 36f, HomeMailButton.cardHeight(1), 0.01f)
        assertEquals(22f * 2 + 40f + 3 * 36f, HomeMailButton.cardHeight(3), 0.01f)
        assertEquals(HomeMailButton.cardHeight(3), HomeMailButton.cardHeight(9), 0.01f)          // three at most
        // its width: the widest line and the padding, at least CARD_MIN_W, at most the room left of the envelope's right edge
        assertEquals(HomeMailButton.CARD_MIN_W, HomeMailButton.cardWidth(50f, 500f), 0.01f)
        assertEquals(300f + 44f, HomeMailButton.cardWidth(300f, 500f), 0.01f)
        assertEquals(500f, HomeMailButton.cardWidth(900f, 500f), 0.01f)
        // right edge on the envelope's, never past the map's left inset
        assertEquals(530f - 344f, HomeMailButton.cardLeft(530f, 344f, 8f), 0.01f)
        assertEquals(8f, HomeMailButton.cardLeft(530f, 600f, 8f), 0.01f)
    }
}
