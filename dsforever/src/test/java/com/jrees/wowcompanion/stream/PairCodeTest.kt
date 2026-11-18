package com.jrees.wowcompanion.stream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "OTHER NETWORK": THE PC'S ADDRESS AS A SECOND, SHORT CODE (PairCode; the user's picks 2026-11-18, design B). The vectors are Desktop's
 * (PairAddressTests.cs): the two sides must read each other's codes.
 */
class PairCodeTest {
    // ---------------------------------------------------------------- the format
    @Test fun desktopsVectorsDecode() {
        assertEquals("100279", PairCode.encodeTail(listOf("192.168.1.23")))
        assertEquals("700279049289390", PairCode.encodeTail(listOf("192.168.1.23", "100.71.133.93")))
        assertEquals(PairCode.Parsed("482913", listOf("192.168.1.23"), 48210), PairCode.parse("482913100279"))
        assertEquals(PairCode.Parsed("482913", listOf("192.168.1.23", "100.71.133.93"), 48210), PairCode.parse("482913700279049289390"))
        assertEquals("100 279", PairCode.groups("100279"))
        assertEquals("700 279 049 289 390", PairCode.groups("700279049289390"))
        assertEquals('4', PairCode.damm("572"))                                      // Damm's own example
        assertEquals("82", PairCode.mod97("32142829123456987654321611"))            // ISO 7064 as an IBAN uses it (GB82 WEST ...)
    }

    @Test fun everyKindAndPortRoundTripsInWholeGroups() {
        val cases = listOf("192.168.0.0", "192.168.255.255", "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.255", "100.64.0.1",
            "100.127.255.255", "8.8.4.4", "255.255.255.255", "172.32.0.1", "100.128.0.1")
        for (a in cases) for (port in listOf(48210, 48211, 52000, 1, 65535)) {
            val net = PairCode.encodeTail(listOf(a), port)
            assertEquals("$a $port", PairCode.Parsed("000000", listOf(a), port), PairCode.parse("000000$net"))
            assertEquals(net, 0, net.length % 3)
            for (b in listOf("100.71.133.93", "10.9.8.7")) if (b != a) {
                val two = PairCode.encodeTail(listOf(a, b), port)
                assertEquals("$a $b $port", PairCode.Parsed("000000", listOf(a, b), port), PairCode.parse("000000$two"))
                assertTrue(two, two.length % 3 == 0 && two.length <= PairCode.NET_MAX)
            }
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
        assertNull("407e162d's 7-digit tail is not a network code", PairCode.parse("4829131002790"))
    }

    private fun typos(net: String) = sequence {
        for (i in net.indices) {
            for (d in '0'..'9') if (d != net[i]) yield(net.substring(0, i) + d + net.substring(i + 1))
            if (i + 1 < net.length && net[i] != net[i + 1]) yield(net.substring(0, i) + net[i + 1] + net[i] + net.substring(i + 2))
        }
    }

    @Test fun aTypoIsRefusedBeforeAnythingIsDialled() {
        val codes = ArrayList<String>()
        for (a in listOf("192.168.1.23", "10.20.30.40", "172.20.1.2", "100.71.133.93", "8.8.4.4")) for (port in listOf(48210, 48213)) {
            codes.add(PairCode.encodeTail(listOf(a), port))
            codes.add(PairCode.encodeTail(listOf(a, if (a == "100.71.133.93") "192.168.7.7" else "100.71.133.93"), port))
        }
        for (net in codes) {
            if (net.length == 6) continue                                               // the 6-digit home code has no check (below)
            for (typo in typos(net)) {
                assertNull("$net -> $typo", PairCode.parse("482913$typo"))
                assertEquals(CodeEntry.Check.BadAddress, CodeEntry.validate("482913$typo"))
            }
            assertNull("a digit short", PairCode.parse("482913" + net.dropLast(1)))
            assertNull("a digit too many", PairCode.parse("482913${net}0"))
            assertNull("a group short", PairCode.parse("482913" + net.dropLast(3)))
        }
        // the 6-digit home code: no check digit (two groups of 3, the user's call); a wrong kind or an offset past 65535 is refused
        assertNull(PairCode.parse("482913700279"))
        assertNull(PairCode.parse("482913170000"))
        assertEquals(listOf("192.168.1.22"), PairCode.parse("482913100278")!!.addresses)
        // reserved kinds, out-of-range offsets, a port with no address
        for (body in listOf("000000000", "800000", "900000000", "1999999", "600000", "648211")) for (n in 0..3)
            assertNull("$body+$n", PairCode.decodeTail(body + PairCode.check(body, n)))
        assertEquals("Check the Other network code.", CodeEntry.message(CodeEntry.Check.BadAddress))
    }

    @Test fun theRowShowsTheCodesLength() {
        assertEquals(6, PairCode.boxes(""))
        assertEquals(6, PairCode.boxes("1"))
        assertEquals(6, PairCode.boxes("100279"))
        assertEquals(15, PairCode.boxes("7"))
        assertEquals(15, PairCode.boxes("700279049289390"))
        assertEquals(9, PairCode.boxes("4"))
        assertEquals(15, PairCode.boxes("6"))                                          // a port, then (likely) home
        // every code Desktop makes: the row has its length once it is typed, and never fewer boxes than digits on the way
        for (a in listOf("192.168.1.23", "10.20.30.40", "172.20.1.2", "100.71.133.93", "8.8.4.4")) for (port in listOf(48210, 48213))
            for (net in listOf(PairCode.encodeTail(listOf(a), port), PairCode.encodeTail(listOf(a, if (a == "100.71.133.93") "192.168.7.7" else "100.71.133.93"), port))) {
                for (k in 0 until net.length) assertTrue("$net at $k", PairCode.boxes(net.take(k)) > k)
                assertEquals(net, net.length, PairCode.boxes(net))
            }
    }

    @Test fun thePastedCodeAsDesktopShowsItIsTaken() {
        assertEquals(CodeEntry.Check.Ok("482913100279"), CodeEntry.validate("482 913 · 100 279"))
        val e = CodeEntry()
        assertTrue(e.fill("482 913 · 700 279 049 289 390"))
        assertEquals("482913700279049289390", e.code)
        assertEquals(listOf(4, 8, 2, 9, 1, 3), e.digits)
        assertTrue(e.otherOpen); assertEquals(15, e.otherBoxes)
        assertFalse(e.fill("482 913 · 100 2791")); assertEquals("482913700279049289390", e.code)
    }

    // ---------------------------------------------------------------- typing it (design B: the six boxes, then the Other network row)
    @Test fun theSixBoxesAloneAsBefore() {
        val e = CodeEntry()
        for (c in "482913") e.type(c - '0')
        assertEquals("482913", e.code); assertEquals(5, e.cursor); assertFalse(e.otherOpen)
        e.type(7)                                                                   // the row is closed: the last box is typed over
        assertEquals("482917", e.code)
    }

    @Test fun otherNetworkByTouch() {
        val e = CodeEntry()
        for (c in "482913") e.type(c - '0')
        e.openOther()
        assertTrue(e.otherOpen); assertEquals(6, e.otherBoxes); assertEquals(0, e.otherCursor)
        for (c in "100279") e.type(c - '0')
        assertEquals("482913100279", e.code); assertEquals(5, e.otherCursor)       // the row is full: the cursor stays on its last box
        e.type(8)                                                                   // typed over in place
        assertEquals("482913100278", e.code)
        e.select(6 + 2); e.backspace()                                              // a selected digit goes
        assertEquals("48291310278", e.code); assertEquals(2, e.otherCursor)
        repeat(6) { e.backspace() }                                                 // the row empties, then closes
        assertFalse(e.otherOpen); assertEquals(5, e.cursor); assertEquals("482913", e.code)
        // a first digit 7 (home + Tailscale): the row grows to 15
        e.openOther(); e.type(7)
        assertEquals(15, e.otherBoxes)
        for (c in "00279049289390") e.type(c - '0')
        assertEquals("482913700279049289390", e.code)
        repeat(5) { e.type(1) }                                                     // no further than the code's length
        assertEquals(15, e.other.size)
    }

    @Test fun otherNetworkByController() {
        val e = CodeEntry()
        assertTrue(e.fill("482913"))
        e.down()                                                                    // D-pad down past the boxes: the button
        assertTrue(e.onButton)
        e.left(); e.right(); e.type(5); assertTrue(e.onButton)                       // the button holds the cursor
        e.up(); assertEquals(5, e.cursor)                                           // up: back to the boxes (all full: the last)
        e.down(); e.openOther()                                                     // A on the button
        assertTrue(e.otherOpen); assertEquals(6, e.cursor)
        e.up(); e.up()                                                              // the empty place starts at 0, then turns
        assertEquals(listOf(1), e.other)
        e.right(); e.up(); assertEquals(listOf(1, 0), e.other)
        repeat(20) { e.right() }
        assertEquals(2, e.otherCursor)                                              // no further than the next empty place
        repeat(20) { e.left() }
        assertEquals(0, e.cursor)                                                   // left from the row's first box: back to the boxes
        e.down(); assertEquals(6 + 2, e.cursor)                                     // down from the boxes: into the open row
        e.clear(keepOther = true)
        assertTrue(e.otherOpen); assertEquals(listOf(1, 0), e.other); assertEquals(List(6) { -1 }, e.digits)
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
        val end = StreamPairing(steps).pairWithCode(pc, "482913700279049289390", "")
        // the bridge took the proof (pair_ok: Moonlight's step got the bridge's PIN): the secret alone was proved
        assertEquals(listOf("482913"), steps.armed)
        assertEquals(StreamPairing.State.Failed("Pairing failed: pin 0427."), end)
        assertTrue(b.sent.none { it.toString().contains("482913") || it.toString().contains("700279049289390") })
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
