package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #541 ("cant enchant"): the Professions room greyed out every Enchanting recipe ("needs an item to enchant") and nothing handed over
 * to an item pick. Now an enchant's action is ENCHANT: the same checks as Create (selected, not busy, no cooldown, requirements met,
 * reagents), then ONE tap of the add-on's p27 key `ench` (cast once; the game asks for the item and the Bags room's "Choose an item"
 * answers it). No count, no Create All; an add-on without the key says it needs the update. A plain recipe is unchanged.
 */
class ProfessionsEnchantTest {
    private val M = ProfessionsModel

    private fun overview(sel: Int = 501, ench: Boolean = true, busy: Boolean = false): ProfessionsModel.Overview {
        val ctl = JSONObject().put("make:1", 0).put("make:2", 1).put("all", 26)
        if (ench) ctl.put("ench", 37)
        val p = JSONObject()
            .put("list", JSONArray().put(JSONObject().put("pos", 1).put("id", 333).put("name", "Enchanting").put("craft", true).put("main", true)))
            .put("open", 333).put("sel", sel).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put(M.ROW_CTL, 1).put(M.ROW_SEL, 2)))
        if (busy) p.put("busy", JSONObject().put("id", 502).put("n", 2).put("made", 0))
        return M.parseOverview(p)
    }

    private fun recipe(id: Int, ench: Boolean, cd: Int = 0, met: Boolean = true) = JSONObject().put("id", id).put("name", "r$id").put("cat", 1)
        .put("ench", ench).put("cd", cd).put("slot", id - 500)
        .put("reag", JSONArray().put(JSONObject().put("item", 10940).put("need", 1)))
        .put("req", JSONArray().apply { if (!met) put(JSONObject().put("name", "Runed Copper Rod").put("met", false)) })

    private fun book(vararg r: JSONObject) = M.parseBook(JSONObject().put("id", 333).put("name", "Enchanting").put("r", JSONArray(r.toList())))

    private val dust = mapOf(10940 to 4)

    @Test fun anEnchantIsCastOnceThenItsItemPicked() {
        val ov = overview(); val b = book(recipe(501, true))
        val prof = ov.list[0]
        val act = M.action(ov, prof, b, b.recipe(501), 3, dust)
        assertEquals(ProfessionsModel.Kind.ENCHANT, act.kind)
        assertTrue(act.why, act.enabled)
        assertNotNull(act.tap); assertEquals(M.ROW_CTL, act.tap!!.row); assertEquals(37, act.tap.col)
        // one at a time: no Create All for it
        val all = M.createAll(ov, prof, b, b.recipe(501), dust)
        assertEquals(ProfessionsModel.Kind.ENCHANT, all.kind); assertFalse(all.enabled); assertNull(all.tap)
        assertEquals("one item at a time", all.why)
    }

    @Test fun theSameChecksAsCreate() {
        val prof = overview().list[0]
        fun why(ov: ProfessionsModel.Overview, r: JSONObject, counts: Map<Int, Int> = dust): String? {
            val b = book(r)
            val a = M.action(ov, prof, b, b.recipe(r.getInt("id")), 1, counts)
            assertEquals(ProfessionsModel.Kind.ENCHANT, a.kind); assertFalse(a.enabled); assertNull(a.tap)
            return a.why
        }
        assertEquals("missing reagents", why(overview(), recipe(501, true), emptyMap()))
        assertEquals("cooldown", why(overview(), recipe(501, true, cd = 30)))
        assertEquals("requires Runed Copper Rod", why(overview(), recipe(501, true, met = false)))
        assertEquals("crafting", why(overview(busy = true), recipe(501, true)))
        assertEquals("waiting for the game", why(overview(sel = 502), recipe(501, true)))
        // an add-on before p27 (no `ench` key): said, never a dead button
        assertEquals("needs the add-on update", why(overview(ench = false), recipe(501, true)))
    }

    @Test fun aPlainRecipeIsUnchanged() {
        val ov = overview(sel = 502); val b = book(recipe(502, false))
        val act = M.action(ov, ov.list[0], b, b.recipe(502), 2, dust)
        assertEquals(ProfessionsModel.Kind.CREATE, act.kind); assertTrue(act.enabled); assertEquals(1, act.tap!!.col)
        val all = M.createAll(ov, ov.list[0], b, b.recipe(502), dust)
        assertEquals(ProfessionsModel.Kind.CREATE, all.kind); assertTrue(all.enabled); assertEquals(26, all.tap!!.col)
    }

    @Test fun backFromTheItemPickTheRoomKeepsTheEnchantsPage() {
        // review 11-22: the itarget-off return re-entered the room on its book (goTo(0)) while the game's window and the add-on's hold
        // stayed on the Enchanting page. Back from the pick, the page (and its recipe) is kept while the game still shows it
        val ov = overview()                                                       // the game's window open on Enchanting (333)
        assertTrue(M.keepsPickPage(true, 333, ov))
        assertFalse(M.keepsPickPage(false, 333, ov))                              // an ordinary entry: the book, as always
        assertFalse(M.keepsPickPage(true, 0, ov))                                 // the room was on its book
        assertFalse(M.keepsPickPage(true, 202, ov))                               // another profession than the one the game shows
        assertFalse(M.keepsPickPage(true, 333, null))
        val closed = M.parseOverview(JSONObject().put("list", JSONArray().put(JSONObject().put("pos", 1).put("id", 333).put("name", "Enchanting")))
            .put("ctl", JSONObject()))                                            // the game's window closed meanwhile (its own B)
        assertFalse(M.keepsPickPage(true, 333, closed))
    }
}
