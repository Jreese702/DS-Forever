package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * hub52 / Trail ag10 FRIENDLY PLATES (the user, 2026-11-13: "friendly plates off ... maybe we should set this as a toggle defaulted to
 * off"): the switch on "uisw" 38 / 39, Settings > Top > Target, per character, OFF by default; an add-on without it gets no row and no tap.
 */
class FriendlyPlatesTest {
    private val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
        "PRD_OFF" to 6, "PRD_ON" to 7, "NPF_OFF" to 38, "NPF_ON" to 39)
    private val defaults = mapOf(GameUi.SW_PLAYER to false, GameUi.SW_TARGET to false, GameUi.SW_NP to true, GameUi.SW_PRD to true, GameUi.SW_NPF to false)
    private val allSet = mapOf("player" to true, "target" to true, "np" to true, "prd" to true)

    @Test fun offByDefault() = assertEquals(false, GameUi.swWant(GameUi.SW_NPF))

    @Test fun itsOwnColumns() {
        val s = GameUi.Sw("uisw", cols, false, false, true, true, allSet, npf = false)
        assertEquals(39, GameUi.swCol(s, GameUi.SW_NPF, true))
        assertEquals(38, GameUi.swCol(s, GameUi.SW_NPF, false))
    }

    @Test fun tappedOnceAfterAReloadThenSettled() {
        val fresh = GameUi.Sw("uisw", cols, false, false, true, true, allSet, npf = false)
        // off by default: the add-on's own "off" is still said once after a reload (set.npf absent)
        assertEquals(GameUi.SW_NPF to false, GameUi.swNext(fresh) { defaults[it]!! })
        val done = fresh.copy(set = allSet + ("npf" to true))
        assertNull(GameUi.swNext(done) { defaults[it]!! })
        assertEquals(GameUi.SW_NPF to true, GameUi.swNext(done) { if (it == GameUi.SW_NPF) true else defaults[it]!! })
        // an older add-on with no switch: never tapped, no row
        assertNull(GameUi.swNext(fresh.copy(npf = null, set = allSet)) { if (it == GameUi.SW_NPF) true else defaults[it]!! })
        assertNull(GameUi.friendlyPlatesRow())
    }
}
