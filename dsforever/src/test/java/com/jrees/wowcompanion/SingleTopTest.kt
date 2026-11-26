package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SINGLE SCREEN MODE TO THE ADD-ON, THE APP'S HALF (SingleTop; the user's pick for "While playing": the Wings and our top cast bar in
 * fights, our top cast bar for every cast): the fourth page's taps (padtab 39, 38, 0, then 0 / 1) spaced TapSpacing.MIN_MS apart, the
 * send / wait / match / renew rules on the add-on's `ss` (nothing to an older add-on, nothing in a fight, re-sent after RESEND_MS, renewed
 * inside the add-on's lease, OFF from a two-screen host while the add-on still has it), the padmap's `ss`, the page-4 codes apart, and
 * the host's wiring (every host runs it; the page-3 guard counts it). Pure.
 */
class SingleTopTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @Test fun theFourthPagesTaps() {
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 0, "padtab" to 1), SingleTop.cells(true))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 0, "padtab" to 0), SingleTop.cells(false))
        assertEquals("spaced past TapPad's 0.25 s repeat guard (code 0 right after the prefix's 0)", TapSpacing.MIN_MS, SingleTop.GAP_MS)
        assertTrue(SingleTop.GAP_MS >= 260L)
        assertEquals(3 * SingleTop.GAP_MS, SingleTop.BATCH_MS)
    }

    @Test fun thePageFourCodesAreApart() {
        val all = PadMapModel.PAGE4_CODES.values.flatMap { it.toList() }
        assertEquals("no two uses share a code", all.size, all.toSet().size)
        assertTrue(all.all { it in 0..39 })
        assertEquals(0..1, PadMapModel.PAGE4_CODES["single screen mode"])
    }

    @Test fun sendWaitMatch() {
        assertEquals("an older add-on (no ss): nothing", SingleTop.Act.Wait, SingleTop.decide(true, null, false, null))
        assertEquals("the mode on, the add-on off: send ON", SingleTop.Act.Send(true), SingleTop.decide(true, 0, false, null))
        assertEquals("in a fight: wait (out of combat only)", SingleTop.Act.Wait, SingleTop.decide(true, 0, true, null))
        assertEquals("a send just went: wait", SingleTop.Act.Wait, SingleTop.decide(true, 0, false, 1_000L))
        assertEquals("re-sent after RESEND_MS", SingleTop.Act.Send(true), SingleTop.decide(true, 0, false, SingleTop.RESEND_MS))
        assertEquals("the add-on has it: matched", SingleTop.Act.Matched, SingleTop.decide(true, 1, false, 10_000L))
        assertEquals("a two-screen host while the add-on still has it: OFF", SingleTop.Act.Send(false), SingleTop.decide(false, 1, false, null))
        assertEquals("two screens, the add-on off: nothing", SingleTop.Act.Matched, SingleTop.decide(false, 0, false, null))
    }

    @Test fun renewedInsideTheLease() {
        assertEquals("a mode still on is renewed RENEW_MS after its send", SingleTop.Act.Send(true), SingleTop.decide(true, 1, false, SingleTop.RENEW_MS))
        assertEquals("never in a fight (the add-on keeps an expired lease until the fight's end)", SingleTop.Act.Matched,
            SingleTop.decide(true, 1, true, SingleTop.RENEW_MS + 1))
        assertTrue("renewed well inside the add-on's 900 s lease", SingleTop.RENEW_MS + SingleTop.BATCH_MS + SingleTop.RESEND_MS < SingleTop.ADDON_TTL_MS)
        assertEquals(SingleTop.RENEW_MS, SingleTop.renewDelay(5_000L, 5_000L))
        assertEquals(1_000L, SingleTop.renewDelay(10_000_000L, 0L))
        // the review (2026-11-25): a lease this host did not send is looked at again soon (renewed out of a fight), never trusted for 600 s
        assertEquals(SingleTop.RESEND_MS, SingleTop.renewDelay(42L, null))
    }

    /**
     * THE REVIEW (2026-11-25, "each new host restarts the add-on lease's renewal clock"): a new host (every switch, every launcher resume)
     * that first sees `ss` 1 trusted it as a fresh lease and renewed 600 s later - after the add-on's 900 s lease, started at the OLD
     * host's send, had lapsed (the next fight with no Wings and no top cast bar). A lease this host did not send is renewed at once out of
     * a fight (within one RESEND_MS), its own after RENEW_MS
     */
    @Test fun aNewHostRenewsALeaseItDidNotSend() {
        assertEquals("a new host, the add-on on: ON at once (the lease renewed)", SingleTop.Act.Send(true), SingleTop.decide(true, 1, false, null))
        assertEquals("... in a fight: after it (the add-on keeps an expired lease to the fight's end)", SingleTop.Act.Matched,
            SingleTop.decide(true, 1, true, null))
        assertTrue("looked at again within one RESEND_MS", SingleTop.renewDelay(0L, null) <= SingleTop.RESEND_MS)
        assertEquals("its own send: renewed after RENEW_MS", SingleTop.Act.Matched, SingleTop.decide(true, 1, false, SingleTop.RESEND_MS + 1))
        assertEquals("two screens, the add-on off: nothing (a new host too)", SingleTop.Act.Matched, SingleTop.decide(false, 0, false, null))
    }

    /**
     * THE REVIEW (2026-11-25): the host's half - no first-sighting clock (ssUpAt), a batch TapBridge did not send is neither recorded as
     * sent nor counted as a try (a loading screen: GameInput not live), a fresh current word starts the tries over (no hard stop for the
     * host after six), and before the host's first current word ON goes on the replayed state (idempotent: it renews or turns it on), OFF
     * waits for a current word
     */
    @Test fun theHostsSendsAreCountedOnlyWhenSent() {
        val h = File(src, "CompanionHost.kt").readText()
        val chk = h.substringAfter("private fun singleTopCheck(d: PadMapModel.Dpad, fresh: Boolean = true) {").substringBefore("\n    }\n")
        assertTrue("the check takes whether the word is fresh", chk.isNotEmpty() && chk.length < h.length / 2)
        assertFalse("no first-sighting clock", h.contains("ssUpAt"))
        assertTrue("a fresh word: the tries start over", chk.contains("if (fresh) ssTries = 0"))
        assertTrue("not live: nothing sent, looked at again", chk.contains("if (!GameInput.live(System.currentTimeMillis())) { ui.postDelayed(ssAgain, SingleTop.RESEND_MS); return }"))
        assertTrue("sent only: the stamp and the try", chk.contains("if (sent) { ssSentAt = now + SingleTop.BATCH_MS; ssTries++ }"))
        assertTrue("replayed: ON only", chk.contains("if (!mirrorCurrent && !want) return"))
        assertTrue("the replayed state is looked at in single screen mode", h.contains("else if (!mirrorCurrent && SingleScreen.inUse) singleTopCheck(d, fresh = false)"))
        assertTrue("the renewal's re-check is not fresh", h.contains("private val ssAgain = Runnable { lastDpad?.let { singleTopCheck(it, fresh = false) } }"))
    }

    /** THE REVIEW (2026-11-25): PadMap m200 says a lapsed lease at once (soon()), so the app hears `ss` 0 and sends again */
    @Test fun aLapsedLeaseIsSaid() {
        val pm = File(src.parentFile.parentFile.parentFile.parentFile, "assets/companion/addons/WowCompanionSpells/PadMap.lua").readText()
        val on = pm.substringAfter("R.singleOn = function()").substringBefore("\nend")
        assertTrue("the lapse sends the padmap", on.contains("s.on, s.at = false, 0; soon(); return false"))
    }

    @Test fun theLeaseIsTheAddOnsOwn() {
        val pm = File(src.parentFile.parentFile.parentFile.parentFile, "assets/companion/addons/WowCompanionSpells/PadMap.lua").readText()
        assertTrue("PadMap's SS_TTL is the app's ADDON_TTL_MS", pm.contains("R.SS_TTL = ${SingleTop.ADDON_TTL_MS / 1000}"))
    }

    @Test fun thePadmapsSs() {
        fun dpad(extra: String) = PadMapModel.parse(org.json.JSONObject("""{"type":"padmap","v":"m199","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true$extra}}}"""))!!.dpad!!
        assertEquals(1, dpad(""","ss":1""").ss)
        assertEquals(0, dpad(""","ss":0""").ss)
        assertEquals("out of range reads as on / off only", 1, dpad(""","ss":7""").ss)
        assertNull("an older add-on: null", dpad("").ss)
    }

    @Test fun theHostRunsItAndTheGuardCountsIt() {
        val h = File(src, "CompanionHost.kt").readText()
        assertTrue("every host checks it on each current dpad state", h.contains("previewCheck(d); singleTopCheck(d)"))
        val guard = h.substringAfter("private fun page3Busy(now: Long, mine: String): Boolean =").substringBefore("\n\n")
        assertTrue("the page-3 guard counts the single batch (the same first cells)", guard.contains("\"ss\" to ssSentAt"))
        val chk = h.substringAfter("private fun singleTopCheck(d: PadMapModel.Dpad, fresh: Boolean = true) {").substringBefore("\n    }\n")
        assertTrue("the mode wanted is SingleScreen's", chk.contains("SingleScreen.inUse"))
        assertTrue("spaced taps", chk.contains("TapBridge.tapsSpaced(") && chk.contains("SingleTop.GAP_MS"))
        assertTrue("it waits for the other page-3 batches", chk.contains("page3Busy(now, \"ss\")"))
    }
}
