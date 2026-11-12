package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issues #18 / #20 / #31 ("Trying to bind an attack, doesnt show up on bottom screen"; "Any modifier key binding doesn't work"): every
 *  Bind box answer is one log line with the add-on's trigger read and the app's own, so the next report can tell where a bind went */
class BindLogLineTest {
    private fun ack(s: String) = JSONObject(s)
    private val none = SpellsModel.AppTrig(true, null, null)
    private fun rtAgo(ms: Long) = SpellsModel.AppTrig(true, null, ms)

    @Test fun aPlacedTriggerBindNamesItsSetAndTheRead() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"s":198,"pg":1,"b":3,"p":6},"probe":{"src":"pad","pad":3,"padSrc":"mapped","slotSrc":"formula","shift":false}}"""), rtAgo(0))
        assertTrue(l, l.startsWith("spells bind: b50 id=133 ok at s=198 pg=1 b=3 p=6;"))
        assertTrue(l, l.contains("src=pad pad=3 padSrc=mapped"))
        assertTrue(l, l.contains("slotSrc=formula"))
        assertTrue(l, l.endsWith("app lt=- rt=0ms recent set=3"))
        assertFalse(l, l.contains("MISMATCH"))
    }

    /** review: ZR let go before the ack arrives still reads as held (the latch), so a good ZR bind is not mis-flagged */
    @Test fun aTriggerLetGoBeforeTheAckStillCounts() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"s":198,"pg":1,"b":3,"p":6},"probe":{"src":"pad","pad":3}}"""), rtAgo(400))
        assertTrue(l, l.endsWith("app lt=- rt=400ms recent set=3"))
    }

    /** the failure #20 asks about: the add-on bound set 1 while the app saw ZR moments before; raw numbers, no verdict */
    @Test fun aTriggerTheAddOnMissedShowsInTheRawNumbers() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"s":182,"pg":1,"b":1,"p":2},"probe":{"src":"game","padSrc":"none","game":1}}"""), rtAgo(300))
        assertTrue(l, l.contains("src=game pad=- padSrc=none game=1"))
        assertTrue(l, l.contains("b=1 p=2;"))
        assertTrue(l, l.endsWith("rt=300ms recent set=3"))
    }

    @Test fun anOldTriggerIsNotRecent() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"s":182,"pg":1,"b":1,"p":2}}"""), rtAgo(SpellsModel.APP_TRIG_WINDOW_MS + 1))
        assertTrue(l, l.endsWith("recent set=1"))
    }

    /** review: the Cast room's reader holds the pad (SpellsOverlay never starts its own): say so, never a fake set 1 */
    @Test fun theAppReaderOffSaysSo() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"s":198,"pg":1,"b":3,"p":6}}"""), SpellsModel.AppTrig(false, null, null))
        assertTrue(l, l.endsWith("; app reader off"))
        assertFalse(l, l.contains("set="))
    }

    /** review of #31: the box keeps waiting after these refusals, so the new-spell flow must not go Home on them */
    @Test fun keepWaitingRefusalsAreNotFinal() {
        fun r(ok: Boolean, why: String?) = SpellsModel.BindResult(133, ok, why, null)
        for (w in listOf("dpad bar", "reserved", "unsure", "form-unknown")) assertFalse(w, SpellsModel.bindFinal(r(false, w)))
        for (w in listOf("combat", "failed", "bind", "unknown")) assertTrue(w, SpellsModel.bindFinal(r(false, w)))
        assertTrue(SpellsModel.bindFinal(r(true, null)))
        val parsed = SpellsModel.parseBind(ack("""{"type":"spellsack","op":"bind","id":133,"ok":false,"why":"dpad bar"}"""))!!
        assertEquals("dpad bar", parsed.why)
        assertFalse(SpellsModel.bindFinal(parsed))
    }

    @Test fun aRefusalNamesItsReason() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":false,"why":"dpad bar",
            "probe":{"src":"pad","pad":1,"padSrc":"gps"}}"""), none)
        assertTrue(l, l.contains("refused dpad bar at -;"))
        assertTrue(l, l.endsWith("app lt=- rt=- recent set=1"))
    }

    @Test fun anInCombatBindSaysFight() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"pg":1,"b":1,"p":2,"fight":true},"probe":{"src":"pad","pad":1}}"""), none)
        assertTrue(l, l.contains("at s=- pg=1 b=1 p=2 fight;"))
    }
}
