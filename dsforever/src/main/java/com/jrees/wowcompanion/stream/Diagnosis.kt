package com.jrees.wowcompanion.stream

/**
 * "IF THE PC ISN'T FOUND, THE APP EXPLAINS WHY (the PC is off, it's on another network, or Desktop isn't running) AND OFFERS TO
 * RETRY" (STREAMING_PLAN.md section 1). What was measured goes in ([Probe]); the reason the player reads comes out. Pure.
 */
object Diagnosis {
    /** one TCP port's answer: OPEN (it listens), REFUSED (the PC is there, nothing listens), TIMEOUT (no answer at all) */
    enum class Port { OPEN, REFUSED, TIMEOUT, NOT_TRIED }

    data class Probe(
        val onWifi: Boolean,
        /** the Thor's Wi-Fi IPv4 /24 now ("" = unknown) */
        val thorSubnet: String,
        /** the remembered PC's /24 ("" = unknown) */
        val pcSubnet: String,
        val sunshine: Port,
        val bridge: Port,
        /** the PC's adverts seen by mDNS just now */
        val seenSunshine: Boolean = false,
        val seenBridge: Boolean = false,
        /** paired with a Desktop (a bridge token): false = a plain Sunshine */
        val expectsBridge: Boolean = true,
        /** Sunshine answered serverinfo but says we are not paired (or the https pin failed) */
        val notPaired: Boolean = false,
        /** a VPN (the Thor's Tailscale) is up: off Wi-Fi the PC is still reachable through it (the user, 2026-11-11: streaming away
         *  fixes - mobile data with Tailscale up said "The Thor isn't on Wi-Fi") */
        val vpnUp: Boolean = false,
        /** the PC's address dialled is a Tailscale one */
        val pcTailscale: Boolean = false,
    )

    enum class Reason(val title: String, val detail: String, val autoRetry: Boolean) {
        OK("Connected", "", false),
        NO_WIFI("The Thor isn't on Wi-Fi", "Connect to your home Wi-Fi or turn on Tailscale, then retry.", true),
        OTHER_NETWORK("The PC is on another network", "The Thor and the PC must be on the same home Wi-Fi.", true),
        DESKTOP_NOT_RUNNING("DS Forever Desktop isn't running", "Start DS Forever Desktop on the PC (or turn on \"Start with Windows\").", true),
        PC_OFF("The PC isn't answering", "The PC is off or asleep, or DS Forever Desktop isn't running.", true),
        /** off the home network over the Thor's VPN, nothing answers */
        VPN_NO_ANSWER("The PC isn't answering over Tailscale", "Is the PC on, with Tailscale and DS Forever Desktop running?", true),
        BRIDGE_ONLY_DOWN("The companion can't reach the PC", "The stream works, but Desktop's bridge isn't answering: restart DS Forever Desktop.", true),
        NOT_PAIRED("Pair again", "The PC doesn't know this Thor any more. Enter the code shown in DS Forever Desktop.", false),
    }

    fun reason(p: Probe): Reason = when {
        p.notPaired -> Reason.NOT_PAIRED
        !p.onWifi && !p.vpnUp -> Reason.NO_WIFI
        p.sunshine == Port.OPEN && (!p.expectsBridge || p.bridge == Port.OPEN) -> Reason.OK
        p.sunshine == Port.OPEN -> Reason.BRIDGE_ONLY_DOWN
        // the PC's address answers, nothing of ours listens
        p.sunshine == Port.REFUSED || p.bridge == Port.REFUSED -> Reason.DESKTOP_NOT_RUNNING
        // nothing answers over the VPN (off Wi-Fi, or dialling the PC's Tailscale address): not "another network"
        p.vpnUp && (!p.onWifi || p.pcTailscale) -> Reason.VPN_NO_ANSWER
        // nothing answers: a different subnet says "other network" (unless mDNS sees it here after all)
        p.thorSubnet.isNotEmpty() && p.pcSubnet.isNotEmpty() && p.thorSubnet != p.pcSubnet && !p.seenSunshine && !p.seenBridge -> Reason.OTHER_NETWORK
        p.seenBridge && !p.seenSunshine -> Reason.DESKTOP_NOT_RUNNING
        else -> Reason.PC_OFF
    }
}

/**
 * RECONNECT ("After that it reconnects by itself"): retries 1, 2, 4, 8 then every 15 s, and after [maxAuto] tries in a row the
 * screen stops trying by itself and offers Retry (a PC that is off stays off; the Thor's battery matters). A network change or the
 * player's Retry starts over at once. Pure: the session asks [nextDelayMs] after each failure.
 */
class ReconnectPolicy(private val maxAuto: Int = 10) {
    var attempts = 0
        private set

    /** ms to wait before the next try, or -1 = stop and offer Retry */
    fun nextDelayMs(reason: Diagnosis.Reason): Long {
        if (!reason.autoRetry) return -1
        if (attempts >= maxAuto) return -1
        val d = DELAYS_S[attempts.coerceAtMost(DELAYS_S.lastIndex)]
        attempts++
        return d * 1000L
    }

    fun reset() { attempts = 0 }

    companion object { private val DELAYS_S = longArrayOf(1, 2, 4, 8, 15) }
}

/**
 * NO ADDRESS OF THE PC ANSWERED (the "Can't reach your PC" curtain; the user, 2026-11-11: streaming away fixes - it retried every 3 s
 * for ever once [ReconnectPolicy]'s tries were spent). It backs off 3 s, 10 s, 30 s, then every 60 s, and from [MANUAL_AFTER] failures
 * in a row the curtain gives way to the problem screen and its Retry (still trying every 60 s). A network change or Retry starts over.
 * Pure: [failures] = failed tries in a row so far, this one included.
 */
object ReachBackoff {
    const val MANUAL_AFTER = 10

    fun delayMs(failures: Int): Long = when {
        failures <= 3 -> 3_000L
        failures <= 6 -> 10_000L
        failures <= 9 -> 30_000L
        else -> 60_000L
    }

    /** offer the player's Retry (the problem screen) instead of the curtain */
    fun manual(failures: Int): Boolean = failures >= MANUAL_AFTER
}
