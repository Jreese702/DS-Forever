package com.jrees.wowcompanion.stream

import org.json.JSONObject

/**
 * THE PC'S ADDRESS INSIDE THE PAIRING CODE (the user's pick 2026-11-18: "Address inside the pairing code"; it supersedes a typed "PC
 * address" box). A Thor that can't see the PC's mDNS advert (another subnet, a guest / mesh Wi-Fi that drops multicast, Tailscale or
 * WireGuard) pairs by typing the code alone. Desktop's PairAddress.cs makes the code; this reads it. Pure. Digits only:
 *
 *   code  = secret (6 digits, exactly as before) + tail
 *   tail  = "" (an older Desktop, or none known) | entry+ check
 *   entry = 1 + 5 digits: 192.168.0.0/16 | 2 + 8: 10.0.0.0/8 | 3 + 7: 172.16.0.0/12 | 4 + 7: 100.64.0.0/10 (Tailscale)
 *         | 5 + 10: any IPv4 | 6 + 5: the bridge port when it is not 48210; 0 / 7 / 8 / 9 reserved
 *   check = Damm's check digit over the tail's entries (never the secret): a typo is refused before anything is dialled
 *
 * Example: "482 913 · 100 2790" = secret 482913, PC at 192.168.1.23, bridge 48210.
 *
 * TRUST: the address is only where to dial, as an mDNS answer is. The proof sent is keyed by the 6 secret digits alone
 * ([BridgeProtocol.proof]); the bridge's TLS certificate must match the fingerprint its /v1/info names ([RealPairSteps]); Moonlight's
 * handshake and every later connection are pinned exactly as for a PC found by mDNS ([Route]: "an address is only where to dial").
 */
object PairCode {
    const val SECRET = CodeEntry.LENGTH
    /** the longest code Desktop makes is 6 + 11 + 11 + 6 + 1 = 35 digits (two "any IPv4" + a port); real ones are 13 or 21 */
    const val MAX = 40
    const val DEFAULT_PORT = BridgeProtocol.DEFAULT_PORT

    data class Parsed(val secret: String, val addresses: List<String> = emptyList(), val bridgePort: Int = DEFAULT_PORT) {
        val hasAddress get() = addresses.isNotEmpty()
    }

    private class Kind(val k: Char, val base: Long, val digits: Int, val count: Long)

    private val KINDS = listOf(
        Kind('1', 0xC0A80000L, 5, 1L shl 16), Kind('2', 0x0A000000L, 8, 1L shl 24), Kind('3', 0xAC100000L, 7, 1L shl 20),
        Kind('4', 0x64400000L, 7, 1L shl 22), Kind('5', 0L, 10, 1L shl 32),
    )
    private const val PORT_KIND = '6'

    /** a typed code (digits only, at least [SECRET]) -> its parts; null = the part after the secret isn't one Desktop makes */
    fun parse(digits: String): Parsed? {
        if (digits.length < SECRET || digits.any { it !in '0'..'9' }) return null
        val tail = digits.substring(SECRET)
        if (tail.isEmpty()) return Parsed(digits)
        val (addrs, port) = decodeTail(tail) ?: return null
        return Parsed(digits.substring(0, SECRET), addrs, port)
    }

    fun decodeTail(tail: String): Pair<List<String>, Int>? {
        if (tail.length < 2 || tail.any { it !in '0'..'9' } || damm(tail) != '0') return null
        val body = tail.dropLast(1)
        val out = ArrayList<String>()
        var port = DEFAULT_PORT
        var portSeen = false
        var i = 0
        while (i < body.length) {
            val c = body[i++]
            if (c == PORT_KIND) {
                if (portSeen || i + 5 > body.length) return null
                val p = body.substring(i, i + 5).toInt()
                if (p !in 1..65535) return null
                port = p; portSeen = true; i += 5
                continue
            }
            val k = KINDS.firstOrNull { it.k == c } ?: return null
            if (i + k.digits > body.length) return null
            val off = body.substring(i, i + k.digits).toLong()
            if (off >= k.count) return null
            i += k.digits
            out.add(ipv4(k.base + off))
        }
        return if (out.isEmpty()) null else out to port
    }

    /** the tail for [addresses] (IPv4, the first two distinct) and [port]: what Desktop shows (the tests' vectors) */
    fun encodeTail(addresses: List<String>, port: Int = DEFAULT_PORT): String {
        val sb = StringBuilder()
        val used = HashSet<Long>()
        for (a in addresses) {
            val v = ipv4(a) ?: continue
            if (v == 0L || (v shr 24) == 127L || !used.add(v)) continue
            val k = KINDS.first { v >= it.base && v - it.base < it.count }
            sb.append(k.k).append((v - k.base).toString().padStart(k.digits, '0'))
            if (used.size >= 2) break
        }
        if (sb.isEmpty()) return ""
        if (port != DEFAULT_PORT && port in 1..65535) sb.append(PORT_KIND).append(port.toString().padStart(5, '0'))
        sb.append(damm(sb.toString()))
        return sb.toString()
    }

    /** "1002790" -> "100 2790": threes, a last four instead of a lone digit (as Desktop shows it) */
    fun groups(tail: String): String {
        val parts = ArrayList<String>()
        var i = 0
        while (i < tail.length) {
            val n = if (tail.length - i == 4) 4 else minOf(3, tail.length - i)
            parts.add(tail.substring(i, i + n)); i += n
        }
        return parts.joinToString(" ")
    }

    private val Q = arrayOf(
        intArrayOf(0, 3, 1, 7, 5, 9, 8, 6, 4, 2), intArrayOf(7, 0, 9, 2, 1, 5, 4, 8, 6, 3), intArrayOf(4, 2, 0, 6, 8, 7, 1, 3, 5, 9),
        intArrayOf(1, 7, 5, 0, 9, 8, 3, 4, 2, 6), intArrayOf(6, 1, 2, 3, 0, 4, 5, 9, 7, 8), intArrayOf(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
        intArrayOf(5, 8, 6, 9, 7, 2, 0, 1, 3, 4), intArrayOf(8, 9, 4, 5, 3, 6, 2, 0, 1, 7), intArrayOf(9, 4, 3, 8, 6, 1, 7, 2, 0, 5),
        intArrayOf(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
    )

    /** Damm's check digit (every single wrong digit and every swap of neighbours is caught) */
    fun damm(digits: String): Char {
        var x = 0
        for (c in digits) x = Q[x][c - '0']
        return '0' + x
    }

    private fun ipv4(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
    private fun ipv4(a: String): Long? {
        val p = a.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (s in p) { val n = s.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n.toLong() }
        return v
    }

    // ---------------------------------------------------------------- which PC the code pairs with

    sealed class Target {
        /** pair with [pc]; [byCode] = found at the code's address (mDNS didn't see it) */
        data class Use(val pc: FoundPc, val byCode: Boolean) : Target()
        /** the code's addresses didn't answer as DS Forever Desktop, and mDNS saw no PC with Desktop */
        data class NotReachable(val addresses: List<String>) : Target()
        object NoPc : Target()
    }

    /**
     * [p]'s PC: a code without an address pairs with the PC picked on the screen ([selected]), as before. With an address: a PC mDNS
     * found there ([found]); else the address itself, when its /v1/info ([info]: address -> body or null, a worker thread) answers as
     * Desktop; else the picked PC (the code's address may be stale while mDNS works); else [Target.NotReachable].
     */
    fun target(p: Parsed, found: List<FoundPc>, selected: FoundPc?, info: (String) -> String?): Target {
        if (!p.hasAddress) return selected?.let { Target.Use(it, false) } ?: Target.NoPc
        found.firstOrNull { it.hasBridge && it.address in p.addresses }?.let { return Target.Use(it, false) }
        for (a in p.addresses) fromInfo(a, p.bridgePort, info(a))?.let { return Target.Use(it, true) }
        return selected?.takeIf { it.hasBridge }?.let { Target.Use(it, false) } ?: Target.NotReachable(p.addresses)
    }

    /**
     * Desktop's GET /v1/info at [address] as the PC its mDNS advert would have given (name, id, Sunshine's port and id, the TLS
     * fingerprint the pairing connection must then show); null = not Desktop's answer
     */
    fun fromInfo(address: String, port: Int, body: String?): FoundPc? {
        if (body == null) return null
        val o = try { JSONObject(body) } catch (_: Throwable) { return null }
        val id = o.optString("host_id")
        if (id.isEmpty() || o.optInt("proto", 0) < 1) return null
        val sun = o.optJSONObject("sunshine")
        val txt = HashMap<String, String>()
        txt["id"] = id
        o.optString("pc_name").takeIf { it.isNotBlank() }?.let { txt["pc"] = it }
        sun?.optInt("http_port", 0)?.takeIf { it > 0 }?.let { txt["sunshine"] = it.toString() }
        sun?.optString("uniqueid")?.takeIf { it.isNotBlank() }?.let { txt["sid"] = it }
        if (o.optBoolean("tls", false)) { txt["tls"] = "1"; txt["fp"] = o.optString("fp") }
        val bp = o.optInt("bridge_port", port).takeIf { it in 1..65535 } ?: port
        val pc = FoundPcs.merge(listOf(Advert(FoundPcs.DSFOREVER, txt["pc"] ?: address, address, bp, txt))).firstOrNull() ?: return null
        // a TLS bridge must name its certificate: the pairing connection is checked against it
        return if (pc.bridgeTls && pc.bridgeFingerprint.length != 64) null else pc
    }

    /** the line the pairing screen shows when the code's addresses didn't answer (terse: the address is the hint) */
    fun notReachable(addresses: List<String>): String = "PC not reachable at " + addresses.joinToString(" / ")
}
