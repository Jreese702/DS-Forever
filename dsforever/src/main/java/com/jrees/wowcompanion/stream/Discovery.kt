package com.jrees.wowcompanion.stream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address

/** one mDNS answer: Sunshine's _nvstream._tcp or the bridge's _dsforever._tcp (docs/STREAM_BRIDGE_PROTOCOL.md section 1) */
data class Advert(val type: String, val name: String, val address: String, val port: Int, val txt: Map<String, String> = emptyMap())

/** a PC on the LAN as the pairing screen lists it: Sunshine's port and, when Desktop runs, the bridge's */
data class FoundPc(
    val name: String,
    val address: String,
    val sunshinePort: Int = 0,
    val bridgePort: Int = 0,
    val desktopId: String = "",
    val sunshineId: String = "",
    val bridgeFingerprint: String = "",
    /** the advert's tls=1 (a wss:// bridge); Desktop v1 serves ws:// */
    val bridgeTls: Boolean = false,
    /** the Sunshine port Desktop's own TXT named (0 = none): it wins over an _nvstream advert ([FoundPcs.merge]) */
    val desktopSunshine: Int = 0,
) {
    val hasSunshine get() = sunshinePort > 0
    val hasBridge get() = bridgePort > 0
}

object FoundPcs {
    const val NVSTREAM = "_nvstream._tcp"
    const val DSFOREVER = "_dsforever._tcp"

    /**
     * The adverts as PCs: one PC per IPv4 address (Sunshine and the bridge on one PC share it). The bridge's TXT "pc" (else its
     * instance name) is the PC's name; Sunshine's is used when no bridge answered. Desktop PCs (with a bridge) first, then by name.
     * The bridge's TXT (Desktop v1): proto, id (Desktop's host id), pc, sunshine (Sunshine's http port), ver; fp / sid / tls when given.
     */
    fun merge(ads: Collection<Advert>, preferSubnet: String = ""): List<FoundPc> {
        val byAddr = LinkedHashMap<String, FoundPc>()
        for (a in ads) {
            val t = a.type.trim('.').removeSuffix(".local").trim('.')
            val cur = byAddr[a.address] ?: FoundPc(name = a.name, address = a.address)
            byAddr[a.address] = when {
                // Desktop's TXT names ITS Sunshine: it wins over an _nvstream advert at the same address, which may be another
                // Sunshine on the PC (the user's PC 2026-11-13: a separate Sunshine service on 47989, Desktop's on 52989)
                t.startsWith(NVSTREAM) -> cur.copy(sunshinePort = if (cur.desktopSunshine > 0) cur.desktopSunshine else a.port, name = if (cur.hasBridge) cur.name else a.name)
                t.startsWith(DSFOREVER) -> cur.copy(bridgePort = a.port, name = a.txt["pc"]?.takeIf { it.isNotBlank() } ?: a.name,
                    desktopId = a.txt["id"].orEmpty(), sunshineId = a.txt["sid"].orEmpty(), bridgeFingerprint = a.txt["fp"].orEmpty().lowercase(),
                    bridgeTls = a.txt["tls"] == "1",
                    // Desktop's TXT names Sunshine's port: a PC whose _nvstream advert was missed is still streamable
                    desktopSunshine = a.txt["sunshine"]?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
                    sunshinePort = a.txt["sunshine"]?.toIntOrNull()?.takeIf { it > 0 } ?: cur.sunshinePort)
                else -> cur
            }
        }
        // ONE PC SEEN AT TWO ADDRESSES (stream2, 2026-10-23: the bridge answered on the PC's Tailscale 100.x, Sunshine on its LAN
        // 192.168.1.x): the same name (Desktop's TXT "pc" = Sunshine's instance name = the PC's name) is one PC. Its ports combine; its
        // address is the one on the Thor's own Wi-Fi /24 when there is one (the home LAN, not a VPN tunnel), else the bridge's
        val byName = LinkedHashMap<String, FoundPc>()
        for (pc in byAddr.values) {
            val k = pc.name.lowercase()
            val cur = byName[k]
            if (cur == null) { byName[k] = pc; continue }
            val onLan = listOf(cur, pc).firstOrNull { preferSubnet.isNotEmpty() && PairedHost.subnetOf(it.address) == preferSubnet }
            val base = onLan ?: (if (pc.hasBridge && !cur.hasBridge) pc else cur)
            val other = if (base === cur) pc else cur
            byName[k] = base.copy(
                sunshinePort = listOf(base.desktopSunshine, other.desktopSunshine, base.sunshinePort, other.sunshinePort).firstOrNull { it > 0 } ?: 0,
                desktopSunshine = if (base.desktopSunshine > 0) base.desktopSunshine else other.desktopSunshine,
                bridgePort = if (base.bridgePort > 0) base.bridgePort else other.bridgePort,
                desktopId = base.desktopId.ifEmpty { other.desktopId }, sunshineId = base.sunshineId.ifEmpty { other.sunshineId },
                bridgeFingerprint = base.bridgeFingerprint.ifEmpty { other.bridgeFingerprint }, bridgeTls = base.bridgeTls || other.bridgeTls,
                name = if (base.hasBridge || !other.hasBridge) base.name else other.name,
            )
        }
        return byName.values.sortedWith(compareBy<FoundPc>({ !it.hasBridge }, { it.name.lowercase() }))
    }

    /** of a PC's IPv4 addresses, the one on the Thor's Wi-Fi /24 when there is one, else the first */
    fun pickAddress(addresses: List<String>, preferSubnet: String): String? =
        addresses.firstOrNull { preferSubnet.isNotEmpty() && PairedHost.subnetOf(it) == preferSubnet } ?: addresses.firstOrNull()
}

/**
 * mDNS on the Thor (NsdManager: no permission prompt, no multicast lock needed). Both service types are browsed; each found service
 * is resolved ONE AT A TIME (before Android 14 a second resolve while one runs fails with "already active"). [onChange] gets the
 * merged list on the main thread after every resolve or loss.
 */
class Discovery(context: Context, private val preferSubnet: () -> String = { "" }, private val onChange: (List<FoundPc>) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val ads = LinkedHashMap<String, Advert>()           // "type/name" -> resolved advert
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    @Volatile private var running = false

    fun start() {
        if (running || nsd == null) return
        running = true
        for (type in listOf(FoundPcs.NVSTREAM, FoundPcs.DSFOREVER)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { Log.i(TAG, "browsing $serviceType") }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "browse $serviceType failed $errorCode") }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) { main.post { queue.addLast(info); next() } }
                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post { if (ads.remove(key(info.serviceType, info.serviceName)) != null) publish() }
                }
            }
            listeners.add(l)
            try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) } catch (t: Throwable) { Log.w(TAG, "browse: ${t.message}") }
        }
    }

    fun stop() {
        running = false
        for (l in listeners) try { nsd?.stopServiceDiscovery(l) } catch (_: Throwable) {}
        listeners.clear(); queue.clear()
    }

    fun current(): List<FoundPc> = FoundPcs.merge(ads.values, preferSubnet())

    private fun key(type: String, name: String) = type.trim('.') + "/" + name

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || !running) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) queue.addLast(si); main.postDelayed({ next() }, 200) }
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    // every IPv4 address the PC answered with (Android 14+ lists them all), the home LAN's first
                    val all = try {
                        if (android.os.Build.VERSION.SDK_INT >= 34) si.hostAddresses.filterIsInstance<Inet4Address>() else listOfNotNull(si.host as? Inet4Address)
                    } catch (_: Throwable) { listOfNotNull(si.host as? Inet4Address) }
                    val pick = FoundPcs.pickAddress(all.mapNotNull { it.hostAddress }, try { preferSubnet() } catch (_: Throwable) { "" })
                    val txt = HashMap<String, String>()
                    try { for ((k, v) in si.attributes) txt[k] = v?.toString(Charsets.UTF_8).orEmpty() } catch (_: Throwable) {}
                    main.post {
                        resolving = false
                        if (pick != null) {
                            ads[key(si.serviceType, si.serviceName)] = Advert(si.serviceType, si.serviceName, pick, si.port, txt)
                            publish()
                        }
                        next()
                    }
                }
            })
        } catch (t: Throwable) { resolving = false; Log.w(TAG, "resolve: ${t.message}") }
    }

    private fun publish() = onChange(current())

    companion object { const val TAG = "DSFSTREAM" }
}
