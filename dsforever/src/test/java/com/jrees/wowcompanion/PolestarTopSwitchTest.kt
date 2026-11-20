package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * POLESTAR HIDDEN ON THE TOP SCREEN BY DEFAULT (the user, 2026-11-20: "does polestar show on the top screen by default? we should hide
 * that by default"): the hub31 switch ("uisw" 30 PS_SHOW / 31 PS_HIDE, the doc's sw.ps = hidden) now wants HIDDEN until the player
 * picks On in Settings > Top screen > Navigation > Polestar. Its own branch in swNow / swCol / swNext (an unlisted part would fall to
 * the resource bar's `else -> prd`). Pure: no character and no preferences = the defaults.
 */
class PolestarTopSwitchTest {
    private val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
        "PRD_OFF" to 6, "PRD_ON" to 7, "PS_SHOW" to 30, "PS_HIDE" to 31)
    private val defaults = mapOf(GameUi.SW_PLAYER to false, GameUi.SW_TARGET to false, GameUi.SW_NP to true, GameUi.SW_PRD to true,
        GameUi.SW_PS to true)
    private val allSet = mapOf("player" to true, "target" to true, "np" to true, "prd" to true)

    @Test fun hiddenByDefault() {
        assertEquals(true, GameUi.SW_PS_DEFAULT_HIDDEN)
        assertEquals("no character, no choice: hidden", true, GameUi.swWant(GameUi.SW_PS))
    }

    @Test fun itsOwnColumns() {
        val s = GameUi.Sw("uisw", cols, false, false, true, true, allSet, ps = false)
        assertEquals(31, GameUi.swCol(s, GameUi.SW_PS, true))
        assertEquals(30, GameUi.swCol(s, GameUi.SW_PS, false))
    }

    /** a game that shows Polestar (a fresh login, or hub67's restore of an older build's PS_SHOW): one PS_HIDE, then nothing */
    @Test fun aShownPolestarGetsOneHideThenSettles() {
        val shown = GameUi.Sw("uisw", cols, false, false, true, true, allSet + ("ps" to true), ps = false)
        val next = GameUi.swNext(shown) { defaults[it]!! }
        assertEquals(GameUi.SW_PS to true, next)
        assertEquals(31, GameUi.swCol(shown, next!!.first, next.second))
        // the batch the app sends at a connect carries it too
        assertEquals(listOf(GameUi.SW_PS to 31), GameUi.swBatch(shown, emptySet(), { true }) { defaults[it]!! })
        // hidden and set: nothing more
        assertNull(GameUi.swNext(shown.copy(ps = true)) { defaults[it]!! })
    }

    /** never set since a /reload (the add-on's hub.ps.hidden = nil reads as shown, not set): the hide goes absolutely */
    @Test fun notSetSinceAReloadIsTapped() {
        val fresh = GameUi.Sw("uisw", cols, false, false, true, true, allSet, ps = false)
        assertEquals(GameUi.SW_PS to true, GameUi.swNext(fresh) { defaults[it]!! })
    }

    /** a player who turned it On keeps it: shown wanted, PS_SHOW, never a hide */
    @Test fun aChosenOnStaysShown() {
        val shown = GameUi.Sw("uisw", cols, false, false, true, true, allSet + ("ps" to true), ps = false)
        assertNull(GameUi.swNext(shown) { if (it == GameUi.SW_PS) false else defaults[it]!! })
        val hidden = shown.copy(ps = true)
        val next = GameUi.swNext(hidden) { if (it == GameUi.SW_PS) false else defaults[it]!! }
        assertEquals(GameUi.SW_PS to false, next)
        assertEquals(30, GameUi.swCol(hidden, next!!.first, next.second))
    }

    @Test fun anAddOnWithoutTheSwitchIsNeverTapped() {
        val older = GameUi.Sw("uisw", cols, false, false, true, true, allSet)
        assertNull(older.ps)
        assertNull(GameUi.swNext(older) { defaults[it]!! })
    }

    /** the choice is a per-character LAYOUTS key: Reset > Layouts (or Everything) takes it back to the default, hidden */
    @Test fun resetLayoutsTakesTheChoiceBack() {
        val who = "Shammy-Fakerealm"
        assertEquals(ResetPlan.Kind.LAYOUTS, ResetPlan.ruleOf("gameui", "sw.ps.$who")?.kind)
        val keys = setOf("sw.ps.$who", "sw.ps.Huntress-Fakerealm")
        assertEquals(setOf("sw.ps.$who"), ResetPlan.prefKeys("gameui", keys, setOf(ResetPlan.Kind.LAYOUTS), who, false))
        assertTrue(ResetPlan.prefKeys("gameui", keys, setOf(ResetPlan.Kind.SKILLS), who, false).isEmpty())
    }
}
