package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issues #18 / #20 / #31 ("Trying to bind an attack, doesnt show up on bottom screen"; "Any modifier key binding doesn't work"): every
 *  Bind box answer is one log line with the add-on's trigger read and the app's own, so the next report can tell where a bind went */
class BindLogLineTest {
    private fun ack(s: String) = JSONObject(s)

    @Test fun aPlacedTriggerBindNamesItsSetAndTheRead() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"s":198,"pg":1,"b":3,"p":6},"probe":{"src":"pad","pad":3,"padSrc":"mapped","slotSrc":"formula","shift":false}}"""), false, true)
        assertTrue(l, l.startsWith("spells bind: b50 id=133 ok at s=198 pg=1 b=3 p=6;"))
        assertTrue(l, l.contains("src=pad pad=3 padSrc=mapped"))
        assertTrue(l, l.contains("slotSrc=formula"))
        assertTrue(l, l.endsWith("app set=3"))
        assertFalse(l, l.contains("MISMATCH"))
    }

    @Test fun aTriggerTheAddOnCouldNotSeeShowsAsAMismatch() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"s":182,"pg":1,"b":1,"p":2},"probe":{"src":"game","padSrc":"none","game":1}}"""), false, true)
        assertTrue(l, l.contains("src=game pad=- padSrc=none game=1"))
        assertTrue(l, l.endsWith("app set=3 SET MISMATCH"))
    }

    @Test fun aRefusalNamesItsReason() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":false,"why":"dpad bar",
            "probe":{"src":"pad","pad":1,"padSrc":"gps"}}"""), false, false)
        assertTrue(l, l.contains("refused dpad bar at -;"))
        assertTrue(l, l.endsWith("app set=1"))
    }

    @Test fun anInCombatBindSaysFight() {
        val l = SpellsModel.bindLogLine(ack("""{"type":"spellsack","op":"bind","v":"b50","id":133,"ok":true,
            "pad":{"pg":1,"b":1,"p":2,"fight":true},"probe":{"src":"pad","pad":1}}"""), false, false)
        assertTrue(l, l.contains("at s=- pg=1 b=1 p=2 fight;"))
    }
}
