package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOLD TO CAST IN THE GAME, THE APP'S HALF (PadMap m194; the user on test 1529, 2026-11-24: "When I use ours it feels baggy because of
 * how its delayed for the normal spell buttons"; the pick "Yes, rebuild it that way"). (1) THE GATE IS GONE: the launcher's controller
 * path is the pre-hold-cast one - every press goes to the game in the same call, as made, the packets carry the controller's own state
 * (its digital L2 / R2 bits included: the 1528 audit's fix, now simply the original road), no Guide marker, no focus hook, no probe.
 * (2) The picture: [HoldCast.Watch] times the arc to the add-on's looks (full at the hold point, N x (0.2 s + a frame)), the flash on a
 * release surely past it or on the add-on's word, nothing for a place the add-on does not keep, never B. (3) The hold time to the
 * add-on (Book b82's page-3 codes 29-31): sent out of combat until `hold.n` says it.
 */
class HoldCastTest {
    private val root = listOf(File("../app"), File("app")).first { it.isDirectory }.parentFile!!.absoluteFile
    private fun src(rel: String) = File(root, rel).readText()

    // ---------------------------------------------------------------- (1) the launcher's road: as before hold to cast

    @Test fun thePressesGoToTheGameUnchanged() {
        val w = src("app/src/main/java/com/winlator/winhandler/WinHandler.java")
        assertFalse("no gate in the controller path", w.contains("HoldCast") || w.contains("HoldGate") || w.contains("holdGated"))
        assertTrue("player 1's state as the controller has it (the shared memory)", w.contains("GamepadState state = controller.state;"))
        assertEquals("both packet roads write the controller's own state: every button bit, L2 / R2 included", 2,
            Regex("this\\.currentController\\.state\\.writeTo\\(this\\.sendData\\);").findAll(w).count())
        assertFalse("no Guide marker on SDL button 5", w.contains("sdlButtons[5]"))
        assertFalse("no timer re-sending the pad", w.contains("holdTimer") || w.contains("holdTick") || w.contains("holdResend"))
        val x = src("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt")
        assertFalse("no focus hook", x.contains("HoldCast"))
        assertFalse("the app's gate file is gone", File(root, "companion/app/src/main/java/com/jrees/wowcompanion/HoldGate.kt").exists())
        val h = src("companion/app/src/main/java/com/jrees/wowcompanion/HoldCast.kt")
        assertFalse("no probe, no stored answer", h.contains("class Probe") || h.contains("getSharedPreferences") || h.contains("fun filter("))
        val host = src("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")
        assertFalse("the probe's document is not read", host.contains("\"holdprobe\""))
        assertTrue("the key channel stays parked", src("companion/app/src/main/java/com/jrees/wowcompanion/TapBridge.kt").contains("private const val KEYS_ON = false"))
    }

    // ---------------------------------------------------------------- (2) the picture

    private fun watch(ticks: Int = 3, vararg keys: Int) = HoldCast.Watch { ticks }.apply { map = keys.toSet() }
    private val bareA = HoldCast.key(0, 8)

    @Test fun theHoldPointFollowsTheGamesLooks() {
        assertEquals("Short: always a tap below 0.2 s", 200L, HoldCast.tapBelowMs(2))
        assertEquals("Normal: always a tap below 0.4 s", 400L, HoldCast.tapBelowMs(3))
        assertEquals("Long: always a tap below 0.6 s", 600L, HoldCast.tapBelowMs(4))
        assertEquals("Short: always the hold from 2 looks + a frame each", 434L, HoldCast.holdFromMs(2))
        assertEquals("Normal", 651L, HoldCast.holdFromMs(3))
        assertEquals("Long", 868L, HoldCast.holdFromMs(4))
        assertEquals(listOf(2, 3, 4), CastSettings.HoldTime.values().map { it.ticks })
        assertEquals("test 1528's stored names keep their places", listOf("SHORT", "NORMAL", "LONG"), CastSettings.HoldTime.values().map { it.name })
    }

    @Test fun theArcFillsToTheHoldPointWhileAKeptPressIsHeld() {
        val w = watch(3, bareA)
        w.press(8, 0, 1000)
        assertEquals(0f, w.progress(8, 1000), 0f)
        assertEquals(0.5f, w.progress(8, 1000 + HoldCast.holdFromMs(3) / 2), 0.01f)
        assertEquals("full at the hold point", 1f, w.progress(8, 1000 + HoldCast.holdFromMs(3)), 0f)
        assertEquals("and stays full", 1f, w.progress(8, 5000), 0f)
        assertEquals("on the layer of its press", 0, w.layerOf(8))
        assertEquals("another button: nothing", -1f, w.progress(6, 1300), 0f)
        val lt = watch(3, bareA)
        lt.press(8, 1, 1000)
        assertEquals("LT + A: not a kept place (no arc)", -1f, lt.progress(8, 1300), 0f)
        assertEquals(-1, lt.layerOf(8))
        val b = watch(3, HoldCast.key(0, 7))
        b.press(7, 0, 1000)
        assertEquals("never B", -1f, b.progress(7, 1300), 0f)
    }

    @Test fun theFlashOnTheRelease() {
        val w = watch(3, bareA)
        w.press(8, 0, 1000)
        assertFalse("a tap: no flash", w.release(8, 1000 + HoldCast.tapBelowMs(3) - 1)); assertNull(w.lastFire)
        assertEquals("let go: no arc", -1f, w.progress(8, 1500), 0f)
        w.press(8, 0, 2000)
        assertTrue("surely past the hold point: the flash at the release", w.release(8, 2000 + HoldCast.holdFromMs(3)))
        assertEquals(8 to 2000L + HoldCast.holdFromMs(3), w.lastFire)
        assertFalse("the add-on's word for the same release: no second flash", w.confirm(8, 2000 + HoldCast.holdFromMs(3) + 300))
        // between the two ends the game's phase decided: the add-on's word (its release's facecast) flashes it
        w.press(8, 0, 4000)
        assertFalse(w.release(8, 4500))
        assertTrue("the add-on said it held: the flash then", w.confirm(8, 4700))
        assertEquals(8 to 4700L, w.lastFire)
        assertFalse("a word long after any release of ours: nothing", w.confirm(8, 4500 + HoldCast.CONFIRM_MS + 1))
        assertFalse("a word for a button never let go here: nothing", watch(3, bareA).confirm(8, 4700))
    }

    @Test fun theHoldTimeFollowsSettings() {
        val w = watch(2, bareA)
        w.press(8, 0, 0)
        assertTrue("Short: past 0.43 s", w.release(8, HoldCast.holdFromMs(2)))
        val l = watch(4, bareA)
        l.press(8, 0, 0)
        assertFalse("Long: 0.7 s is not sure yet", l.release(8, 700))
    }

    @Test fun theMap() {
        val m = HoldCast.parseMap(JSONObject().put("type", "holdmap").put("on", true).put("b", "bare:8,lt:6,ltrt:1,rt:9,odd:2,bare,bare:7"))!!
        assertEquals("the places the add-on keeps (never B)", setOf(HoldCast.key(0, 8), HoldCast.key(1, 6), HoldCast.key(3, 1)), m)
        assertEquals(emptySet<Int>(), HoldCast.parseMap(JSONObject().put("type", "holdmap").put("b", "bare:8")))
        assertNull(HoldCast.parseMap(JSONObject().put("type", "padmap")))
        assertEquals(HoldCast.key(0, 8), HoldCast.keyOf("bare", 8)); assertEquals(HoldCast.key(3, 1), HoldCast.keyOf("ltrt", 1))
        assertNull(HoldCast.keyOf("bare", 9)); assertNull(HoldCast.keyOf("x", 1))
    }

    @Test fun theAddonsWordFlashesOnlyAKeptReleasesHold() {
        HoldCast.watch.reset(); HoldCast.watch.map = setOf(bareA)
        val now = 50_000L
        HoldCast.clock = { now }; HoldCast.bandClock = { now }
        HoldCast.watch.press(8, 0, now - 500); HoldCast.watch.release(8, now - 100)
        var told = 0
        HoldCast.onChange = { told++ }
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 8).put("kind", "native").put("down", true))
        assertEquals("a key-down's word: nothing", 0, told)
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 8).put("kind", "slothold").put("hk", "rel").put("down", true))
        assertEquals("the kept release ran its hold: the flash", 1, told)
        HoldCast.onChange = null; HoldCast.watch.reset(); HoldCast.watch.map = emptySet(); HoldCast.takeover.clear()
        HoldCast.clock = { android.os.SystemClock.uptimeMillis() }; HoldCast.bandClock = { android.os.SystemClock.elapsedRealtime() }
    }

    // ---------------------------------------------------------------- (2b) m195: the charge ring round the badge

    @Test fun theChargeRingShowsFromAQuarterSecondAndFillsToTheHoldPoint() {
        for ((n, full) in listOf(2 to 434L, 3 to 651L, 4 to 868L)) {
            val w = watch(n, bareA)
            w.press(8, 0, 1000)
            assertEquals("$n: hidden at 0.2 s (quick taps show nothing)", -1f, w.charge(8, 1200), 0f)
            assertEquals("$n: hidden just before 0.25 s", -1f, w.charge(8, 1249), 0f)
            assertEquals("$n: appears at 0.25 s, empty", 0f, w.charge(8, 1250), 0f)
            assertEquals("$n: half way to the hold point", 0.5f, w.charge(8, 1000 + 250 + (full - 250) / 2), 0.01f)
            assertFalse("$n: not charged before the hold point", w.charged(8, 1000 + full - 1))
            assertEquals("$n: full at the hold point", 1f, w.charge(8, 1000 + full), 0f)
            assertTrue("$n: charged from it", w.charged(8, 1000 + full))
            w.release(8, 1000 + full + 50)
            assertEquals("$n: let go: gone", -1f, w.charge(8, 1000 + full + 60), 0f)
        }
        val e = watch(3, bareA)
        e.press(8, 0, 0)
        assertFalse("an early release (0.4 s): a tap", e.release(8, 400))
        assertEquals("... the ring gone", -1f, e.charge(8, 410), 0f)
        assertNull(e.lastFire)
        val nk = watch(3)
        nk.press(8, 0, 0)
        assertEquals("a place the add-on does not keep: never a ring", -1f, nk.charge(8, 600), 0f)
    }

    // ---------------------------------------------------------------- (2c) m195: the takeover

    @Test fun theTakeoverHoldsTheFaceToTheCastAndTheGcdThenGoesBack() {
        val t = HoldCast.Takeover()
        assertEquals("nothing yet", 0f, t.alpha(0), 0f)
        // a 1.8 s cast with a 1.5 s GCD: to the cast's end, then eased back over 0.2 s
        t.start(8, 0, 1000)
        assertEquals(1f, t.alpha(1000), 0f)
        t.cast(1800, 1050); t.gcd(1050, 1500)
        assertEquals("mid cast", 1f, t.alpha(2500), 0f)
        assertEquals("the GCD over, the cast still running", 1f, t.alpha(2800), 0f)
        assertEquals("the cast ended: easing", 0.5f, t.alpha(2850 + HoldCast.EASE_MS / 2), 0.01f)
        assertEquals("back", 0f, t.alpha(2850 + HoldCast.EASE_MS), 0f)
        assertEquals("cleared", -1, t.pos)
        // an instant: to its GCD's end
        t.start(8, 0, 5000); t.ok(5040); t.gcd(5040, 1500)
        assertEquals(1f, t.alpha(6500), 0f)
        assertEquals("the GCD done: back", 0f, t.alpha(6540 + HoldCast.EASE_MS), 0f)
        // refused / interrupted / cancelled: back at once
        t.start(8, 0, 8000); t.cast(2000, 8050); t.fail(8300)
        assertTrue("fail: easing at once", t.alpha(8300 + HoldCast.EASE_MS / 2) < 1f)
        assertEquals(0f, t.alpha(8300 + HoldCast.EASE_MS), 0f)
        // no word at all: the fallback (never stuck)
        t.start(8, 0, 10_000)
        assertEquals(1f, t.alpha(10_000 + HoldCast.FALLBACK_MS - 1), 0f)
        assertEquals(0f, t.alpha(10_000 + HoldCast.FALLBACK_MS + HoldCast.EASE_MS), 0f)
        // a GCD that started before the fire is not the hold's
        t.start(8, 0, 20_000); t.gcd(19_000, 5_000)
        assertEquals(0f, t.alpha(20_000 + HoldCast.FALLBACK_MS + HoldCast.EASE_MS), 0f)
        // a very long cast still ends (15 s cap)
        t.start(8, 0, 30_000); t.cast(60_000, 30_000)
        assertEquals(0f, t.alpha(45_000 + HoldCast.EASE_MS), 0f)
    }

    /**
     * RULING A (the user's pick 2026-11-25, "Stay until it ends (Recommended)"): a TRIGGER layer's hold (LT + X) keeps its takeover on the
     * main circle after the trigger is let go - the band back on the bare layer still shows LT + X's hold spell on that button's face
     * until its cast and GCD end (or it fails, or the existing timeouts); a layer with a trigger the press did not have (RT, LT + RT) is
     * that layer's own buttons. LT + RT's hold: either trigger let go (LT alone, RT alone) or both. A bare hold: the bare layer only
     */
    @Test fun aTriggerLayersTakeoverStaysOnTheMainCircleAfterTheTriggerIsLetGo() {
        val t = HoldCast.Takeover()
        assertFalse("nothing running: on no layer", (0..3).any { t.showsOn(it) })
        t.start(5, 1, 1_000)                                            // LT + X's hold fired
        assertTrue("LT held: its own layer", t.showsOn(1))
        assertTrue("LT let go: the bare layer's main circle keeps it", t.showsOn(0))
        assertFalse("RT held instead: RT's own buttons", t.showsOn(2))
        assertFalse("LT + RT held: that layer's own", t.showsOn(3))
        t.cast(1_800, 1_050); t.gcd(1_050, 1_500)
        assertEquals("LT let go mid-cast: still on the face", 1f, t.alpha(2_500), 0f)
        assertEquals("the cast over: back (the takeover's own end, whatever the layer)", 0f, t.alpha(2_850 + HoldCast.EASE_MS), 0f)
        assertFalse("ended: on no layer", t.showsOn(0))
        t.start(5, 3, 5_000)                                            // LT + RT + X
        assertTrue((0..3).all { t.showsOn(it) })
        t.start(5, 2, 6_000)                                            // RT + X
        assertTrue(t.showsOn(2) && t.showsOn(0)); assertFalse(t.showsOn(1) || t.showsOn(3))
        t.start(5, 0, 7_000)                                            // the bare X
        assertTrue(t.showsOn(0)); assertFalse((1..3).any { t.showsOn(it) })
        t.fail(7_100)
        assertEquals("a fail: back at once (no layer keeps it)", 0f, t.alpha(7_100 + HoldCast.EASE_MS), 0f)
        // the band draws it there: CastView asks showsOn for the layer it shows, and the hold refs of the FIRED layer
        val cast = src("companion/app/src/main/java/com/jrees/wowcompanion/CastView.kt")
        assertTrue(cast.contains("if (t.pos != src + 1 || !t.showsOn(li)) return Long.MAX_VALUE"))
        assertTrue("the fired layer's hold spell, not the shown layer's", cast.contains("val key = t.layer * 8 + src"))
    }

    @Test fun theTakeoverFollowsTheAddonsWords() {
        HoldCast.takeover.clear(); HoldCast.watch.reset(); HoldCast.watch.map = setOf(bareA)
        var now = 100_000L
        HoldCast.bandClock = { now }; HoldCast.clock = { now }
        HoldCast.watch.press(8, 0, now); now += HoldCast.holdFromMs(CastSettings.holdTime.ticks)
        assertTrue(HoldCast.watch.release(8, now))
        assertEquals("a sure release starts the takeover on that button and layer", 8, HoldCast.takeover.pos)
        HoldCast.onHoldCast(JSONObject().put("type", "holdcast").put("pos", 8).put("st", "cast").put("d", 3.0))
        now += 2500
        assertEquals("its 3 s cast keeps it", 1f, HoldCast.takeover.alpha(now), 0f)
        HoldCast.onHoldCast(JSONObject().put("type", "holdcast").put("pos", 8).put("st", "fail"))
        now += HoldCast.EASE_MS
        assertEquals("interrupted: back", 0f, HoldCast.takeover.alpha(now), 0f)
        // the app's own timing said "sure" but the add-on's release ran the TAP: back at once
        HoldCast.watch.press(8, 0, now); now += HoldCast.holdFromMs(CastSettings.holdTime.ticks)
        HoldCast.watch.release(8, now)
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 8).put("kind", "native").put("hk", "rel").put("down", true))
        now += HoldCast.EASE_MS
        assertEquals("the release was the tap: no takeover", 0f, HoldCast.takeover.alpha(now), 0f)
        // a release in the phase window: the add-on's word starts it
        HoldCast.watch.press(8, 0, now); now += 500
        assertFalse(HoldCast.watch.release(8, now))
        assertEquals(-1, HoldCast.takeover.pos)
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 8).put("kind", "slothold").put("hk", "rel").put("down", true))
        assertEquals("the add-on said the hold ran: the takeover", 8, HoldCast.takeover.pos)
        HoldCast.takeover.clear(); HoldCast.watch.reset(); HoldCast.watch.map = emptySet()
        HoldCast.bandClock = { android.os.SystemClock.elapsedRealtime() }; HoldCast.clock = { android.os.SystemClock.uptimeMillis() }
    }

    /** the 1531 audit: the Bind box's holdmap (why "box": every place, for the ring of its own timing) - a long press there places the
     *  box's spell as a hold, it casts nothing, so no takeover; out of the box the same long press is one */
    @Test fun aLongPressInTheBindBoxIsNoTakeover() {
        HoldCast.takeover.clear(); HoldCast.watch.reset()
        val band = HoldCast.bandClock
        HoldCast.bandClock = { 1_000L }
        fun map(why: String) = HoldCast.onMap(JSONObject().put("type", "holdmap").put("on", true).put("b", "bare:8").put("why", why))
        map("box")
        var now = 50_000L
        HoldCast.watch.press(8, 0, now); now += HoldCast.holdFromMs(HoldCast.watch.ticks) + 10L
        HoldCast.watch.release(8, now)
        assertEquals("the box's long press: no takeover", -1, HoldCast.takeover.pos)
        map("on")
        HoldCast.watch.press(8, 0, now); now += HoldCast.holdFromMs(HoldCast.watch.ticks) + 10L
        HoldCast.watch.release(8, now)
        assertEquals("a kept press's sure hold: the takeover", 8, HoldCast.takeover.pos)
        HoldCast.takeover.clear(); HoldCast.watch.reset()
        // the 1532 audit: the add-on's word (a slothold facecast) for a release in the Bind box is no takeover either
        map("box")
        HoldCast.watch.press(8, 0, now); now += 300L
        HoldCast.watch.release(8, now)
        HoldCast.clock = { now }
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 8).put("kind", "slothold").put("hk", "rel").put("down", true))
        assertEquals("the box: the add-on's word starts nothing", -1, HoldCast.takeover.pos)
        HoldCast.clock = { android.os.SystemClock.uptimeMillis() }
        HoldCast.takeover.clear(); HoldCast.watch.reset(); HoldCast.watch.map = emptySet(); HoldCast.bandClock = band
    }

    /**
     * THE 1532 AUDIT - every page-3 sender waits out every OTHER one's batch (TapPad's 0.25 s repeat guard swallows a batch's first 39
     * when another batch's 39 just went): the toolbar mirror, the top flag, the fight cast bar, the swing, Edit Home's preview and the hold
     * time all read one guard over all six stamps (1531: the mirror / top / cast / swing checks did not wait for the hold time's batch,
     * which Settings > Hold time sends at once)
     */
    @Test fun everyPageThreeSenderWaitsOutEveryOther() {
        val host = src("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")
        val guard = host.substringAfter("private fun page3Busy(").substringBefore("\n    }").substringBefore("\n\n")
        for (st in listOf("mirrorSentAt", "topSentAt", "castSentAt", "swingSentAt", "pvSentAt", "holdSentAt"))
            assertTrue("the guard reads $st", guard.contains(st))
        for ((fn, mine) in listOf("mirrorCheck" to "mirror", "topCheck" to "top", "castCheck" to "cast", "swingCheck" to "swing",
                "previewCheck" to "pv", "holdTicksCheck" to "hold")) {
            val body = host.substringAfter("private fun $fn(").substringBefore("\n    private fun ")
            assertTrue("$fn waits out the others", body.contains("page3Busy(now, \"$mine\")"))
        }
    }

    /** the 1532 audit: the hold time's sends stop after [HoldCast.SEND_TRIES] for one wish (a later padmap no longer sends it again) */
    @Test fun theHoldTimeSendsStopAfterItsTries() {
        assertEquals(3, HoldCast.ticksToSend(3, 2, false, null, tries = 0))
        assertEquals(3, HoldCast.ticksToSend(3, 2, false, 5_000L, tries = HoldCast.SEND_TRIES - 1))
        assertNull("its tries spent: no more", HoldCast.ticksToSend(3, 2, false, 5_000L, tries = HoldCast.SEND_TRIES))
    }

    // ---------------------------------------------------------------- (3) the hold time to the add-on

    @Test fun theHoldTimeIsSentOutOfCombatUntilTheAddonSaysIt() {
        assertNull("an add-on before m194: nothing", HoldCast.ticksToSend(2, null, false, null))
        assertNull("already said", HoldCast.ticksToSend(3, 3, false, null))
        assertEquals(2, HoldCast.ticksToSend(2, 3, false, null))
        assertNull("in a fight: after it", HoldCast.ticksToSend(2, 3, true, null))
        assertNull("just sent", HoldCast.ticksToSend(2, 3, false, 1000L))
        assertEquals("again after the resend gap", 4, HoldCast.ticksToSend(4, 3, false, TopCombatBar.RESEND_MS))
        assertEquals(listOf(PadMapModel.ROW_PADTAB to 39, PadMapModel.ROW_PADTAB to 38, PadMapModel.ROW_PADTAB to 37, PadMapModel.ROW_PADTAB to 29),
            PadMapModel.holdTimeCells(2))
        assertEquals(PadMapModel.ROW_PADTAB to 31, PadMapModel.holdTimeCells(4).last())
        assertEquals(29..31, PadMapModel.PAGE3_CODES["hold time"])
        val m = PadMapModel.parse(JSONObject().put("type", "padmap").put("v", "m194").put("m", JSONObject().put("page", 1).put("pages", 3)
            .put("sets", org.json.JSONArray()).put("hold", JSONObject().put("on", true).put("n", 4).put("lk", 12))))!!
        assertEquals(4, m.holdN); assertEquals(12, m.holdLooks)
    }

    /** the 1531 audit: the hold time's page-3 batch never lands inside another batch's TapPad repeat guard (the same first cells 39 / 38 /
     *  37) - it waits out the toolbar mirror's, the top bar's, the cast bar's and the swing's sends, and a padmap's check runs AFTER that
     *  padmap's D-pad checks (posted), so a batch they just sent is one it sees; before, a login after a reset could have both in 0.25 s */
    @Test fun theHoldTimeWaitsOutEveryOtherPage3Batch() {
        val host = src("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")
        assertTrue(host.contains("if (page3Busy(now, \"hold\")) { ui.postDelayed(holdAgain, CAST_AFTER_TOP_MS); return }"))   // (every other page-3 batch: the 1532 audit)
        assertTrue("the padmap's check posted after its D-pad checks",
            host.contains("if (current) ui.post { lastPadMap?.let { m -> if (m === it) holdTicksCheck(m) } }"))
        assertFalse(host.contains("if (current) holdTicksCheck(it)"))
    }
}
