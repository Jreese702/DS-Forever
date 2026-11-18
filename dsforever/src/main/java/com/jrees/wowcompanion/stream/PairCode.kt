package com.jrees.wowcompanion.stream

import org.json.JSONObject

/**
 * "OTHER NETWORK": THE PC'S ADDRESS AS A SECOND, SHORT CODE (the user's picks 2026-11-18: "Address inside the pairing code", then design
 * "B: Two steps"). Everyone types the 6-digit secret exactly as before. A Thor that can't see the PC's mDNS advert (another subnet, a
 * guest / mesh Wi-Fi that drops multicast, Tailscale or WireGuard) also types the network code Desktop shows under it, quieter, as
 * "Other network". Desktop's PairAddress.cs makes it; this reads it. Pure. Digits only, always whole groups of 3:
 *
 *   net   = entry+ check
 *   entry = 1 + 5 digits: 192.168.0.0/16 | 2 + 8: 10.0.0.0/8 | 3 + 7: 172.16.0.0/12 | 4 + 7: 100.64.0.0/10 (Tailscale)
 *         | 5 + 10: any IPv4 | 6 + 5: the bridge port when it is not 48210 (first) | 7 + 5 + 7: 192.168/16 then 100.64/10 (home, then
 *         Tailscale); 0 / 8 / 9 reserved
 *   check = 1 to 3 digits to whole groups of 3, the last always Damm's over everything before it (1 = Damm; 2 = Damm of the entries read
 *           backwards, then Damm; 3 = MOD 97-10's two, then Damm); none for a lone "1" entry: the commonest code stays 6 digits
 *
 * Examples: "100 279" = 192.168.1.23 (6 digits); "700 279 049 289 390" = 192.168.1.23 + Tailscale 100.71.133.93 (15).
 * The whole code [parse] reads = the 6 secret digits + the network digits.
 *
 * TRUST: the address is only where to dial, as an mDNS answer is. The proof sent is keyed by the 6 secret digits alone
 * ([BridgeProtocol.proof]); the bridge's TLS certificate must match the fingerprint its /v1/info names ([RealPairSteps]); Moonlight's
 * handshake and every later connection are pinned exactly as for a PC found by mDNS ([Route]: "an address is only where to dial").
 */
object PairCode {
    const val SECRET = CodeEntry.LENGTH
    /** the longest network code Desktop makes: a port + two "any IPv4" + 2 check digits (real ones are 6 or 15) */
    const val NET_MAX = 30
    /** the longest whole code: the secret + the longest network code */
    const val MAX = SECRET + NET_MAX
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
    private const val HOME_TAILSCALE_KIND = '7'

    /** a typed code (digits only: the secret, then any network digits) -> its parts; null = the network digits aren't a code Desktop
     *  makes (a typo) */
    fun parse(digits: String): Parsed? {
        if (digits.length < SECRET || digits.any { it !in '0'..'9' }) return null
        val net = digits.substring(SECRET)
        if (net.isEmpty()) return Parsed(digits)
        val (addrs, port) = decodeTail(net) ?: return null
        return Parsed(digits.substring(0, SECRET), addrs, port)
    }

    /** a network code -> its addresses and bridge port; null = not one Desktop makes */
    fun decodeTail(net: String): Pair<List<String>, Int>? {
        if (net.length < 6 || net.length > NET_MAX || net.length % 3 != 0 || net.any { it !in '0'..'9' }) return null
        val out = ArrayList<String>()
        var port = DEFAULT_PORT
        var portSeen = false
        var i = 0
        // entries are 6 digits or more and the check 3 at most: the code's length says where the entries stop
        while (net.length - i > 3) {
            when (val c = net[i++]) {
                PORT_KIND -> {
                    if (portSeen || i + 5 > net.length) return null
                    val p = net.substring(i, i + 5).toInt()
                    if (p !in 1..65535) return null
                    port = p; portSeen = true; i += 5
                }
                HOME_TAILSCALE_KIND -> {
                    if (i + 12 > net.length) return null
                    val home = net.substring(i, i + 5).toLong(); val ts = net.substring(i + 5, i + 12).toLong()
                    if (home >= 1L shl 16 || ts >= 1L shl 22) return null
                    out.add(ipv4(0xC0A80000L + home)); out.add(ipv4(0x64400000L + ts))
                    i += 12
                }
                else -> {
                    val k = KINDS.firstOrNull { it.k == c } ?: return null
                    if (i + k.digits > net.length) return null
                    val off = net.substring(i, i + k.digits).toLong()
                    if (off >= k.count) return null
                    i += k.digits
                    out.add(ipv4(k.base + off))
                }
            }
        }
        val body = net.substring(0, i)
        if (out.isEmpty() || net.length - i != checkCount(body) || net.substring(i) != check(body, net.length - i)) return null
        return out to port
    }

    /** the network code for [addresses] (IPv4, the first two distinct) and [port]: what Desktop shows (the tests' vectors) */
    fun encodeTail(addresses: List<String>, port: Int = DEFAULT_PORT): String {
        val used = ArrayList<Long>()
        for (a in addresses) {
            val v = ipv4(a) ?: continue
            if (v == 0L || (v shr 24) == 127L || v in used) continue
            used.add(v)
            if (used.size >= 2) break
        }
        if (used.isEmpty()) return ""
        val sb = StringBuilder()
        if (port != DEFAULT_PORT && port in 1..65535) sb.append(PORT_KIND).append(port.toString().padStart(5, '0'))
        fun kind(v: Long) = KINDS.first { v >= it.base && v - it.base < it.count }
        if (used.size == 2 && kind(used[0]).k == '1' && kind(used[1]).k == '4')
            sb.append(HOME_TAILSCALE_KIND).append((used[0] - 0xC0A80000L).toString().padStart(5, '0'))
                .append((used[1] - 0x64400000L).toString().padStart(7, '0'))
        else for (v in used) { val k = kind(v); sb.append(k.k).append((v - k.base).toString().padStart(k.digits, '0')) }
        val body = sb.toString()
        return body + check(body, checkCount(body))
    }

    /** how many check digits follow [body]: enough for whole groups of 3 (1 to 3); none for a lone home entry (the 6-digit code) */
    fun checkCount(body: String): Int {
        if (body.length == 6 && body[0] == '1') return 0
        val n = (3 - body.length % 3) % 3
        return if (n == 0) 3 else n
    }

    fun check(body: String, count: Int): String = when (count) {
        0 -> ""
        1 -> damm(body).toString()
        2 -> { val d = damm(body.reversed()).toString(); d + damm(body + d) }
        else -> { val m = mod97(body); m + damm(body + m) }
    }

    /**
     * How many boxes the Thor's "Other network" row shows for the digits typed so far: the code's length as its first digits say
     * ("1" = 6, "7" = 15, "4" = 9, a port first = 6 more), then, for the kinds that may carry a second address (not 1 or 7), one more group
     * of 3 whenever the digits run past that length without making a code.
     */
    fun boxes(typed: String): Int {
        val n = typed.length
        val at = if (typed.getOrNull(0) == PORT_KIND) 6 else 0
        val k = typed.getOrNull(at) ?: '1'
        val entry = when (k) { '1' -> 6; '2' -> 9; '3', '4' -> 8; '5' -> 11; '7' -> 13; else -> 6 }
        val len = at + entry
        val shape = len + if (at == 0 && k == '1') 0 else checkCount("0".repeat(len))
        val closed = k == '1' || k == '7'          // Desktop's own pairs: nothing ever follows them
        return when {
            n < shape -> shape
            closed -> minOf(NET_MAX, maxOf(shape, (n + 2) / 3 * 3))
            n % 3 == 0 && decodeTail(typed) != null -> n
            else -> minOf(NET_MAX, (n + 1 + 2) / 3 * 3)
        }
    }

    /** "700279049289390" -> "700 279 049 289 390" (as Desktop shows it) */
    fun groups(digits: String): String = digits.chunked(3).joinToString(" ")

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

    /** ISO 7064 MOD 97-10's two check digits (as an IBAN's): every single wrong digit and every swap of neighbours is caught */
    fun mod97(digits: String): String {
        var r = 0
        for (c in digits) r = (r * 10 + (c - '0')) % 97
        r = r * 100 % 97
        return (98 - r).toString().padStart(2, '0')
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

    /** the row's label and the button's, the same words and globe as Desktop's (es / fr: Tr) */
    const val OTHER_NETWORK = "Other network"
    /** said under the row when Pair found no PC on this network (the row opens by itself) */
    const val NOT_FOUND = "PC not found"
}
