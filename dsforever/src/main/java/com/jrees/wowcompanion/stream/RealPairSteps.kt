package com.jrees.wowcompanion.stream

import android.os.Build

/**
 * [StreamPairing.Steps] on the device: the bridge over [WsClient] (Desktop v1: pair_hello -> pair_challenge -> pair_proof ->
 * pair_ok / pair_error, then pair_stream once Sunshine took the PIN), Sunshine over [NvHttp] + [MoonPairing].
 */
class RealPairSteps(
    private val identity: ClientIdentity,
    private val deviceId: String,
    private val dialer: BridgeDialer = WsClient,
    private val deviceName: String = (Build.MODEL ?: "AYN Thor").ifBlank { "AYN Thor" },
) : StreamPairing.Steps {
    private var bridge: BridgeConn? = null
    /** after pair_ok the bridge keeps talking (welcome, backlog, pings) while Moonlight's handshake runs: read here, the confirmation kept */
    private var confirm: java.util.concurrent.CompletableFuture<StreamPairing.Finish>? = null

    override fun armBridge(pc: FoundPc, code: String): StreamPairing.Arm {
        val c = try { dialer.dial(pc.address, pc.bridgePort, pc.bridgeTls, null) } catch (t: Throwable) {
            return StreamPairing.Arm.Unreachable(t.message ?: t.javaClass.simpleName)
        }
        bridge = c
        // a wss bridge's advert may name its certificate: what TLS showed must match it (a second PC answering there would not)
        if (pc.bridgeTls && pc.bridgeFingerprint.isNotEmpty() && !pc.bridgeFingerprint.equals(c.peerFingerprint, ignoreCase = true))
            return StreamPairing.Arm.Unreachable("its certificate doesn't match its advert")
        c.send(BridgeProtocol.pairHello(deviceId, deviceName))
        while (true) {
            val text = c.receive() ?: return StreamPairing.Arm.Unreachable("the bridge closed (${c.closeCode})")
            when (val m = BridgeProtocol.parse(text) ?: continue) {
                is BridgeMsg.PairChallenge -> {
                    if (m.locked) return StreamPairing.Arm.NotPairing
                    c.send(BridgeProtocol.pairProof(BridgeProtocol.proof(code, m.nonce, deviceId)))
                }
                is BridgeMsg.PairOk -> {
                    drain(c)
                    return StreamPairing.Arm.Armed(c.peerFingerprint, m.token, m.hostId, m.pcName, m.moonlightPin, m.httpPort, m.httpsPort)
                }
                is BridgeMsg.PairError -> return when (m.error) {
                    "wrong_code" -> StreamPairing.Arm.WrongCode(m.attemptsLeft)
                    "code_replaced" -> StreamPairing.Arm.CodeExpired
                    "locked" -> StreamPairing.Arm.NotPairing
                    else -> StreamPairing.Arm.Unreachable(m.error)
                }
                is BridgeMsg.Error -> return StreamPairing.Arm.Unreachable(m.code)
                else -> {}
            }
        }
    }

    override fun moonlightPair(pc: FoundPc, pin: String): StreamPairing.MoonOutcome {
        val http = NvHttp(pc.address, pc.sunshinePort.takeIf { it > 0 } ?: 47989, identity = identity)
        val info = http.serverInfo(false)
        XmlFields.get(info, "HttpsPort")?.toIntOrNull()?.let { if (it > 0) http.httpsPort = it }
        val r = MoonPairing(http, identity).pair(MoonPairing.majorVersion(XmlFields.get(info, "appversion")), pin)
        return StreamPairing.MoonOutcome(r.state, r.serverCert?.let { ClientIdentity.toPem(it.encoded) } ?: "",
            XmlFields.get(info, "uniqueid").orEmpty(), http.httpsPort, r.why)
    }

    /** ok: the bridge's pair_stream (the session after pair_ok also carries the welcome and the backlog: skipped here) */
    override fun finishBridge(ok: Boolean, why: String?): StreamPairing.Finish {
        val c = bridge ?: return StreamPairing.Finish.Failed("no bridge connection")
        if (!ok) {
            // the code was right but the stream didn't pair: take this device back off Desktop's list
            try { c.send(BridgeProtocol.unpair()) } catch (_: Throwable) {}
            return StreamPairing.Finish.Failed(why ?: "not paired")
        }
        return try { confirm?.get(CONFIRM_MS, java.util.concurrent.TimeUnit.MILLISECONDS) ?: StreamPairing.Finish.Failed("no confirmation") }
        catch (t: Throwable) { StreamPairing.Finish.Failed("no confirmation") }
    }

    /** reads the bridge from pair_ok on (so its welcome and backlog never back up), keeping pair_stream's answer */
    private fun drain(c: BridgeConn) {
        val f = java.util.concurrent.CompletableFuture<StreamPairing.Finish>()
        confirm = f
        Thread({
            try {
                while (!f.isDone) {
                    val text = c.receive() ?: break
                    when (val m = BridgeProtocol.parse(text) ?: continue) {
                        is BridgeMsg.PairStream -> f.complete(if (m.ok) StreamPairing.Finish.Confirmed else StreamPairing.Finish.Failed(m.error))
                        is BridgeMsg.Error -> f.complete(StreamPairing.Finish.Failed(m.code))
                        else -> {}
                    }
                }
            } catch (_: Throwable) {}
            f.complete(StreamPairing.Finish.Failed("the bridge closed (${c.closeCode})"))
        }, "dsf-pair-drain").apply { isDaemon = true; start() }
    }

    override fun closeBridge() { try { bridge?.close(1000) } catch (_: Throwable) {}; bridge = null }

    companion object { const val CONFIRM_MS = 15_000L }
}
