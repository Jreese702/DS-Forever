package com.jrees.wowcompanion.stream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch in Streaming (2026-10-23): touch = mouse, the on-screen controls' pad, the LAN address that sticks, WoW starting. Off-device. */
class StreamTouchTest {
    private class Rec : MoonInput {
        val log = ArrayList<String>()
        override fun key(vk: Int, down: Boolean, mods: Int) { log.add("key $vk $down") }
        override fun mousePosition(x: Int, y: Int, refW: Int, refH: Int) { log.add("pos $x,$y") }
        override fun mouseMove(dx: Int, dy: Int) { log.add("move $dx,$dy") }
        override fun mouseButton(down: Boolean, button: Int) { log.add("${if (down) "down" else "up"} $button") }
        override fun scroll(amount: Int) { log.add("scroll $amount") }
    }

    // a 1920x1080 view showing a 1920x1080 stream, 10 px slop
    private fun touch(r: Rec) = StreamTouch(r, 1920, 1080, 1920, 1080, 10f)

    @Test fun aTapIsALeftClickWhereTheFingerWas() {
        val r = Rec(); val t = touch(r)
        t.down(0, 960f, 540f, 0); t.move(0, 963f, 541f, 50); t.up(0, 120)
        assertEquals(listOf("pos 962,540", "down 1", "up 1"), r.log)
    }

    @Test fun holdingStillIsARightClick() {
        val r = Rec(); val t = touch(r)
        t.down(0, 100f, 100f, 0); t.up(0, StreamTouch.LONG_MS + 10)
        assertEquals(listOf("pos 99,99", "down 3", "up 3"), r.log)
    }

    @Test fun aDragHoldsTheLeftButtonFromWhereItStarted() {
        val r = Rec(); val t = touch(r)
        t.down(0, 100f, 100f, 0); t.move(0, 140f, 100f, 30); t.move(0, 200f, 150f, 60); t.up(0, 90)
        assertEquals(listOf("pos 99,99", "down 1", "pos 139,99", "pos 199,149", "pos 199,149", "up 1"), r.log)
        // the next touch starts a new gesture
        r.log.clear(); t.down(1, 10f, 10f, 200); t.up(1, 250)
        assertEquals("down 1", r.log[1])
    }

    @Test fun aTwoFingerTapIsARightClickAtTheFirstFinger() {
        val r = Rec(); val t = touch(r)
        t.down(0, 500f, 500f, 0); t.down(1, 600f, 500f, 20); t.up(1, 120); t.up(0, 130)
        assertEquals(listOf("pos 499,499", "down 3", "up 3"), r.log)
    }

    @Test fun aTwoFingerDragScrolls() {
        val r = Rec(); val t = touch(r)
        t.down(0, 500f, 500f, 0); t.down(1, 600f, 500f, 10)
        t.move(0, 500f, 540f, 30); t.move(1, 600f, 540f, 30)        // past the slop (10 px): scrolling starts at centroid 520
        t.move(0, 500f, 580f, 60); t.move(1, 600f, 580f, 60)        // 60 px more after that, x3 = 180 (positive = up: content follows)
        t.up(0, 900); t.up(1, 910)
        assertEquals(180, r.log.filter { it.startsWith("scroll") }.sumOf { it.substringAfter(' ').toInt() })
        assertTrue(r.log.none { it.startsWith("down") })            // no click after a scroll, however long it took
    }

    @Test fun aCancelLetsGoOfAHeldButton() {
        val r = Rec(); val t = touch(r)
        t.down(0, 100f, 100f, 0); t.move(0, 200f, 100f, 30); t.cancel()
        assertEquals("up 1", r.log.last())
    }

    @Test fun theViewMapsOntoTheStream() {
        val r = Rec(); val t = StreamTouch(r, 1000, 500, 1920, 1080, 10f)
        t.down(0, 500f, 250f, 0); t.up(0, 10)
        assertEquals("pos 959,539", r.log.first())
    }

    @Test fun theLanAddressSticks() {
        val lan = "192.168.1.11"; val vpn = "100.69.36.86"; val net = "192.168.1"
        assertEquals(AddressPolicy.Move.MOVE, AddressPolicy.decide(vpn, lan, net))           // to the LAN
        assertEquals(AddressPolicy.Move.PROBE_FIRST, AddressPolicy.decide(lan, vpn, net))    // away from it only if it stops answering
        assertEquals(AddressPolicy.Move.MOVE, AddressPolicy.decide(lan, "192.168.1.12", net)) // DHCP on the LAN
        assertEquals(AddressPolicy.Move.STAY, AddressPolicy.decide(lan, lan, net))
        assertEquals(AddressPolicy.Move.MOVE, AddressPolicy.decide("10.0.0.2", vpn, net))    // neither on the LAN: follow
    }

    @Test fun wowStartingOnThePc() {
        assertEquals(BridgeMsg.Wow("starting", "", PcWow("starting")), BridgeProtocol.parse(JSONObject().put("t", "wow").put("state", "starting").toString()))
        val w = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("pc_name", "DEN")
            .put("wow", JSONObject().put("running", false).put("state", "starting")).toString()) as BridgeMsg.Welcome
        assertEquals("starting", w.wowState)
        assertEquals("Starting WoW on your PC…", BridgeProtocol.wowText("starting", ""))
        assertTrue(BridgeProtocol.wowText("start_failed", "no window").startsWith("WoW didn't start: no window"))
        assertEquals("", BridgeProtocol.wowText("running", ""))

        // through BridgeLog: the welcome, a focus_result while it starts, the wow message
        val frames = listOf(
            JSONObject().put("t", "welcome").put("pc_name", "DEN").put("wow", JSONObject().put("running", false).put("state", "starting")).toString(),
            JSONObject().put("t", "focus_result").put("ok", false).put("state", "starting").put("why", "starting WoW").toString(),
            JSONObject().put("t", "wow").put("state", "running").toString())
        val states = ArrayList<String>(); val status = ArrayList<String>()
        var i = 0
        val conn = object : BridgeConn {
            override fun send(text: String) {}
            override fun receive(): String? = frames.getOrNull(i++)
            override val closeCode = 1000
            override val peerFingerprint = ""
            override fun close(code: Int) {}
        }
        var log: BridgeLog? = null
        var used = false
        log = BridgeLog("pc", 48210, false, "", "t".repeat(43), "d", { _, _, _, _ -> if (used) throw java.io.IOException("x") else { used = true; conn } },
            onMessage = {}, onStatus = { status.add(it) }, onWow = { s, _ -> states.add(s) }, sleeper = { log!!.stop() })
        log.runHere()
        assertEquals(listOf("starting", "starting", "running"), states)
    }
}
