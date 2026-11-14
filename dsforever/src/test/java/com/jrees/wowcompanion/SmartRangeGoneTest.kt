package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PadMap m160 SMART RANGE IS GONE (the user, 2026-11-13: "Lets scrap the smart ranged attack"): an older add-on's document with Smart
 * Range fields reads as plain fight spells (the add-on's clean-up keeps the top spell), Home draws that one spell, the In-combat backup
 * written by a Smart Range build drops a pair's Range half, and the clean-up's counts (`srx`) reach the problem report's line. The add-on
 * side is tools/test_smart_range_gone.py (the clean-up itself, from the real Lua).
 */
class SmartRangeGoneTest {
    @After fun reset() = SmartRangeGone.reset()

    // a padmap from a 1.0.28-1.0.32 add-on: the old fields (sr / h / mn / rg, the slot's box, `sro`) are read as nothing
    private val old = """{"type":"padmap","v":"m159","m":{"page":1,"pages":3,"dpad":{"name":"Korvash","realm":"Realm"},
        "sro":{"c":1,"sw":[2973]},"ffsr":[{"f":5487,"p":2}],
        "sets":[{"set":1,"slots":[{"pos":6,"kind":"fixed","n":"Inspect","sr":true,"cf":{"n":"Raptor Strike","id":2973,"i":132223,"sr":1,
                   "h":"m","mn":"DSF Y","rg":{"n":"Arcane Shot","id":3044,"i":132218}}}]},
                {"set":3,"slots":[{"pos":2,"s":26,"kind":"combat","ov":true,"base":"none","sr":true,"c":{"n":"Raptor Strike","id":2973,
                   "i":132223,"sr":1,"h":"m","mn":"DSF ZR+Up","rg":{"n":"Arcane Shot","id":3044,"i":132218}}}]}]}}"""

    @Test fun anOldDocumentIsPlainFightSpells() {
        val m = PadMapModel.parse(JSONObject(old))!!
        val zr = PadMapModel.slot(m, 3, 2)!!
        assertEquals("the top spell is the button's one fight spell", "Raptor Strike", PadMapModel.fightRef(zr)?.name)
        assertEquals(PadMapModel.Ref(2973, "Raptor Strike", 132223), zr.combat)
        assertEquals(PadMapModel.Ref(2973, "Raptor Strike", 132223), PadMapModel.slot(m, 1, 6)?.fight)
        assertEquals("Home's trigger-set picture is that spell", 132223, HomeFightFace.trigFights(m, fight = true)[HomeFightFace.cmdKey(3, 2)]?.first)
        assertNull("no clean-up word from an older add-on", m.srGone)
    }

    @Test fun theCleanUpsCountsReachTheReport() {
        val m = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m160","m":{"page":1,"sets":[],"srx":{"m":4,"b":3,"k":1}}}"""))!!
        assertEquals(PadMapModel.SrGone(4, 3, 1, false), m.srGone)
        assertNull("nothing said before a padmap", SmartRangeGone.line())
        assertTrue(SmartRangeGone.note(m.srGone))
        assertFalse("the same counts again: no second log line", SmartRangeGone.note(m.srGone))
        assertEquals("Smart Range clean-up: 4 macros deleted, 3 buttons back to one spell, 1 look-alike macros kept", SmartRangeGone.line())
        val wait = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m160","m":{"page":1,"sets":[],"srx":{"p":1}}}"""))!!
        assertTrue(SmartRangeGone.note(wait.srGone))
        assertEquals("Smart Range clean-up: waiting (macros not loaded yet, or a fight)", SmartRangeGone.line())
        val earlier = PadMapModel.SrGone(2, 2, 0, true)
        assertTrue(SmartRangeGone.line(earlier).endsWith("(an earlier session; this one has not run yet)"))
        assertFalse("counts only: no macro or character name", SmartRangeGone.line()!!.contains("DSF"))
    }

    @Test fun anOldBackupDropsAPairsRangeHalf() {
        // CombatKeep's file from a Smart Range build: ZR + Up's pair (m + r), a Range-only box (r alone), a plain one
        val json = """{"v":1,"who":"Korvash-Realm","entries":[
            {"page":1,"set":3,"pos":2,"id":2973,"n":"Raptor Strike","h":"m"},{"page":1,"set":3,"pos":2,"id":3044,"n":"Arcane Shot","h":"r"},
            {"page":1,"set":3,"pos":4,"id":3044,"n":"Arcane Shot","h":"r"},
            {"page":0,"set":1,"pos":6,"id":2973,"n":"Raptor Strike","face":true,"h":"m"},{"page":0,"set":1,"pos":6,"id":3044,"n":"Arcane Shot","face":true,"h":"r"},
            {"page":1,"set":2,"pos":1,"id":1978,"n":"Serpent Sting"}]}"""
        val b = CombatKeep.fromJson(json)!!
        assertEquals(listOf(
            CombatKeep.Entry(1, 3, 2, 2973, "Raptor Strike", false),
            CombatKeep.Entry(1, 3, 4, 3044, "Arcane Shot", false),
            CombatKeep.Entry(0, 1, 6, 2973, "Raptor Strike", true),
            CombatKeep.Entry(1, 2, 1, 1978, "Serpent Sting", false)), b.entries)
        assertFalse("written back without the halves", CombatKeep.toJson(b).contains("\"h\""))
        // the restore: one put per button, no padtab 39 code (Smart Range's taps are gone)
        val plan = CombatKeep.plan(b, 1) { id -> listOf("spells" to id % 40) }
        assertEquals(4, plan.restored.size)
        assertTrue(plan.batches.flatten().none { it == PadMapModel.ROW_PADTAB to 39 })
    }
}
