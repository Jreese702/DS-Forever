package com.jrees.wowcompanion.stream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PC'S ADDRESS INSIDE THE PAIRING CODE (PairCode; the user's pick 2026-11-18). The vectors are Desktop's (PairAddressTests.cs):
 * the two sides must read each other's codes.
 */
class PairCodeTest {
    // ---------------------------------------------------------------- the format
    @Test fun desktopsVectorsDecode() {
        assertEquals(PairCode.Parsed("482913", listOf("192.168.1.23"), 48210), PairCode.parse("4829131002790"))
        assertEquals(PairCode.Parsed("482913", listOf("192.168.1.23", "100.71.133.93"), 48210), PairCode.parse("482913100279404928933"))
        assertEquals("1002790", PairCode.encodeTail(listOf("192.168.1.23")))
        assertEquals("100279404928933", PairCode.encodeTail(listOf("192.168.1.23", "100.71.133.93")))
        assertEquals("100 2790", PairCode.groups("1002790"))
        assertEquals("100 279 404 928 933", PairCode.groups("100279404928933"))
        assertEquals('4', PairCode.damm("572"))                                      // Damm's own example
    }

    @Test fun everyKindAndPortRoundTrips() {
        val cases = listOf("192.168.0.0", "192.168.255.255", "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.255", "100.64.0.1",
            "100.127.255.255", "8.8.4.4", "255.255.255.255", "172.32.0.1", "100.128.0.1")
        for (a in cases) for (port in listOf(48210, 48211, 52000, 1, 65535)) {
            val tail = PairCode.encodeTail(listOf(a), port)
            assertEquals("$a $port", PairCode.Parsed("000000", listOf(a), port), PairCode.parse("000000$tail"))
        }
        // two at most, no duplicates, no loopback
        assertEquals(listOf("192.168.1.23", "100.71.133.93"),
            PairCode.parse("123456" + PairCode.encodeTail(listOf("192.168.1.23", "192.168.1.23", "127.0.0.1", "100.71.133.93", "10.0.0.5")))!!.addresses)
        assertEquals("", PairCode.encodeTail(listOf("127.0.0.1", "0.0.0.0", "not an address")))
    }

    @Test fun anOldSixDigitCodeIsJustTheSecret() {
        assertEquals(PairCode.Parsed("012345"), PairCode.parse("012345"))
        assertFalse(PairCode.parse("012345")!!.hasAddress)
        assertNull(PairCode.parse("01234"))
        assertEquals(CodeEntry.Check.Ok("012345"), CodeEntry.validate("012 345"))
    }

    @Test fun aTypoInTheAddressIsRefusedBeforeAnythingIsDialled() {
        for (tail in listOf(PairCode.encodeTail(listOf("192.168.1.23")), PairCode.encodeTail(listOf("192.168.1.23", "100.71.133.93"), 48213))) {
            for (i in tail.indices) {
                for (d in '0'..'9') {
                    if (d == tail[i]) continue
                    val typo = tail.substring(0, i) + d + tail.substring(i + 1)
                    assertNull("one wrong digit: $typo", PairCode.parse("482913$typo"))
                    assertEquals(CodeEntry.Check.BadAddress, CodeEntry.validate("482913$typo"))
                }
                if (i + 1 < tail.length && tail[i] != tail[i + 1]) {
                    val swap = tail.substring(0, i) + tail[i + 1] + tail[i] + tail.substring(i + 2)
                    assertNull("neighbours swapped: $swap", PairCode.parse("482913$swap"))
                }
            }
            assertNull("a digit short", PairCode.parse("482913" + tail.dropLast(1)))
            assertNull("a digit too many", PairCode.parse("482913${tail}0"))
        }
        // reserved kinds, out-of-range offsets, a port with no address, a port 0
        for (body in listOf("7", "0", "8123", "9", "199999", "648211", "6000001002790"))
            assertNull(body, PairCode.parse("482913$body${PairCode.damm(body)}"))
        assertEquals("Check the digits after the first 6.", CodeEntry.message(CodeEntry.Check.BadAddress))
    }

    @Test fun thePastedCodeAsDesktopShowsItIsTaken() {
        assertEquals(CodeEntry.Check.Ok("4829131002790"), CodeEntry.validate("482 913 · 100 2790"))
        val e = CodeEntry()
        assertTrue(e.fill("482 913 · 100 279 404 928 933"))
        assertEquals("482913100279404928933", e.code)
        assertEquals(listOf(4, 8, 2, 9, 1, 3), e.digits)
        assertFalse(e.fill("482 913 · 100 2791")); assertEquals("482913100279404928933", e.code)
    }

    // ---------------------------------------------------------------- typing it
    @Test fun typingRunsOnFromTheBoxesIntoTheAddress() {
        val e = CodeEntry()
        for (c in "4829131002790") e.type(c - '0')
        assertEquals("4829131002790", e.code)
        assertEquals(listOf(1, 0, 0, 2, 7, 9, 0), e.tail)
        assertEquals(13, e.cursor)                                                  // the empty slot after the tail
        e.backspace()                                                               // the empty slot: the one before it
        assertEquals("482913100279", e.code); assertEquals(12, e.cursor)
        e.left(); e.left(); e.backspace()                                           // a selected tail digit goes
        assertEquals("48291310029", e.code); assertEquals(10, e.cursor)
        e.type(7)                                                                   // typed over in place
        assertEquals("48291310027", e.code)
        repeat(20) { e.backspace() }
        assertEquals(List(6) { -1 }, e.digits); assertTrue(e.tail.isEmpty()); assertEquals(0, e.cursor)
    }

    @Test fun theControllerReachesTheAddress() {
        val e = CodeEntry()
        assertTrue(e.fill("482913"))
        e.right(); assertEquals(6, e.cursor)
        e.up(); e.up()                                                              // the empty slot starts at 0, then turns
        assertEquals(listOf(1), e.tail)
        e.right(); e.down()                                                         // the next empty slot starts at 9
        assertEquals(listOf(1, 9), e.tail)
        repeat(60) { e.right() }
        assertEquals(8, e.cursor)                                                   // no further than the empty slot
        // the longest code has room, no more
        val f = CodeEntry()
        repeat(PairCode.MAX + 5) { f.type(1) }
        assertEquals(PairCode.MAX, f.code!!.length)
    }

    // ---------------------------------------------------------------- which PC
    private val info = JSONObject().put("t", "info").put("proto", 1).put("bridge_port", 48210).put("host_id", "h1").put("pc_name", "DEN")
        .put("tls", true).put("fp", "ab".repeat(32))
        .put("sunshine", JSONObject().put("http_port", 52989).put("https_port", 52984).put("uniqueid", "sun-1")).toString()

    @Test fun desktopsInfoIsThePcItsAdvertWouldGive() {
        val pc = PairCode.fromInfo("100.71.133.93", 48210, info)!!
        assertEquals("DEN", pc.name); assertEquals("100.71.133.93", pc.address); assertEquals(48210, pc.bridgePort)
        assertEquals("h1", pc.desktopId); assertEquals("sun-1", pc.sunshineId); assertEquals(52989, pc.sunshinePort)
        assertTrue(pc.bridgeTls); assertEquals("ab".repeat(32), pc.bridgeFingerprint)
        // not Desktop's answer, or a TLS bridge that names no certificate: no PC
        assertNull(PairCode.fromInfo("1.2.3.4", 48210, null))
        assertNull(PairCode.fromInfo("1.2.3.4", 48210, "<html>router</html>"))
        assertNull(PairCode.fromInfo("1.2.3.4", 48210, "{\"proto\":1}"))
        assertNull(PairCode.fromInfo("1.2.3.4", 48210, JSONObject(info).put("fp", "").toString()))
    }

    private val den = FoundPc("DEN", "192.168.1.20", sunshinePort = 52989, bridgePort = 48210, desktopId = "h1")
    private val attic = FoundPc("ATTIC", "192.168.1.30", sunshinePort = 52989, bridgePort = 48210, desktopId = "h2")

    @Test fun theCodePicksThePc() {
        val asked = ArrayList<String>()
        fun ask(body: Map<String, String?>): (String) -> String? = { a -> asked.add(a); body[a] }
        // an old code: the PC picked on the screen, as before (nothing dialled)
        assertEquals(PairCode.Target.Use(den, false), PairCode.target(PairCode.parse("123456")!!, listOf(den, attic), den, ask(emptyMap())))
        assertEquals(PairCode.Target.NoPc, PairCode.target(PairCode.parse("123456")!!, emptyList(), null, ask(emptyMap())))
        // the code names ATTIC's address: ATTIC, even with DEN picked (nothing dialled: mDNS saw it)
        val atAttic = PairCode.parse("123456" + PairCode.encodeTail(listOf("192.168.1.30")))!!
        assertEquals(PairCode.Target.Use(attic, false), PairCode.target(atAttic, listOf(den, attic), den, ask(emptyMap())))
        assertTrue(asked.isEmpty())
        // mDNS saw nothing (a VPN): the code's addresses are asked, in order, and the first Desktop answer is used
        val two = PairCode.parse("123456" + PairCode.encodeTail(listOf("192.168.1.23", "100.71.133.93")))!!
        val t = PairCode.target(two, emptyList(), null, ask(mapOf("100.71.133.93" to info)))
        assertEquals("100.71.133.93", ((t as PairCode.Target.Use).pc.address)); assertTrue(t.byCode)
        assertEquals(listOf("192.168.1.23", "100.71.133.93"), asked)
        // nothing answers: the code's addresses for the line; with a PC on the screen, that one
        assertEquals(PairCode.Target.NotReachable(two.addresses), PairCode.target(two, emptyList(), null, ask(emptyMap())))
        assertEquals("PC not reachable at 192.168.1.23 / 100.71.133.93", PairCode.notReachable(two.addresses))
        assertEquals(PairCode.Target.Use(den, false), PairCode.target(two, listOf(den), den, ask(emptyMap())))
        // something else answers at the address (a router's page, another program): not a PC
        assertEquals(PairCode.Target.NotReachable(two.addresses), PairCode.target(two, emptyList(), null, ask(mapOf("192.168.1.23" to "{}"))))
    }

    // ---------------------------------------------------------------- trust: exactly as for a PC mDNS found
    /** a bridge that speaks the protocol with [code] and shows the certificate [fp] */
    private class Bridge(val code: String, override val peerFingerprint: String) : BridgeConn {
        val sent = ArrayList<JSONObject>()
        private val out = java.util.concurrent.LinkedBlockingQueue<String>()
        override val closeCode = 1000
        override fun send(text: String) {
            val m = JSONObject(text); sent.add(m)
            when (m.getString("t")) {
                "pair_hello" -> out.put(JSONObject().put("t", "pair_challenge").put("nonce", "00ff").put("attempts_left", 5).toString())
                "pair_proof" -> out.put(if (m.getString("proof") == BridgeProtocol.proof(code, "00ff", "dev1"))
                    JSONObject().put("t", "pair_ok").put("token", "T".repeat(43)).put("host_id", "h1").put("pc_name", "DEN").put("moonlight_pin", "0427").toString()
                    else JSONObject().put("t", "pair_error").put("error", "wrong_code").put("attempts_left", 4).toString())
            }
        }
        override fun receive(): String? = out.poll(2, java.util.concurrent.TimeUnit.SECONDS)
        override fun close(code: Int) {}
    }

    /** the device's bridge steps; Moonlight's step answered here (never dialled from a test) */
    private class Steps(val inner: RealPairSteps) : StreamPairing.Steps by inner {
        val armed = ArrayList<String>()
        override fun armBridge(pc: FoundPc, code: String): StreamPairing.Arm { armed.add(code); return inner.armBridge(pc, code) }
        override fun moonlightPair(pc: FoundPc, pin: String) = StreamPairing.MoonOutcome(MoonPairing.State.FAILED, why = "pin $pin")
    }

    private val id by lazy { ClientIdentity.generate(java.security.SecureRandom()) }

    @Test fun onlyTheSecretIsProvedAndTheAddressPartNeverLeaves() {
        val pc = PairCode.fromInfo("100.71.133.93", 48210, info)!!
        val b = Bridge("482913", "ab".repeat(32))
        val steps = Steps(RealPairSteps(id, "dev1", dialer = { _, _, _, _ -> b }, deviceName = "AYN Thor"))
        val end = StreamPairing(steps).pairWithCode(pc, "482913100279404928933", "")
        // the bridge took the proof (pair_ok: Moonlight's step got the bridge's PIN): the secret alone was proved
        assertEquals(listOf("482913"), steps.armed)
        assertEquals(StreamPairing.State.Failed("Pairing failed: pin 0427."), end)
        assertTrue(b.sent.none { it.toString().contains("482913") || it.toString().contains("100279404928933") })
    }

    @Test fun aSpoofedAddressCantPassForThePc() {
        // a program at the code's address whose certificate isn't the one its /v1/info names: refused before the proof is sent
        val pc = PairCode.fromInfo("100.71.133.93", 48210, info)!!
        val b = Bridge("482913", "cd".repeat(32))
        val steps = RealPairSteps(id, "dev1", dialer = { _, _, _, _ -> b }, deviceName = "AYN Thor")
        val r = steps.armBridge(pc, "482913")
        assertTrue(r is StreamPairing.Arm.Unreachable)
        assertTrue(b.sent.isEmpty())
        // another Desktop at a wrong address doesn't know this code: wrong_code, nothing paired
        val other = RealPairSteps(id, "dev1", dialer = { _, _, _, _ -> Bridge("111111", "ab".repeat(32)) }, deviceName = "AYN Thor")
        assertEquals(StreamPairing.Arm.WrongCode(4), other.armBridge(pc, "482913"))
    }
}
