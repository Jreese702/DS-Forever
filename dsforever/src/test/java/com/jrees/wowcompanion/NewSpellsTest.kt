package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-new-spell-prompt (the user: "After learning new spells when I return home they should have a prompt 1 at a time a to bind or b to
 *  dismiss"): which learned spells are asked about, in what order, never twice */
class NewSpellsTest {
    private fun e(slot: Int, id: Int, name: String, rank: String? = null, passive: Boolean = false, bars: List<Int> = emptyList(),
                  pv: Int? = slot, line: Int = 2, prof: String? = null) =
        SpellsModel.Entry(slot = slot, pet = false, kind = SpellsModel.Kind.SPELL, actionId = id, spellId = id, name = name, rank = rank,
            icon = 100 + id, passive = passive, lowRank = false, flyoutMember = false, levelLearned = null, line = line, desc = null,
            castMs = 1500, rangeMin = null, rangeMax = null, cost = emptyList(), bars = bars, pad = emptyList(), autoCastAllowed = false,
            autoCastOn = false, padColumn = null, prof = prof, padNumber = pv)

    private fun book(vararg items: SpellsModel.Entry) = SpellsModel.Book("s", 20, false, true, false,
        listOf(SpellsModel.Line(1, "General", 0, false), SpellsModel.Line(2, "Elemental", 0, false)), items.toList(),
        null, 0, false, emptyList(), emptyList(), emptyList(), null, 0, 0)

    private val bolt1 = e(1, 403, "Lightning Bolt", "Rank 1", bars = listOf(1))
    private val bolt2 = e(2, 529, "Lightning Bolt", "Rank 2")
    private val shock = e(3, 8042, "Earth Shock", "Rank 1")
    private val passive = e(4, 9000, "Ancestral Knowledge", passive = true)
    private val totem = e(5, 8071, "Stoneskin Totem", "Rank 1")

    @Test fun aNewCastableSpellIsAsked() = assertTrue(NewSpells.worthAsking(book(bolt1, shock), 8042, emptySet()))
    @Test fun aPassiveIsNot() = assertFalse(NewSpells.worthAsking(book(passive), 9000, emptySet()))
    @Test fun aNewRankOfABoundSpellIsNot() = assertFalse(NewSpells.worthAsking(book(bolt1, bolt2), 529, emptySet()))
    @Test fun aNewRankOfAnUnboundSpellIs() =
        assertTrue(NewSpells.worthAsking(book(bolt1.copy(bars = emptyList()), bolt2), 529, emptySet()))
    @Test fun aSpellInTheCompanionsSetsIsNot() = assertFalse(NewSpells.worthAsking(book(shock), 8042, setOf(8042)))
    @Test fun aSpellWithoutAPadNumberCannotBeBound() = assertFalse(NewSpells.worthAsking(book(shock.copy(padNumber = null)), 8042, emptySet()))
    @Test fun anUnknownSpellIsNot() = assertFalse(NewSpells.worthAsking(book(shock), 1234, emptySet()))

    @Test fun theQueueKeepsLearningOrderAndEachOnce() {
        val q = NewSpells.enqueue(emptyList(), emptySet(), listOf(8042, 8071, 8042)) { true }
        assertEquals(listOf(8042, 8071), q)
    }
    @Test fun aHandledSpellNeverComesBack() =
        assertEquals(listOf(8071), NewSpells.enqueue(emptyList(), setOf(8042), listOf(8042, 8071)) { true })
    @Test fun theUnworthyAreLeftOut() =
        assertEquals(listOf(8071), NewSpells.enqueue(emptyList(), emptySet(), listOf(9000, 8071)) { it != 9000 })
    @Test fun aSpellBoundMeanwhileDropsOutUnasked() =
        assertEquals(listOf(8071), NewSpells.stillWanted(listOf(8042, 8071)) { it == 8071 })

    @Test fun theBindFlowBindsTheControllerListsEntry() {
        val b = book(bolt1.copy(bars = emptyList()), bolt2, shock)
        assertEquals("Earth Shock", NewSpells.bindEntry(b, 8042)?.name)
    }

    @Test fun whatThePadmapShowsCountsAsBound() {
        val slots = listOf(PadMapModel.Slot(1, 1, PadMapModel.Kind.SPELL, 403, "Lightning Bolt", 0),
            PadMapModel.Slot(5, null, PadMapModel.Kind.FIXED, null, "Interact", 0, fight = PadMapModel.Ref(8042, "Earth Shock", 0)))
        val m = PadMapModel.Map("m55", 1, 1, false, listOf(PadMapModel.PadSet(1, slots)), null, null, emptyList())
        assertEquals(setOf(403, 8042), NewSpells.onPad(m))
    }
}
