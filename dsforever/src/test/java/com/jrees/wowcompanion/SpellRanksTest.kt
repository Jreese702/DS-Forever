package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #471 ("I cannot see which rank my spells are when assigning them to my crossbars"): which spells wear a rank badge, and when it is grey */
class SpellRanksTest {
    @After fun clear() = SpellRanks.update(null)

    private fun e(slot: Int, id: Int, name: String, rank: String? = null, passive: Boolean = false, line: Int = 2, prof: String? = null,
                  kind: SpellsModel.Kind = SpellsModel.Kind.SPELL, pet: Boolean = false, low: Boolean = false) =
        SpellsModel.Entry(slot = slot, pet = pet, kind = kind, actionId = id, spellId = id, name = name, rank = rank,
            icon = 100 + id, passive = passive, lowRank = low, flyoutMember = false, levelLearned = null, line = line, desc = null,
            castMs = 1500, rangeMin = null, rangeMax = null, cost = emptyList(), bars = emptyList(), pad = emptyList(), autoCastAllowed = false,
            autoCastOn = false, padColumn = null, prof = prof)

    private fun book(vararg items: SpellsModel.Entry) = SpellsModel.Book("s", 20, false, true, false,
        listOf(SpellsModel.Line(1, "General", 0, false), SpellsModel.Line(2, "Elemental", 0, false)), items.toList(),
        null, 0, false, emptyList(), emptyList(), emptyList(), null, 0, 0)

    private val bolt1 = e(1, 403, "Lightning Bolt", "Rank 1", low = true)
    private val bolt2 = e(2, 529, "Lightning Bolt", "Rank 2", low = true)
    private val bolt3 = e(3, 548, "Lightning Bolt", "Rank 3")

    @Test fun eachRankOfAFamilyHasItsNumberAndOnlyTheHighestIsTop() {
        SpellRanks.update(book(bolt1, bolt2, bolt3))
        assertEquals(SpellRanks.Badge(1, false), SpellRanks.of(403))
        assertEquals(SpellRanks.Badge(2, false), SpellRanks.of(529))
        assertEquals(SpellRanks.Badge(3, true), SpellRanks.of(548))
        assertEquals("R3", SpellRanks.of(548)!!.text)
    }

    @Test fun aLoneRankHasNoBadge() {
        SpellRanks.update(book(e(1, 8042, "Earth Shock", "Rank 1")))
        assertNull(SpellRanks.of(8042))
    }

    @Test fun aRanklessSpellHasNoBadge() {
        // "Racial" and no sub-name at all: no number, no badge - even with a namesake
        SpellRanks.update(book(e(1, 20549, "War Stomp", "Racial"), e(2, 20550, "War Stomp", "Racial"), e(3, 2645, "Ghost Wolf")))
        assertNull(SpellRanks.of(20549)); assertNull(SpellRanks.of(20550)); assertNull(SpellRanks.of(2645))
    }

    @Test fun passivesProfessionsAndUnlearnedSpellsHaveNone() {
        SpellRanks.update(book(
            e(1, 16176, "Ancestral Healing", "Rank 1", passive = true), e(2, 16235, "Ancestral Healing", "Rank 2", passive = true),
            e(3, 3273, "First Aid", "Apprentice", prof = "First Aid"),
            bolt3, e(4, 915, "Lightning Bolt", "Rank 4", kind = SpellsModel.Kind.FUTURE)))
        assertNull(SpellRanks.of(16176)); assertNull(SpellRanks.of(16235)); assertNull(SpellRanks.of(3273))
        assertNull("a rank not learned yet makes no family", SpellRanks.of(548))
    }

    @Test fun theSameNameOnAnotherLineIsAnotherFamily() {
        SpellRanks.update(book(e(1, 100, "Strike", "Rank 1", line = 1), e(2, 200, "Strike", "Rank 2", line = 2)))
        assertNull(SpellRanks.of(100)); assertNull(SpellRanks.of(200))
    }

    @Test fun aPetsRanksGroupAmongThePets() {
        SpellRanks.update(book(e(1, 3110, "Firebolt", "Rank 1", pet = true), e(2, 7799, "Firebolt", "Rank 2", pet = true),
            e(3, 133, "Firebolt", "Rank 9")))
        assertEquals(SpellRanks.Badge(1, false), SpellRanks.of(3110))
        assertEquals(SpellRanks.Badge(2, true), SpellRanks.of(7799))
        assertNull("the player's own namesake is alone", SpellRanks.of(133))
    }

    @Test fun aNewBookReplacesTheOldAndNoBookClearsIt() {
        SpellRanks.update(book(bolt1, bolt2))
        assertTrue(SpellRanks.of(529)!!.top)
        SpellRanks.update(book(bolt1, bolt2, bolt3))                         // Rank 3 learned: Rank 2 is no longer the top
        assertFalse(SpellRanks.of(529)!!.top)
        SpellRanks.update(null)
        assertNull(SpellRanks.of(529))
        assertNull(SpellRanks.of(null)); assertNull(SpellRanks.of(0))
    }

    /** the rank is the add-on's plain `sub` (Spells.lua readItem: subName, else GetSpellSubtext), in any language with a number */
    @Test fun theRankComesFromTheSpellsDocumentsSub() {
        val items = JSONArray()
            .put(JSONObject().put("sl", 1).put("t", 1).put("a", 403).put("id", 403).put("n", "Lightning Bolt").put("sub", "Rango 1").put("ln", 2).put("low", true))
            .put(JSONObject().put("sl", 2).put("t", 1).put("a", 529).put("id", 529).put("n", "Lightning Bolt").put("sub", "Rango 2").put("ln", 2))
        val doc = JSONObject().put("type", "spells").put("v", "s17").put("s", JSONObject().put("lvl", 8)
            .put("lines", JSONArray().put(JSONObject().put("k", 2).put("n", "Elemental")))
            .put("items", items))
        val b = SpellsModel.parse(doc)!!
        assertEquals(2, b.items.size)
        SpellRanks.update(b)
        assertEquals(SpellRanks.Badge(1, false), SpellRanks.of(403))
        assertEquals(SpellRanks.Badge(2, true), SpellRanks.of(529))
    }
}
