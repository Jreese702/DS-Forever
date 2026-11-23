package com.jrees.wowcompanion

import android.content.Context
import android.util.Log

/**
 * ONE LINE PER NETWORK STALL (issues 582 / 560, 11-23, logging only: hangs in fights and returns to the login screen, where the
 * device kept up - key lag 11-16 ms - and the game's traffic stopped for 5 s, a dozen times in 7 minutes). While the run is in the
 * world, a run of [MIN_S] or more seconds in which the game received under [QUIET_BYTES] a second is a stall; when it ends (or the
 * game screen goes) one WCNET line (logcat and the report's own log, DiagLog) says how long, what was received and sent meanwhile (sending with nothing back = the server or
 * the path is silent), the Wi-Fi signal, link speed and band at the stall's [MIN_S]th second, whether the default network changed,
 * and the game's own latency (the add-on's read-only GetNetStats, Hub hub75, with its age). Never the network's name or address.
 *
 * Permissions: none new. The signal comes from the connection's own capabilities (ACCESS_NETWORK_STATE, declared) or the Wi-Fi
 * service's connection info (ACCESS_WIFI_STATE, declared); without location access Android blanks only the network's name and
 * address, which this never reads. Where Android gives no signal the line says "wifi signal ?".
 */
object NetStall {
    const val QUIET_BYTES = 200L
    const val MIN_S = 3
    /** at most this many stall lines a run (then only counted) */
    const val MAX_LINES = 60

    /** what a stall was ([feed]'s answer when one ends) */
    class Stall(val seconds: Int, val rxBytes: Long, val txBytes: Long, val wifiAt: String, val netAt: String?)

    /** the pure core: one second's received / sent bytes in, a finished [Stall] out */
    class Tracker(private val snapshot: () -> Pair<String, String?> = { "wifi ?" to null }) {
        private var secs = 0
        private var rx = 0L
        private var tx = 0L
        private var wifi = ""
        private var net: String? = null
        /** seconds of the current quiet run (0 = none) */
        val quietNow: Int get() = secs

        /** one second: [inWorld] = the run is in the world now (a quiet run starts only then; one under way runs to its end) */
        fun feed(rxBytes: Long, txBytes: Long, inWorld: Boolean): Stall? {
            if (rxBytes < QUIET_BYTES) {
                if (secs == 0 && !inWorld) return null
                secs++; rx += rxBytes.coerceAtLeast(0L); tx += txBytes.coerceAtLeast(0L)
                if (secs == MIN_S) { val (w, n) = runCatching(snapshot).getOrDefault("wifi ?" to null); wifi = w; net = n }
                return null
            }
            return end()
        }

        /** the quiet run ends now (traffic back, or the game screen went): the stall, or null when it was shorter than [MIN_S] */
        fun end(): Stall? {
            val s = if (secs >= MIN_S) Stall(secs, rx, tx, wifi, net) else null
            secs = 0; rx = 0L; tx = 0L; wifi = ""; net = null
            return s
        }
    }

    /** "1.2 KB" / "380 B" (pure) */
    fun bytes(b: Long): String = if (b >= 1024) "%.1f KB".format(java.util.Locale.US, b / 1024.0) else "$b B"

    /** "wifi -67 dBm, 433 Mb/s, 5 GHz" / "cellular" / "wifi signal ?" (pure); [transport] "wifi" / "cellular" / "ethernet" / "none" */
    fun linkWords(transport: String, rssi: Int?, linkMbps: Int?, freqMhz: Int?): String {
        if (transport != "wifi") return transport
        val parts = buildList {
            add(if (rssi != null && rssi in -127..0) "wifi $rssi dBm" else "wifi signal ?")
            if (linkMbps != null && linkMbps > 0) add("$linkMbps Mb/s")
            if (freqMhz != null && freqMhz > 0) add(when { freqMhz >= 5925 -> "6 GHz"; freqMhz >= 4900 -> "5 GHz"; else -> "2.4 GHz" })
        }
        return parts.joinToString(", ")
    }

    /** the game's latency from the add-on: (home ms, world ms, at epoch ms); null = none yet */
    @Volatile private var lat: Triple<Int, Int, Long>? = null
    fun latency(homeMs: Int, worldMs: Int, at: Long) { if (homeMs >= 0 || worldMs >= 0) lat = Triple(homeMs, worldMs, at) }
    /** "game latency 45 / 60 ms (12 s old)" / "game latency ?" (pure) */
    fun latencyWords(l: Triple<Int, Int, Long>?, now: Long): String {
        l ?: return "game latency ?"
        fun ms(v: Int) = if (v < 0) "?" else "$v"
        return "game latency ${ms(l.first)} / ${ms(l.second)} ms (${((now - l.third).coerceAtLeast(0L)) / 1000} s old)"
    }

    /** the stall's line (pure): "net stall: nothing received for 6 s (rx 120 B, tx 1.2 KB meanwhile); wifi -67 dBm, 433 Mb/s, 5 GHz;
     *  network same; game latency 45 / 60 ms (12 s old); stall 3 this run, the longest 9 s" */
    fun line(s: Stall, netNow: String?, latWords: String, count: Int, longest: Int): String =
        "net stall: nothing received for ${s.seconds} s (rx ${bytes(s.rxBytes)}, tx ${bytes(s.txBytes)} meanwhile); ${s.wifiAt.ifEmpty { "wifi ?" }}; " +
            "network ${if (s.netAt == null || netNow == null) "?" else if (s.netAt == netNow) "same" else "changed ($netNow)"}; $latWords; " +
            "stall $count this run, the longest $longest s"

    // ---------------------------------------------------------------- on the device (NativeLogin's traffic second, main thread)
    @Volatile var count = 0; private set
    @Volatile var longest = 0; private set
    @Volatile private var ctx: Context? = null
    private val tracker = Tracker { snapshotNow() }

    /** one second of the game's traffic ([NativeLogin.onTraffic]'s main-thread part) */
    fun second(c: Context?, rx: Long, tx: Long, inWorld: Boolean) {
        if (c != null) ctx = c.applicationContext
        tracker.feed(rx, tx, inWorld)?.let { said(it) }
    }
    /** the game screen went: a stall under way is said now */
    fun runEnds() { tracker.end()?.let { said(it) } }

    private fun said(s: Stall) {
        count++; if (s.seconds > longest) longest = s.seconds
        if (count > MAX_LINES) return
        val l = line(s, runCatching { snapshotNow().second }.getOrNull(), latencyWords(lat, System.currentTimeMillis()), count, longest)
        Log.i("WCNET", l)                                  // (not WCLOG: the start-up log's 200 lines stay the start-up's)
        runCatching { DiagLog.event("net", l) }
    }

    /** the report's App section: "Network stalls this run: 12, the longest 7 s" */
    fun summary(): String = "Network stalls this run (${MIN_S}+ s with nothing received in the world): $count" +
        if (count > 0) ", the longest $longest s" else ""

    /** (the link in words, the default network's id + transport); never the network's name or address */
    private fun snapshotNow(): Pair<String, String?> {
        val c = ctx ?: return "wifi ?" to null
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java) ?: return "wifi ?" to null
        val n = cm.activeNetwork ?: return "none" to "none"
        val caps = cm.getNetworkCapabilities(n)
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        var rssi: Int? = null; var link: Int? = null; var freq: Int? = null
        if (transport == "wifi") {
            val info: android.net.wifi.WifiInfo? = if (android.os.Build.VERSION.SDK_INT >= 31) caps?.transportInfo as? android.net.wifi.WifiInfo
                else runCatching { @Suppress("DEPRECATION") c.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo }.getOrNull()
            info?.let { rssi = it.rssi; link = it.linkSpeed; freq = it.frequency }
            if (rssi == null && android.os.Build.VERSION.SDK_INT >= 29) caps?.signalStrength?.takeIf { it != Int.MIN_VALUE }?.let { rssi = it }
        }
        return linkWords(transport, rssi, link, freq) to "$n $transport"
    }
}
