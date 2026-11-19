package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop clicks the pad (the user's pick 2026-11-18, "Desktop clicks on the PC"): the stream's pad presses go to Desktop in the pad's
 * own game px; Local is unchanged; an older Desktop keeps the touch path; quiet posted clicks only (the user's ruling 2026-11-19, "Off
 * once quiet clicks work"): posted presses that stop landing are one terse line, never a fallback.
 */
class DeskClicksTest {
    @After fun clean() = DeskClicks.reset()

    /** test 1496's pad: rows 2 px tall from y 12, pnav row 1 (y 14-16), iact said by the add-on to stand at 52 */
    private val pad = JSONObject().put("x", 1100).put("y", 12).put("cw", 6).put("ch", 2).put("cols", 8).put("pw", 1920).put("ph", 1080)
        .put("rows", JSONObject().put("pnav", 1).put("uisw", 51).put("iact", 20))
        .put("off", JSONObject().put("iact", 52.0)).put("calib", JSONObject().put("x", 1080).put("y", 12).put("w", 6).put("h", 110))

    private class Wire : DeskClicks.Link {
        val sent = ArrayList<JSONObject>()
        override fun send(text: String): Boolean { sent += JSONObject(text); return true }
    }

    @Test fun localIsUnchangedTheStreamGoesToDesktopAndAnOlderDesktopKeepsTheTouch() {
        assertEquals(DeskClicks.Route.LOCAL, DeskClicks.route(streaming = false, linked = false))
        assertEquals(DeskClicks.Route.LOCAL, DeskClicks.route(streaming = false, linked = true))
        assertEquals(DeskClicks.Route.DESKTOP, DeskClicks.route(streaming = true, linked = true))
        assertEquals(DeskClicks.Route.STREAM_TOUCH, DeskClicks.route(streaming = true, linked = false))
        // nothing streaming and no link in this JVM: Local
        assertEquals(DeskClicks.Route.LOCAL, DeskClicks.route())
    }

    @Test fun aCellIsAimedAtItsRowsOwnCentrePixel() {
        val p = DeskClicks.cellPoint(pad, "pnav", 2)!!
        assertEquals(1100 + 2.5 * 6 + 0.5, p.gx, 1e-9)
        assertEquals(15.5, p.gy, 1e-9)                  // rows 14-16: pixel 15 is pnav's own (14 and 16 are shared edges)
        assertEquals(14.0, p.top, 1e-9)
        assertEquals(53.5, DeskClicks.cellPoint(pad, "iact", 0)!!.gy, 1e-9)       // the add-on's own word for where the row stands
        assertEquals(115.5, DeskClicks.cellPoint(pad, "uisw", 0)!!.gy, 1e-9)
        assertNull(DeskClicks.cellPoint(pad, "nope", 0))
        assertNull(DeskClicks.cellPoint(pad, "pnav", 8))
        assertNull(DeskClicks.cellPoint(null, "pnav", 0))
        assertNull(DeskClicks.gamePoint(pad, 1920.0, 10.0))
        assertEquals(10.0 to 20.0, DeskClicks.gamePoint(pad, 10.0, 20.0))
    }

    @Test fun theMessageCarriesGamePixelsAndWaitsAlwaysPostedNeverAPark() {
        val m = DeskClicks.message(7, 1920.0, 1080.0, listOf(Triple(1115.5, 15.5, 0L), Triple(1115.5, 115.5, 120L), Triple(1.0, 1.0, 5000L)))
        assertEquals("pad_click", m.getString("t")); assertEquals(7L, m.getLong("id")); assertEquals("post", m.getString("mode"))
        assertFalse(m.getBoolean("park")); assertFalse(m.has("sink"))
        assertEquals(1920.0, m.getDouble("pw"), 0.0); assertEquals(1080.0, m.getDouble("ph"), 0.0)
        val t = m.getJSONArray("taps")
        assertEquals(15.5, t.getJSONObject(0).getDouble("y"), 0.0); assertEquals(120L, t.getJSONObject(1).getLong("w"))
        assertEquals(1000L, t.getJSONObject(2).getLong("w"))      // a wait is capped
    }

    @Test fun aRefusalReachesTheCaller() {
        val w = Wire(); DeskClicks.link = w
        val fails = ArrayList<String>()
        assertTrue(DeskClicks.send(1, listOf("pnav" to 2), listOf(1115.5 to 15.5), listOf(0L), 1920.0, 1080.0, false, 1000L) { fails += it })
        assertEquals(1, w.sent.size)
        assertNull(DeskClicks.onResult(JSONObject().put("id", 1).put("state", "done").put("n", 1), now = 1000L))
        assertTrue(DeskClicks.send(2, listOf("pnav" to 2), listOf(1115.5 to 15.5), listOf(0L), 1920.0, 1080.0, false, 1000L) { fails += it })
        val note = DeskClicks.onResult(JSONObject().put("id", 2).put("state", "refused").put("why", "no_wow").put("n", 0))
        assertTrue(note!!, note.contains("refused (no_wow)"))
        assertEquals(listOf("WoW isn't running on the PC: nothing was pressed"), fails)
        DeskClicks.link = null
        assertFalse("no link: nothing sent", DeskClicks.send(3, null, listOf(1.0 to 1.0), listOf(0L), 1920.0, 1080.0, false, 1000L) {})
    }

    private fun sendDone(w: Wire, id: Long, at: Long, cells: List<Pair<String, Int>>? = listOf("pnav" to 2)) {
        DeskClicks.send(id, cells, listOf(1115.5 to 15.5), listOf(0L), 1920.0, 1080.0, false, at) {}
        DeskClicks.onResult(JSONObject().put("id", id).put("state", "done").put("n", 1), now = at)
    }

    /** the user's ruling 2026-11-19, "Off once quiet clicks work": unseen posted presses are ONE line (tap log + Desktop's log), no switch */
    @Test fun postedPressesThatStopLandingAreOneLineNeverAFallback() {
        val w = Wire(); DeskClicks.link = w
        sendDone(w, 1, 1_000L)
        assertTrue(DeskClicks.check(2_000L, true).isEmpty())                       // not yet: its answer may still come
        assertTrue("one miss: nothing said", DeskClicks.check(5_000L, true).isEmpty())
        sendDone(w, 2, 6_000L)
        assertEquals(listOf("desktop clicks: posted presses not landing (2 batches unseen); no fallback"), DeskClicks.check(10_000L, true))
        assertEquals("pad_click_unseen", w.sent.last().getString("t")); assertEquals(2, w.sent.last().getInt("n"))
        assertEquals("post", DeskClicks.mode)
        sendDone(w, 3, 11_000L); sendDone(w, 4, 11_500L)
        assertEquals("post", w.sent.last { it.getString("t") == "pad_click" }.getString("mode"))
        assertFalse(w.sent.last { it.getString("t") == "pad_click" }.getBoolean("park"))
        assertTrue("said once", DeskClicks.check(20_000L, true).isEmpty())
        assertEquals(1, w.sent.count { it.getString("t") == "pad_click_unseen" })
        assertTrue("nothing sent again", DeskClicks.takePartials().isEmpty())
        // a posted press seen again: said, and a later stop is said once more
        sendDone(w, 5, 21_000L)
        assertEquals("desktop clicks: posted presses landing again (pnav:2)", DeskClicks.landed("pnav", 2, 21_200L))
        assertTrue(DeskClicks.postSeen)
        sendDone(w, 6, 22_000L); sendDone(w, 7, 23_000L)
        assertEquals(1, DeskClicks.check(30_000L, true).size)
        assertEquals(2, w.sent.count { it.getString("t") == "pad_click_unseen" })
    }

    @Test fun gamePointsAndAnAddOnWithoutThePressRingAreNeverCounted() {
        val w = Wire(); DeskClicks.link = w
        sendDone(w, 1, 1_000L, cells = null); sendDone(w, 2, 1_000L, cells = null)
        assertTrue(DeskClicks.check(10_000L, true).isEmpty())
        sendDone(w, 3, 1_000L); sendDone(w, 4, 1_000L)
        assertTrue(DeskClicks.check(10_000L, false).isEmpty())
        assertEquals(0, w.sent.count { it.getString("t") == "pad_click_unseen" })
    }

    @Test fun aBatchIsJudgedOnlyOnceTheRingMovedPastItOrTimeRanOut() {
        val w = Wire(); DeskClicks.link = w
        sendDone(w, 1, 1_000L, listOf("qact" to 18)); sendDone(w, 2, 1_200L, listOf("qact" to 9)); sendDone(w, 3, 1_300L, listOf("qact" to 7))
        assertTrue("too soon, nothing later seen", DeskClicks.check(1_500L, true).isEmpty())
        DeskClicks.landed("qact", 7, 1_600L)                                         // the ring moved past batches 1 and 2
        assertEquals(listOf("desktop clicks: posted presses not landing (2 batches unseen); no fallback"), DeskClicks.check(1_700L, true))
    }

    @Test fun repeatedCellsAreMatchedOldestFirst() {
        val w = Wire(); DeskClicks.link = w
        sendDone(w, 1, 1_000L, listOf("qact" to 8)); sendDone(w, 2, 1_100L, listOf("qact" to 8)); sendDone(w, 3, 1_200L, listOf("qact" to 9))
        DeskClicks.landed("qact", 8, 1_500L)
        // the newer ones are still waiting for their word: the older one was seen
        assertTrue(DeskClicks.check(2_000L, true).isEmpty())
        assertEquals(1, DeskClicks.check(6_000L, true).size)                         // 2 and 3 unseen by time: the one line
    }

    @Test fun aRingThatMovedMoreThanItListsLeavesTheOlderBatchesUnknown() {
        val w = Wire(); DeskClicks.link = w
        sendDone(w, 1, 1_000L, listOf("qact" to 18)); sendDone(w, 2, 1_100L, listOf("qact" to 9))
        DeskClicks.ringGap(3, 2_000L)
        assertTrue(DeskClicks.check(10_000L, true).isEmpty())
    }

    @Test fun aPartialBatchsRestGoesOnceMore() {
        val w = Wire(); DeskClicks.link = w
        val cells = listOf("pnav" to 38, "pnav" to 12, "pnav" to 15, "pnav" to 17)
        DeskClicks.send(5, cells, List(4) { 1.0 to 1.0 }, List(4) { 0L }, 1920.0, 1080.0, false, 1_000L) {}
        val note = DeskClicks.onResult(JSONObject().put("id", 5).put("state", "partial").put("why", "moved").put("n", 2), now = 1_200L)
        assertTrue(note!!, note.endsWith("2 pressed, the rest sent again"))
        assertEquals(listOf(5L to listOf("pnav" to 15, "pnav" to 17)), DeskClicks.takePartials())
        assertTrue(DeskClicks.takePartials().isEmpty())
        DeskClicks.send(6, cells, List(4) { 1.0 to 1.0 }, List(4) { 0L }, 1920.0, 1080.0, true, 2_000L) {}
        DeskClicks.onResult(JSONObject().put("id", 6).put("state", "partial").put("why", "moved").put("n", 1), now = 2_100L)
        assertTrue("a retry is never retried", DeskClicks.takePartials().isEmpty())
    }

    @Test fun aCountThatFellIsANewClientAndEveryPressIsNew() {
        assertEquals(12, GameUi.freshPresses(40, 12))                  // the Thor's own client said 40; the PC's fresh one says 12
        assertEquals(12, GameUi.freshPresses(-1, 12))
        assertEquals(1, GameUi.freshPresses(12, 13))
        assertEquals(0, GameUi.freshPresses(13, 13))
        assertEquals(0, GameUi.freshPresses(5, 0))
    }
}
