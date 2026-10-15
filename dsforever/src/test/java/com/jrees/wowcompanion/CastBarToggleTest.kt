package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** f-top-castbar-toggle (the user 15 Oct: "a UI Toggle for the top screen to Show/hide the cast bar"): the tap rules, off-device */
class CastBarToggleTest {
    private fun hub(boot: Long = 7, n: Int = 0, current: Boolean = true, row: Boolean = true) =
        HubState(boot, n, want = 0, pending = false, hasRow = row, els = emptyList(), current = current)

    @Test fun showIsTheDefaultAndNeedsNoTap() = assertNull(CastBarPolicy().step(false, hide = false, visible = true, s = hub(), live = true, now = 0))

    @Test fun hideTapsTheHideColumn() =
        assertEquals(CastBarPolicy.COL_HIDE, CastBarPolicy().step(false, hide = true, visible = true, s = hub(), live = true, now = 0))

    @Test fun showAgainTapsTheShowColumn() =
        assertEquals(CastBarPolicy.COL_SHOW, CastBarPolicy().step(true, hide = false, visible = true, s = hub(), live = true, now = 0))

    @Test fun theCompanionOffScreenShowsItAgain() =
        assertEquals(CastBarPolicy.COL_SHOW, CastBarPolicy().step(true, hide = true, visible = false, s = hub(), live = true, now = 0))

    @Test fun inPlaceNothingIsTapped() = assertNull(CastBarPolicy().step(true, hide = true, visible = true, s = hub(), live = true, now = 0))

    @Test fun anOlderAddOnWithoutTheCastBarGetsNoTap() =
        assertNull(CastBarPolicy().step(null, hide = true, visible = true, s = hub(), live = true, now = 0))

    @Test fun noTapBeforeTheGameIsLiveOrTheRowPublished() {
        val p = CastBarPolicy()
        assertNull(p.step(false, true, true, hub(), live = false, now = 0))
        assertNull(p.step(false, true, true, hub(current = false), live = true, now = 0))
        assertNull(p.step(false, true, true, hub(row = false), live = true, now = 0))
        assertNull(p.step(false, true, true, hub(), live = true, now = 0, settleUntil = 100))
    }

    @Test fun oneTapThenWaitsForTheReplyThenOneMoreThenSlow() {
        val p = CastBarPolicy()
        assertEquals(CastBarPolicy.COL_HIDE, p.step(false, true, true, hub(n = 0), true, now = 0))
        assertNull(p.step(false, true, true, hub(n = 0), true, now = 1_000))                       // waiting for the reply
        assertEquals(CastBarPolicy.COL_HIDE, p.step(false, true, true, hub(n = 1), true, now = 2_000))   // replied, unchanged: once more
        assertNull(p.step(false, true, true, hub(n = 2), true, now = 3_000))                       // tried twice: slow now
        assertEquals(CastBarPolicy.COL_HIDE, p.step(false, true, true, hub(n = 2), true, now = 2_000 + GameUiPolicy.SLOW_RETRY_MS))
    }

    @Test fun aReloadAsksAfresh() {
        val p = CastBarPolicy()
        p.step(false, true, true, hub(boot = 1), true, now = 0)
        assertEquals(CastBarPolicy.COL_HIDE, p.step(false, true, true, hub(boot = 2), true, now = 10))
    }

    @Test fun theColumnsArePastTheLayoutsAndInsideTheRow() {
        assertEquals(1 shl GameUiPolicy.BITS, CastBarPolicy.COL_SHOW)
        assertEquals(CastBarPolicy.COL_SHOW + 1, CastBarPolicy.COL_HIDE)
        assert(CastBarPolicy.COL_HIDE < 40)
    }
}
