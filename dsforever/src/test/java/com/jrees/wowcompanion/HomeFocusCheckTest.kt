package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix/focus-latency (test50): Home's focus tap is read back ([FocusCheck]); one that did not take goes again by quest id with its
 * column from the newest quests list, at most [HomeQuests.MAX_TRIES] sends, then a clear give-up.
 */
class HomeFocusCheckTest {
    private val pad = JSONObject().put("rows", JSONObject().put("qpick", 10).put("qact", 11))

    /** a quests document: [quests] = (id, slot) tracked, in log order; [focus] the game's focus (0 none) */
    private fun doc(focus: Int, vararg quests: Pair<Int, Int>): JSONObject {
        val list = JSONArray()
        for ((id, slot) in quests) list.put(JSONObject().put("id", id).put("title", "Q$id").put("slot", slot).put("watch", 1)
            .apply { if (id == focus) put("focus", true) })
        return JSONObject().put("pad", pad).put("list", list).apply { if (focus != 0) put("focus", focus) }
    }
    private fun ack(ok: Boolean, id: Int?, why: String? = null) =
        JSONObject().put("op", "track").put("ok", ok).apply { if (id != null) put("id", id); if (why != null) put("why", why) }

    @Test fun aFocusThatTakesIsConfirmedByTheDocument() {
        val hq = HomeQuests()
        hq.update(doc(92703, 92703 to 0, 92860 to 8), current = true, now = 0L)
        assertEquals(FocusTap.Send(92860, 8), hq.tapDisc(92860, live = true, now = 100L))
        assertEquals(FocusCheck.Idle, hq.check(600L))                                   // inside the window: wait
        hq.update(doc(92860, 92703 to 0, 92860 to 8), current = true, now = 700L)
        assertEquals(FocusCheck.Took(92860, 1, 650L), hq.check(750L))
        assertNull(hq.wanted)
        assertNull(hq.pending(760L))
    }

    @Test fun theAddonsOkForOurQuestIsTheConfirm() {
        val hq = HomeQuests()
        hq.update(doc(0, 92860 to 8), current = true, now = 0L)
        hq.tapDisc(92860, live = true, now = 100L)
        assertEquals("", hq.ack(ack(true, 92860), 400L))
        assertNull(hq.wanted)
        assertEquals(FocusCheck.Idle, hq.check(2_000L))                                 // nothing more to do
    }

    @Test fun aStaleColumnIsRetriedByIdFromTheFreshList() {
        // test50's 92860: the add-on's column 8 held no quest any more; the fresh list behind the refusal gives the column again
        val hq = HomeQuests()
        hq.update(doc(0, 92703 to 0, 92860 to 8), current = true, now = 0L)
        hq.tapDisc(92860, live = true, now = 100L)
        assertNull("a refusal shows nothing yet", hq.ack(ack(false, null, "column 8 holds no quest (a stale document)"), 400L))
        assertEquals("the retry waits (a little) for the list behind the refusal", FocusCheck.Idle, hq.check(450L))
        hq.update(doc(0, 92703 to 0, 92860 to 12), current = true, now = 500L)        // (a new session's columns, say)
        val r = hq.check(510L)
        assertTrue(r is FocusCheck.Retry)
        r as FocusCheck.Retry
        assertEquals(92860, r.questId); assertEquals(12, r.slot); assertEquals(1, r.n); assertNull(r.gameFocus)
        assertTrue(r.why, r.why.contains("holds no quest"))
        hq.update(doc(92860, 92703 to 0, 92860 to 12), current = true, now = 900L)
        assertEquals(FocusCheck.Took(92860, 2, 800L), hq.check(900L))
    }

    @Test fun aQuestThatLeftTheLogIsGivenUpAtOnce() {
        val hq = HomeQuests()
        hq.update(doc(0, 92703 to 0, 92860 to 8), current = true, now = 0L)
        hq.tapDisc(92860, live = true, now = 100L)
        hq.ack(ack(false, null, "column 8 holds no quest (a stale document)"), 400L)
        hq.update(doc(0, 92703 to 0), current = true, now = 500L)                       // turned in: not in the fresh list
        val g = hq.check(510L)
        assertTrue(g is FocusCheck.GaveUp)
        g as FocusCheck.GaveUp
        assertTrue(g.why, g.why.endsWith("not in the game's quest list any more"))
        assertNull(hq.pending(520L))
    }

    @Test fun silenceIsRetriedThenGivenUpAfterThreeSends() {
        val hq = HomeQuests()
        hq.update(doc(92703, 92703 to 0, 94896 to 8), current = true, now = 0L)
        hq.tapDisc(94896, live = true, now = 0L)
        assertEquals(FocusCheck.Idle, hq.check(HomeQuests.CHECK_MS - 1))
        val r1 = hq.check(HomeQuests.CHECK_MS)
        assertTrue(r1 is FocusCheck.Retry && r1.n == 1 && r1.gameFocus == 92703 && r1.slot == 8)
        assertEquals(FocusCheck.Idle, hq.check(HomeQuests.CHECK_MS + 100))
        val r2 = hq.check(2 * HomeQuests.CHECK_MS)
        assertTrue(r2 is FocusCheck.Retry && r2.n == 2)
        val g = hq.check(3 * HomeQuests.CHECK_MS)
        assertTrue(g is FocusCheck.GaveUp)
        g as FocusCheck.GaveUp
        assertEquals(HomeQuests.MAX_TRIES, g.tries); assertEquals(92703, g.gameFocus)
        assertEquals(FocusCheck.Idle, hq.check(10_000L))
    }

    @Test fun aRefusalWithNoFreshListStillRetriesAfterItsWait() {
        // an older add-on (q47) sends no list behind a refusal: the retry goes after DOC_WAIT_MS with the newest columns it has
        val hq = HomeQuests()
        hq.update(doc(0, 92860 to 8), current = true, now = 0L)
        hq.tapDisc(92860, live = true, now = 100L)
        hq.ack(ack(false, 92860, "did not take"), 300L)
        assertEquals(FocusCheck.Idle, hq.check(300L + HomeQuests.DOC_WAIT_MS - 1))
        val r = hq.check(300L + HomeQuests.DOC_WAIT_MS)
        assertTrue(r is FocusCheck.Retry && (r as FocusCheck.Retry).why == "did not take")
    }

    @Test fun anOkForAnotherQuestIsAStaleColumn() {
        val hq = HomeQuests()
        hq.update(doc(0, 92860 to 8, 94896 to 9), current = true, now = 0L)
        hq.tapDisc(92860, live = true, now = 0L)
        assertNull(hq.ack(ack(true, 94896), 300L))
        hq.update(doc(94896, 92860 to 8, 94896 to 9), current = true, now = 400L)
        val r = hq.check(1_400L)
        assertTrue(r is FocusCheck.Retry)
        assertTrue((r as FocusCheck.Retry).why.contains("focused 94896 instead"))
        assertEquals(94896, r.gameFocus)
    }

    @Test fun aNewFocusTapReplacesAWaitingOne() {
        // test50: the default focus push for 92860 waited (unanswered) and the player's own tap was refused for 8 s
        val hq = HomeQuests()
        hq.update(doc(0, 92703 to 0, 92860 to 8, 94896 to 9), current = true, now = 0L)
        assertEquals(FocusTap.Send(92860, 8), hq.tapDisc(92860, live = true, now = 0L))
        assertEquals(FocusTap.Refused("Waiting for the game's answer"), hq.tapDisc(92860, live = true, now = 100L))
        assertEquals(FocusTap.Send(94896, 9), hq.tapDisc(94896, live = true, now = 200L))
        assertEquals(94896, hq.wanted)
        // another action still waits for a waiting focus
        assertEquals(FocusTap.Refused("Waiting for the game's answer"), hq.tapUntrack(92703, live = true, now = 300L))
    }

    @Test fun aSendThatFailedWaitsForNothing() {
        val hq = HomeQuests()
        hq.update(doc(0, 92860 to 8), current = true, now = 0L)
        hq.tapDisc(92860, live = true, now = 0L)
        hq.sendFailed()
        assertNull(hq.wanted)
        assertEquals(FocusCheck.Idle, hq.check(5_000L))
    }
}
