package com.jrees.wowcompanion.stream

/**
 * PAIRING BY THE CODE ON THE PC (STREAMING_PLAN.md section 2, docs/STREAM_BRIDGE_PROTOCOL.md "Pairing"), as a state machine over
 * three steps the device does with sockets and the tests do with fakes:
 *  1. [Steps.armBridge]: proves the code to the bridge (an HMAC over its nonce: the code never crosses the network); the bridge
 *     answers with the bridge token and a 4-digit Moonlight PIN, and hands that PIN to the bundled Sunshine (whose PIN API takes
 *     exactly 4 digits);
 *  2. [Steps.moonlightPair]: Moonlight's own handshake with Sunshine, PIN = that PIN ([moonlightPin]);
 *  3. [Steps.finishBridge]: waits for the bridge's word that Sunshine took the PIN (or, on a failure, takes the device back off).
 * Then the PC is remembered ([PairedHost]). A PC that runs a plain Sunshine (no Desktop) pairs by [pairPlainSunshine]: the app
 * shows a PIN to type into Sunshine's own page (the first test against a stock Sunshine; no companion data then).
 * Runs on a worker thread; every state goes to [onState].
 */
class StreamPairing(private val steps: Steps, private val onState: (State) -> Unit = {}) {

    sealed class State {
        data class Working(val what: String) : State()
        data class BadCode(val message: String) : State()
        data class WrongCode(val triesLeft: Int) : State()
        data class Failed(val message: String, val canRetry: Boolean = true) : State()
        data class ShowPin(val pin: String) : State()
        data class Paired(val host: PairedHost) : State()
    }

    sealed class Arm {
        /** the code was right: the bridge's token and the PC's details ([moonlightPin] "" = use the code itself) */
        data class Armed(val fingerprint: String, val token: String, val desktopId: String, val name: String, val moonlightPin: String,
                         val httpPort: Int = 0, val httpsPort: Int = 0) : Arm()
        data class WrongCode(val left: Int) : Arm()
        object CodeExpired : Arm()
        /** too many wrong codes: Desktop pauses pairing until its Refresh */
        object NotPairing : Arm()
        data class Unreachable(val why: String) : Arm()
    }

    /** [httpPort] / [httpsPort] = the Sunshine ports the handshake used (0 = not said) */
    data class MoonOutcome(val state: MoonPairing.State, val serverCertPem: String = "", val uuid: String = "", val httpsPort: Int = 0, val why: String = "",
                           val httpPort: Int = 0)

    sealed class Finish {
        object Confirmed : Finish()
        data class Failed(val why: String) : Finish()
    }

    interface Steps {
        fun armBridge(pc: FoundPc, code: String): Arm
        fun moonlightPair(pc: FoundPc, pin: String): MoonOutcome
        fun finishBridge(ok: Boolean, why: String?): Finish
        /** the pairing connection to the bridge is done with (always called) */
        fun closeBridge()
    }

    /** the PC (with a bridge) + what the player typed -> the remembered host, or why not */
    fun pairWithCode(pc: FoundPc, typed: String, thorSubnet: String): State {
        val check = CodeEntry.validate(typed)
        if (check !is CodeEntry.Check.Ok) return emit(State.BadCode(CodeEntry.message(check) ?: "Check the code."))
        if (!pc.hasBridge) return emit(State.Failed("DS Forever Desktop isn't running on ${pc.name}.", true))
        if (!pc.hasSunshine) return emit(State.Failed("${pc.name}'s streaming service isn't answering. Restart DS Forever Desktop.", true))
        val code = check.code
        try {
            emit(State.Working("Checking the code with ${pc.name}…"))
            val armed = when (val a = steps.armBridge(pc, code)) {
                is Arm.Armed -> a
                is Arm.WrongCode -> return emit(State.WrongCode(a.left))
                Arm.CodeExpired -> return emit(State.Failed("That code expired. Enter the new code shown in DS Forever Desktop."))
                Arm.NotPairing -> return emit(State.Failed("Pairing is paused after too many wrong codes: press Refresh in DS Forever Desktop, then enter the new code."))
                is Arm.Unreachable -> return emit(State.Failed("Couldn't reach DS Forever Desktop on ${pc.name} (${a.why})."))
            }
            if (!BridgeProtocol.validToken(armed.token)) {
                try { steps.finishBridge(false, "bad token") } catch (_: Throwable) {}
                return emit(State.Failed("The PC sent a bad companion token. Pair again."))
            }
            emit(State.Working("Pairing the stream with ${pc.name}…"))
            val moon = steps.moonlightPair(pc, moonlightPin(code, armed))
            if (moon.state != MoonPairing.State.PAIRED) {
                try { steps.finishBridge(false, moon.state.name) } catch (_: Throwable) {}
                return emit(when (moon.state) {
                    MoonPairing.State.PIN_WRONG -> State.Failed("The PC didn't accept the code. Enter the code shown in DS Forever Desktop again.")
                    MoonPairing.State.ALREADY_IN_PROGRESS -> State.Failed("Another device is pairing with ${pc.name} right now. Try again in a moment.")
                    else -> State.Failed("Pairing failed${if (moon.why.isNotEmpty()) ": ${moon.why}" else ""}.")
                })
            }
            // Moonlight said PAIRED, so Sunshine took the PIN: the bridge's own word is a confirmation, not a condition
            val f = try { steps.finishBridge(true, null) } catch (t: Throwable) { Finish.Failed(t.message ?: "no answer") }
            if (f is Finish.Failed) try { android.util.Log.i("DSFSTREAM", "pairing: the bridge didn't confirm (${f.why}); Moonlight paired") } catch (_: Throwable) {}
            val http = moon.httpPort.takeIf { it > 0 } ?: StreamPorts.forPairing(armed.httpPort, pc)
            val host = PairedHost(
                name = armed.name.ifEmpty { pc.name }, address = pc.address,
                httpPort = http, httpsPort = StreamPorts.httpsOf(http, moon.httpsPort),
                uuid = moon.uuid, serverCertPem = moon.serverCertPem, bridgePort = pc.bridgePort, bridgeTls = pc.bridgeTls,
                bridgeFingerprint = armed.fingerprint, bridgeToken = armed.token, desktopId = armed.desktopId.ifEmpty { pc.desktopId },
                subnet = thorSubnet, lastSeenMs = System.currentTimeMillis(),
            )
            return emit(State.Paired(host))
        } catch (t: Throwable) {
            return emit(State.Failed("Pairing stopped: ${t.message ?: t.javaClass.simpleName}"))
        } finally {
            try { steps.closeBridge() } catch (_: Throwable) {}
        }
    }

    /** a PC with a plain Sunshine: [pin] is shown for Sunshine's own PIN page while the handshake waits for it */
    fun pairPlainSunshine(pc: FoundPc, pin: String, thorSubnet: String): State {
        if (!pc.hasSunshine) return emit(State.Failed("${pc.name} isn't streaming (no Sunshine found)."))
        emit(State.ShowPin(pin))
        return try {
            val moon = steps.moonlightPair(pc, pin)
            if (moon.state != MoonPairing.State.PAIRED) emit(State.Failed(if (moon.state == MoonPairing.State.PIN_WRONG)
                "Sunshine didn't take the PIN. Try again." else "Pairing failed${if (moon.why.isNotEmpty()) ": ${moon.why}" else ""}."))
            else emit(State.Paired(PairedHost(name = pc.name, address = pc.address, httpPort = moon.httpPort.takeIf { it > 0 } ?: pc.sunshinePort,
                httpsPort = StreamPorts.httpsOf(moon.httpPort.takeIf { it > 0 } ?: pc.sunshinePort, moon.httpsPort),
                uuid = moon.uuid, serverCertPem = moon.serverCertPem, subnet = thorSubnet, lastSeenMs = System.currentTimeMillis())))
        } catch (t: Throwable) { emit(State.Failed("Pairing stopped: ${t.message ?: t.javaClass.simpleName}")) }
    }

    private fun emit(s: State): State { onState(s); return s }

    companion object {
        /**
         * The PIN Moonlight's handshake uses: the bridge's 4-digit moonlight_pin (Sunshine's PIN API takes exactly 4 digits, S3 found),
         * or the code itself from a bridge that sends none.
         */
        fun moonlightPin(code: String, armed: Arm.Armed): String = armed.moonlightPin.ifEmpty { code }

        /** a 4-digit PIN for a plain Sunshine's page */
        fun randomPin(rng: java.util.Random = java.security.SecureRandom()): String = (0 until 4).joinToString("") { rng.nextInt(10).toString() }
    }
}
