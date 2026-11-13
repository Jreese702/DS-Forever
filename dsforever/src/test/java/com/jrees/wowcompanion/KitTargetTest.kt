package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** issues #126 / #39 (2026-11-11): "disenchantment of items not possible. Game tries to equip instead" - a pick's bag gear goes to its
 *  own /use cell (binv 20..39), never the bag's secure "item" cell (which equips it) */
class KitTargetTest {
    @Test fun bagGearGoesToItsUseCell() {
        assertEquals("binv" to 22, KitTarget.ofRow(JSONObject("""{"src":"bag","bag":1,"s":4,"cell":22,"ok":true}""")))
        assertEquals("binv" to 4, KitTarget.ofRow(JSONObject("""{"src":"inv","slot":5,"ok":true}""")))          // the chest: /use 5
        // a consumable (Feed Pet): the bag's own cell, its right-click
        assertEquals("bag0" to 2, KitTarget.ofRow(JSONObject("""{"src":"bag","bag":0,"s":3,"ok":true}""")))
        assertNull(KitTarget.ofRow(JSONObject("""{"src":"bag"}""")))
    }

    @Test fun theAddonsUseRequest() {
        assertEquals("binv" to 25, KitTarget.ofUse(JSONObject("""{"cell":25,"seq":7}""")))
        assertEquals("binv" to 9, KitTarget.ofUse(JSONObject("""{"inv":10,"seq":7}""")))
        assertEquals("binv" to KitTarget.STOP_COL, KitTarget.ofUse(JSONObject("""{"stop":true,"seq":7}""")))
        assertNull(KitTarget.ofUse(JSONObject("""{"bag":0,"slot":3,"seq":7}""")))    // a plain use: the caller's bag cell
        assertNull(KitTarget.ofUse(JSONObject("""{"cell":3,"seq":7}""")))            // never a worn-slot cell by a stray number
    }

    /** bags-kit3 (the user's pick, 2026-11-13: "the Character room taps a slot only if it is a matching row") */
    @Test fun theCharacterRoomTapsOnlyAListedSlot() {
        val doc = JSONObject("""{"type":"itarget","on":true,"ctx":"cond","wornOk":",5,10,"}""")
        assertEquals(true, KitTarget.slotFits(doc, 5))
        assertEquals(true, KitTarget.slotFits(doc, 10))
        assertEquals(false, KitTarget.slotFits(doc, 1))          // the head: not a row, never ",1," inside ",10,"
        assertEquals(false, KitTarget.slotFits(JSONObject("""{"wornOk":","}"""), 7))   // Feed Pet: no worn slot
        assertEquals(true, KitTarget.slotFits(JSONObject("""{"on":true}"""), 7))       // an older add-on: the game decides
        assertEquals(true, KitTarget.slotFits(null, 7))
    }
}
