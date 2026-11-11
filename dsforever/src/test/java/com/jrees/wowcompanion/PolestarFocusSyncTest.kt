package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ps3 (the user, 2026-11-11): the light blue Polestar row is the game's focus too, never fighting the game's own auto-track */
class PolestarFocusSyncTest {
    private val none = PolestarFocusSync.Act.None
    private val settle = PolestarFocusSync.SETTLE_MS
    private val retry = PolestarFocusSync.RETRY_MS

    @Test fun aStepQuestIsFocusedOnceTheDriftHasLasted() {
        val s = PolestarFocusSync()
        assertEquals("login: the game on 5, the step on 7: wait", none, s.tick(true, 7, 5, 0))
        assertEquals(none, s.tick(true, 7, 5, settle - 1))
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 5, settle))
        s.sent(7, true, settle)
        assertEquals("not again while the game answers", none, s.tick(true, 7, 5, settle + 3_000))
        assertEquals("the game took it", none, s.tick(true, 7, 7, settle + 4_000))
    }

    @Test fun anObjectiveUpdateDriftIsReassertedNotReleased() {
        val s = PolestarFocusSync()
        assertEquals(none, s.tick(true, 7, 7, 0))                         // in sync
        assertEquals("the game auto-tracks 9 on an objective update", none, s.tick(true, 7, 9, 100_000))
        assertEquals(none, s.tick(true, 7, 9, 100_000 + settle - 1))
        assertEquals("re-asserted after the settle", PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 9, 100_000 + settle))
        s.sent(7, true, 100_000 + settle)
        assertEquals("the game back on 7", none, s.tick(true, 7, 7, 103_000))
        // a drift that ends inside the settle never sends
        assertEquals(none, s.tick(true, 7, 9, 200_000))
        assertEquals(none, s.tick(true, 7, 7, 201_000))
        assertEquals("the drift was reset", none, s.tick(true, 7, 9, 201_500))
        assertEquals(none, s.tick(true, 7, 9, 201_500 + settle - 1))
    }

    @Test fun aReassertIsCappedAtOnePerRetryPerTarget() {
        val s = PolestarFocusSync()
        s.tick(true, 7, 9, 0)
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 9, settle))
        s.sent(7, true, settle)
        assertEquals(none, s.tick(true, 7, 9, settle + retry - 1))
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 9, settle + retry))
    }

    @Test fun theTurnInOrderDoesNotRelease() {
        val s = PolestarFocusSync()
        assertEquals(none, s.tick(true, 7, 7, 0))
        // the map's focus goes to none before the quests document drops 7
        assertEquals(none, s.tick(true, 7, 0, 10_000))
        assertEquals(none, s.tick(true, 7, 0, 11_000))
        // the log drops it, the next step's quest 8 is tracked: a new step, the debounce from scratch
        assertEquals(none, s.tick(true, 8, 0, 11_500))
        assertEquals(none, s.tick(true, 8, 0, 11_500 + settle - 1))
        assertEquals(PolestarFocusSync.Act.Focus(8), s.tick(true, 8, 0, 11_500 + settle))
    }

    @Test fun aRefusedSendIsTriedAgainAfterTheSettleNotTheRetry() {
        val s = PolestarFocusSync()
        s.tick(true, 7, 5, 0)
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 5, settle))
        s.sent(7, false, settle)                                          // "Waiting for the game's quest list"
        assertEquals("the floor between tries", none, s.tick(true, 7, 5, settle + 1_000))
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 5, 2 * settle))
        s.sent(7, true, 2 * settle)
        assertEquals("sent: now the cap", none, s.tick(true, 7, 5, 3 * settle))
    }

    @Test fun aLastingRefusalCountsAsASend() {
        val s = PolestarFocusSync()
        s.tick(true, 0, 5, 0)
        var t = settle
        repeat(PolestarFocusSync.MAX_REFUSED) {
            assertEquals(PolestarFocusSync.Act.Unfocus(5), s.tick(true, 0, 5, t))
            s.sent(0, false, t)                                           // "This quest has no tap column"
            t += settle
        }
        assertEquals("capped like a send", none, s.tick(true, 0, 5, t))
    }

    @Test fun aStepChangeResetsTheDebounceAndCancelsTheOldPush() {
        val s = PolestarFocusSync()
        s.tick(true, 7, 5, 0)
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 5, settle))
        s.sent(7, true, settle)
        assertEquals("no cancel yet", 0, s.takeCancel())
        assertEquals("a new step, while the old drift ran", none, s.tick(true, 8, 5, settle + 1))
        assertEquals("the old push's focus check stops", 7, s.takeCancel())
        assertEquals("once", 0, s.takeCancel())
        assertEquals(none, s.tick(true, 8, 5, 2 * settle))
        assertEquals(PolestarFocusSync.Act.Focus(8), s.tick(true, 8, 5, 2 * settle + 1))
    }

    @Test fun aNoQuestStepUnfocusesOnceDebouncedAndCapped() {
        val s = PolestarFocusSync()
        assertEquals("an accept step, the game on 5: wait", none, s.tick(true, 0, 5, 0))
        assertEquals(none, s.tick(true, 0, 5, settle - 1))
        assertEquals(PolestarFocusSync.Act.Unfocus(5), s.tick(true, 0, 5, settle))
        s.sent(0, true, settle)
        assertEquals("cleared: nothing", none, s.tick(true, 0, 0, settle + 1_000))
        // the game auto-tracks 6 on an objective update: the drift waits, then the cap holds for RETRY_MS
        assertEquals(none, s.tick(true, 0, 6, settle + 5_000))
        assertEquals("capped: once per RETRY_MS", none, s.tick(true, 0, 6, settle + 5_000 + settle))
        assertEquals(PolestarFocusSync.Act.Unfocus(6), s.tick(true, 0, 6, settle + retry))
    }

    @Test fun anUntrackedStepQuestLeavesTheGameAlone() {
        val s = PolestarFocusSync()
        assertEquals(none, s.tick(true, PolestarFocusSync.LEAVE, 5, 0))
        assertEquals(none, s.tick(true, PolestarFocusSync.LEAVE, 5, 60_000))
    }

    @Test fun nothingWhileTheRowDoesNotHold() {
        val s = PolestarFocusSync()
        assertEquals(none, s.tick(false, 7, 5, 0))
        assertEquals(none, s.tick(false, 7, 5, 60_000))
        assertEquals(none, s.tick(false, 0, 5, 120_000))
        // a send, then the row let go: the push is cancelled, nothing more
        s.tick(true, 7, 5, 200_000)
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 5, 200_000 + settle))
        s.sent(7, true, 200_000 + settle)
        assertEquals(none, s.tick(false, 7, 5, 203_000))
        assertEquals(7, s.takeCancel())
    }

    @Test fun aNoneGapKeepsTheCap() {
        val s = PolestarFocusSync()
        s.tick(true, 7, 5, 0)
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 5, settle))
        s.sent(7, true, settle)
        assertEquals("a none-gap (taxi)", none, s.tick(false, 7, 5, 5_000))
        s.tick(true, 7, 5, 6_000)
        assertEquals("back: still capped", none, s.tick(true, 7, 5, 6_000 + settle))
    }

    @Test fun noneGapsUnderWaitsGiveNoDefaultPush() {
        val gap = PolestarFocusSync.GAP_MS; val first = PolestarFocusSync.FIRST_MS
        assertTrue("a step now", PolestarFocusSync.waits(true, true, true, 0, 600_000))
        assertTrue("a none-gap", PolestarFocusSync.waits(true, true, false, gap - 1, 600_000))
        assertFalse("a long none: Polestar has no step", PolestarFocusSync.waits(true, true, false, gap, 600_000))
        assertTrue("login, no pstep yet", PolestarFocusSync.waits(true, true, false, null, first - 1))
        assertFalse("no Polestar at all: the default push again", PolestarFocusSync.waits(true, true, false, null, first))
        assertFalse("the row off", PolestarFocusSync.waits(true, false, true, 0, 0))
        assertFalse("another mode", PolestarFocusSync.waits(false, true, true, 0, 0))
    }

    @Test fun aLastingNoneAfterTheLastStepLetsTheDefaultPushGo() {
        // the host stamps only a pstep WITH a step (MapView.psStepAt): Map.lua's none re-sends every 15 s do not count as a gap
        val gap = PolestarFocusSync.GAP_MS
        var stepAt = 0L
        fun pstep(now: Long, step: Boolean) { if (step) stepAt = now }
        pstep(100_000, true)
        for (t in 115_000L..160_000L step 15_000L) pstep(t, false)     // none re-sends, 15 s apart
        val now = 100_000 + gap + 1_000                               // 31 s after the last real step
        assertFalse("a lasting none: the default push goes", PolestarFocusSync.waits(true, true, false, now - stepAt, 600_000))
        assertTrue("within GAP_MS of the last step it still waits", PolestarFocusSync.waits(true, true, false, gap - 1, 600_000))
    }

    @Test fun anExplicitRePickLiftsTheCap() {
        val s = PolestarFocusSync()
        s.tick(true, 7, 5, 0)
        assertEquals(PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 5, settle))
        s.sent(7, true, settle)
        assertEquals("released (Unfocus on the row), the player picks quest 9", none, s.tick(false, 7, 9, 5_000))
        s.picked()                                                    // A on the row again, within RETRY_MS of the send
        assertEquals("the settle first", none, s.tick(true, 7, 9, 6_000))
        assertEquals(none, s.tick(true, 7, 9, 6_000 + settle - 1))
        assertEquals("then Focus(7), cap lifted", PolestarFocusSync.Act.Focus(7), s.tick(true, 7, 9, 6_000 + settle))
        s.sent(7, true, 6_000 + settle)
        assertEquals("once", none, s.tick(true, 7, 9, 6_000 + 2 * settle + 1))
    }
}
