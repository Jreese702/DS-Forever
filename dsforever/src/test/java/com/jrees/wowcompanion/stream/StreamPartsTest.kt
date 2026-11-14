package com.jrees.wowcompanion.stream

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Streaming (S2): the pure parts. Off-device. */
class StreamPartsTest {
    // ---------------------------------------------------------------- the code entry
    @Test fun codeValidation() {
        assertEquals(CodeEntry.Check.Ok("123456"), CodeEntry.validate("123456"))
        assertEquals(CodeEntry.Check.Ok("123456"), CodeEntry.validate(" 123 456 "))
        assertEquals(CodeEntry.Check.Ok("123456"), CodeEntry.validate("123-456"))
        assertEquals(CodeEntry.Check.Empty, CodeEntry.validate("   "))
        assertEquals(CodeEntry.Check.TooShort, CodeEntry.validate("12345"))
        assertEquals(CodeEntry.Check.TooLong, CodeEntry.validate("1234567"))
        assertEquals(CodeEntry.Check.NotDigits, CodeEntry.validate("12a456"))
        assertEquals(CodeEntry.Check.NotDigits, CodeEntry.validate("12.456"))
        assertNull(CodeEntry.message(CodeEntry.Check.Ok("123456")))
        assertTrue(CodeEntry.message(CodeEntry.Check.TooShort)!!.contains("6"))
    }

    @Test fun codeByTouch() {
        val e = CodeEntry()
        for (d in listOf(4, 8, 2, 9, 1)) e.type(d)
        assertFalse(e.complete); assertNull(e.code)
        e.type(3)
        assertEquals("482913", e.code)
        e.backspace()                                 // the last box (the cursor stays on it once full)
        assertEquals(listOf(4, 8, 2, 9, 1, -1), e.digits)
        e.backspace()                                 // empty box: steps back and clears that one
        assertEquals(listOf(4, 8, 2, 9, -1, -1), e.digits)
        assertEquals(4, e.cursor)
    }

    @Test fun codeByController() {
        val e = CodeEntry()
        e.up(); e.up(); e.up()                        // empty -> 0 -> 1 -> 2
        assertEquals(2, e.digits[0])
        e.right(); e.down()                           // empty -> 9
        assertEquals(9, e.digits[1])
        e.down(); e.down()                            // 9 -> 8 -> 7
        e.up(); e.up(); e.up(); e.up()                // 7 -> 8 -> 9 -> 0 -> 1 (wraps)
        assertEquals(1, e.digits[1])
        e.left(); e.left()                            // stops at the first box
        assertEquals(0, e.cursor)
        repeat(10) { e.right() }
        assertEquals(5, e.cursor)
        assertTrue(e.fill("000 111")); assertEquals("000111", e.code)
        assertFalse(e.fill("12")); assertEquals("000111", e.code)
    }

    // ---------------------------------------------------------------- keys to the PC
    private class Rec : MoonInput {
        val keys = ArrayList<Triple<Int, Boolean, Int>>(); val pos = ArrayList<IntArray>(); val buttons = ArrayList<Pair<Boolean, Int>>()
        override fun key(vk: Int, down: Boolean, mods: Int) { keys.add(Triple(vk, down, mods)) }
        override fun mousePosition(x: Int, y: Int, refW: Int, refH: Int) { pos.add(intArrayOf(x, y, refW, refH)) }
        override fun mouseMove(dx: Int, dy: Int) {}
        override fun mouseButton(down: Boolean, button: Int) { buttons.add(down to button) }
    }

    @Test fun aChordGoesAsVirtualKeysWithModifiers() {
        val r = Rec(); val k = StreamKeys(r, 1920, 1080) {}
        k.key(37, true); k.key(50, true); k.key(110, true); k.key(110, false); k.key(38, true); k.key(38, false); k.key(50, false); k.key(37, false)
        assertEquals(listOf(
            Triple(0xA2, true, 2), Triple(0xA0, true, 3), Triple(0x24, true, 3), Triple(0x24, false, 3),
            Triple(0x41, true, 3), Triple(0x41, false, 3), Triple(0xA0, false, 2), Triple(0xA2, false, 0)), r.keys)
        assertTrue(k.heldKeys().isEmpty())
    }

    @Test fun systemKeysNeverReachThePc() {
        val r = Rec(); val k = StreamKeys(r, 1920, 1080) {}
        for (x in listOf(64, 108, 133, 134, 135, 67, 70, 76, 95, 96, 107, 127, 66)) { k.key(x, true); k.key(x, false) }
        assertTrue(r.keys.isEmpty())
        // Esc alone is a game key; Esc under Ctrl is the Start menu / Task Manager
        k.key(9, true); k.key(9, false)
        assertEquals(2, r.keys.size)
        k.key(37, true); k.key(9, true); k.key(9, false); k.key(37, false)
        assertEquals(listOf(0x1B, 0x1B, 0xA2, 0xA2), r.keys.map { it.first })
        // unknown X keycodes are dropped
        k.key(250, true)
        assertEquals(4, r.keys.size)
    }

    @Test fun typedTextAndEnterStillWork() {
        val r = Rec(); val k = StreamKeys(r, 1920, 1080) {}
        for (x in listOf(43, 31, 36, 22, 65)) { k.key(x, true); k.key(x, false) }      // h i Enter Backspace Space
        assertEquals(listOf(0x48, 0x49, 0x0D, 0x08, 0x20), r.keys.filter { it.second }.map { it.first })
    }

    @Test fun aTapIsAnAbsoluteClickOnTheStream() {
        val r = Rec(); val k = StreamKeys(r, 1920, 1080) {}
        k.tap(0.5f, 0.25f)
        assertArrayEquals(intArrayOf(15000, 7500, StreamKeys.REF, StreamKeys.REF), r.pos.single())
        assertEquals(listOf(true to StreamKeys.BUTTON_LEFT, false to StreamKeys.BUTTON_LEFT), r.buttons)
        k.tap(2f, -1f)                                                                       // clamped to the screen
        assertArrayEquals(intArrayOf(StreamKeys.REF, 0, StreamKeys.REF, StreamKeys.REF), r.pos.last())
        k.button(true, true)
        assertEquals(true to StreamKeys.BUTTON_RIGHT, r.buttons.last())
    }

    @Test fun releaseAllLetsGoOfEverything() {
        val r = Rec(); val k = StreamKeys(r, 1280, 720) {}
        k.key(37, true); k.key(38, true)
        k.releaseAll()
        assertTrue(k.heldKeys().isEmpty())
        assertEquals(listOf(false, false), r.keys.drop(2).map { it.second })
    }

    // ---------------------------------------------------------------- the pad
    @Test fun padButtonsTriggersAndSticks() {
        val p = StreamPad()
        assertTrue(p.key(KeyEvent.KEYCODE_BUTTON_A, true))
        assertFalse(p.key(KeyEvent.KEYCODE_BUTTON_A, true))                                // no change, nothing to send
        assertTrue(p.key(KeyEvent.KEYCODE_BUTTON_L1, true))
        assertEquals(StreamPad.A or StreamPad.LB, p.snapshot().buttons)
        assertTrue(p.key(KeyEvent.KEYCODE_BUTTON_R2, true))
        assertEquals(255, p.snapshot().rt)
        assertTrue(p.axes(1f, -1f, 0f, 0.5f, 0.5f, 0f, 0f, 1f))
        val s = p.snapshot()
        assertEquals(32767, s.lx); assertEquals(32767, s.ly)                                // y up positive
        assertEquals(-16383, s.ry); assertEquals(128, s.lt)
        assertTrue(s.buttons and StreamPad.DOWN != 0)                                       // the hat as the D-pad
        assertEquals(0, StreamPad.stick(0.05f))                                             // dead zone
        p.clear(); assertEquals(StreamPad.Snapshot(0, 0, 0, 0, 0, 0, 0), p.snapshot())
    }

    @Test fun theGuideButtonNeverGoes() {
        val p = StreamPad()
        assertFalse(p.key(KeyEvent.KEYCODE_BUTTON_MODE, true))
        assertEquals(0, p.snapshot().buttons)
        assertTrue(StreamPad.isPadKey(KeyEvent.KEYCODE_BUTTON_MODE))                        // swallowed by Streaming, not sent
        assertEquals(0, StreamPad.SUPPORTED and 0x0400)
    }

    // ---------------------------------------------------------------- quality
    @Test fun qualityDefaultsAndStorage() {
        val d = StreamQuality()
        assertEquals(StreamQuality.Codec.HEVC, d.codec); assertEquals(1920, d.width); assertEquals(1080, d.height)
        assertEquals(60, d.fps); assertEquals(20_000, d.bitrateKbps); assertTrue(d.audioOnThor)
        val q = StreamQuality(StreamQuality.Codec.H264, 720, 30, 10_000, false)
        assertEquals(q, StreamQuality.fromJson(JSONObject(q.toJson().toString())))
        // nonsense falls back field by field
        val bad = StreamQuality.fromJson(JSONObject().put("codec", "VP9").put("height", 2160).put("fps", 144).put("kbps", 7))
        assertEquals(StreamQuality(), bad)
        assertEquals(StreamQuality(), StreamQuality.fromJson(null))
    }

    @Test fun videoFormatsFollowTheDecoders() {
        val hevc = StreamQuality()
        assertEquals(0x0101, hevc.videoFormats(hasHevcDecoder = true, hasAv1Decoder = false))
        assertEquals(0x0001, hevc.videoFormats(hasHevcDecoder = false, hasAv1Decoder = false))
        assertEquals(0x1101, hevc.copy(codec = StreamQuality.Codec.AV1).videoFormats(true, true))
        assertEquals(0x0001, hevc.copy(codec = StreamQuality.Codec.H264).videoFormats(true, true))
        assertEquals(30, StreamQuality.step(StreamQuality.FPS, 60, 1))
        assertEquals(50_000, StreamQuality.step(StreamQuality.BITRATES_KBPS, 5_000, -1))
    }

    // ---------------------------------------------------------------- finding the PC
    @Test fun advertsMergeIntoPcs() {
        // Desktop v1's TXT: proto, id, pc, sunshine, ver
        val pcs = FoundPcs.merge(listOf(
            Advert("._nvstream._tcp", "DESKTOP-ABC", "192.168.1.20", 47989),
            Advert("_dsforever._tcp.", "DESKTOP-ABC", "192.168.1.20", 48210, mapOf("proto" to "1", "id" to "d1", "pc" to "Den PC", "sunshine" to "47989", "ver" to "0.1")),
            Advert("_nvstream._tcp.local.", "OFFICE", "192.168.1.30", 47989),
            Advert("_dsforever._tcp", "LAPTOP", "192.168.1.40", 48210, mapOf("id" to "d2", "sunshine" to "47989", "tls" to "1", "fp" to "AB".repeat(32))),
        ))
        assertEquals(3, pcs.size)
        val den = pcs.first { it.address == "192.168.1.20" }
        assertEquals("Den PC", den.name); assertEquals(47989, den.sunshinePort); assertEquals(48210, den.bridgePort)
        assertEquals("d1", den.desktopId); assertFalse(den.bridgeTls)
        val laptop = pcs.first { it.address == "192.168.1.40" }
        assertEquals(47989, laptop.sunshinePort)                    // from the bridge's TXT: its _nvstream advert was missed
        assertTrue(laptop.bridgeTls); assertEquals("ab".repeat(32), laptop.bridgeFingerprint)
        assertEquals("OFFICE", pcs.last().name); assertFalse(pcs.last().hasBridge)
    }

    // ---------------------------------------------------------------- errors and reconnect
    private fun probe(sun: Diagnosis.Port, br: Diagnosis.Port, thor: String = "192.168.1", pc: String = "192.168.1", wifi: Boolean = true,
                      seenS: Boolean = false, seenB: Boolean = false, bridge: Boolean = true, notPaired: Boolean = false) =
        Diagnosis.Probe(wifi, thor, pc, sun, br, seenS, seenB, bridge, notPaired)

    @Test fun whyThePcIsNotFound() {
        val O = Diagnosis.Port.OPEN; val R = Diagnosis.Port.REFUSED; val T = Diagnosis.Port.TIMEOUT
        assertEquals(Diagnosis.Reason.OK, Diagnosis.reason(probe(O, O)))
        assertEquals(Diagnosis.Reason.OK, Diagnosis.reason(probe(O, Diagnosis.Port.NOT_TRIED, bridge = false)))
        assertEquals(Diagnosis.Reason.BRIDGE_ONLY_DOWN, Diagnosis.reason(probe(O, R)))
        assertEquals(Diagnosis.Reason.DESKTOP_NOT_RUNNING, Diagnosis.reason(probe(R, R)))
        assertEquals(Diagnosis.Reason.PC_OFF, Diagnosis.reason(probe(T, T)))
        assertEquals(Diagnosis.Reason.OTHER_NETWORK, Diagnosis.reason(probe(T, T, thor = "10.0.0")))
        assertEquals(Diagnosis.Reason.PC_OFF, Diagnosis.reason(probe(T, T, thor = "10.0.0", seenS = true)))    // mDNS sees it here
        assertEquals(Diagnosis.Reason.NO_WIFI, Diagnosis.reason(probe(T, T, wifi = false)))
        assertEquals(Diagnosis.Reason.NOT_PAIRED, Diagnosis.reason(probe(O, O, notPaired = true)))
        assertFalse(Diagnosis.Reason.NOT_PAIRED.autoRetry)
    }

    @Test fun reconnectBacksOffThenOffersRetry() {
        val p = ReconnectPolicy(maxAuto = 7)
        val waits = List(8) { p.nextDelayMs(Diagnosis.Reason.PC_OFF) }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L, 15000L, -1L), waits)
        p.reset(); assertEquals(1000L, p.nextDelayMs(Diagnosis.Reason.PC_OFF))
        assertEquals(-1L, ReconnectPolicy().nextDelayMs(Diagnosis.Reason.NOT_PAIRED))
    }

    // ---------------------------------------------------------------- the bridge's wire
    @Test fun webSocketFramesRoundTrip() {
        val mask = byteArrayOf(1, 2, 3, 4)
        for (n in listOf(0, 5, 125, 126, 300, 70_000)) {
            val payload = ByteArray(n) { (it % 251).toByte() }
            val masked = WsCodec.read(ByteArrayInputStream(WsCodec.encode(WsCodec.TEXT, payload, mask)))
            assertArrayEquals(payload, masked.payload); assertTrue(masked.fin); assertEquals(WsCodec.TEXT, masked.opcode)
            val plain = WsCodec.read(ByteArrayInputStream(WsCodec.encode(WsCodec.PING, payload, null)))
            assertArrayEquals(payload, plain.payload); assertEquals(WsCodec.PING, plain.opcode)
        }
        // the first byte already read by the caller
        val f = WsCodec.encode(WsCodec.TEXT, "hi".toByteArray(), null)
        assertEquals("hi", String(WsCodec.read(ByteArrayInputStream(f, 1, f.size - 1), f[0].toInt() and 0xFF).payload))
        // RFC 6455's own example
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsCodec.accept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun bridgeMessages() {
        fun o(vararg kv: Pair<String, Any>) = JSONObject().apply { for ((k, v) in kv) put(k, v) }.toString()
        val sun = JSONObject().put("running", true).put("http_port", 47989).put("https_port", 47984)
        assertEquals(BridgeMsg.Welcome("d1", "Den", true, 47989, 47984, pc = PcWow()),
            BridgeProtocol.parse(o("t" to "welcome", "proto" to 1, "host_id" to "d1", "pc_name" to "Den", "wow" to JSONObject().put("running", true), "sunshine" to sun)))
        assertEquals(BridgeMsg.Error("unauthorized", "pair again"), BridgeProtocol.parse(o("t" to "error", "error" to "unauthorized", "detail" to "pair again")))
        // issue #54: a newer Desktop's word on the controller driver; an older Desktop says nothing
        assertEquals("missing", (BridgeProtocol.parse(o("t" to "welcome", "host_id" to "d1", "pad_driver" to "missing", "sunshine" to sun)) as BridgeMsg.Welcome).padDriver)
        assertEquals("", (BridgeProtocol.parse(o("t" to "welcome", "host_id" to "d1", "sunshine" to sun)) as BridgeMsg.Welcome).padDriver)
        assertTrue(PadNote.driver("missing").startsWith("MISSING"))
        assertEquals("installed", PadNote.driver("vigem"))
        assertEquals("not reported (an older Desktop)", PadNote.driver(""))
        assertEquals("controller: nothing sent", PadNote.sent(0, "vigem"))
        assertEquals("controller: 12 inputs sent, but the PC has no ViGEmBus", PadNote.sent(12, "missing"))
        assertEquals("controller: 12 inputs sent", PadNote.sent(12, "vigem"))
        assertEquals("controller: not sent (the stream library was not loaded)", PadNote.sent(-1, ""))
        assertEquals(BridgeMsg.Doc(7, 99L, "{}"), BridgeProtocol.parse(o("t" to "doc", "seq" to 7, "gt" to 99, "doc" to "{}")))
        assertEquals(BridgeMsg.Lines(listOf("a", "b")), BridgeProtocol.parse(o("t" to "lines", "l" to org.json.JSONArray(listOf("a", "b")))))
        assertEquals(BridgeMsg.PairChallenge("n1", 5, false), BridgeProtocol.parse(o("t" to "pair_challenge", "nonce" to "n1", "attempts_left" to 5, "locked" to false)))
        assertEquals(BridgeMsg.PairOk("tok", "c1", "h1", "Den", "0427", 47989, 47984), BridgeProtocol.parse(o("t" to "pair_ok", "device_id" to "c1",
            "token" to "tok", "host_id" to "h1", "pc_name" to "Den", "sunshine" to sun, "moonlight_pin" to "0427")))
        assertEquals(BridgeMsg.PairError("wrong_code", 4), BridgeProtocol.parse(o("t" to "pair_error", "error" to "wrong_code", "attempts_left" to 4)))
        assertEquals(BridgeMsg.PairStream(false, "timeout"), BridgeProtocol.parse(o("t" to "pair_stream", "ok" to false, "error" to "timeout")))
        assertEquals(BridgeMsg.Log("switch"), BridgeProtocol.parse(o("t" to "log", "event" to "switch")))
        assertEquals(BridgeMsg.Unknown("later"), BridgeProtocol.parse(o("t" to "later", "x" to 1)))
        assertNull(BridgeProtocol.parse("[1,2]"))
        val hello = JSONObject(BridgeProtocol.hello("c1", "tok"))
        assertEquals("hello", hello.getString("t")); assertEquals("c1", hello.getString("device_id")); assertEquals("tok", hello.getString("token"))
        assertFalse(hello.has("addon_set"))                                   // unknown set: not sent
        BridgeProtocol.addonSet = 391
        try { assertEquals(391, JSONObject(BridgeProtocol.hello("c1", "tok")).getInt("addon_set")) } finally { BridgeProtocol.addonSet = 0 }
        val ph = JSONObject(BridgeProtocol.pairHello("c1", "AYN Thor"))
        assertEquals("pair_hello", ph.getString("t")); assertEquals("AYN Thor", ph.getString("name")); assertTrue(ph.getBoolean("stream"))
        // the proof as Desktop's PairingCodes.Proof computes it: HMAC-SHA256(code, "dsf-pair-v1|nonce|device"), lower-case hex
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec("123456".toByteArray(), "HmacSHA256")) }
        val want = mac.doFinal("dsf-pair-v1|abc|c1".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(want, BridgeProtocol.proof("123456", "abc", "c1"))
        assertEquals(64, want.length)
        assertTrue(BridgeProtocol.validToken("Ab-_".repeat(10) + "xyz"))
        assertFalse(BridgeProtocol.validToken("Ab+/".repeat(10) + "xyz"))
    }

    // ---------------------------------------------------------------- launching
    @Test fun launchPlan() {
        assertEquals(5 to "DS Forever", LaunchPlan.pickApp(listOf(1 to "Desktop", 5 to "DS Forever", 9 to "Steam Big Picture")))
        assertEquals(7 to "WoW Forever", LaunchPlan.pickApp(listOf(1 to "Desktop", 5 to "DS Forever", 7 to "WoW Forever")))
        assertEquals(1 to "Desktop", LaunchPlan.pickApp(listOf(9 to "Steam Big Picture", 1 to "Desktop")))
        assertNull(LaunchPlan.pickApp(emptyList()))
        assertEquals("resume", LaunchPlan.verb(5, 5)); assertEquals("launch", LaunchPlan.verb(0, 5)); assertEquals("launch", LaunchPlan.verb(7, 5))
        val q = LaunchPlan.query(5, StreamQuality(), ByteArray(16) { 0x11 }, 42, "&corever=1")
        assertTrue(q.startsWith("appid=5&mode=1920x1080x60&additionalStates=1&sops=1&rikey=" + "11".repeat(16) + "&rikeyid=42"))
        assertTrue(q.contains("&localAudioPlayMode=0&surroundAudioInfo=196610"))
        assertTrue(q.endsWith("&corever=1"))
        assertArrayEquals(byteArrayOf(0, 0, 0, 42) + ByteArray(12), LaunchPlan.riIv(42))
    }

    @Test fun gameStreamXml() {
        val info = "<?xml version=\"1.0\"?><root status_code=\"200\"><hostname>DEN</hostname><appversion>7.1.431.-1</appversion>" +
            "<PairStatus>1</PairStatus><currentgame>0</currentgame><GfeVersion/><state>SUNSHINE_SERVER_FREE</state></root>"
        assertEquals("DEN", XmlFields.get(info, "hostname")); assertEquals("1", XmlFields.get(info, "PairStatus"))
        assertEquals("", XmlFields.get(info, "GfeVersion")); assertNull(XmlFields.get(info, "nothing"))
        assertEquals(200, XmlFields.statusCode(info))
        assertEquals(401, XmlFields.statusCode("<root status_code=\"401\" status_message=\"The client is not authorized. Certificate verification failed.\"/>"))
        val apps = "<root status_code=\"200\"><App><IsHdrSupported>0</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>" +
            "<App><AppTitle>Steam &amp; Co</AppTitle><ID>1093255277</ID></App></root>"
        assertEquals(listOf(881448767 to "Desktop", 1093255277 to "Steam & Co"), XmlFields.apps(apps))
        assertArrayEquals(byteArrayOf(0x0A, -1, 0), Hex.bytes("0aFF00")); assertEquals("0AFF00", Hex.of(byteArrayOf(0x0A, -1, 0)))
    }

    @Test fun thePairedHostRoundTrips() {
        val h = PairedHost(name = "Den", address = "192.168.1.20", uuid = "u1", serverCertPem = "PEM", bridgePort = 48210,
            bridgeToken = "t".repeat(43), desktopId = "d1", subnet = "192.168.1", lastSeenMs = 99L)
        assertEquals(h, PairedHost.fromJson(JSONObject(h.toJson().toString())))
        val tls = h.copy(bridgeTls = true, bridgeFingerprint = "ab".repeat(32))
        assertEquals(tls, PairedHost.fromJson(JSONObject(tls.toJson().toString())))
        assertTrue(h.hasBridge); assertFalse(h.copy(bridgeToken = "").hasBridge)
        assertFalse(h.copy(bridgeTls = true).hasBridge)                                       // wss without a pin: never
        assertNull(PairedHost.fromJson(JSONObject().put("address", "x")))
        assertEquals("192.168.1", PairedHost.subnetOf("192.168.1.20")); assertEquals("", PairedHost.subnetOf("fe80::1")); assertEquals("", PairedHost.subnetOf("1.2.3"))
    }
}
