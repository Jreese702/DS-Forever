package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hub67 ONE-TAP MEMORY (external PR #532 by mummigast, reworked; the user, 2026-11-18: "so we cant do this on a session basis, that way
 * if it launches on desktop not through the thor my DS forever shit doesnt take over?" -> "Yes, build it"): the app's ONE restore tap
 * per load, off-device. Hub.lua's side is tools/test_hud_switch_memory.py and tools/test_layout_memory.py.
 */
class HubRestoreTest {
    private fun hub(boot: Long = 7, n: Int = 0, current: Boolean = true, row: Boolean = true) =
        HubState(boot, n, want = 0, pending = false, hasRow = row, els = emptyList(), current = current)
    private fun rst(n: Int = 0, q: Boolean = false) = HubRestore(CastBarPolicy.RESTORE, n, q)

    @Test fun theRestoreColumnIsTheOldHudShow() = assertEquals(34, CastBarPolicy.RESTORE)

    @Test fun oneTapPerLoadThenNothing() {
        val p = RestorePolicy()
        assertEquals(CastBarPolicy.RESTORE, p.step(rst(), hub(), live = true, now = 0))
        assertTrue("the other taps wait for its answer", p.holding(rst(), hub(), now = 1_000))
        // answered: done for this load, nothing held
        assertNull(p.step(rst(n = 1), hub(n = 1), live = true, now = 1_200))
        assertFalse(p.holding(rst(n = 1), hub(n = 1), now = 1_200))
        assertNull(p.step(rst(n = 1), hub(n = 5), live = true, now = 60_000))
    }

    @Test fun anOlderAddOnSetGetsNoTapAndHoldsNothing() {
        val p = RestorePolicy()
        assertNull(p.step(null, hub(), live = true, now = 0))
        assertFalse(p.holding(null, hub(), now = 0))
    }

    @Test fun noTapBeforeTheGameIsLiveTheRowPublishedOrTheLoadSettled() {
        val p = RestorePolicy()
        assertNull(p.step(rst(), hub(), live = false, now = 0))
        assertNull(p.step(rst(), hub(current = false), live = true, now = 0))
        assertNull(p.step(rst(), hub(row = false), live = true, now = 0))
        assertNull(p.step(rst(), hub(), live = true, now = 0, settleUntil = 100))
        assertNull(p.step(rst(), null, live = true, now = 0))
        assertFalse("nothing sent yet: nothing held", p.holding(rst(), hub(), now = 0))
    }

    @Test fun aLostTapGoesOnceMoreThenTheUsualCompareTakesOver() {
        val p = RestorePolicy()
        assertEquals(CastBarPolicy.RESTORE, p.step(rst(), hub(), true, now = 0))
        assertNull(p.step(rst(), hub(), true, now = 2_000))                              // waiting for the answer
        assertEquals(CastBarPolicy.RESTORE, p.step(rst(), hub(), true, now = 3_100))     // silent: once more
        assertNull(p.step(rst(), hub(), true, now = 6_500))                              // and no more
        assertFalse("past the tries nothing is held: the switches compare as before", p.holding(rst(), hub(), now = 6_500))
    }

    @Test fun aReloadIsANewLoad() {
        val p = RestorePolicy()
        assertEquals(CastBarPolicy.RESTORE, p.step(rst(), hub(boot = 7), true, now = 0))
        assertNull(p.step(rst(n = 1), hub(boot = 7), true, now = 500))
        // /reload: a new boot, the add-on's count back to 0
        assertEquals(CastBarPolicy.RESTORE, p.step(rst(), hub(boot = 9), true, now = 10_000))
    }

    @Test fun aFreshLoginStartsTheTriesAfresh() {
        val p = RestorePolicy()
        p.step(rst(), hub(), true, now = 0); p.step(rst(), hub(), true, now = 3_100)
        assertNull(p.step(rst(), hub(), true, now = 7_000))
        p.freshLogin()
        assertEquals(CastBarPolicy.RESTORE, p.step(rst(), hub(), true, now = 7_100))
    }

    @Test fun aRestoreHeldForAFightHoldsNothingHere() {
        val p = RestorePolicy()
        assertEquals(CastBarPolicy.RESTORE, p.step(rst(), hub(), true, now = 0))
        // the add-on answers "held for the fight's end": no second tap, the app's own taps go on (the add-on keeps them)
        assertNull(p.step(rst(q = true), hub(n = 1), true, now = 3_500))
        assertFalse(p.holding(rst(q = true), hub(n = 1), now = 500))
    }

    /** after the restore the game's words are the kept ones, `set` true: the app's per-character compare finds nothing to tap */
    @Test fun afterTheRestoreTheSwitchCompareTapsNothingThatMatches() {
        val parts = listOf(GameUi.SW_PLAYER, GameUi.SW_TARGET, GameUi.SW_NP, GameUi.SW_PRD)
        val set = parts.associateWith { true }
        val s = GameUi.Sw("uisw", emptyMap(), player = false, target = false, np = true, prd = true, set = set)
        val want = mapOf(GameUi.SW_PLAYER to false, GameUi.SW_TARGET to false, GameUi.SW_NP to true, GameUi.SW_PRD to true)
        assertNull(GameUi.swNext(s) { p -> want[p] ?: false })
        // a choice that differs (made on another device) is still tapped
        assertEquals(GameUi.SW_PLAYER to true, GameUi.swNext(s) { p -> if (p == GameUi.SW_PLAYER) true else want[p] ?: false })
        // without the restore (`set` empty, e.g. an older add-on set) every part is tapped, as before
        assertEquals(GameUi.SW_PLAYER to false, GameUi.swNext(s.copy(set = emptyMap())) { p -> want[p] ?: false })
    }

    // ---------------------------------------------------------------- fix/1492-feedback (the user, 2026-11-18, test 1492 streamed:
    // "it looked like it was doing a lot of clicks again and also the UI didnt hide")

    @Test fun anUnansweredRestoreIsSaidOncePerLoad() {
        val p = RestorePolicy()
        p.step(rst(), hub(), true, now = 0); p.step(rst(), hub(), true, now = 3_100)
        assertFalse("its second try still waits", p.unanswered(rst(), hub(), now = 5_000))
        assertTrue(p.unanswered(rst(), hub(), now = 6_200))
        assertFalse("once", p.unanswered(rst(), hub(), now = 9_000))
        assertFalse("answered is never unanswered", RestorePolicy().also { it.step(rst(), hub(), true, 0) }.unanswered(rst(n = 1), hub(n = 1), 9_000))
    }

    private val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
        "PRD_OFF" to 6, "PRD_ON" to 7)

    @Test fun everySwitchThatDiffersGoesInOneBatch() {
        val s = GameUi.Sw("uisw", cols, player = true, target = true, np = false, prd = false, set = emptyMap())
        val want = mapOf(GameUi.SW_PLAYER to false, GameUi.SW_TARGET to false, GameUi.SW_NP to true, GameUi.SW_PRD to true)
        val b = GameUi.swBatch(s, emptySet(), { true }) { p -> want[p] ?: false }
        assertEquals(listOf(GameUi.SW_PLAYER to 1, GameUi.SW_TARGET to 3, GameUi.SW_NP to 5, GameUi.SW_PRD to 7), b)
        // a parked part sits out, one waiting for its own answer too; the others still go
        val b2 = GameUi.swBatch(s, setOf(GameUi.SW_PLAYER), { p -> p != GameUi.SW_NP }) { p -> want[p] ?: false }
        assertEquals(listOf(GameUi.SW_TARGET to 3, GameUi.SW_PRD to 7), b2)
        // after the restore (words as chosen, `set`): nothing
        val done = s.copy(player = false, target = false, np = true, prd = true, set = want.keys.associateWith { true })
        assertTrue(GameUi.swBatch(done, emptySet(), { true }) { p -> want[p] ?: false }.isEmpty())
        assertTrue("the batch's taps are spaced past nothing: each its own cell", GameUi.SW_GAP_MS in 1L..120L)
    }

    /** the Thor's kept switches and layout (hubSw / hubLy, per character) reach the PC at a stream's start: the Thor's win */
    @Test fun theKeptSwitchesTravelWithTheSettingsSync() {
        val thor = SvLua.parse("""{["hubLy"]={["Rutger-Mograine"]={["cb"]=true,["mask"]=31}},["hubSw"]={["Rutger-Mograine"]={["0"]=1,["1"]=3}},["x"]=1}""")
        val pc = SvLua.parse("""{["hubSw"]={["Other-Realm"]={["0"]=0}},["x"]=1}""")
        val m = SvMerge.merge(thor, pc, null).table
        @Suppress("UNCHECKED_CAST") val sw = m["hubSw"] as Map<Any, Any>
        assertTrue("this character's switches go to the PC", sw.containsKey("Rutger-Mograine"))
        assertTrue("the PC's other character stays", sw.containsKey("Other-Realm"))
        @Suppress("UNCHECKED_CAST") val ly = m["hubLy"] as Map<Any, Any>
        assertTrue(ly.containsKey("Rutger-Mograine"))
        assertFalse("not a skipped key", "hubSw" in SvLua.SKIP["WowCompanionDB"].orEmpty() || "hubLy" in SvLua.SKIP["WowCompanionDB"].orEmpty())
    }
}
