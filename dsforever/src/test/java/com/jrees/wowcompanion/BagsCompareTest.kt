package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bags-act1 (the user's picks, 2026-11-13: "Swap deltas: keep them in the hover tooltip and the Compare sheet; a two-hander compares vs
 * main hand + off hand together; empty slot shows all gains via app-side diff of row.stats vs worn stats when the game gives no delta").
 */
class BagsCompareTest {
    private fun stat(k: String, n: String, v: Int) = JSONObject().put("k", k).put("n", n).put("v", v)
    private fun stats(vararg s: JSONObject) = JSONArray().also { a -> s.forEach { a.put(it) } }
    private fun line(t: String, c: String? = null) = JSONObject().put("t", t).also { if (c != null) it.put("c", c) }
    private fun row(equip: String, st: JSONArray) = JSONObject().put("bag", 0).put("slot", 1).put("id", 500).put("equip", equip).put("stats", st)
    private fun worn(slot: Int, id: Int?, name: String, st: JSONArray? = null, item: String? = null) = JSONObject().put("slot", slot).put("name", name)
        .also { o -> if (id != null) o.put("id", id); if (st != null) o.put("stats", st); if (item != null) o.put("item", item) }
    private fun texts(card: JSONObject) = card.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("t") } }
    private fun colours(card: JSONObject) = card.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("c") } }
    private val STA = "ITEM_MOD_STAMINA_SHORT"; private val STR = "ITEM_MOD_STRENGTH_SHORT"; private val ARM = "RESISTANCE0_NAME"

    @Test fun theAppsOwnCountIsBagLessWorn() {
        val d = BagsCompare.diff(stats(stat(STA, "Stamina", 5), stat(STR, "Strength", 2)),
            listOf(stats(stat(STA, "Stamina", 3), stat(STR, "Strength", 2), stat(ARM, "Armor", 40))))
        assertEquals(listOf("+2 Stamina", "-40 Armor"), d.map { it.getString("t") })
        assertEquals(listOf(BagsCompare.GAIN, BagsCompare.LOSS), d.map { it.getString("c") })
    }

    @Test fun anEmptySlotShowsAllGains() {
        val r = row("INVTYPE_HEAD", stats(stat(STA, "Stamina", 4), stat(ARM, "Armor", 30)))
        val cards = BagsCompare.cards(JSONObject().put("key", "bag 0 1 500"), r, mapOf(1 to worn(1, null, "Head")), false)
        assertEquals(1, cards.length())
        val c = cards.getJSONObject(0)
        assertTrue(c.optBoolean("empty"))
        assertEquals(listOf("Head", "Empty", " ", "Stat changes", "+4 Stamina", "+30 Armor"), texts(c))
        assertEquals(2, c.optInt("mine"))
    }

    @Test fun theGamesChangesAreKeptAsTheyCame() {
        val r = row("INVTYPE_CHEST", stats(stat(STA, "Stamina", 9)))
        val game = JSONObject().put("slot", 5).put("dn", 1)
            .put("lines", JSONArray().put(line("Old Shirt")).put(line(" ")).put(line("If you replace ...", "ffd100")).put(line("+9 Stamina", "00ff00")))
        val tip = JSONObject().put("cmp", JSONArray().put(game))
        val cards = BagsCompare.cards(tip, r, mapOf(5 to worn(5, 900, "Chest", stats(stat(STA, "Stamina", 1)))), false)
        assertEquals(1, cards.length())
        assertEquals(listOf("Old Shirt", " ", "If you replace ...", "+9 Stamina"), texts(cards.getJSONObject(0)))
        assertFalse(cards.getJSONObject(0).has("mine"))
    }

    @Test fun noChangesFromTheGameThenTheAppCounts() {
        val r = row("INVTYPE_CHEST", stats(stat(STA, "Stamina", 9)))
        val game = JSONObject().put("slot", 5).put("lines", JSONArray().put(line("Old Shirt")))
        val cards = BagsCompare.cards(JSONObject().put("cmp", JSONArray().put(game)), r,
            mapOf(5 to worn(5, 900, "Chest", stats(stat(STA, "Stamina", 4)))), false)
        assertEquals(listOf("Old Shirt", " ", "Stat changes", "+5 Stamina"), texts(cards.getJSONObject(0)))
    }

    @Test fun ringsShowBothAndAnEmptyOne() {
        val r = row("INVTYPE_FINGER", stats(stat(STA, "Stamina", 3)))
        val game = JSONObject().put("slot", 11).put("dn", 1)
            .put("lines", JSONArray().put(line("Band")).put(line(" ")).put(line("If", "ffd100")).put(line("+1 Stamina", "00ff00")))
        val cards = BagsCompare.cards(JSONObject().put("cmp", JSONArray().put(game)), r,
            mapOf(11 to worn(11, 70, "Finger 1", stats(stat(STA, "Stamina", 2))), 12 to worn(12, null, "Finger 2")), false)
        assertEquals(2, cards.length())
        assertEquals(11, cards.getJSONObject(0).getInt("slot"))
        assertEquals(listOf("Finger 2", "Empty", " ", "Stat changes", "+3 Stamina"), texts(cards.getJSONObject(1)))
    }

    @Test fun aTwoHanderCountsAgainstBothHands() {
        val r = row("INVTYPE_2HWEAPON", stats(stat(STR, "Strength", 10)))
        val wornM = worn(16, 801, "Main Hand", stats(stat(STR, "Strength", 4)), "Sword")
        val wornO = worn(17, 802, "Off Hand", stats(stat(STR, "Strength", 3), stat(ARM, "Armor", 200)), "Shield")
        // the game gave its two cards without any changes (a build without the paired comparison): the app counts against the pair
        val m = JSONObject().put("slot", 16).put("with", 17).put("lines", JSONArray().put(line("Sword")))
        val o = JSONObject().put("slot", 17).put("pair", 16).put("lines", JSONArray().put(line("Shield")))
        val cards = BagsCompare.cards(JSONObject().put("cmp", JSONArray().put(m).put(o)), r, mapOf(16 to wornM, 17 to wornO), false)
        assertEquals(2, cards.length())
        assertEquals(listOf("Sword", " ", "Stat changes", "+3 Strength", "-200 Armor"), texts(cards.getJSONObject(0)))
        assertEquals(listOf(BagsCompare.GAIN, BagsCompare.LOSS), colours(cards.getJSONObject(0)).takeLast(2))
        assertEquals(16, cards.getJSONObject(1).getInt("pair"))
        assertEquals(listOf("Shield"), texts(cards.getJSONObject(1)))       // no changes of its own: they are under the main hand
    }

    @Test fun aTwoHanderFromAnOlderInteractStillCountsThePair() {
        // an Interact before i157: the main hand only, its tooltip without changes; the off hand from the worn row's own tooltip
        val r = row("INVTYPE_2HWEAPON", stats(stat(STR, "Strength", 10)))
        val m = JSONObject().put("slot", 16).put("lines", JSONArray().put(line("Sword")))
        val wornO = worn(17, 802, "Off Hand", stats(stat(STR, "Strength", 3)), "Shield")
            .put("tip", JSONArray().put(line("Shield")).put(line("200 Armor")))
        val cards = BagsCompare.cards(JSONObject().put("cmp", JSONArray().put(m)), r,
            mapOf(16 to worn(16, 801, "Main Hand", stats(stat(STR, "Strength", 4))), 17 to wornO), false)
        assertEquals(listOf("Sword", " ", "Stat changes", "+3 Strength"), texts(cards.getJSONObject(0)))
        assertEquals(listOf("Shield", "200 Armor"), texts(cards.getJSONObject(1)))
        assertEquals(16, cards.getJSONObject(1).getInt("pair"))
    }

    @Test fun aOneHanderOffHandOnlyForADualWielder() {
        val r = row("INVTYPE_WEAPON", stats(stat(STR, "Strength", 2)))
        val w = mapOf(16 to worn(16, 801, "Main Hand", stats(stat(STR, "Strength", 1))), 17 to worn(17, null, "Off Hand"))
        assertEquals(listOf(16), BagsCompare.cards(JSONObject(), r, w, false).let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("slot") } })
        assertEquals(listOf(16, 17), BagsCompare.cards(JSONObject(), r, w, true).let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("slot") } })
    }

    @Test fun anOffHandAgainstAWornTwoHanderFollowsTheGame() {
        // Interact compared the shield with the worn two-hander (slot 16), the off hand being empty: the game's slot, no empty card
        val r = row("INVTYPE_SHIELD", stats(stat(ARM, "Armor", 300)))
        val game = JSONObject().put("slot", 16).put("lines", JSONArray().put(line("Greatsword")))
        val cards = BagsCompare.cards(JSONObject().put("cmp", JSONArray().put(game)), r,
            mapOf(16 to worn(16, 801, "Main Hand", stats(stat(STR, "Strength", 9))), 17 to worn(17, null, "Off Hand")), false)
        assertEquals(1, cards.length())
        assertEquals(listOf("Greatsword", " ", "Stat changes", "+300 Armor", "-9 Strength"), texts(cards.getJSONObject(0)))
    }

    @Test fun notGearIsTheGamesCompareOrNothing() {
        val r = JSONObject().put("bag", 0).put("slot", 2).put("id", 117)
        assertEquals(0, BagsCompare.cards(JSONObject(), r, emptyMap(), false).length())
        assertEquals(0, BagsCompare.cards(JSONObject(), row("INVTYPE_BAG", JSONArray()), emptyMap(), false).length())
    }
}
