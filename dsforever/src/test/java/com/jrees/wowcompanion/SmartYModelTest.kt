package com.jrees.wowcompanion

import com.jrees.wowcompanion.SmartYModel.Row
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** m62 Smart buffs: the Settings section's half (SmartYModel): the padmap's `sb` read, and every edit's ONE batch of taps */
class SmartYModelTest {
    private val list = """[{"id":1459,"n":"Arcane Intellect","i":135932,"k":"aura"},{"id":168,"n":"Frost Armor","i":135843,"k":"aura"},
                 {"id":7302,"n":"Ice Armor","i":135843,"k":"aura"},{"id":604,"n":"Dampen Magic","i":136006,"k":"aura"}]"""
    private val ops = """{"remove":24,"up":25,"down":26,"add":27,"addor":28,"out":29}"""
    /** an add-on before m127: buff rows only, Food / Water fixed above them */
    private val sb = JSONObject("""
        {"rows":[{"ids":[1459]},{"ids":[168,7302]}], "list":$list,
         "at":2,"row":"gamemenu","pad":true,"sel0":8, "ops":$ops}""")
    private val doc = SmartYModel.parse(sb)!!
    private val spell = listOf("spells" to 30, "spells" to 35)

    /** m127 (the user, 2026-11-11): the typed list, in the player's order - a buff above Food, Manners in the middle */
    private val v2 = JSONObject("""
        {"v":2,"rows":[{"k":"buff","ids":[1459]},{"k":"food","ids":[]},{"k":"manners","ids":[]},{"k":"buff","ids":[168,7302]}],
         "list":$list, "at":1,"row":"gamemenu","pad":true,"sel0":8,"ops":$ops,"on":true,
         "mn":true,"kinds":{"food":8,"drink":9,"manners":10},"icons":{"food":133964,"drink":132794,"manners":136078},"max":16}""")
    private val typed = SmartYModel.parse(v2)!!

    @Test fun reads() {
        assertEquals(listOf(Row("buff", listOf(1459)), Row("buff", listOf(168, 7302))), doc.rows)
        assertTrue(doc.fixedFood)                                            // the old add-on: Food / Water drawn fixed on top
        assertEquals("Frost Armor or Ice Armor", SmartYModel.label(doc, 1))
        assertTrue(SmartYModel.isOr(doc, 1))
        assertEquals(2, doc.at)
        assertNull(SmartYModel.parse(null))                                 // an add-on before m62: no section
    }

    @Test fun readsTypedRows() {
        assertEquals(listOf(Row("buff", listOf(1459)), Row("food"), Row("manners"), Row("buff", listOf(168, 7302))), typed.rows)
        assertFalse(typed.fixedFood)
        assertTrue(typed.manners)
        assertEquals("Food", SmartYModel.label(typed, 1))
        assertEquals("Manners", SmartYModel.label(typed, 2))
        assertEquals(listOf(136078), SmartYModel.icons(typed, 2))           // a kind's picture
        assertEquals(listOf(135843, 135843), SmartYModel.icons(typed, 3))   // an OR row's chained slots
        assertFalse(SmartYModel.isOr(typed, 1))
        assertEquals("< 80%", SmartYModel.kindTag("food"))
        // a kind of a newer add-on, or a buff row with no ids: not shown
        val odd = SmartYModel.parse(JSONObject(v2.toString()).put("rows", org.json.JSONArray("""[{"k":"later"},{"k":"buff"},{"k":"drink"}]""")))!!
        assertEquals(listOf(Row("drink")), odd.rows)
        // v2 with only buff rows (Food / Water removed): not the old fixed look
        val noFood = SmartYModel.parse(JSONObject(v2.toString()).put("rows", org.json.JSONArray("""[{"ids":[1459]}]""")))!!
        assertFalse(noFood.fixedFood)
    }

    @Test fun choices() {
        assertEquals(listOf(604), SmartYModel.addChoices(doc).map { it.id })                       // not on any row yet
        assertEquals(listOf(1459, 604), SmartYModel.orChoices(doc, 1).map { it.id })               // not on that row
        assertEquals(listOf(168, 7302), SmartYModel.outChoices(doc, 1).map { it.id })
        assertEquals(emptyList<String>(), SmartYModel.kindChoices(doc))                           // the old add-on: none
        assertEquals(listOf("drink"), SmartYModel.kindChoices(typed))                             // Water is not on the list
        assertEquals(emptyList<Any>(), SmartYModel.orChoices(typed, 1))                           // no Or on Food
        val noMn = SmartYModel.parse(JSONObject(v2.toString()).put("mn", false).put("rows", org.json.JSONArray("[]")))!!
        assertEquals(listOf("food", "drink"), SmartYModel.kindChoices(noMn))                      // Manners only when installed
    }

    /** m76: Smart Y is OFF by default (an add-on that sends no `on` too); off = only the On / Off row; one absolute tap each way */
    @Test fun onOff() {
        assertTrue(!doc.on)                                                  // the test doc has no "on": off
        assertTrue(!SmartYModel.rowsShown(doc))
        assertTrue(!SmartYModel.rowsShown(null))
        val on = SmartYModel.parse(JSONObject(sb.toString()).put("on", true))!!
        assertTrue(SmartYModel.rowsShown(on))
        assertEquals(listOf("gamemenu" to 6), SmartYModel.toggleTaps(doc, true))
        assertEquals(listOf("gamemenu" to 7), SmartYModel.toggleTaps(on, false))
    }

    @Test fun taps() {
        assertEquals(listOf("gamemenu" to 27) + spell, SmartYModel.addTaps(doc, spell))
        assertEquals(listOf("gamemenu" to 9, "gamemenu" to 28) + spell, SmartYModel.spellTaps(doc, 1, "addor", spell))
        assertEquals(listOf("gamemenu" to 9, "gamemenu" to 29) + spell, SmartYModel.spellTaps(doc, 1, "out", spell))
        assertEquals(listOf("gamemenu" to 8, "gamemenu" to 24), SmartYModel.rowTaps(doc, 0, "remove"))
        assertEquals(listOf("gamemenu" to 9, "gamemenu" to 25), SmartYModel.rowTaps(doc, 1, "up"))
        assertNull(SmartYModel.rowTaps(doc, 0, "up"))                         // the top row cannot go up
        assertNull(SmartYModel.rowTaps(doc, 1, "down"))                       // nor the last one down
        assertNull(SmartYModel.addTaps(doc, null))                            // a spell with no tap way: nothing sent
    }

    /** m127: Food / Manners rows move and go like any row; Add + the kind's cell brings one back; Or only on a buff row */
    @Test fun typedTaps() {
        assertEquals(listOf("gamemenu" to 9, "gamemenu" to 25), SmartYModel.rowTaps(typed, 1, "up"))       // Food above the buff
        assertEquals(listOf("gamemenu" to 10, "gamemenu" to 24), SmartYModel.rowTaps(typed, 2, "remove"))  // Manners removed
        assertEquals(listOf("gamemenu" to 27, "gamemenu" to 9), SmartYModel.addKindTaps(typed, "drink"))
        assertNull(SmartYModel.addKindTaps(typed, "food"))                    // already on the list
        assertNull(SmartYModel.spellTaps(typed, 1, "addor", spell))           // no Or on Food
        assertEquals(listOf("gamemenu" to 11, "gamemenu" to 29) + spell, SmartYModel.spellTaps(typed, 3, "out", spell))
    }

    /** m127: the row Y acts on now, by the padmap's `smarty` kind */
    @Test fun nowRow() {
        assertEquals(1, SmartYModel.nowRow(typed, "food"))
        assertEquals(1, SmartYModel.nowRow(typed, "both"))
        assertEquals(2, SmartYModel.nowRow(typed, "manners"))
        assertEquals(0, SmartYModel.nowRow(typed, "buff"))                   // at = 1 (the add-on counts from 1)
        assertNull(SmartYModel.nowRow(typed, "drink"))                       // not on the list
        assertNull(SmartYModel.nowRow(typed, null))
    }

    /** m190 SIT BY THE FIRE (the user, 2026-11-24: "I want to add a smart y option to sit when the player has the nearby campfire
     *  buff"): a kind row of its own - read, named (terse), pictured, offered back by Add (select cell sel0 + 3), moved / removed like
     *  any row, no Or; Y's pick "fire" lights it */
    private val fire = JSONObject("""
        {"v":2,"rows":[{"k":"food","ids":[]},{"k":"drink","ids":[]},{"k":"buff","ids":[1459]},{"k":"fire","ids":[]}],
         "list":$list, "at":null,"row":"gamemenu","pad":true,"sel0":8,"ops":$ops,"on":true,
         "mn":false,"kinds":{"food":8,"drink":9,"manners":10,"fire":11},
         "icons":{"food":133964,"drink":132794,"manners":136078,"fire":135805},"max":16}""")

    @Test fun sitByTheFire() {
        val d = SmartYModel.parse(fire)!!
        assertEquals(listOf(Row("food"), Row("drink"), Row("buff", listOf(1459)), Row("fire")), d.rows)
        assertEquals("Sit by the fire", SmartYModel.label(d, 3))
        assertEquals(listOf(135805), SmartYModel.icons(d, 3))              // the Cozy Fire spell's own picture
        assertNull(SmartYModel.kindTag(SmartYModel.FIRE))                    // terse: the name says it
        assertFalse(SmartYModel.isOr(d, 3))
        assertEquals(emptyList<Any>(), SmartYModel.orChoices(d, 3))          // no Or on it
        assertEquals(listOf("gamemenu" to 11, "gamemenu" to 25), SmartYModel.rowTaps(d, 3, "up"))
        assertEquals(listOf("gamemenu" to 11, "gamemenu" to 24), SmartYModel.rowTaps(d, 3, "remove"))
        assertNull(SmartYModel.rowTaps(d, 3, "down"))                        // the last row
        assertEquals(3, SmartYModel.nowRow(d, "fire"))                       // Y sits now: its row is gold
        assertEquals(emptyList<String>(), SmartYModel.kindChoices(d))        // on the list already (Manners not installed)
        // removed: Add offers it back, by its own select cell
        val gone = SmartYModel.parse(JSONObject(fire.toString()).put("rows", org.json.JSONArray("""[{"k":"food","ids":[]}]""")))!!
        assertEquals(listOf("drink", "fire"), SmartYModel.kindChoices(gone))
        assertEquals(listOf("gamemenu" to 27, "gamemenu" to 11), SmartYModel.addKindTaps(gone, "fire"))
        assertNull(SmartYModel.nowRow(gone, "fire"))
        // an add-on before m190 (no "fire" cell): never offered
        val old = SmartYModel.parse(JSONObject(fire.toString()).put("kinds", JSONObject("""{"food":8,"drink":9,"manners":10}"""))
            .put("rows", org.json.JSONArray("[]")))!!
        assertEquals(listOf("food", "drink"), SmartYModel.kindChoices(old))
    }

    /** m190: Y's pick of the fire row keeps its kind on its way through the padmap */
    @Test fun firePickKeepsItsKind() {
        val msg = JSONObject("""{"type":"padmap","v":"m190","m":{"smarty":{"id":7353,"n":"Cozy Fire","i":135805,"k":"fire"}}}""")
        val ref = PadMapModel.parse(msg)?.smartY
        assertEquals("fire", ref?.kind)
        assertEquals(135805, ref?.icon)
        assertFalse(ref?.manners == true)
    }

    /** m127: Smart Y's pick keeps its kind (PadMapModel dropped it: a Manners pick looked like Smart Y's own) */
    @Test fun pickKeepsItsKind() {
        val msg = JSONObject("""{"type":"padmap","v":"m127","m":{"smarty":{"id":0,"n":"Arcane Intellect on Bob","i":135932,"k":"manners"}}}""")
        val ref = PadMapModel.parse(msg)?.smartY
        assertEquals("manners", ref?.kind)
        assertTrue(ref?.manners == true)
    }
}
