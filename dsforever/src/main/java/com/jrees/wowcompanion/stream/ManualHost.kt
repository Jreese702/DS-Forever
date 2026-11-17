package com.jrees.wowcompanion.stream

import org.json.JSONObject

/**
 * A PC the player names by IP address, for a Thor on another subnet than the PC (mDNS doesn't cross subnets, so [Discovery] never
 * sees it). The pairing screen's "PC's IP address" row sends the typed text here; the bridge is then looked for on Desktop's ports
 * ([BridgePorts.DEFAULT] ..) with /v1/info, whose answer names the PC and its Sunshine, so the result is the same [FoundPc] mDNS
 * would have built and the pairing runs unchanged. Pure: the network part is [NetProbe.findDesktop].
 */
object ManualHost {
    sealed class Check {
        /** [address] an IPv4 (no leading zeros kept); [port] > 0 when the player typed "address:port" (that bridge port only) */
        data class Ok(val address: String, val port: Int = 0) : Check()
        object Empty : Check()
        object Invalid : Check()
    }

    /** "192.168.2.10" or "192.168.2.10:48211"; spaces around are dropped. IPv4 only (the bridge and Sunshine pairing are IPv4 here) */
    fun parse(text: String): Check {
        val t = text.trim()
        if (t.isEmpty()) return Check.Empty
        val addr = t.substringBefore(':')
        val portText = if (t.contains(':')) t.substringAfter(':') else null
        val parts = addr.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || it.toInt() > 255 }) return Check.Invalid
        val ip = parts.joinToString(".") { it.toInt().toString() }
        if (ip == "0.0.0.0" || parts[0].toInt() == 127 || parts[0].toInt() >= 224) return Check.Invalid     // not a PC on a network
        val port = if (portText == null) 0 else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return Check.Invalid
        return Check.Ok(ip, port)
    }

    /** what the screen says for a refused address */
    fun message(c: Check): String? = when (c) {
        is Check.Ok -> null
        Check.Empty -> "Type the PC's IPv4 address, like 192.168.1.20."
        Check.Invalid -> "That isn't an IPv4 address (like 192.168.1.20)."
    }

    /** the words for an address nothing answered at */
    fun notFound(address: String) = "DS Forever Desktop didn't answer at $address. Is it running, and is the PC reachable from this network (firewall, VLAN)?"

    /** a bridge's /v1/info (Desktop v1) at [address]:[port] as a PC; null for anything that isn't Desktop's answer */
    fun fromInfo(address: String, port: Int, info: String?): FoundPc? {
        if (info == null) return null
        val o = try { JSONObject(info) } catch (_: Throwable) { return null }
        val id = o.optString("host_id")
        if (id.isEmpty()) return null
        val sun = o.optJSONObject("sunshine")
        val http = sun?.optInt("http_port", 0)?.takeIf { it > 0 } ?: 0
        return FoundPc(
            name = o.optString("pc_name").ifBlank { address }, address = address,
            sunshinePort = http, bridgePort = port, desktopId = id,
            bridgeFingerprint = o.optString("fp").lowercase(), bridgeTls = o.optBoolean("tls", false),
            desktopSunshine = http,
        )
    }
}
