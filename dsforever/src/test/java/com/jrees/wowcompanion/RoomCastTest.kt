package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * feat/wk-itemcast (the user, 2026-11-16: "the cast bar for using Items from my bag or applying enchants is not visible"): the rooms'
 * cast band, off-device - it shows only while a cast or channel runs and through the game's own finish / interrupt, never for a swing,
 * and it sits inside the rooms' bar's tracking band (never on a room, never on the rooms' pictures), its name left of HOME's disc.
 */
class RoomCastTest {
    private val fin = CastFx.FINISH_MS; private val fail = CastFx.FAIL_MS

    @Test fun onlyWhileACastRunsAndThroughItsEnd() {
        // nothing ever cast
        assertEquals(RoomCast.NONE, RoomCast.state(5000.0, Double.NaN, 0.0, false, 0L, fin))
        // a 2 s cast, a quarter in; the same at its last frame and 100 ms past (the game's end lands late)
        assertEquals(RoomCast.RUNNING, RoomCast.state(5500.0, 5000.0, 7000.0, false, 0L, fin))
        assertEquals(RoomCast.RUNNING, RoomCast.state(7099.0, 5000.0, 7000.0, false, 0L, fin))
        assertEquals(RoomCast.NONE, RoomCast.state(7100.0, 5000.0, 7000.0, false, 0L, fin))
        // a broken pair (end before start) never draws
        assertEquals(RoomCast.NONE, RoomCast.state(5500.0, 7000.0, 5000.0, false, 0L, fin))
        // the finish: the game's flash and fade, then nothing
        assertEquals(RoomCast.FINISH, RoomCast.state(7300.0, Double.NaN, 0.0, true, 0L, fin))
        assertEquals(RoomCast.FINISH, RoomCast.state(7300.0, Double.NaN, 0.0, true, fin - 1, fin))
        assertEquals(RoomCast.NONE, RoomCast.state(7300.0, Double.NaN, 0.0, true, fin, fin))
        // an interrupt holds a second, then fades
        assertEquals(RoomCast.FINISH, RoomCast.state(7300.0, Double.NaN, 0.0, true, 1200L, fail))
        assertEquals(RoomCast.NONE, RoomCast.state(7300.0, Double.NaN, 0.0, true, fail, fail))
        // a swing's restart flash is no cast (the caller passes flashCast = false for it): nothing
        assertEquals(RoomCast.NONE, RoomCast.state(7300.0, Double.NaN, 0.0, false, 10L, fin))
        // a new cast while the last one's finish still plays: the cast
        assertEquals(RoomCast.RUNNING, RoomCast.state(7300.0, 7250.0, 9250.0, true, 50L, fin))
    }

    @Test fun theBandSitsInTheTrackingBarLeftOfHome() {
        val w = 1240f
        val discL = w / 2f - 64f - 6f - 2f                 // Strip: HOME 128, RING 6, the 2 px the experience bar keeps clear
        val top = 0f; val tracks = Strip.TRACKS.toFloat()
        val p = RoomCast.place(w, top, tracks, discL)
        // inside the tracking band: never over a room (above the bar) nor the rooms' pictures (the BUTTONS under it)
        assertTrue("top ${p[1]}", p[1] >= top + 1f)
        assertTrue("bottom ${p[3]}", p[3] <= top + tracks - 1f)
        assertEquals(RoomCast.BAR_H, p[3] - p[1], 1e-3f)
        assertEquals((p[1] - top), (top + tracks - p[3]), 1e-3f)                 // centred in the band
        // across the screen with its margin, as the experience bar
        assertEquals(RoomCast.SIDE, p[0], 1e-3f); assertEquals(w - RoomCast.SIDE, p[2], 1e-3f)
        // the name: inside the bar, wholly left of HOME's disc
        assertTrue(p[4] > p[0] && p[5] < discL && p[5] - p[4] > 400f)
        // the rooms' bar placed at the screen's bottom: the band still its own top 34 px
        val q = RoomCast.place(w, 935f, tracks, discL)
        assertTrue(q[1] > 935f && q[3] < 935f + tracks)
    }
}
