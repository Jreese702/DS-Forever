package com.jrees.wowcompanion.stream

import com.jrees.wowcompanion.SetupFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** the curtain over the stream (the user, 2026-10-28): the PC's desktop is never shown; Battle.net's sign-in is the one exception */
class StreamCurtainTest {
    private fun curtain(s: StreamCurtain.Shows) = s as StreamCurtain.Shows.Curtain

    @Test fun curtainUntilSignedInAndInFront() {
        val starting = PcWow(state = "starting", screen = "none", focused = false, bnet = "signed_in")
        assertEquals("Starting WoW on your PC", curtain(StreamCurtain.decide(true, true, starting)).line)
        val login = PcWow(state = "running", screen = "signing_in", focused = true, bnet = "signed_in")
        assertEquals("Signing in", curtain(StreamCurtain.decide(true, true, login)).line)
        val charBehind = PcWow(state = "running", screen = "charselect", focused = false, bnet = "signed_in")
        assertEquals("Bringing WoW to the front", curtain(StreamCurtain.decide(true, true, charBehind)).line)
        assertEquals(StreamCurtain.Shows.Stream, StreamCurtain.decide(true, true, charBehind.copy(focused = true)))
        assertEquals(StreamCurtain.Shows.Stream, StreamCurtain.decide(true, true, charBehind.copy(screen = "world", focused = true)))
        // the stream not up yet: the curtain, whatever the PC says
        assertTrue(StreamCurtain.decide(false, true, charBehind.copy(focused = true)) is StreamCurtain.Shows.Curtain)
    }

    @Test fun rowsSayWhereItIs() {
        val c = curtain(StreamCurtain.decide(true, true, PcWow(state = "running", screen = "login", focused = true, bnet = "signed_in", loginError = "308")))
        assertEquals(listOf("PC found", "Battle.net", "WoW starting", "Signing in"), c.rows.map { it.label })
        assertEquals(SetupFlow.Mark.OK, c.rows.first().mark)
        assertEquals(listOf(SetupFlow.Mark.OK, SetupFlow.Mark.OK, SetupFlow.Mark.OK, SetupFlow.Mark.BAD), c.rows.map { it.mark })
        val b = curtain(StreamCurtain.decide(true, true, PcWow(state = "stopped", screen = "none", bnet = "not_running")))
        assertEquals("Starting Battle.net on your PC", b.line)
        assertEquals(SetupFlow.Mark.WAIT, b.rows.first { it.label == "Battle.net" }.mark)
        val none = curtain(StreamCurtain.decide(true, true, null, dots = 2))
        assertEquals("Loading..", none.line)
        assertEquals("Connecting to your PC", curtain(StreamCurtain.decide(false, true, null)).line)
    }

    @Test fun battleNetSignInShowsItsWindowOnly() {
        val r = listOf(0.42f, 0.29f, 0.16f, 0.43f)
        assertEquals(StreamCurtain.Shows.SignIn(StreamCurtain.SIGN_IN, r),
            StreamCurtain.decide(true, true, PcWow(state = "stopped", screen = "none", bnet = "signin_needed", show = r)))
        assertEquals(StreamCurtain.Shows.SignIn(StreamCurtain.NEEDS_YOU, r),
            StreamCurtain.decide(true, true, PcWow(state = "running", screen = "login", focused = true, bnet = "signed_in", needsYou = true, show = r)))
        // where the window is isn't known yet: the curtain says it, the desktop never shows (the user, 2026-10-30)
        val c = StreamCurtain.decide(true, true, PcWow(state = "stopped", screen = "none", bnet = "signin_needed"))
        assertEquals(StreamCurtain.SIGN_IN, (c as StreamCurtain.Shows.Curtain).line)
        // signed in to Battle.net again: the curtain comes back until WoW is in
        assertTrue(StreamCurtain.decide(true, true, PcWow(state = "starting", screen = "none", bnet = "signed_in")) is StreamCurtain.Shows.Curtain)
        assertEquals(r, PcWow.of(JSONObject().put("show", "0.42,0.29,0.16,0.43"))!!.show)
        assertEquals(emptyList<Float>(), PcWow.of(JSONObject().put("show", "0.42,0.29,2,0.43"))!!.show)
    }

    @Test fun theSavedLoginGoesInBehindTheCurtain() {
        val r = listOf(0.42f, 0.29f, 0.16f, 0.43f)
        val s = StreamCurtain.decide(true, true, PcWow(state = "stopped", screen = "none", bnet = "signin_needed", show = r), signingIn = true)
        assertEquals(StreamCurtain.SIGNING_IN, (s as StreamCurtain.Shows.Curtain).line)
        val form = BridgeProtocol.parse("""{"t":"bnet_ready_result","ok":true,"password":"0.14,0.18","button":"0.14,0.25"}""") as BridgeMsg.BnetReady
        assertEquals(BnetFormAt(null, 0.14f to 0.18f, 0.14f to 0.25f), form.form)
        assertEquals(null, (BridgeProtocol.parse("""{"t":"bnet_ready_result","ok":false,"why":"x"}""") as BridgeMsg.BnetReady).form)
    }

    @Test fun oneAttemptThenThePlayer() {
        var t = 0L; var has = true; var open = false; var asks = 0; var forms = 0; val typed = ArrayList<BnetFormAt>()
        val s = StreamSignIn({ has }, { open }, { forms++; open = true }, { asks++ }, { typed += it }, { t })
        val form = BnetFormAt(null, 0.1f to 0.2f, 0.1f to 0.3f)
        s.tick(true); assertEquals(StreamSignIn.Phase.ASKING, s.phase); assertEquals(1, asks)
        s.answer(form); assertEquals(listOf(form), typed); assertTrue(s.working)
        t += StreamSignIn.TYPED_MS + 1; s.tick(true)
        assertEquals("a refused login is never typed again", StreamSignIn.Phase.MANUAL, s.phase)
        s.tick(true); assertEquals(1, asks); assertEquals(1, typed.size)
        s.tick(false); assertEquals(StreamSignIn.Phase.IDLE, s.phase)
        // an unknown form, or an old Desktop that never answers: the player, nothing typed
        s.tick(true); s.answer(null); assertEquals(StreamSignIn.Phase.MANUAL, s.phase); assertEquals(1, typed.size)
        s.tick(false); s.tick(true); t += StreamSignIn.ASK_MS + 1; s.tick(true); assertEquals(StreamSignIn.Phase.MANUAL, s.phase)
        // nothing saved: the form once; saved -> asks; closed unsaved -> the player
        s.tick(false); has = false; s.tick(true); assertEquals(StreamSignIn.Phase.FORM, s.phase); assertEquals(1, forms)
        has = true; s.tick(true); assertEquals(StreamSignIn.Phase.ASKING, s.phase)
        s.tick(false); has = false; open = false; s.tick(true); open = false
        t += StreamSignIn.FORM_GRACE_MS + 1; s.tick(true); assertEquals(StreamSignIn.Phase.MANUAL, s.phase)
    }

    @Test fun olderDesktopAndSunshineOnly() {
        // Desktop 0.2.0: no screen; running and (when said) focused opens it
        assertEquals(StreamCurtain.Shows.Stream, StreamCurtain.decide(true, true, PcWow(state = "running")))
        assertTrue(StreamCurtain.decide(true, true, PcWow(state = "running", focused = false)) is StreamCurtain.Shows.Curtain)
        assertTrue(StreamCurtain.decide(true, true, PcWow(state = "starting")) is StreamCurtain.Shows.Curtain)
        // Sunshine alone says nothing about WoW: the stream as before
        assertEquals(StreamCurtain.Shows.Stream, StreamCurtain.decide(true, false, null))
    }

    @Test fun desktopFieldsParse() {
        val w = BridgeProtocol.parse(JSONObject().put("t", "wow").put("state", "running").put("screen", "charselect").put("focused", true)
            .put("bnet", "signed_in").put("needs_you", false).put("login_error", "308").toString()) as BridgeMsg.Wow
        assertEquals(PcWow("running", "", "charselect", true, "signed_in", false, "308"), w.pc)
        val welcome = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("wow", JSONObject().put("running", true).put("state", "running")
            .put("screen", "world").put("focused", false).put("bnet", "signin_needed")).toString()) as BridgeMsg.Welcome
        assertEquals("signin_needed", welcome.pc?.bnet)
        assertEquals(false, welcome.pc?.focused)
        val fr = BridgeProtocol.parse(JSONObject().put("t", "focus_result").put("ok", false).put("state", "running").put("screen", "login")
            .put("focused", true).toString()) as BridgeMsg.FocusResult
        assertEquals("login", fr.pc?.screen)
        val old = BridgeProtocol.parse(JSONObject().put("t", "focus_result").put("ok", true).toString()) as BridgeMsg.FocusResult
        assertEquals(null, old.pc)
    }

    /** party3 said "PC found" while the PC never got a connection: it ticks only once the PC answered, with the route; a failure says why */
    @Test fun pcFoundOnlyWhenThePcAnswered() {
        val looking = curtain(StreamCurtain.decide(false, true, null))
        assertEquals(listOf(StreamCurtain.LOOKING), looking.rows.map { it.label })
        assertEquals(SetupFlow.Mark.WAIT, looking.rows.first().mark)
        val why = "Can't reach your PC - is DS Forever Desktop running? (Tailscale is off)"
        assertEquals(why, curtain(StreamCurtain.decide(false, true, null, problem = why)).line)
        val home = curtain(StreamCurtain.decide(false, true, PcWow(state = "starting", screen = "none", bnet = "signed_in"), route = "home Wi-Fi"))
        assertEquals("PC found (home Wi-Fi)", home.rows.first().label)
        assertEquals(SetupFlow.Mark.OK, home.rows.first().mark)
        assertEquals(listOf(StreamCurtain.LOOKING), curtain(StreamCurtain.decide(false, false, null)).rows.map { it.label })
    }

    /** the user's PC 2026-10-29: WoW crashed on start (its files older than the game's data) behind a black stream: say so, show Battle.net */
    @Test fun aCrashedClientShowsBattleNetWithWhy() {
        val upd = PcWow(state = "running", screen = "login", bnet = "signed_in", crash = "update")
        val r = listOf(0.4f, 0.1f, 0.5f, 0.7f)
        assertEquals(StreamCurtain.Shows.SignIn(StreamCurtain.UPDATE, r), StreamCurtain.decide(true, true, upd.copy(show = r)))
        assertEquals(StreamCurtain.Shows.SignIn(StreamCurtain.CRASHED, r), StreamCurtain.decide(true, true, upd.copy(crash = "crash", show = r)))
        assertEquals(StreamCurtain.UPDATE, (StreamCurtain.decide(true, true, upd) as StreamCurtain.Shows.Curtain).line)
        assertEquals("update", PcWow.of(org.json.JSONObject().put("state", "running").put("crash", "update"))!!.crash)
    }

    @Test fun wowHoldingAtItsLoginStepsShowsOnceInFront() {
        val rules = PcWow(state = "running", focused = true, screen = "signing_in", bnet = "signed_in")
        assertEquals("Signing in", curtain(StreamCurtain.decide(true, true, rules)).line)
        assertEquals(StreamCurtain.Shows.Stream, StreamCurtain.decide(true, true, rules, atLoginLong = true))
        assertEquals("Signing in", curtain(StreamCurtain.decide(true, true, rules.copy(focused = false), atLoginLong = true)).line)
    }
}
