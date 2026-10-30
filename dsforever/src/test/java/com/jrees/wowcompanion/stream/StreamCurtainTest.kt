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

    @Test fun battleNetSignInLiftsTheCurtainOnly() {
        assertEquals(StreamCurtain.Shows.SignIn(StreamCurtain.SIGN_IN),
            StreamCurtain.decide(true, true, PcWow(state = "stopped", screen = "none", bnet = "signin_needed")))
        assertEquals(StreamCurtain.Shows.SignIn(StreamCurtain.NEEDS_YOU),
            StreamCurtain.decide(true, true, PcWow(state = "running", screen = "login", focused = true, bnet = "signed_in", needsYou = true)))
        // signed in to Battle.net again: the curtain comes back until WoW is in
        assertTrue(StreamCurtain.decide(true, true, PcWow(state = "starting", screen = "none", bnet = "signed_in")) is StreamCurtain.Shows.Curtain)
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
        assertEquals(StreamCurtain.Shows.SignIn(StreamCurtain.UPDATE), StreamCurtain.decide(true, true, upd))
        assertEquals(StreamCurtain.Shows.SignIn(StreamCurtain.CRASHED), StreamCurtain.decide(true, true, upd.copy(crash = "crash")))
        assertEquals("update", PcWow.of(org.json.JSONObject().put("state", "running").put("crash", "update"))!!.crash)
    }
}
