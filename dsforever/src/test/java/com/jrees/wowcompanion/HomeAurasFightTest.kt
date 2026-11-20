package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BOTTOM SCREEN'S AURAS IN A FIGHT (#558 / #476, the user 2026-11-19: "Just remove them during fights"): while a fight is on
 * ([HomeAuras.fightHidden]: the character session's a8 `hide`, or an older add-on's `combat`) every aura display is empty - no frozen
 * list, no greyed rows, no estimate - and the lists come back with the first document after the fight. An aura lock outside a fight
 * (an encounter's) still shows the last open lists greyed, as before.
 */
class HomeAurasFightTest {
    private val buffs = """[{"id": 11, "n": "Lightning Shield", "i": 136051, "dur": 600, "exp": 1500, "slot": 2},
                            {"id": 12, "n": "Water Walking", "i": 135863, "dur": 600, "exp": 1550, "slot": 3}]"""
    private val debuffs = """[{"id": 21, "n": "Rend", "i": 132155, "dur": 15, "exp": 1010, "t": "Bleed"}]"""
    private val pad = """{"rows": {"aurapick": 3, "auracancel": 4}}"""

    private fun out() = JSONObject("""{"type": "auras", "v": "a8", "now": 1000, "b": $buffs, "d": $debuffs, "e": [], "pad": $pad}""")
    private fun fight() = JSONObject("""{"type": "auras", "v": "a8", "now": 1001, "b": [], "d": [], "e": [], "hide": true, "combat": true}""")
    private fun oldFight() = JSONObject("""{"type": "auras", "v": "a7", "now": 1001, "b": $buffs, "d": $debuffs, "e": [], "lock": true, "combat": true}""")
    private fun lockedNoFight() = JSONObject("""{"type": "auras", "v": "a8", "now": 1002, "b": [{"id": 11}], "d": [], "e": [], "lock": true}""")

    @Test fun outOfAFightEverythingShows() {
        val a = HomeAuras()
        assertTrue(a.update(out(), true, 0L))
        assertEquals(listOf(11, 12), a.shownBuffs.map { it.id })
        assertEquals(listOf(21), a.shownDebuffs.map { it.id })
        assertFalse(a.fightHidden)
        assertFalse(a.greyed)
    }

    @Test fun aFightHidesEveryAuraDisplay() {
        val a = HomeAuras()
        a.update(out(), true, 0L)
        assertTrue("the fight's document is a change", a.update(fight(), true, 1_000L))
        assertTrue(a.fightHidden)
        assertTrue("no frozen buff list", a.shownBuffs.isEmpty())
        assertTrue("no frozen debuff list", a.shownDebuffs.isEmpty())
        assertFalse("nothing greyed (nothing shown)", a.greyed)
        assertTrue("the document is still live (not a stale warning)", a.live(1_500L))
        // a repeated fight document changes nothing
        assertFalse(a.update(fight(), true, 2_000L))
    }

    @Test fun anOlderAddonsFightIsHiddenToo() {
        // a7 sent the lists (secret fields) with combat = true; the app hides them all the same
        val a = HomeAuras()
        a.update(out(), true, 0L)
        a.update(oldFight(), true, 1_000L)
        assertTrue(a.fightHidden)
        assertTrue(a.shownBuffs.isEmpty() && a.shownDebuffs.isEmpty())
    }

    @Test fun theListsComeBackAfterTheFight() {
        val a = HomeAuras()
        a.update(out(), true, 0L)
        a.update(fight(), true, 1_000L)
        assertTrue(a.update(out(), true, 5_000L))
        assertFalse(a.fightHidden)
        assertEquals(listOf(11, 12), a.shownBuffs.map { it.id })
        assertEquals(listOf(21), a.shownDebuffs.map { it.id })
    }

    @Test fun removingIsRefusedInAFight() {
        val a = HomeAuras()
        a.update(out(), true, 0L)
        assertTrue(a.remove(11, live = true, now = 100L) is AuraTap.Send)
        a.sendFailed()
        a.update(fight(), true, 1_000L)
        assertEquals(AuraTap.Refused("Out of combat only"), a.remove(11, live = true, now = 1_100L))
    }

    @Test fun aFightDoesNotOverwriteTheOpenListsOrEndAPendingRemoval() {
        val a = HomeAuras()
        a.update(out(), true, 0L)
        assertTrue(a.remove(11, live = true, now = 100L) is AuraTap.Send)
        a.update(fight(), true, 200L)
        assertEquals("the fight's empty lists say nothing about the buff being removed", 11, a.pending(300L))
        // an aura lock after the fight (no fight word): the last OPEN lists (from before the fight), greyed - not the fight's empty ones
        a.update(lockedNoFight(), true, 400L)
        assertFalse(a.fightHidden)
        assertTrue(a.greyed)
        assertEquals(listOf(11, 12), a.shownBuffs.map { it.id })
    }
}
