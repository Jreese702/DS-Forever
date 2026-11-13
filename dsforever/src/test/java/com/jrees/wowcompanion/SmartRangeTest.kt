package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PadMap m146 / PadTabCtl p46 SMART RANGE (the user, 2026-11-12: "Melee should show that skill when the enemy is in melee range, range
 * should show that skill when the enemy is not in Melee range. only having one selected will just show that ability all of the time. this
 * should be per button/ button modifier combo"): the documents' halves, Home's picture following the press, the backup's halves and their
 * restore taps. The add-on side is tools/test_smart_range.py (the document shape is checked there from the real Lua).
 */
class SmartRangeTest {
    private val pair = """{"pos":2,"s":26,"kind":"combat","ov":true,"base":"none","sr":true,"c":{"n":"Raptor Strike","id":2973,"i":132223,
        "sr":1,"h":"m","mn":"DSF ZR+Up","rg":{"n":"Arcane Shot","id":3044,"i":132218}}}"""
    private val rangeOnly = """{"pos":4,"s":28,"kind":"combat","ov":true,"base":"none","sr":true,"c":{"n":"Arcane Shot","id":3044,"i":132218,"sr":1,"h":"r"}}"""
    private val yPair = """{"pos":6,"kind":"fixed","n":"Inspect","sr":true,"cf":{"n":"Raptor Strike","id":2973,"i":132223,"sr":1,"h":"m",
        "mn":"DSF Y","rg":{"n":"Arcane Shot","id":3044,"i":132218}}}"""
    private fun map() = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m146","m":{"page":1,"pages":3,"dpad":{"name":"Korvash","realm":"Realm"},
        "sets":[{"set":1,"slots":[$yPair]},{"set":3,"slots":[$pair,$rangeOnly]}]}}"""))!!

    @Test fun theDocumentsHalves() {
        val m = map()
        val zr = PadMapModel.slot(m, 3, 2)
        assertTrue(zr!!.smart)
        assertEquals("Raptor Strike", PadMapModel.srMelee(zr)?.name)
        assertEquals(3044, PadMapModel.srRange(zr)?.id)
        assertEquals("DSF ZR+Up", zr.combat?.macro)
        val ro = PadMapModel.slot(m, 3, 4)
        assertNull("Range only: no Melee half", PadMapModel.srMelee(ro))
        assertEquals("Arcane Shot", PadMapModel.srRange(ro)?.name)
        val y = PadMapModel.slot(m, 1, 6)
        assertEquals("Raptor Strike", PadMapModel.srMelee(y)?.name)
        assertEquals("Arcane Shot", PadMapModel.srRange(y)?.name)
        assertFalse("a plain slot: no box", PadMapModel.srOn(PadMapModel.Slot(1, 1, PadMapModel.Kind.SPELL, 1, "x", 0)))
    }

    @Test fun homesPictureFollowsThePress() {
        val m = map()
        val pairs = HomeFightFace.smartPairs(m)
        assertEquals("both halves only (the Range-only button is a plain one)", setOf(HomeFightFace.cmdKey(3, 2), HomeFightFace.cmdKey(1, 6)), pairs.keys)
        val p = pairs.getValue(HomeFightFace.cmdKey(3, 2))
        assertEquals("the target in melee range (the Melee spell's state r = 1): Raptor Strike", "Raptor Strike", HomeFightFace.smartPick(p, 1).name)
        assertEquals("out of melee range: Arcane Shot", "Arcane Shot", HomeFightFace.smartPick(p, 0).name)
        assertEquals("no range known (no target): the Range half", "Arcane Shot", HomeFightFace.smartPick(p, -1).name)
        assertEquals("no state at all: the Range half", "Arcane Shot", HomeFightFace.smartPick(p, null).name)
        assertEquals("the trigger set's In-combat picture is the Melee half (CastView swaps it by the state)", 132223,
            HomeFightFace.trigFights(m, fight = true)[HomeFightFace.cmdKey(3, 2)]?.first)
    }

    @Test fun theBackupKeepsBothHalvesAndRestoresThemInOrder() {
        val m = map()
        val cur = CombatKeep.current(m)
        val zr = cur.filter { it.set == 3 && it.pos == 2 }
        assertEquals(listOf("m" to 2973, "r" to 3044), zr.map { it.half to it.id })
        assertEquals("the Range-only button: its one half", listOf("r"), cur.filter { it.set == 3 && it.pos == 4 }.map { it.half })
        assertEquals(listOf("m", "r"), cur.filter { it.face }.map { it.half })
        val b = CombatKeep.Backup("Korvash-Realm", cur)
        val back = CombatKeep.fromJson(CombatKeep.toJson(b))!!
        assertEquals(b, back)
        val plan = CombatKeep.plan(b, 1) { id -> listOf(PadMapModel.ROW_SPELLS to id % 40) }
        val zrRange = plan.batches.first { it.contains(PadMapModel.ROW to PadMapModel.colSet(3)) && it.contains(PadMapModel.ROW to PadMapModel.colPos(2)) &&
            it.contains(PadMapModel.ROW_SPELLS to 3044 % 40) }
        val i = zrRange.indexOf(PadMapModel.ROW_LAYOUT to PadMapModel.COL_COMBAT_LAYER)
        assertEquals("the box on, then the Range half picked, right before the combat-layer pick and the spell",
            PadMapModel.srCells(PadMapModel.SR_ON) + PadMapModel.srCells(PadMapModel.SR_RANGE), zrRange.subList(i - 4, i))
        assertTrue("nothing missing when the add-on shows both halves", CombatKeep.missing(b, m).isEmpty())
        val lost = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m146","m":{"page":1,"pages":3,"sets":[{"set":3,"slots":[{"pos":2,"s":26,
            "kind":"combat","ov":true,"c":{"n":"Raptor Strike","id":2973,"i":1,"sr":1,"h":"m"}}]}]}}"""))!!
        assertEquals("the Range half did not take: it is the one missing", listOf("r"), CombatKeep.missing(b, lost).filter { !it.face && it.set == 3 && it.pos == 2 }.map { it.half })
    }

    @Test fun theTouchCells() {
        assertEquals(listOf("padtab" to 39, "padtab" to 1), PadMapModel.srCells(PadMapModel.srCode("m")))
        assertEquals(listOf("padtab" to 39, "padtab" to 2), PadMapModel.srCells(PadMapModel.srCode("r")))
        assertEquals(listOf("padtab" to 39, "padtab" to 3), PadMapModel.srCells(PadMapModel.SR_ON))
    }
}
