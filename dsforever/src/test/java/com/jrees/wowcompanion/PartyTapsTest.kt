package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyTaps.PbOp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** every action's cells from partycfg.pad (PARTY_BUILD.md 2.2-2.4); null when a column is missing; Add / PB ops ONE batch */
class PartyTapsTest {
    private val cfg = PartyModelTest.cfg()
    private val book = listOf("spells" to 3, "spells" to 9)

    @Test fun cellsFromThePad() {
        // m129: a plate's tap is always its pick cell (the add-on's snippet: off Target, another member the pick, the picked one Touch)
        assertEquals(listOf("party" to 2), PartyTaps.choose(cfg, 2))
        val spellTouch = cfg.copy(touch = PartyModel.Spell(331, "Healing Wave", 136052))
        assertEquals(listOf("party" to 2), PartyTaps.choose(spellTouch, 2))               // the Touch spell rides the pick button
        assertEquals(listOf("party" to 10), PartyTaps.on(cfg))
        assertEquals(listOf("party" to 11), PartyTaps.off(cfg))
        assertEquals(listOf("party" to 21), PartyTaps.menu(cfg, 4))
        assertEquals(listOf("party" to 12), PartyTaps.focus(cfg, 0))
        assertEquals(listOf("party" to 22), PartyTaps.resend(cfg))
        assertEquals(listOf("party" to 35), PartyTaps.menuDone(cfg))
        // m131: the pet's pick cell is pick + 5; no player menu / focus cell for it
        assertEquals(listOf("party" to 5), PartyTaps.choose(cfg, 5)); assertNull(PartyTaps.choose(cfg, 6))
        assertNull(PartyTaps.menu(cfg, 5)); assertNull(PartyTaps.focus(cfg, 5)); assertNull(PartyTaps.menu(cfg, -1))
    }

    /** PadMap m157 (the user on 1462: "pressing B in a player inspect radial opened from party mode does not close the radial"; "holding
     *  on the pet bar does not open the pet inspect"): a hold's ONE batch = MENU HOLD first, then the menu read; the pet's own menu cell */
    @Test fun aHoldIsMenuHoldThenTheRead() {
        val c = cfg.copy(pad = cfg.pad.copy(mhold = 6, pmenu = 7))
        assertEquals(listOf("party" to 6, "party" to 19) to true, PartyTaps.hold(c, 2))
        assertEquals(listOf("party" to 6, "party" to 17) to true, PartyTaps.hold(c, 0))
        assertEquals(listOf("party" to 7), PartyTaps.menu(c, PartyModel.PET_I))
        assertEquals(listOf("party" to 6, "party" to 7) to true, PartyTaps.hold(c, PartyModel.PET_I))
        assertNull(PartyTaps.hold(c, 6)); assertNull(PartyTaps.hold(c, -1))
        // an older add-on: no MENU HOLD = the read alone (the wheel reads the pad itself); no pet menu cell = no pet hold
        assertEquals(listOf("party" to 18) to false, PartyTaps.hold(cfg, 1))
        assertNull(PartyTaps.hold(cfg, PartyModel.PET_I))
        assertNull(PartyTaps.hold(null, 1))
    }

    @Test fun smartYPartyListOpsAreOneBatch() {
        assertEquals(listOf("party" to 23, "party" to 31), PartyTaps.pbOp(cfg, 1, PbOp.REMOVE))
        assertEquals(listOf("party" to 30, "party" to 32), PartyTaps.pbOp(cfg, 8, PbOp.UP))
        assertEquals(listOf("party" to 24, "party" to 33), PartyTaps.pbOp(cfg, 2, PbOp.DOWN))
        assertNull(PartyTaps.pbOp(cfg, 9, PbOp.DOWN))
        assertEquals(listOf("party" to 34) + book, PartyTaps.pbAdd(cfg, book))
        assertNull(PartyTaps.pbAdd(cfg, null))
    }

    @Test fun controllerTabByTouch() {
        assertEquals(listOf("padtab" to 26), PartyTaps.tab(cfg))
        assertEquals(listOf("padtab" to 27), PartyTaps.pick(cfg, "a"))
        assertEquals(listOf("padtab" to 29), PartyTaps.pick(cfg, "yc"))
        assertEquals(listOf("padtab" to 30), PartyTaps.pick(cfg, "touch"))
        assertEquals(listOf("padtab" to 36), PartyTaps.pick(cfg, "zry"))
        assertEquals(listOf("padtab" to 29, "padtab" to 37), PartyTaps.setNone(cfg, "yc"))
        assertEquals(listOf("padtab" to 28) + book, PartyTaps.setSpell(cfg, "x", 331, book))
        assertNull(PartyTaps.setSpell(cfg, "x", 999, book))                                // not in partycfg.spells
        assertNull(PartyTaps.pick(cfg, "b"))
    }

    @Test fun aMissingColumnMeansUnavailable() {
        assertNull(PartyTaps.choose(null, 0))
        val bare = PartyModel.parseCfg(JSONObject("""{"type":"partycfg","c":{"pad":{"row":"party","pick":0,"on":10}}}"""))!!
        assertEquals(listOf("party" to 1), PartyTaps.choose(bare, 1))
        assertEquals(listOf("party" to 10), PartyTaps.on(bare))
        assertNull(PartyTaps.off(bare)); assertNull(PartyTaps.menu(bare, 1)); assertNull(PartyTaps.resend(bare))
        assertNull(PartyTaps.pbOp(bare, 1, PbOp.REMOVE)); assertNull(PartyTaps.pbAdd(bare, book)); assertNull(PartyTaps.tab(bare))
        assertNull(PartyTaps.setNone(bare, "a"))
        val noRow = PartyModel.parseCfg(JSONObject("""{"type":"partycfg","c":{"pad":{"pick":0}}}"""))!!
        assertNull(PartyTaps.choose(noRow, 0))
        // an older add-on's target / touch-spell columns are not read (no pick column: no tap)
        assertNull(PartyTaps.choose(PartyModel.parseCfg(JSONObject("""{"type":"partycfg","c":{"pad":{"row":"party","tgt":0,"tsp":5}}}""")), 0))
    }
}
