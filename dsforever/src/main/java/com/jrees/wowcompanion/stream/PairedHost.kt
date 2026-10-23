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
    val httpPort: Int = 47989,
    val httpsPort: Int = 47984,
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
) {
    val hasBridge: Boolean get() = bridgePort > 0 && bridgeToken.isNotEmpty() && (!bridgeTls || bridgeFingerprint.isNotEmpty())

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1); put("name", name); put("address", address); put("httpPort", httpPort); put("httpsPort", httpsPort)
        put("uuid", uuid); put("serverCert", serverCertPem); put("bridgePort", bridgePort); put("bridgeTls", bridgeTls); put("bridgeFp", bridgeFingerprint)
        put("bridgeToken", bridgeToken); put("desktopId", desktopId); put("subnet", subnet); put("lastSeen", lastSeenMs)
    }

    companion object {
        fun fromJson(o: JSONObject): PairedHost? {
            val address = o.optString("address"); val cert = o.optString("serverCert")
            if (address.isEmpty() || cert.isEmpty()) return null
            return PairedHost(
                name = o.optString("name", address), address = address,
                httpPort = o.optInt("httpPort", 47989), httpsPort = o.optInt("httpsPort", 47984),
                uuid = o.optString("uuid"), serverCertPem = cert,
                bridgePort = o.optInt("bridgePort", 0), bridgeTls = o.optBoolean("bridgeTls", false), bridgeFingerprint = o.optString("bridgeFp"),
                bridgeToken = o.optString("bridgeToken"), desktopId = o.optString("desktopId"),
                subnet = o.optString("subnet"), lastSeenMs = o.optLong("lastSeen", 0L),
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
