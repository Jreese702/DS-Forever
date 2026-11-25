package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
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
        assertEquals(SingleTop.RENEW_MS, SingleTop.renewDelay(42L, null))
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
        val chk = h.substringAfter("private fun singleTopCheck(d: PadMapModel.Dpad) {").substringBefore("\n    }\n")
        assertTrue("the mode wanted is SingleScreen's", chk.contains("SingleScreen.inUse"))
        assertTrue("spaced taps", chk.contains("TapBridge.tapsSpaced(") && chk.contains("SingleTop.GAP_MS"))
        assertTrue("it waits for the other page-3 batches", chk.contains("page3Busy(now, \"ss\")"))
    }
}
