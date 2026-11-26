package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PadMap m204 / PadTabCtl p64: a form's own In-combat set on EVERY layer (a Druid's field report, 2026-11-26: "Regardless of which tab
 * I'm on for each form changing them on one form changes it on everything"). The padmap's `ff` entries carry their set (`b`, none = the
 * bare set); the room's button with a form chosen is that form's own entry for the set on show; Home's bare-set pictures never take a
 * trigger set's entry.
 */
class FormFightSetsTest {
    private val cat = 768
    private val bear = 5487

    private fun map(): PadMapModel.Map = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m204","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[{"pos":5,"kind":"fixed","n":"Interact"}]},{"set":3,"slots":[{"pos":5,"s":29,"kind":"spell","id":5176,"n":"Wrath","i":136006}]}],
        "forms":[{"x":1,"id":$bear,"n":"Bear Form","i":132276},{"x":2,"id":$cat,"n":"Cat Form","i":132115,"on":true}],
        "ff":[{"f":$cat,"p":5,"id":1082,"n":"Claw","i":132140},
              {"f":$cat,"b":3,"p":5,"id":5221,"n":"Shred","i":136231},
              {"f":$bear,"b":3,"p":5,"id":33917,"n":"Mangle","i":132135},
              {"f":$cat,"b":2,"p":7,"id":6807,"n":"Maul","i":132136},
              {"f":$cat,"p":7,"id":6807,"n":"Maul","i":132136}]}}"""))!!

    @Test fun eachEntryKeepsItsSet() {
        val m = map()
        assertEquals("Claw", PadMapModel.formFight(m, cat, 5)?.name)              // no `b`: the bare set
        assertEquals("Shred", PadMapModel.formFight(m, cat, 5, 3)?.name)          // ZR + X: Cat Form's own
        assertEquals("Mangle", PadMapModel.formFight(m, bear, 5, 3)?.name)        // ZR + X: Bear Form's own
        assertEquals("Maul", PadMapModel.formFight(m, cat, 7, 2)?.name)           // ZL + B: a place like any other
        assertNull(PadMapModel.formFight(m, cat, 7))                              // B on the bare set: never (the game's Back)
        assertNull(PadMapModel.formFight(m, bear, 5))                             // Bear Form has no bare X of its own
    }

    @Test fun theRoomShowsTheChosenFormsOwnOnATriggerSet() {
        val m = map()
        val s = PadMapModel.formSlot(m, cat, 3, 5)!!
        assertEquals(PadMapModel.Kind.COMBAT, s.kind); assertEquals("Shred", s.name); assertEquals(true, s.ov)
        assertEquals("Mangle", PadMapModel.formSlot(m, bear, 3, 5)!!.name)
        assertEquals(PadMapModel.Kind.NONE, PadMapModel.formSlot(m, bear, 3, 6)!!.kind)    // nothing of its own: empty (the caster's fights)
        assertNull(PadMapModel.formSlot(m, cat, 1, 7))                                       // bare B: the bar's own slot
        assertEquals("Claw", PadMapModel.formSlot(m, cat, 1, 5)!!.name)
    }

    @Test fun homesBareSetNeverTakesATriggerSetsEntry() {
        val own = HomeFightFace.formOwn(map())
        assertEquals(setOf(5), own.keys)
        assertEquals("Claw", own[5]?.name)
    }
}
