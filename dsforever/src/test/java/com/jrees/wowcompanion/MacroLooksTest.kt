package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A MACRO ON OUR BUTTONS (PadMap m165) AND THE PET'S PICKS (Spells s13), the app's pure half. The user, 2026-11-16: "the macro room
 * needs some work- for starters when using show tooltip on a macro it doesn't actually show the GCD when using it ... also things like
 * my pet attack are missing from the spells options." The documents are the add-on's own (tools/test_macro_room.py r. / s., MACRO_DUMP
 * looks_padmap / pick_pet, trimmed): a macro's ref carries its live picture and `ms` (the spell it casts now) / `mt` (the item), never
 * `id`; the fight faces take their state, cast time and cooldown by those.
 */
class MacroLooksTest {
    private val padmap = """{"type": "padmap", "v": "m165", "m": {"page": 1, "pages": 3, "combat": true, "fight": true,
        "face": {"armed": {"5": "r", "6": "r", "8": "r"}, "keys": {"5": "PAD3", "6": "PAD4", "8": "PAD1"}, "on": true, "router": true},
        "sets": [{"set": 1, "slots": [
            {"pos": 1, "kind": "none"},
            {"pos": 2, "kind": "combat", "ov": true, "s": 2, "c": {"i": 136048, "key": "mc:Shift", "ms": 403, "n": "Shift", "t": "macro"}},
            {"pos": 3, "kind": "none"}, {"pos": 4, "kind": "none"},
            {"pos": 5, "kind": "fixed", "cf": {"i": 136048, "key": "mc:Bolt", "ms": 403, "n": "Bolt", "t": "macro"}},
            {"pos": 6, "kind": "fixed", "cf": {"i": 134830, "key": "mc:Pot", "mt": 118, "n": "Pot", "t": "macro"}},
            {"pos": 7, "kind": "fixed"},
            {"pos": 8, "kind": "fixed", "cf": {"i": 136095, "key": "mc:Own", "ms": 8042, "n": "Own", "t": "macro"}}]},
          {"set": 2, "slots": [{"pos": 3, "kind": "combat", "ov": true, "s": 15,
            "c": {"i": 136052, "key": "mc:Heal", "ms": 331, "n": "Heal", "t": "macro"}}]}]}}"""

    private val map get() = PadMapModel.parse(JSONObject(padmap))!!

    @Test fun aMacrosRefIsItsLiveLookNeverASpell() {
        val x = PadMapModel.slot(map, 1, 5)!!.fight!!
        assertEquals(136048, x.icon)                         // the spell's picture (a #showtooltip macro's question mark resolved)
        assertNull(x.id)                                     // never an id: nothing takes it for a spell
        assertEquals(403, x.ms); assertEquals(403, x.stateId)
        assertEquals("macro", x.layer); assertEquals("mc:Bolt", x.key)
        val y = PadMapModel.slot(map, 1, 6)!!.fight!!
        assertEquals(118, y.mt); assertNull(y.ms); assertNull(y.stateId)
        val spell = PadMapModel.Ref(403, "Lightning Bolt", 136048)
        assertEquals(403, spell.stateId); assertNull(spell.ms)  // a spell's own as before
    }

    @Test fun theFightFacesGoByWhatTheMacroCastsNow() {
        val m = map
        assertEquals(mapOf(5 to 403, 8 to 8042, 2 to 403), HomeFightFace.ids(m, fight = true))
        assertEquals(mapOf(5 to 403, 8 to 8042), HomeFightFace.ids(m, fight = false))          // the D-pad's only in a fight
        assertEquals(mapOf(6 to 118), HomeFightFace.items(m, fight = true))
        assertEquals(mapOf(5 to 136048, 6 to 134830, 8 to 136095), HomeFightFace.icons(m))
        assertEquals(mapOf(2 to 136048), HomeFightFace.dpadIcons(m, fight = true))
        // LT's In combat macro: its picture with the state of the spell it casts now
        assertEquals(mapOf(HomeFightFace.cmdKey(2, 3) to (136052 to 331)), HomeFightFace.trigFights(m, fight = true))
    }

    @Test fun theFacecdOfAMacroIsAFightFacesCooldown() {
        val cd = PadMapModel.parseCd(JSONObject("""{"type": "facecd", "v": "m165", "k": {"2": {"d": 1.5, "e": true, "g": true, "l": 1.5},
            "5": {"d": 1.5, "e": true, "g": true, "l": 1.5}, "8": {"d": 1.5, "e": true, "g": true, "l": 1.5}}}"""))!!
        val sweeps = HomeFightFace.cooldowns(cd, 10_000L)
        for (p in listOf(2, 5, 8)) assertEquals(10_000L to 1_500L, sweeps[p])
        assertEquals(0L to 0L, sweeps[6])                                                       // the item's macro: the itemcd road
    }

    @Test fun thePetsPicksCarryTheirOwnLines() {
        val s = MacrosModel.parse(JSONObject("""{"type": "macros", "v": "s13", "m": {"ready": true, "mode": "pick", "view": "pick",
            "pick": {"cur": 3, "page": 1, "pages": 2, "rows": [{"i": 136048, "n": "Lightning Bolt", "t": "s"},
              {"i": 132152, "l": "/petattack", "n": "Attack", "t": "p"}, {"i": 136222, "l": "/petdismiss", "n": "Dismiss", "t": "p"},
              {"i": 135809, "n": "Firebolt", "t": "s"}, {"i": 134830, "n": "Minor Healing Potion", "t": "u"}]}}}"""))!!
        val rows = s.pick!!.rows
        assertEquals(listOf("/cast Lightning Bolt", "/petattack", "/petdismiss", "/cast Firebolt", "/use Minor Healing Potion"),
            rows.map { MacrosModel.lineOf(it) })
        assertEquals("p", rows[1].kind); assertEquals(132152, rows[1].icon)
        assertNull(rows[0].line)
        assertTrue(MacrosModel.lineOf(MacrosModel.PickRow("p", "Attack", 0)).isEmpty())      // no line said: nothing invented
    }
}
