package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE ITEM COOLDOWNS (Spells' Radial.lua r20 `itemcd`, the user's picks 2026-11-13, feat/item-cooldowns), off-device: the app's count
 * (ItemCd) from the add-on's event-driven document, the fight's "keep" rule, the held potion's dim, the Hearthstone's end time kept per
 * character, the class teleport; the count rule on the wheels (RadialModel.countShown) and the spell radial's learned / dim words (q4).
 */
class ItemCooldownsTest {
    private val kept = HashMap<String, String>()
    private var wallNow = 1_700_000_000_000L

    @Before fun setUp() {
        ItemCd.reset(); RadialModel.Cd.reset(); kept.clear()
        ItemCd.store = object : ItemCd.Store {
            override fun get(key: String) = kept[key]
            override fun put(key: String, v: String?) { if (v == null) kept.remove(key) else kept[key] = v }
        }
        ItemCd.wall = { wallNow }
    }
    @After fun clean() { ItemCd.reset(); ItemCd.store = null; ItemCd.wall = { System.currentTimeMillis() }; RadialModel.Cd.reset() }

    private fun doc(json: String) = JSONObject(json)

    @Test fun aDocumentCountsDownOnTheAppsClock() {
        assertTrue(ItemCd.onDoc(doc("""{"type":"itemcd","v":"r20","t":{"6948":{"d":1800,"l":600},"5512":{"d":120,"l":30.5}}}"""), now = 0L))
        assertEquals(600.0, ItemCd.left(6948, now = 0L), 1e-6)
        assertEquals(1800.0, ItemCd.len(6948, now = 0L), 1e-6)
        assertEquals(20.5, ItemCd.left(5512, now = 10_000L), 1e-6)
        assertEquals(0.0, ItemCd.left(5512, now = 31_000L), 1e-6)          // run out: ready, forgotten
        assertEquals(0.0, ItemCd.len(5512, now = 31_000L), 1e-6)
        assertTrue(ItemCd.anyRunning(now = 31_000L))                         // the Hearthstone still counts
    }

    @Test fun outOfAFightNotSaidIsReadyInAFightItIsKept() {
        ItemCd.onDoc(doc("""{"type":"itemcd","t":{"5512":{"d":120,"l":60}}}"""), now = 0L)
        // a fight starts: the add-on's doc does not carry 5512 (a secret read): the countdown started before the fight keeps running
        ItemCd.onDoc(doc("""{"type":"itemcd","fight":true}"""), now = 1_000L)
        assertEquals(59.0, ItemCd.left(5512, now = 1_000L), 1e-6)
        // the fight ends with a plain read that says nothing: ready
        ItemCd.onDoc(doc("""{"type":"itemcd"}"""), now = 2_000L)
        assertEquals(0.0, ItemCd.left(5512, now = 2_000L), 1e-6)
    }

    @Test fun aHeldPotionAndAnUnknownLengthAreDimWithNoNumber() {
        ItemCd.onDoc(doc("""{"type":"itemcd","fight":true,"t":{"118":{"h":true},"929":{"x":true}}}"""), now = 0L)
        assertTrue(ItemCd.dim(118)); assertTrue(ItemCd.dim(929))
        assertEquals(0.0, ItemCd.left(118, now = 0L), 1e-6)                // no number
        assertFalse(ItemCd.anyRunning(now = 0L))                             // a dim needs no redraw
        ItemCd.onDoc(doc("""{"type":"itemcd","t":{"118":{"d":120,"l":120}}}"""), now = 5_000L)   // the fight's end: the real one
        assertFalse(ItemCd.dim(118)); assertFalse(ItemCd.dim(929))
        assertEquals(120.0, ItemCd.left(118, now = 5_000L), 1e-6)
    }

    @Test fun aReplayedDocumentIsNotTrusted() {
        assertTrue(ItemCd.onDoc(doc("""{"type":"itemcd","t":{"6948":{"d":1800,"l":900}}}"""), current = false, now = 0L))
        assertEquals(0.0, ItemCd.left(6948, now = 0L), 1e-6)
        assertFalse(ItemCd.onDoc(doc("""{"type":"radial"}""")))
    }

    @Test fun theHearthstonesEndTimeIsKeptPerCharacter() {
        ItemCd.character("Korvash-Realm", now = 0L)
        ItemCd.onDoc(doc("""{"type":"itemcd","t":{"6948":{"d":1800,"l":600}},"hs":{"have":true,"i":134414}}"""), now = 0L)
        assertTrue(kept["hs:Korvash-Realm"]!!.startsWith("${wallNow + 600_000L},"))
        assertEquals(true, ItemCd.hearthHave)
        // a new app life, 100 s later: the kept end time counts until the game's own word comes
        ItemCd.reset(); wallNow += 100_000L
        ItemCd.character("Korvash-Realm", now = 50L)
        assertEquals(500.0, ItemCd.left(ItemCd.HEARTH, now = 50L), 1e-3)
        // another character has none of it
        ItemCd.reset()
        ItemCd.character("Other-Realm", now = 0L)
        assertEquals(0.0, ItemCd.left(ItemCd.HEARTH, now = 0L), 1e-6)
        // the game says ready: the kept time goes
        ItemCd.reset(); ItemCd.character("Korvash-Realm", now = 0L)
        ItemCd.onDoc(doc("""{"type":"itemcd","hs":{"have":true}}"""), now = 0L)
        assertNull(kept["hs:Korvash-Realm"])
    }

    // r20a (review): a switch to another character in ONE app life (a relog) never shows or keeps the old one's countdowns as the new one's
    @Test fun aCharacterSwitchInOneAppLifeKeepsNothingUnderTheWrongName() {
        ItemCd.character("Korvash-Realm", now = 0L)
        ItemCd.onDoc(doc("""{"type":"itemcd","who":"Korvash-Realm","t":{"6948":{"d":1800,"l":600},"118":{"d":120,"l":60}}}"""), now = 0L)
        val korvash = kept["hs:Korvash-Realm"]
        // the new character's document comes BEFORE its padmap: Korvash's kept time stays Korvash's, nothing of his counts for Other
        ItemCd.onDoc(doc("""{"type":"itemcd","who":"Other-Realm","fight":true,"hs":{"have":true}}"""), now = 1_000L)
        assertEquals(korvash, kept["hs:Korvash-Realm"])
        assertEquals(0.0, ItemCd.left(ItemCd.HEARTH, now = 1_000L), 1e-6)
        assertEquals(0.0, ItemCd.left(118, now = 1_000L), 1e-6)
        assertNull(kept["hs:Other-Realm"])
        ItemCd.character("Other-Realm", now = 1_000L)
        assertEquals(korvash, kept["hs:Korvash-Realm"])
        // back to Korvash, the padmap first: his kept time counts again, the other's word is not written under his name
        ItemCd.character("Korvash-Realm", now = 2_000L)
        assertEquals(korvash, kept["hs:Korvash-Realm"])
        assertEquals(600.0, ItemCd.left(ItemCd.HEARTH, now = 2_000L), 1e-3)   // the test wall clock stands still
    }

    // r20a (review): the item radial's doc has no cd any more: a cooldown begun since the tap (ItemCd) says the use happened
    @Test fun noRetryWhenTheItemsCooldownBegan() {
        val d = RadialModel.parse(doc("""{"type":"radial","r":{"fight":true,"items":[{"col":0,"id":6948,"c":1,"g":4,"st":false}]}}"""))!!
        val it = d.items[0]
        assertTrue(RadialModel.shouldRetry(it, d, acked = false))
        assertFalse(RadialModel.shouldRetry(it, d, acked = false, cdBegan = true))
    }

    @Test fun theClassTeleportOnceKnown() {
        ItemCd.onDoc(doc("""{"type":"itemcd","tp":{"id":556,"n":"Astral Recall","i":136010,"cd":{"d":900,"l":300}}}"""), now = 0L)
        assertEquals(556, ItemCd.teleport?.id)
        assertEquals(300.0, ItemCd.teleportLeft(now = 0L), 1e-6)
        assertEquals(900.0, ItemCd.teleportLen(now = 0L), 1e-6)
        // in a fight with nothing said: kept; a dim when the length was never learned
        ItemCd.onDoc(doc("""{"type":"itemcd","fight":true,"tp":{"id":556}}"""), now = 1_000L)
        assertEquals(299.0, ItemCd.teleportLeft(now = 1_000L), 1e-6)
        ItemCd.onDoc(doc("""{"type":"itemcd","fight":true,"tp":{"id":556,"x":true}}"""), now = 2_000L)
        assertTrue(ItemCd.teleportDim())
        // not known (another character): none
        ItemCd.onDoc(doc("""{"type":"itemcd"}"""), now = 3_000L)
        assertNull(ItemCd.teleport)
    }

    @Test fun mmss() {
        assertEquals("10:00", ItemCd.mmss(600.0))
        assertEquals("0:05", ItemCd.mmss(4.2))
        assertEquals("29:59", ItemCd.mmss(1798.5))
        assertEquals("1:00h", ItemCd.mmss(3600.0))
    }

    // ---------------------------------------------------------------- the count rule (rule 1d)
    @Test fun countsOnlyOnStackablesNeverOnSpellsNorRanOutPins() {
        val d = RadialModel.parse(doc("""{"type":"radial","r":{"prow":"radialpin","items":[
            {"col":0,"id":159,"c":5,"g":1,"st":true},{"col":1,"id":6948,"c":1,"g":4,"st":false},{"col":2,"id":5512,"c":1,"g":4}],
            "pins":{"0":{"id":118,"c":0,"have":false,"st":true},"1":{"id":2091,"c":1,"have":true,"st":false}}}}"""))!!
        val by = d.items.associateBy { it.id }
        assertEquals(5, RadialModel.countShown(false, by[159]!!))
        assertEquals(-1, RadialModel.countShown(false, by[6948]!!))          // the Hearthstone: no count
        assertEquals(1, RadialModel.countShown(false, by[5512]!!))           // no `st` (an older add-on / uncached): the count, as today
        assertEquals(-1, RadialModel.countShown(false, d.pins[0]!!))         // ran out: grey, no number
        assertEquals(-1, RadialModel.countShown(false, d.pins[1]!!))
        // Smart buffs' wheel (a spell wheel): no count even when an older add-on says c = 1
        val so = RadialModel.parse(doc("""{"type":"smartor","r":{"items":[{"id":1243,"s":1,"col":6,"c":1}]}}"""))!!
        assertEquals(-1, so.items[0].count)
        assertEquals(-1, RadialModel.countShown(true, so.items[0]))
    }

    // ---------------------------------------------------------------- the spell radials' learned lengths (q4)
    @Test fun aLearnedLengthCountsAndActDims() {
        assertTrue(RadialModel.Cd.onCast(doc("""{"type":"sradialcd","v":"q4","id":403,"cd":{"d":6,"l":5.8,"e":true},"fight":true}"""), now = 0L))
        assertEquals(5.8, RadialModel.Cd.left(403, now = 0L), 1e-6)
        assertTrue(RadialModel.Cd.onCast(doc("""{"type":"sradialcd","v":"q4","id":324,"act":true,"fight":true}"""), now = 0L))
        assertTrue(RadialModel.Cd.dim(324))
        assertEquals(0.0, RadialModel.Cd.left(324, now = 0L), 1e-6)
        // the fight's end list (no cd, out of a fight): ready, the dim goes
        RadialModel.Cd.onList(doc("""{"type":"sradial","v":"q4","r":{"list":[{"u":1,"items":[{"id":324}]}]}}"""), now = 1_000L)
        assertFalse(RadialModel.Cd.dim(324))
        // in a fight the list keeps the dim
        RadialModel.Cd.onCast(doc("""{"type":"sradialcd","id":324,"act":true,"fight":true}"""), now = 0L)
        RadialModel.Cd.onList(doc("""{"type":"sradial","r":{"fight":true,"list":[{"u":1,"items":[{"id":324}]}]}}"""), now = 1_000L)
        assertTrue(RadialModel.Cd.dim(324))
    }
}
