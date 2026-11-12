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

    // ---------------------------------------------------------------- rank up (the user 2026-11-12: "Prompt, A upgrades all", "Skip this rank")
    private fun rbook(vararg items: SpellsModel.Entry, rk: List<SpellsModel.RankUp> = emptyList(), win: Map<String, Int> = WIN) =
        book(*items).copy(rankUps = rk, win = win)
    private val WIN = (0 until 13).associate { "vb$it" to it } + (0 until 13).associate { "vo$it" to 13 + it }
    private val bolt3 = e(6, 548, "Lightning Bolt", "Rank 3", pv = 20)

    @Test fun aNewRankWithLowerCopiesOnButtonsIsARankUp() {
        val b = rbook(bolt1, bolt2.copy(bars = listOf(2)), bolt3, rk = listOf(SpellsModel.RankUp(548, 3, 0)))
        assertEquals(NewSpells.Ask.RANK_UP, NewSpells.askKind(b, 548, emptySet()))
        assertEquals(3, NewSpells.rankUpOf(b, 548)?.count)
    }
    @Test fun aNewRankTheAddOnDoesNotListIsNotAsked() =     // every lower copy picked on purpose ("Never touch those"): no `rk`
        assertEquals(null, NewSpells.askKind(rbook(bolt1, bolt3), 548, emptySet()))
    @Test fun aNewSpellOnNoButtonIsStillABind() =
        assertEquals(NewSpells.Ask.BIND, NewSpells.askKind(rbook(shock), 8042, emptySet()))
    @Test fun aRankUpJoinsTheSameQueueInLearningOrder() {
        val b = rbook(bolt1, bolt3, shock, rk = listOf(SpellsModel.RankUp(548, 1, 0)))
        val q = NewSpells.enqueue(emptyList(), emptySet(), listOf(548, 8042)) { NewSpells.askKind(b, it, emptySet()) != null }
        assertEquals(listOf(548, 8042), q)
    }
    @Test fun aSkippedRankIsNeverAskedAgainButTheNextRankIs() {
        val handled = setOf(548)                                  // B on Rank 3 ("Skip this rank": done = handled, per character)
        val b = rbook(bolt1, bolt3, e(7, 915, "Lightning Bolt", "Rank 4", pv = 21), rk = listOf(SpellsModel.RankUp(548, 1, 0), SpellsModel.RankUp(915, 1, 0)))
        val q = NewSpells.enqueue(emptyList(), handled, listOf(548, 915)) { NewSpells.askKind(b, it, emptySet()) != null }
        assertEquals(listOf(915), q)
    }
    @Test fun anUpgradedRankDropsOutUnasked() {
        val b = rbook(bolt1, bolt3)                               // the add-on no longer lists it (upgraded meanwhile)
        assertEquals(emptyList<Int>(), NewSpells.stillWanted(listOf(548)) { NewSpells.askKind(b, it, emptySet()) != null })
    }
    @Test fun theRankUpTapsArePadtab38ThenTheSpellsTwoTaps() {
        val b = rbook(bolt1, bolt3, rk = listOf(SpellsModel.RankUp(548, 1, 0)))
        assertEquals(listOf("padtab" to 38, "spells" to 1, "spells" to 13 + 7), NewSpells.rankUpCells(b, 548))
        assertEquals(null, NewSpells.rankUpCells(rbook(bolt1, bolt3.copy(padNumber = null), win = emptyMap()), 548))
    }
    @Test fun theWords() {
        assertEquals("Lightning Bolt (Rank 3): upgrade on your buttons?", NewSpells.rankUpTitle(bolt3))
        assertEquals("Upgraded on 3 buttons", NewSpells.rankUpLine(3))
        assertEquals("Upgraded on 1 button", NewSpells.rankUpLine(1))
        assertEquals("Lower rank on 2 buttons", NewSpells.rankUpCount(2))
    }
    @Test fun theFeedsRankUps() {
        val msg = org.json.JSONObject("""{"type":"spells","v":"s8","s":{"lines":[],"items":[],"news":[{"id":548,"nid":3}],
            "rk":[{"id":548,"n":3,"kept":1},{"id":0,"n":2},{"id":999,"n":0}]}}""")
        assertEquals(listOf(SpellsModel.RankUp(548, 3, 1)), SpellsModel.parse(msg)?.rankUps)
        assertEquals(emptyList<SpellsModel.RankUp>(), SpellsModel.parse(org.json.JSONObject("""{"type":"spells","s":{}}"""))?.rankUps)
    }
    @Test fun theWordsAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            Tr.load(l, java.io.File(dir, "$l.json").readText())
            for (w in listOf("Upgrade", "Skip", "Upgraded on 1 button", "Upgraded on 3 buttons", "Lower rank on 2 buttons", "Lower rank on 1 button",
                    "Lightning Bolt (Rank 3): upgrade on your buttons?", "Not while in combat", "That did not work"))
                assertTrue("$l: $w", Tr.t(w, l) != w)
            assertTrue(Tr.t("Lightning Bolt (Rank 3): upgrade on your buttons?", l).startsWith("Lightning Bolt (Rank 3)"))
        }
    }

    /** the user 2026-11-12: "the pop up toasts that appear before that are redundant and should be scrapped" - the pick: ONLY the 4 s
     *  "New spell" card; the Bind box's "Bound to", "Put back" and refusal lines stay */
    @Test fun theNewSpellCardIsGone() {
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { java.io.File(it) }.first { it.isDirectory }
        val ov = java.io.File(src, "SpellsOverlay.kt").readText()
        assertFalse(ov.contains("Tr.t(\"New spell\")"))
        assertFalse(ov.contains("CARD_MS") || ov.contains("cardSpell") || ov.contains("onOpen"))
        assertTrue(ov.contains("\"Bound to\"") && ov.contains("Put back what was there") && ov.contains("Not while in combat"))
        assertFalse(java.io.File(src, "CompanionHost.kt").readText().contains("spellbookView.openAt(id) },"))   // the card's tap is gone
    }

    @Test fun whatThePadmapShowsCountsAsBound() {
        val slots = listOf(PadMapModel.Slot(1, 1, PadMapModel.Kind.SPELL, 403, "Lightning Bolt", 0),
            PadMapModel.Slot(5, null, PadMapModel.Kind.FIXED, null, "Interact", 0, fight = PadMapModel.Ref(8042, "Earth Shock", 0)))
        val m = PadMapModel.Map("m55", 1, 1, false, listOf(PadMapModel.PadSet(1, slots)), null, null, emptyList())
        assertEquals(setOf(403, 8042), NewSpells.onPad(m))
    }
}
