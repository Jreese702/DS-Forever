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
    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** the Thor's own IPv4 /24 on the active network ("" = none) */
    fun subnet(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return ""
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return ""
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
