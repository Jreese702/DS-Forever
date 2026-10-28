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
        assertEquals(listOf("party" to 2), PartyTaps.touch(cfg, 2))                       // Touch = Target
        val spellTouch = cfg.copy(touch = PartyModel.Spell(331, "Healing Wave", 136052))
        assertEquals(listOf("party" to 7), PartyTaps.touch(spellTouch, 2))                // Touch = a spell
        assertEquals(listOf("party" to 10), PartyTaps.on(cfg))
        assertEquals(listOf("party" to 11), PartyTaps.off(cfg))
        assertEquals(listOf("party" to 21), PartyTaps.menu(cfg, 4))
        assertEquals(listOf("party" to 12), PartyTaps.focus(cfg, 0))
        assertEquals(listOf("party" to 22), PartyTaps.resend(cfg))
        assertEquals(listOf("party" to 35), PartyTaps.menuDone(cfg))
        assertNull(PartyTaps.touch(cfg, 5)); assertNull(PartyTaps.menu(cfg, -1))
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
        assertNull(PartyTaps.touch(null, 0))
        val bare = PartyModel.parseCfg(JSONObject("""{"type":"partycfg","c":{"pad":{"row":"party","tgt":0,"on":10}}}"""))!!
        assertEquals(listOf("party" to 1), PartyTaps.touch(bare, 1))
        assertEquals(listOf("party" to 10), PartyTaps.on(bare))
        assertNull(PartyTaps.off(bare)); assertNull(PartyTaps.menu(bare, 1)); assertNull(PartyTaps.resend(bare))
        assertNull(PartyTaps.pbOp(bare, 1, PbOp.REMOVE)); assertNull(PartyTaps.pbAdd(bare, book)); assertNull(PartyTaps.tab(bare))
        assertNull(PartyTaps.setNone(bare, "a"))
        val noRow = PartyModel.parseCfg(JSONObject("""{"type":"partycfg","c":{"pad":{"tgt":0}}}"""))!!
        assertNull(PartyTaps.touch(noRow, 0))
    }
}
