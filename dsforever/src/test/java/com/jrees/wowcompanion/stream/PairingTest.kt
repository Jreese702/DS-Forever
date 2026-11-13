package com.jrees.wowcompanion.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * Pairing (S2): Moonlight's handshake against a fake Sunshine that runs the PC's half of the same protocol (real RSA keys, real
 * certificates from our own DER builder, real AES), then the code-on-the-PC state machine with fake steps. Off-device.
 */
class PairingTest {
    companion object {
        lateinit var client: ClientIdentity
        lateinit var server: ClientIdentity
        @BeforeClass @JvmStatic fun keys() {
            client = ClientIdentity.generate(SecureRandom())
            server = ClientIdentity.generate(SecureRandom(), commonName = "Sunshine Gamestream Host")
        }
    }

    /** Sunshine's side of /pair (nvhttp.cpp's steps), with the PIN the PC was given */
    private class FakeSunshine(val pin: String, val serverId: ClientIdentity, val busy: Boolean = false) : PairHttp {
        private val rng = SecureRandom()
        private var key = ByteArray(0)
        private var clientCert: X509Certificate? = null
        private var serverSecret = ByteArray(0)
        private var serverChallenge = ByteArray(0)
        private var clientHash = ByteArray(0)
        var paired = false; var unpaired = 0; var calls = ArrayList<String>()

        private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        private fun arg(args: String, k: String) = args.split('&').first { it.startsWith("$k=") }.substringAfter('=')
        private fun ok(body: String = "") = "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\"><paired>1</paired>$body</root>"
        private val no = "<root status_code=\"200\"><paired>0</paired></root>"

        override fun pairCommand(args: String, waitForUser: Boolean): String {
            calls.add(args.substringBefore('='))
            return when {
                args.startsWith("phrase=getservercert") -> {
                    if (busy) return ok("<plaincert></plaincert>")
                    val salt = Hex.bytes(arg(args, "salt"))
                    key = sha(salt + pin.toByteArray()).copyOf(16)
                    clientCert = ClientIdentity.parseCert(Hex.bytes(arg(args, "clientcert")))
                    ok("<plaincert>${Hex.of(serverId.pem.toByteArray())}</plaincert>")
                }
                args.startsWith("clientchallenge=") -> {
                    val challenge = PairAes.decrypt(Hex.bytes(arg(args, "clientchallenge")), key).copyOf(16)
                    serverSecret = ByteArray(16).also { rng.nextBytes(it) }
                    serverChallenge = ByteArray(16).also { rng.nextBytes(it) }
                    val hash = sha(challenge + serverId.cert.signature + serverSecret)
                    ok("<challengeresponse>${Hex.of(PairAes.encrypt(hash + serverChallenge, key))}</challengeresponse>")
                }
                args.startsWith("serverchallengeresp=") -> {
                    clientHash = PairAes.decrypt(Hex.bytes(arg(args, "serverchallengeresp")), key).copyOf(32)
                    ok("<pairingsecret>${Hex.of(serverSecret + MoonPairing.sign(serverSecret, serverId.key))}</pairingsecret>")
                }
                args.startsWith("clientpairingsecret=") -> {
                    val b = Hex.bytes(arg(args, "clientpairingsecret"))
                    val secret = b.copyOf(16); val sig = b.copyOfRange(16, b.size)
                    val good = MoonPairing.verify(secret, sig, clientCert!!) &&
                        sha(serverChallenge + clientCert!!.signature + secret).contentEquals(clientHash)
                    paired = good
                    if (good) ok() else no
                }
                else -> no
            }
        }
        override fun pairChallenge(serverCert: X509Certificate): String {
            calls.add("pairchallenge")
            assertArrayEquals(serverId.cert.encoded, serverCert.encoded)          // the client pinned OUR certificate
            return if (paired) ok() else no
        }
        override fun unpair() { unpaired++; paired = false }
    }

    @Test fun ourCertificateIsAValidSelfSignedX509() {
        val c = client.cert
        c.checkValidity()
        c.verify(c.publicKey)                                                  // self-signed by its own key
        assertTrue(c.subjectX500Principal.name.contains("CN=NVIDIA GameStream Client"))
        assertEquals(3, c.version)
        assertEquals("SHA256withRSA", c.sigAlgName)
        assertTrue(c.notAfter.time - c.notBefore.time > 19L * 365 * 24 * 3600 * 1000)
        // and it survives the store's round trip
        val back = ClientIdentity.restore(client.pem, ClientIdentity.keyB64(client))
        assertArrayEquals(client.cert.encoded, back.cert.encoded)
        assertArrayEquals(client.key.encoded, back.key.encoded)
        assertTrue(client.pem.startsWith("-----BEGIN CERTIFICATE-----\n") && !client.pem.contains('\r'))
    }

    @Test fun pairsWithTheRightCode() {
        val pc = FakeSunshine("482913", server)
        val r = MoonPairing(pc, client).pair(7, "482913")
        assertEquals(MoonPairing.State.PAIRED, r.state)
        assertArrayEquals(server.cert.encoded, r.serverCert!!.encoded)
        assertEquals(listOf("phrase", "clientchallenge", "serverchallengeresp", "clientpairingsecret", "pairchallenge"), pc.calls)
        assertEquals(0, pc.unpaired)
    }

    @Test fun aWrongCodeIsPinWrongAndUnpairs() {
        val pc = FakeSunshine("482913", server)
        val r = MoonPairing(pc, client).pair(7, "482914")
        assertEquals(MoonPairing.State.PIN_WRONG, r.state)
        assertEquals(1, pc.unpaired)
        assertTrue("never sent our secret after a wrong code", "clientpairingsecret" !in pc.calls)
    }

    @Test fun anotherDevicePairingIsAlreadyInProgress() {
        val r = MoonPairing(FakeSunshine("1", server, busy = true), client).pair(7, "1")
        assertEquals(MoonPairing.State.ALREADY_IN_PROGRESS, r.state)
    }

    @Test fun aMitmCertificateFailsTheSignatureCheck() {
        // the PC claims one certificate but signs with another key
        val liar = FakeSunshine("1234", ClientIdentity(server.cert, client.key))
        val r = MoonPairing(liar, client).pair(7, "1234")
        assertEquals(MoonPairing.State.FAILED, r.state)
        assertEquals(1, liar.unpaired)
    }

    /** issue #219: the PC takes the code (step 5) but its https port answers 401 to the final check */
    @Test fun aRefusedCertificateAtTheFinalCheckSaysSoAndUnpairs() {
        val inner = FakeSunshine("482913", server)
        val pc = object : PairHttp by inner {
            override fun pairChallenge(serverCert: X509Certificate): String =
                throw HostHttpException(401, "pair: The client is not authorized. Certificate verification failed.")
        }
        val r = MoonPairing(pc, client).pair(7, "482913")
        assertEquals(MoonPairing.State.FAILED, r.state)
        assertEquals(MoonPairing.CERT_REFUSED, r.why)
        assertEquals(1, inner.unpaired)
    }

    @Test fun versionParsing() {
        assertEquals(7, MoonPairing.majorVersion("7.1.431.0"))
        assertEquals(0, MoonPairing.majorVersion(null))
    }

    // ---------------------------------------------------------------- the code-on-the-PC flow
    private class FakeSteps(
        var arm: StreamPairing.Arm = StreamPairing.Arm.Armed("", "A".repeat(43), "desk1", "Den PC", "0427", 47989, 47984),
        var moon: StreamPairing.MoonOutcome = StreamPairing.MoonOutcome(MoonPairing.State.PAIRED, "PEM", "uuid-1", 47984),
        var finish: StreamPairing.Finish = StreamPairing.Finish.Confirmed,
    ) : StreamPairing.Steps {
        val log = ArrayList<String>()
        override fun armBridge(pc: FoundPc, code: String): StreamPairing.Arm { log.add("arm $code"); return arm }
        override fun moonlightPair(pc: FoundPc, pin: String): StreamPairing.MoonOutcome { log.add("moon $pin"); return moon }
        override fun finishBridge(ok: Boolean, why: String?): StreamPairing.Finish { log.add("finish $ok"); return finish }
        override fun closeBridge() { log.add("close") }
    }

    private val pc = FoundPc("DEN", "192.168.1.20", sunshinePort = 47989, bridgePort = 48210, desktopId = "desk1")

    @Test fun theWholeFlowRemembersThePc() {
        val steps = FakeSteps(); val states = ArrayList<StreamPairing.State>()
        val end = StreamPairing(steps) { states.add(it) }.pairWithCode(pc, "123 456", "192.168.1")
        val host = (end as StreamPairing.State.Paired).host
        // the bridge's 4-digit Moonlight PIN, not the code, goes to Sunshine
        assertEquals(listOf("arm 123456", "moon 0427", "finish true", "close"), steps.log)
        assertEquals("Den PC", host.name); assertEquals("192.168.1.20", host.address); assertEquals(48210, host.bridgePort)
        assertEquals("A".repeat(43), host.bridgeToken); assertFalse(host.bridgeTls)
        assertEquals("PEM", host.serverCertPem); assertEquals("uuid-1", host.uuid); assertEquals("192.168.1", host.subnet)
        assertTrue(host.hasBridge)
        assertTrue(states.first() is StreamPairing.State.Working)
    }

    @Test fun aBadlyTypedCodeNeverTouchesThePc() {
        val steps = FakeSteps()
        val end = StreamPairing(steps).pairWithCode(pc, "12345", "")
        assertTrue(end is StreamPairing.State.BadCode)
        assertTrue(steps.log.isEmpty())
    }

    @Test fun aWrongCodeAtTheBridgeStopsBeforeMoonlight() {
        val steps = FakeSteps(arm = StreamPairing.Arm.WrongCode(3))
        val end = StreamPairing(steps).pairWithCode(pc, "111111", "")
        assertEquals(StreamPairing.State.WrongCode(3), end)
        assertEquals(listOf("arm 111111", "close"), steps.log)
    }

    @Test fun moonlightRefusingTellsTheBridgeAndFails() {
        val steps = FakeSteps(moon = StreamPairing.MoonOutcome(MoonPairing.State.PIN_WRONG))
        val end = StreamPairing(steps).pairWithCode(pc, "123456", "")
        assertTrue(end is StreamPairing.State.Failed)
        assertEquals(listOf("arm 123456", "moon 0427", "finish false", "close"), steps.log)
    }

    @Test fun aBadTokenIsRefusedBeforeMoonlight() {
        val steps = FakeSteps(arm = StreamPairing.Arm.Armed("", "short", "d", "n", "1111"))
        val end = StreamPairing(steps).pairWithCode(pc, "123456", "")
        assertTrue(end is StreamPairing.State.Failed)
        assertEquals(listOf("arm 123456", "finish false", "close"), steps.log)
    }

    @Test fun anUnconfirmedBridgeStillPairsWhenMoonlightDid() {
        val end = StreamPairing(FakeSteps(finish = StreamPairing.Finish.Failed("no confirmation"))).pairWithCode(pc, "123456", "")
        assertTrue(end is StreamPairing.State.Paired)
    }

    @Test fun expiredNotPairingAndUnreachableAreExplained() {
        for (a in listOf(StreamPairing.Arm.CodeExpired, StreamPairing.Arm.NotPairing, StreamPairing.Arm.Unreachable("timeout"))) {
            val end = StreamPairing(FakeSteps(arm = a)).pairWithCode(pc, "123456", "")
            assertTrue("$a", end is StreamPairing.State.Failed && end.message.isNotEmpty())
        }
    }

    @Test fun aStepThatThrowsStillClosesTheBridge() {
        val steps = object : StreamPairing.Steps {
            var closed = false
            override fun armBridge(pc: FoundPc, code: String) = StreamPairing.Arm.Armed("", "B".repeat(43), "d", "n", "1234")
            override fun moonlightPair(pc: FoundPc, pin: String): StreamPairing.MoonOutcome = throw java.io.IOException("reset")
            override fun finishBridge(ok: Boolean, why: String?) = StreamPairing.Finish.Failed("x")
            override fun closeBridge() { closed = true }
        }
        assertTrue(StreamPairing(steps).pairWithCode(pc, "123456", "") is StreamPairing.State.Failed)
        assertTrue(steps.closed)
    }

    @Test fun aPcWithoutDesktopCannotTakeACode() {
        val end = StreamPairing(FakeSteps()).pairWithCode(pc.copy(bridgePort = 0), "123456", "")
        assertTrue(end is StreamPairing.State.Failed)
    }

    @Test fun aPlainSunshineShowsAPinAndPairsWithoutABridge() {
        val steps = FakeSteps(); val states = ArrayList<StreamPairing.State>()
        val end = StreamPairing(steps) { states.add(it) }.pairPlainSunshine(pc.copy(bridgePort = 0), "4321", "10.0.0")
        assertEquals(StreamPairing.State.ShowPin("4321"), states.first())
        val host = (end as StreamPairing.State.Paired).host
        assertTrue(!host.hasBridge)
        assertEquals(listOf("moon 4321"), steps.log)
    }

    @Test fun theRealHandshakeThroughTheFlowWithTheFakeSunshine() {
        val sunshine = FakeSunshine("777000", server)
        val steps = object : StreamPairing.Steps {
            override fun armBridge(pc: FoundPc, code: String) = StreamPairing.Arm.Armed("", "x".repeat(43), "d", "PC", "")   // no PIN: the code
            override fun moonlightPair(pc: FoundPc, pin: String): StreamPairing.MoonOutcome {
                val r = MoonPairing(sunshine, client).pair(7, pin)
                return StreamPairing.MoonOutcome(r.state, r.serverCert?.let { ClientIdentity.toPem(it.encoded) } ?: "")
            }
            override fun finishBridge(ok: Boolean, why: String?) = StreamPairing.Finish.Confirmed
            override fun closeBridge() {}
        }
        val end = StreamPairing(steps).pairWithCode(pc, "777000", "")
        val host = (end as StreamPairing.State.Paired).host
        assertArrayEquals(server.cert.encoded, ClientIdentity.parseCert(host.serverCertPem.toByteArray()).encoded)
    }

    // ---------------------------------------------------------------- the device steps against a scripted Desktop bridge
    /** Desktop v1's pairing (BridgeServer.RunWs): pair_hello -> pair_challenge, pair_proof -> pair_ok / pair_error, then pair_stream */
    private class FakeBridge(val code: String, val streamOk: Boolean = true) : BridgeConn {
        val sent = ArrayList<org.json.JSONObject>()
        private val out = java.util.concurrent.LinkedBlockingQueue<String>()
        private val nonce = "0123abcd"
        override val closeCode = 1000
        override val peerFingerprint = ""
        override fun send(text: String) {
            val m = org.json.JSONObject(text); sent.add(m)
            fun put(vararg kv: Pair<String, Any>) = out.put(org.json.JSONObject().apply { for ((k, v) in kv) put(k, v) }.toString())
            when (m.getString("t")) {
                "pair_hello" -> put("t" to "pair_challenge", "nonce" to nonce, "attempts_left" to 5, "locked" to false)
                "pair_proof" -> {
                    val want = BridgeProtocol.proof(code, nonce, m.optString("device_id", sent.first().getString("device_id")))
                    if (m.getString("proof") == want) {
                        put("t" to "pair_ok", "device_id" to "dev1", "token" to "T".repeat(43), "host_id" to "h1", "pc_name" to "Den PC",
                            "sunshine" to org.json.JSONObject().put("http_port", 47989).put("https_port", 47984), "moonlight_pin" to "0427")
                        put("t" to "welcome", "proto" to 1, "pc_name" to "Den PC")                // the session goes on meanwhile
                        put("t" to "doc", "seq" to 1, "gt" to 5, "doc" to "{}")
                        put("t" to "pair_stream", "ok" to streamOk)
                    } else put("t" to "pair_error", "error" to "wrong_code", "attempts_left" to 4)
                }
                "unpair" -> put("t" to "unpaired")
            }
        }
        override fun receive(): String? = out.poll(2, java.util.concurrent.TimeUnit.SECONDS)
        override fun close(code: Int) {}
    }

    @Test fun theDeviceStepsProveTheCodeWithoutSendingIt() {
        val b = FakeBridge("123456")
        val steps = RealPairSteps(client, "dev1", dialer = { _, _, _, _ -> b }, deviceName = "AYN Thor")
        val arm = steps.armBridge(pc, "123456") as StreamPairing.Arm.Armed
        assertEquals("T".repeat(43), arm.token); assertEquals("0427", arm.moonlightPin); assertEquals("Den PC", arm.name)
        assertEquals(47989, arm.httpPort); assertEquals("h1", arm.desktopId)
        assertTrue(b.sent.none { m -> m.toString().contains("123456") })                // the code never went out
        assertEquals("AYN Thor", b.sent.first().getString("name"))
        assertEquals(StreamPairing.Finish.Confirmed, steps.finishBridge(true, null))
        steps.closeBridge()
    }

    @Test fun theDeviceStepsSayAWrongCode() {
        val steps = RealPairSteps(client, "dev1", dialer = { _, _, _, _ -> FakeBridge("123456") }, deviceName = "AYN Thor")
        assertEquals(StreamPairing.Arm.WrongCode(4), steps.armBridge(pc, "654321"))
    }

    @Test fun aFailedStreamPairingTakesTheDeviceBackOff() {
        val b = FakeBridge("123456")
        val steps = RealPairSteps(client, "dev1", dialer = { _, _, _, _ -> b }, deviceName = "AYN Thor")
        steps.armBridge(pc, "123456")
        assertTrue(steps.finishBridge(false, "PIN_WRONG") is StreamPairing.Finish.Failed)
        assertEquals("unpair", b.sent.last().getString("t"))
    }
}
