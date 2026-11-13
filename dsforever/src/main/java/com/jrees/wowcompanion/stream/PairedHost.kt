package com.jrees.wowcompanion.stream

import android.content.Context
import org.json.JSONObject

/**
 * THE PAIRED PC, remembered after the code was typed once ("After that it reconnects by itself"): where it was, the certificates
 * that prove it is the same PC (Sunshine's for Moonlight, the bridge's TLS pin), and the bridge token. Plain JSON in the app's own
 * private preferences.
 */
data class PairedHost(
    val name: String,
    val address: String,
    /** Sunshine's http (base) and https ports as pairing or Desktop's welcome last gave them; 0 = not known (never guessed: the user's
     *  PC 2026-11-13 had a separate Sunshine on the stock 47989/47984 and ours on 52989, see [StreamPorts]) */
    val httpPort: Int = 0,
    val httpsPort: Int = 0,
    /** Sunshine's uniqueid (serverinfo), "" when unknown */
    val uuid: String = "",
    /** Sunshine's certificate from the pairing (PEM): every https call is pinned to it */
    val serverCertPem: String,
    /** the bridge (DS Forever Desktop); 0 = paired with a plain Sunshine, no companion data */
    val bridgePort: Int = 0,
    /** wss:// (pinned to [bridgeFingerprint]) instead of Desktop v1's ws:// */
    val bridgeTls: Boolean = false,
    val bridgeFingerprint: String = "",
    val bridgeToken: String = "",
    val desktopId: String = "",
    /** the Wi-Fi's IPv4 /24 at pairing time ("192.168.1"), for "the PC is on another network" */
    val subnet: String = "",
    val lastSeenMs: Long = 0L,
    /** the PC's last home-network address that worked or that Desktop reported ("" = none): tried FIRST (Wi-Fi first, 10-28) */
    val lanAddress: String = "",
    /** its last Tailscale / other address that worked ("" = none): tried after the home network */
    val vpnAddress: String = "",
    /** the PC's Tailscale addresses as Desktop's welcome last listed them (the user, 2026-11-11: streaming away fixes - a Thor that
     *  only ever paired at home still has a Tailscale address to dial away); empty = none reported (an older Desktop) */
    val tailnet: List<String> = emptyList(),
) {
    val hasBridge: Boolean get() = bridgePort > 0 && bridgeToken.isNotEmpty() && (!bridgeTls || bridgeFingerprint.isNotEmpty())

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1); put("name", name); put("address", address); put("httpPort", httpPort); put("httpsPort", httpsPort)
        put("uuid", uuid); put("serverCert", serverCertPem); put("bridgePort", bridgePort); put("bridgeTls", bridgeTls); put("bridgeFp", bridgeFingerprint)
        put("bridgeToken", bridgeToken); put("desktopId", desktopId); put("subnet", subnet); put("lastSeen", lastSeenMs)
        put("lan", lanAddress); put("vpn", vpnAddress)
        if (tailnet.isNotEmpty()) put("ts", org.json.JSONArray(tailnet))
    }

    companion object {
        fun fromJson(o: JSONObject): PairedHost? {
            val address = o.optString("address"); val cert = o.optString("serverCert")
            if (address.isEmpty() || cert.isEmpty()) return null
            return PairedHost(
                name = o.optString("name", address), address = address,
                httpPort = o.optInt("httpPort", 0), httpsPort = o.optInt("httpsPort", 0),
                uuid = o.optString("uuid"), serverCertPem = cert,
                bridgePort = o.optInt("bridgePort", 0), bridgeTls = o.optBoolean("bridgeTls", false), bridgeFingerprint = o.optString("bridgeFp"),
                bridgeToken = o.optString("bridgeToken"), desktopId = o.optString("desktopId"),
                subnet = o.optString("subnet"), lastSeenMs = o.optLong("lastSeen", 0L),
                lanAddress = o.optString("lan"), vpnAddress = o.optString("vpn"),
                tailnet = o.optJSONArray("ts")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList(),
            )
        }

        /** "192.168.1.23" -> "192.168.1" (the /24 most home networks are); "" for anything else */
        fun subnetOf(ipv4: String?): String {
            val p = ipv4?.split('.') ?: return ""
            if (p.size != 4 || p.any { it.toIntOrNull() !in 0..255 }) return ""
            return p.take(3).joinToString(".")
        }
    }
}

/** the paired PC and the app's own Moonlight identity, kept in private preferences */
class HostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun host(): PairedHost? = prefs.getString(KEY_HOST, null)?.let { s -> try { PairedHost.fromJson(JSONObject(s)) } catch (_: Throwable) { null } }
    fun save(h: PairedHost) { prefs.edit().putString(KEY_HOST, h.toJson().toString()).apply() }
    fun forget() { prefs.edit().remove(KEY_HOST).apply() }

    /** made once per install (the bridge protocol's "client"), 32 lower-case hex */
    fun clientId(): String = prefs.getString(KEY_CLIENT, null) ?: Hex.of(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        .lowercase().also { prefs.edit().putString(KEY_CLIENT, it).apply() }

    /** the Moonlight identity, made on first use (an RSA key: a second or so) */
    @Synchronized fun identity(): ClientIdentity {
        val pem = prefs.getString(KEY_CERT, null); val key = prefs.getString(KEY_KEY, null)
        if (pem != null && key != null) try { return ClientIdentity.restore(pem, key) } catch (_: Throwable) {}
        val id = ClientIdentity.generate()
        prefs.edit().putString(KEY_CERT, id.pem).putString(KEY_KEY, ClientIdentity.keyB64(id)).apply()
        return id
    }

    companion object {
        const val PREFS = "dsf_stream"
        private const val KEY_HOST = "host"
        private const val KEY_CLIENT = "client_id"
        private const val KEY_CERT = "client_cert"
        private const val KEY_KEY = "client_key"
    }
}

/**
 * WHERE THE PAIRED PC IS (stream3, 2026-10-23: "moved: 192.168.1.11 -> 100.69.36.86" flipped twice in a second, then a stream went
 * over Tailscale). mDNS may answer with the PC's VPN address or its LAN one, one advert at a time. The home LAN wins and STICKS:
 *  - a candidate on the Thor's Wi-Fi /24: move there (from a VPN address, or a LAN address that changed by DHCP);
 *  - the current address is on the LAN and the candidate is not: PROBE_FIRST (stay while the LAN address answers);
 *  - neither on the LAN: follow the candidate.
 */
object AddressPolicy {
    enum class Move { STAY, MOVE, PROBE_FIRST }

    fun decide(current: String, candidate: String, thorSubnet: String): Move {
        if (candidate.isEmpty() || candidate == current) return Move.STAY
        fun lan(a: String) = thorSubnet.isNotEmpty() && PairedHost.subnetOf(a) == thorSubnet
        return when {
            lan(candidate) -> Move.MOVE
            lan(current) -> Move.PROBE_FIRST
            else -> Move.MOVE
        }
    }
}

/**
 * WHICH OF THE PAIRED PC'S ADDRESSES TO DIAL, IN ORDER (the user, 2026-10-28: "Yes, Wi-Fi first" - the Thor's Tailscale was off and it
 * only knew the PC's Tailscale address). Pure. The home network first (an address on the Thor's own Wi-Fi /24: what mDNS sees for this
 * PC now, the last home address that worked or that Desktop reported, the stored one), then the rest (Tailscale's 100.64/10 ...).
 * Each is tried briefly ([PROBE_MS]); the first that answers is used and remembered ([after]). The bridge's certificate pin and
 * Sunshine's pinned certificate stay exactly the same on every route: an address is only where to dial, never what to trust.
 */
object Route {
    enum class Kind(val label: String) { HOME("home Wi-Fi"), TAILSCALE("Tailscale"), OTHER("another network") }

    const val PROBE_MS = 1_500

    /** Tailscale's CGNAT range 100.64.0.0/10, or its IPv6 range fd7a:115c:a1e0::/48 (the user, 2026-11-11: streaming away fixes) */
    fun tailscale(a: String): Boolean {
        if (a.contains(':')) return a.lowercase().startsWith("fd7a:115c:a1e0:")
        val p = a.split('.').mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p[0] == 100 && p[1] in 64..127
    }

    /** a private home-network IPv4 (10/8, 172.16/12, 192.168/16) */
    fun privateLan(a: String): Boolean {
        val p = a.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    fun kind(a: String, thorSubnet: String): Kind = when {
        thorSubnet.isNotEmpty() && PairedHost.subnetOf(a) == thorSubnet -> Kind.HOME
        thorSubnet.isEmpty() && privateLan(a) -> Kind.HOME
        tailscale(a) -> Kind.TAILSCALE
        else -> Kind.OTHER
    }

    /**
     * The addresses to try for [h], in order: [seen] = this PC's addresses mDNS sees now, [reported] = the home addresses Desktop's
     * welcome listed. Home-network ones first (seen, then the remembered / reported ones, then the stored address), the others after;
     * the Tailscale addresses Desktop reported ([PairedHost.tailnet], IPv4 before IPv6) after the one that last worked.
     */
    fun candidates(h: PairedHost, seen: List<String>, reported: List<String>, thorSubnet: String): List<String> {
        val ts = h.tailnet.sortedBy { it.contains(':') }
        val all = (seen + listOf(h.lanAddress) + reported + listOf(h.address, h.vpnAddress) + ts).filter { it.isNotEmpty() }.distinct()
        val home = all.filter { kind(it, thorSubnet) == Kind.HOME }
        // a home address of ANOTHER /24 than the Thor's can't be reached from here; it stays last (a DHCP change is seen by mDNS)
        val vpn = all.filter { kind(it, thorSubnet) == Kind.TAILSCALE }
        val rest = all - home.toSet() - vpn.toSet()
        return home + vpn + rest
    }

    /** the first of [cands] that [answers] (a short TCP probe of the PC's port), or null */
    fun pick(cands: List<String>, answers: (String) -> Boolean): String? = cands.firstOrNull(answers)

    /** [h] after [worked] answered: dialled there from now on, and remembered as its home or other address */
    fun after(h: PairedHost, worked: String, thorSubnet: String): PairedHost {
        val k = kind(worked, thorSubnet)
        // the address it leaves is kept as the other route (Tailscale stays known while at home, home stays known while away)
        val old = h.address.takeIf { it.isNotEmpty() && it != worked }
        val oldHome = old != null && (privateLan(old) && !tailscale(old))
        return h.copy(
            address = worked,
            lanAddress = if (k == Kind.HOME) worked else h.lanAddress.ifEmpty { if (oldHome) old!! else "" },
            vpnAddress = if (k != Kind.HOME) worked else h.vpnAddress.ifEmpty { if (old != null && !oldHome) old else "" },
            subnet = if (k == Kind.HOME) thorSubnet.ifEmpty { h.subnet } else h.subnet,
        )
    }

    /** Desktop's welcome listed [lan]: remember the one on the Thor's Wi-Fi (else the first) as the home address; and its Tailscale
     *  addresses [tailscale] (a newer Desktop; empty from an older one = the remembered list stays) */
    fun reported(h: PairedHost, lan: List<String>, thorSubnet: String, tailscale: List<String> = emptyList()): PairedHost {
        val ts = tailscale.filter(::tailscale).distinct()
        val withTs = if (ts.isEmpty() || ts == h.tailnet) h else h.copy(tailnet = ts)
        val best = lan.firstOrNull { thorSubnet.isNotEmpty() && PairedHost.subnetOf(it) == thorSubnet } ?: lan.firstOrNull() ?: return withTs
        return if (best == withTs.lanAddress) withTs else withTs.copy(lanAddress = best)
    }

    /** the curtain's line when no address answered ([vpnUp] = the Thor's Tailscale is on; [tried] = what was dialled) */
    fun unreachable(tried: List<String>, vpnUp: Boolean): String {
        val base = "Can't reach your PC - is DS Forever Desktop running?"
        return if (!vpnUp && tried.any(::tailscale) && tried.none { privateLan(it) }) "$base (Tailscale is off)" else base
    }
}

/**
 * WHICH SUNSHINE PORTS A STREAM DIALS (the user, 2026-11-13: streaming worked once, then sat on "PC found"; a separately installed
 * Sunshine held the stock 47984/47989 while DS Forever Desktop's own Sunshine moved to 52989, and the Thor's 47989 fallback reached the
 * foreign, unpaired one. The user's pick: "Yes, add it" - never fall back to the stock ports, use the ports Desktop reports). Pure.
 *  - a PC with Desktop's bridge: the ports its welcome reported win; a welcome without a port yet (its Sunshine not up): wait. No
 *    welcome yet: wait [WAIT_MS], then the ports this pairing stored (a saved pairing keeps working when the bridge is late), else keep
 *    waiting with "Waiting for the PC" - never 47989;
 *  - an older Desktop whose welcome carries no sunshine.http_port: today's behaviour (the stored port, else the stock one);
 *  - a plain Sunshine (no Desktop): the stored port, else the stock one (it IS a stock Sunshine).
 */
object StreamPorts {
    const val STOCK_HTTP = 47989
    const val STOCK_HTTPS = 47984
    /** how long a connect waits for Desktop's welcome before it uses the stored ports */
    const val WAIT_MS = 10_000L
    /** no usable port by then: the problem screen (it retries by the policy) */
    const val GIVE_UP_MS = 20_000L

    /** Desktop's welcome on its Sunshine: [said] = it carries sunshine.http_port (an older Desktop: false); [http] / [https] > 0 = known */
    data class Report(val said: Boolean, val http: Int, val https: Int) {
        val known get() = said && http > 0
        companion object { fun of(w: BridgeMsg.Welcome) = Report(!w.oldDesktop, w.httpPort, w.httpsPort) }
    }

    sealed class Pick {
        /** dial these; [from] for the log */
        data class Use(val http: Int, val https: Int, val from: String) : Pick()
        /** not known yet: wait for Desktop's word */
        object Wait : Pick()
    }

    /** https from http when not said: Sunshine's https = http - 5 */
    fun httpsOf(http: Int, https: Int) = if (https > 0) https else if (http > 5) http - 5 else 0

    /**
     * The port Moonlight's pairing dials: the one Desktop's pair_ok reported ([reportedHttp]), else the PC's advertised one (Desktop's
     * TXT wins over an _nvstream advert at the same address: [FoundPcs.merge]) when pair_ok said nothing (0: an older Desktop); a
     * pair_ok that said "no port" (-1: its Sunshine isn't up) gives 0 too, as does nothing at all (never the stock port by guess)
     */
    fun forPairing(reportedHttp: Int, pc: FoundPc): Int = when {
        reportedHttp > 0 -> reportedHttp
        reportedHttp < 0 -> 0
        else -> pc.sunshinePort.coerceAtLeast(0)
    }

    /** [r] = this bridge connection's welcome (null = none yet), [waitedMs] = since the connect began waiting */
    fun pick(h: PairedHost, r: Report?, waitedMs: Long): Pick {
        fun stored(why: String) = Pick.Use(h.httpPort, httpsOf(h.httpPort, h.httpsPort), why)
        fun storedOrStock(why: String) = if (h.httpPort > 0) stored(why) else Pick.Use(STOCK_HTTP, STOCK_HTTPS, "$why, stock")
        if (!h.hasBridge) return storedOrStock("plain Sunshine")
        if (r != null && r.known) return Pick.Use(r.http, httpsOf(r.http, r.https), "Desktop")
        if (r != null && !r.said) return storedOrStock("older Desktop")
        // Desktop said its Sunshine has no port yet: its word stands (the stored port may be another Sunshine's now)
        if (r != null) return Pick.Wait
        // no welcome yet: wait; a saved pairing's own port after the wait (the bridge late or down)
        if (waitedMs >= WAIT_MS && h.httpPort > 0) return stored("stored")
        return Pick.Wait
    }
}
