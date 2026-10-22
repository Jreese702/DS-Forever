package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** m62 Smart buffs: the Settings section's half (SmartYModel): the padmap's `sb` read, and every edit's ONE batch of taps */
class SmartYModelTest {
    private val sb = JSONObject("""
        {"rows":[{"ids":[1459]},{"ids":[168,7302]}],
         "list":[{"id":1459,"n":"Arcane Intellect","i":135932,"k":"aura"},{"id":168,"n":"Frost Armor","i":135843,"k":"aura"},
                 {"id":7302,"n":"Ice Armor","i":135843,"k":"aura"},{"id":604,"n":"Dampen Magic","i":136006,"k":"aura"}],
         "at":2,"row":"gamemenu","pad":true,"sel0":8,
         "ops":{"remove":24,"up":25,"down":26,"add":27,"addor":28,"out":29}}""")
    private val doc = SmartYModel.parse(sb)!!
    private val spell = listOf("spells" to 30, "spells" to 35)

    @Test fun reads() {
        assertEquals(listOf(listOf(1459), listOf(168, 7302)), doc.rows)
        assertEquals("Frost Armor or Ice Armor", SmartYModel.label(doc, 1))
        assertTrue(SmartYModel.isOr(doc, 1))
        assertEquals(2, doc.at)
        assertNull(SmartYModel.parse(null))                                 // an add-on before m62: no section
    }

    @Test fun choices() {
        assertEquals(listOf(604), SmartYModel.addChoices(doc).map { it.id })                       // not on any row yet
        assertEquals(listOf(1459, 604), SmartYModel.orChoices(doc, 1).map { it.id })               // not on that row
        assertEquals(listOf(168, 7302), SmartYModel.outChoices(doc, 1).map { it.id })
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
}
