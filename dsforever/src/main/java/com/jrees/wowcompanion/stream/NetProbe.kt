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

    /** everything [Diagnosis.reason] needs for [host] (network calls: a worker thread) */
    fun probe(ctx: Context, host: PairedHost, seen: List<FoundPc>, notPaired: Boolean = false): Diagnosis.Probe {
        val wifi = onWifi(ctx)
        val here = seen.firstOrNull { it.address == host.address || (host.desktopId.isNotEmpty() && it.desktopId == host.desktopId) }
        return Diagnosis.Probe(
            onWifi = wifi, thorSubnet = subnet(ctx), pcSubnet = host.subnet.ifEmpty { PairedHost.subnetOf(host.address) },
            sunshine = if (wifi) port(host.address, host.httpPort) else Diagnosis.Port.NOT_TRIED,
            bridge = if (wifi && host.hasBridge) port(host.address, host.bridgePort) else Diagnosis.Port.NOT_TRIED,
            seenSunshine = here?.hasSunshine == true, seenBridge = here?.hasBridge == true,
            expectsBridge = host.hasBridge, notPaired = notPaired,
        )
    }
}
