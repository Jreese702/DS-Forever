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
    fun merge(ads: Collection<Advert>): List<FoundPc> {
        val byAddr = LinkedHashMap<String, FoundPc>()
        for (a in ads) {
            val t = a.type.trim('.').removeSuffix(".local").trim('.')
            val cur = byAddr[a.address] ?: FoundPc(name = a.name, address = a.address)
            byAddr[a.address] = when {
                t.startsWith(NVSTREAM) -> cur.copy(sunshinePort = a.port, name = if (cur.hasBridge) cur.name else a.name)
                t.startsWith(DSFOREVER) -> cur.copy(bridgePort = a.port, name = a.txt["pc"]?.takeIf { it.isNotBlank() } ?: a.name,
                    desktopId = a.txt["id"].orEmpty(), sunshineId = a.txt["sid"].orEmpty(), bridgeFingerprint = a.txt["fp"].orEmpty().lowercase(),
                    bridgeTls = a.txt["tls"] == "1",
                    // Desktop's TXT names Sunshine's port: a PC whose _nvstream advert was missed is still streamable
                    sunshinePort = if (cur.sunshinePort > 0) cur.sunshinePort else a.txt["sunshine"]?.toIntOrNull() ?: 0)
                else -> cur
            }
        }
        return byAddr.values.sortedWith(compareBy<FoundPc>({ !it.hasBridge }, { it.name.lowercase() }))
    }
}

/**
 * mDNS on the Thor (NsdManager: no permission prompt, no multicast lock needed). Both service types are browsed; each found service
 * is resolved ONE AT A TIME (before Android 14 a second resolve while one runs fails with "already active"). [onChange] gets the
 * merged list on the main thread after every resolve or loss.
 */
class Discovery(context: Context, private val onChange: (List<FoundPc>) -> Unit) {
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

    fun current(): List<FoundPc> = FoundPcs.merge(ads.values)

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
                    val host = si.host
                    val txt = HashMap<String, String>()
                    try { for ((k, v) in si.attributes) txt[k] = v?.toString(Charsets.UTF_8).orEmpty() } catch (_: Throwable) {}
                    main.post {
                        resolving = false
                        if (host is Inet4Address) {
                            ads[key(si.serviceType, si.serviceName)] = Advert(si.serviceType, si.serviceName, host.hostAddress ?: "", si.port, txt)
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
