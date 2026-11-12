package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** hub33 THE PET'S STRIP IN THE HUD (the user, 2026-11-11: "find a way to include Pet HP in the HUD, and better distinguish the player HUD
 *  vs Pet HUD"): its own switch on "uisw" 32 / 33, on by default, with its OWN branch in swNow / swCol / swNext (an unlisted part falls
 *  to the resource bar's `else -> prd` and would read and tap the WRONG switch) */
class PetSwitchTest {
    private val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
        "PRD_OFF" to 6, "PRD_ON" to 7, "PET_OFF" to 32, "PET_ON" to 33)
    private val defaults = mapOf(GameUi.SW_PLAYER to false, GameUi.SW_TARGET to false, GameUi.SW_NP to true, GameUi.SW_PRD to true, GameUi.SW_PET to true)
    private val allSet = mapOf("player" to true, "target" to true, "np" to true, "prd" to true)

    @Test fun onByDefault() = assertEquals(true, GameUi.swWant(GameUi.SW_PET))

    @Test fun itsOwnColumnsNotTheResourceBars() {
        val s = GameUi.Sw("uisw", cols, false, false, true, true, allSet, pet = true)
        assertEquals(33, GameUi.swCol(s, GameUi.SW_PET, true))
        assertEquals(32, GameUi.swCol(s, GameUi.SW_PET, false))
        assertEquals(7, GameUi.swCol(s, GameUi.SW_PRD, true))          // the resource bar keeps its own
    }

    @Test fun tappedOnceAfterAReloadThenSettled() {
        // the rest as chosen and set; the pet's word true but never set since the reload: PET_ON, absolutely
        val fresh = GameUi.Sw("uisw", cols, false, false, true, true, allSet, pet = true)
        assertEquals(GameUi.SW_PET to true, GameUi.swNext(fresh) { defaults[it]!! })
        // set: nothing more to tap (a missing set.pet would tap forever)
        val done = fresh.copy(set = allSet + ("pet" to true))
        assertNull(GameUi.swNext(done) { defaults[it]!! })
        // the player turned it off: the game's word differs, PET_OFF
        val off = GameUi.swNext(done) { if (it == GameUi.SW_PET) false else defaults[it]!! }
        assertEquals(GameUi.SW_PET to false, off)
        assertEquals(32, GameUi.swCol(done, off!!.first, off.second))
    }

    @Test fun theResourceBarsWordNeverStandsInForThePets() {
        // the bar off in the game, the pet on: swNext must judge the pet by ITS word (an else -> prd branch would see "false" and tap)
        val s = GameUi.Sw("uisw", cols, false, false, true, false, allSet + ("pet" to true), pet = true)
        val want = defaults + (GameUi.SW_PRD to false)                  // the bar chosen off, the pet on: both as the game says
        assertNull(GameUi.swNext(s) { want[it]!! })
        assertEquals(true, s.pet)
    }

    @Test fun anAddOnWithoutTheSwitchIsNeverTapped() {
        val older = GameUi.Sw("uisw", cols, false, false, true, true, allSet)
        assertNull(older.pet)
        assertNull(GameUi.swNext(older) { defaults[it]!! })
    }
}
