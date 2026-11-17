package com.jrees.wowcompanion.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException

/** the measurements behind [Diagnosis] on the device (no permission beyond ACCESS_NETWORK_STATE, which the app has) */
object NetProbe {
    /**
     * The home network itself: a Wi-Fi (or Ethernet) network that is NOT a VPN. The active network is not the answer: with Tailscale
     * on (stream2: the Thor had wlan 192.168.1.25 AND tun0 100.71.133.93) the active network is the tunnel.
     */
    @Suppress("DEPRECATION")
    private fun lan(ctx: Context): android.net.Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    fun onWifi(ctx: Context): Boolean = lan(ctx) != null

    /** a VPN (the Thor's Tailscale) is up */
    @Suppress("DEPRECATION")
    fun vpnUp(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** the Thor's own IPv4 /24 on its Wi-Fi ("" = none) */
    fun subnet(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return ""
        val lp = cm.getLinkProperties(lan(ctx) ?: return "") ?: return ""
        val v4 = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address } ?: return ""
        return PairedHost.subnetOf(v4.hostAddress)
    }

    /** one TCP port: OPEN / REFUSED (the PC is there, nothing listens) / TIMEOUT (nothing answers) */
    fun port(address: String, port: Int, timeoutMs: Int = 1_500): Diagnosis.Port {
        if (port <= 0) return Diagnosis.Port.NOT_TRIED
        return try {
            Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }
            Diagnosis.Port.OPEN
        } catch (e: ConnectException) {
            // "ECONNREFUSED" = the host answered with a reset; "EHOSTUNREACH" / "ENETUNREACH" = nobody there
            if (e.message?.contains("refused", ignoreCase = true) == true) Diagnosis.Port.REFUSED else Diagnosis.Port.TIMEOUT
        } catch (e: NoRouteToHostException) { Diagnosis.Port.TIMEOUT
        } catch (e: SocketTimeoutException) { Diagnosis.Port.TIMEOUT
        } catch (t: Throwable) { Diagnosis.Port.TIMEOUT }
    }

    /** GET /v1/info on a bridge port (plain HTTP, which Desktop's bridge serves on its one port): the body, or null. Never logged */
    fun bridgeInfo(address: String, port: Int, timeoutMs: Int = Route.PROBE_MS): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(address, port), timeoutMs)
            s.soTimeout = timeoutMs
            val host = if (address.contains(':')) "[$address]" else address
            s.getOutputStream().apply { write("GET /v1/info HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
            val got = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val inp = s.getInputStream()
            while (got.size() < 64 * 1024) { val n = inp.read(buf); if (n < 0) break; got.write(buf, 0, n) }
            val text = got.toString("UTF-8")
            if (!text.startsWith("HTTP/1.1 200")) null else text.substringAfter("\r\n\r\n", "").ifEmpty { null }
        }
    } catch (_: Throwable) { null }

    /**
     * Issue #414: of [ports] at [address], the first (in order) whose /v1/info names Desktop [desktopId], or null. All are asked at
     * once, so a PC that drops closed ports (Windows' firewall) costs one wait, not one per port. A worker thread only.
     */
    fun findBridge(address: String, ports: List<Int>, desktopId: String, timeoutMs: Int = Route.PROBE_MS): Int? {
        if (ports.isEmpty() || desktopId.isEmpty()) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(ports.size.coerceAtMost(BridgePorts.SPAN))
        try {
            val asks = ports.map { p -> pool.submit<Boolean> { BridgePorts.sameDesktop(bridgeInfo(address, p, timeoutMs), desktopId) } }
            for (i in ports.indices) {
                val yes = try { asks[i].get(timeoutMs * 3L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) { false }
                if (yes) return ports[i]
            }
            return null
        } finally { pool.shutdownNow() }
    }

    /**
     * A PC typed in by IP (another subnet: no mDNS): its Desktop's bridge, found by asking /v1/info on [ports] (the first that answers
     * as Desktop wins). Null = nothing there. A worker thread only.
     */
    fun findDesktop(address: String, ports: List<Int> = BridgePorts.others(-1), timeoutMs: Int = Route.PROBE_MS): FoundPc? {
        if (ports.isEmpty()) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(ports.size.coerceAtMost(BridgePorts.SPAN))
        try {
            val asks = ports.map { p -> pool.submit<FoundPc?> { ManualHost.fromInfo(address, p, bridgeInfo(address, p, timeoutMs)) } }
            for (a in asks) {
                val pc = try { a.get(timeoutMs * 3L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) { null }
                if (pc != null) return pc
            }
            return null
        } finally { pool.shutdownNow() }
    }

    /** everything [Diagnosis.reason] needs for [host] (network calls: a worker thread) */
    fun probe(ctx: Context, host: PairedHost, seen: List<FoundPc>, notPaired: Boolean = false): Diagnosis.Probe {
        val wifi = onWifi(ctx)
        // off Wi-Fi a VPN (Tailscale) may still reach the PC: measure it then too (the user, 2026-11-11: streaming away fixes)
        val vpn = vpnUp(ctx)
        val dial = wifi || vpn
        val here = seen.firstOrNull { it.address == host.address || (host.desktopId.isNotEmpty() && it.desktopId == host.desktopId) }
        return Diagnosis.Probe(
            onWifi = wifi, thorSubnet = subnet(ctx), pcSubnet = host.subnet.ifEmpty { PairedHost.subnetOf(host.address) },
            sunshine = if (dial && host.httpPort > 0) port(host.address, host.httpPort) else Diagnosis.Port.NOT_TRIED,
            bridge = if (dial && host.hasBridge) port(host.address, host.bridgePort) else Diagnosis.Port.NOT_TRIED,
            seenSunshine = here?.hasSunshine == true, seenBridge = here?.hasBridge == true,
            expectsBridge = host.hasBridge, notPaired = notPaired, vpnUp = vpn, pcTailscale = Route.tailscale(host.address),
        )
    }
}
