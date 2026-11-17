package com.jrees.wowcompanion.stream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's PC 2026-11-17: Windows refused Sunshine's RTSP port at boot, HTTP kept answering, and the Thor retried "RTSP handshake
 * failed 110" every 11 s for ever. The picks: "Full: PC + Thor" and give-up = "Message + Retry button". Off-device.
 */
class HostHealTest {
    @Test fun todaysLog_isAnRtspTimeout() {
        // "stage RTSP handshake failed 110", then "stream ended (110, paired true)"
        assertTrue(HostHeal.isRtspTimeout(110, "RTSP handshake"))
        assertTrue(HostHeal.isRtspTimeout(111, "RTSP handshake"), "refused: nothing listens on RTSP")
        assertFalse(HostHeal.isRtspTimeout(110, "control stream establishment"))
        assertFalse(HostHeal.isRtspTimeout(-1, "RTSP handshake"))
        assertFalse(HostHeal.isRtspTimeout(110, ""))
    }

    private fun assertTrue(c: Boolean, why: String) = org.junit.Assert.assertTrue(why, c)

    @Test fun twoTimeouts_askThePc_thenOneMoreTry_thenRetryByHand() {
        val h = HostHeal()
        assertEquals(HostHeal.Next.NORMAL, h.rtspTimeout(true))          // the first: the normal retry
        assertEquals(HostHeal.Next.ASK_PC, h.rtspTimeout(true))          // the second: ask Desktop
        assertEquals(HostHeal.Phase.ASKED, h.phase)
        assertEquals(HostHeal.Next.WAIT, h.answer("restarting"))
        assertEquals(HostHeal.Next.RETRY, h.answer("ok"))
        assertEquals(HostHeal.Next.GIVE_UP, h.rtspTimeout(true))         // it failed again: the message and Retry
        assertEquals(HostHeal.Phase.GAVE_UP, h.phase)
        assertEquals(HostHeal.Next.GIVE_UP, h.rtspTimeout(true))         // never loops
        h.reset()                                                         // the player's Retry
        assertEquals(HostHeal.Next.NORMAL, h.rtspTimeout(true))
    }

    @Test fun neverForever_withoutABridgeOrAnAnswer() {
        val plain = HostHeal()
        plain.rtspTimeout(false)
        assertEquals(HostHeal.Next.GIVE_UP, plain.rtspTimeout(false))    // a plain Sunshine: nobody to ask
        val silent = HostHeal()
        silent.rtspTimeout(true); silent.rtspTimeout(true)
        assertEquals(HostHeal.Next.GIVE_UP, silent.noAnswer())
        val failed = HostHeal()
        failed.rtspTimeout(true); failed.rtspTimeout(true)
        assertEquals(HostHeal.Next.GIVE_UP, failed.answer("failed"))
        val live = HostHeal()
        live.rtspTimeout(true); live.rtspTimeout(true)
        assertEquals(HostHeal.Next.GIVE_UP, live.answer("streaming"))
    }

    @Test fun anotherFailureBreaksTheRun_andALateAnswerIsIgnored() {
        val h = HostHeal()
        h.rtspTimeout(true)
        h.otherEnd()
        assertEquals(HostHeal.Next.NORMAL, h.rtspTimeout(true))          // counting starts over
        assertEquals(HostHeal.Next.NORMAL, HostHeal().answer("ok"))      // not asked: nothing to do
    }

    @Test fun desktopsMessagesParse() {
        val sun = JSONObject().put("http_port", 53989).put("https_port", 53984).put("problem", "restarting").put("why", HostHeal.RESTARTING)
        val r = BridgeProtocol.parse(JSONObject().put("t", "host_check_result").put("state", "restarting").put("sunshine", sun).toString())
            as BridgeMsg.Sunshine
        assertEquals("restarting", r.state); assertTrue(r.answer)
        assertEquals(StreamPorts.Report(true, 53989, 53984), r.report)
        val moved = BridgeProtocol.parse(JSONObject().put("t", "sunshine").put("sunshine", sun).toString()) as BridgeMsg.Sunshine
        assertFalse(moved.answer); assertEquals(53989, moved.httpPort)
        assertEquals("host_check", JSONObject(BridgeProtocol.hostCheck()).getString("t"))
        assertEquals(HostHeal.RESTARTING, StreamCurtain.reason("restarting", ""))
        assertEquals(HostHeal.DOWN, StreamCurtain.reason("not_responding", ""))
    }

    @Test fun theBridgePassesThemOn() {
        val welcome = JSONObject().put("t", "welcome").put("host_id", "h").put("pc_name", "Den PC")
            .put("sunshine", JSONObject().put("http_port", 52989)).toString()
        val said = JSONObject().put("t", "sunshine").put("sunshine", JSONObject().put("http_port", 53989)).toString()
        val frames = listOf(welcome, said)
        var i = 0
        val c = object : BridgeConn {
            val sent = ArrayList<String>()
            override val closeCode = 1006
            override val peerFingerprint = ""
            override fun send(text: String) { sent.add(text) }
            override fun receive(): String? = frames.getOrNull(i++)
            override fun close(code: Int) {}
        }
        val got = ArrayList<BridgeMsg.Sunshine>()
        var log: BridgeLog? = null
        log = BridgeLog("192.168.1.20", 48210, false, "", "t".repeat(43), "c".repeat(32), dialer = { _, _, _, _ -> c },
            onMessage = {}, onStatus = {}, onSunshine = { got.add(it) }, sleeper = { log!!.stop() })
        log.runHere()
        assertEquals(1, got.size); assertEquals(53989, got[0].httpPort)
    }

    /** the new words are in Spanish and French */
    @Test fun translated() {
        for (lang in listOf("es", "fr")) {
            val f = java.io.File("../companion/app/src/main/assets/companion/i18n/$lang.json").let { if (it.isFile) it else java.io.File("companion/app/src/main/assets/companion/i18n/$lang.json") }
            val plain = JSONObject(f.readText()).getJSONObject("plain")
            for (w in listOf(HostHeal.RESTARTING, HostHeal.DOWN, HostHeal.DOWN_DETAIL)) assertTrue(plain.has(w), "$lang: $w")
        }
    }
}
