package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the user: "when I open the map module while in combat, I cannot exit it": the module is never left without a way out */
class MapExitGuardTest {
    @Test fun aRefusedHoldStartsHomesOwnReaderAtOnce() {
        assertTrue(MapExitGuard.fallback(wanted = true, held = false, refused = true, sinceAskMs = 0))
    }

    @Test fun noAnswerInTimeStartsItToo() {
        assertFalse(MapExitGuard.fallback(true, false, false, MapExitGuard.ANSWER_MS - 1))
        assertTrue(MapExitGuard.fallback(true, false, false, MapExitGuard.ANSWER_MS))
    }

    @Test fun heldOrClosedNeedsNoReader() {
        assertFalse(MapExitGuard.fallback(true, held = true, refused = true, sinceAskMs = 10_000))
        assertFalse(MapExitGuard.fallback(wanted = false, held = false, refused = true, sinceAskMs = 10_000))
    }

    @Test fun theHoldIsAskedAgainOnlyAfterTheFight() {
        assertFalse(MapExitGuard.retry(true, false, inFight = true, sinceAskMs = 60_000))
        assertFalse(MapExitGuard.retry(true, false, inFight = false, sinceAskMs = MapExitGuard.RETRY_MS - 1))
        assertTrue(MapExitGuard.retry(true, false, inFight = false, sinceAskMs = MapExitGuard.RETRY_MS))
        assertFalse(MapExitGuard.retry(true, held = true, inFight = false, sinceAskMs = 60_000))
    }

    @Test fun bClosesOncePerPress() {
        val b = 1 shl PadInput.SLOT_FACE_RIGHT
        val a = 1 shl PadInput.SLOT_FACE_BOTTOM
        assertTrue(MapExitGuard.bPressed(0, b))
        assertFalse(MapExitGuard.bPressed(b, b))            // still held: no second close
        assertFalse(MapExitGuard.bPressed(0, a))            // A is not B
        assertTrue(MapExitGuard.bPressed(a, a or b))
    }
}
