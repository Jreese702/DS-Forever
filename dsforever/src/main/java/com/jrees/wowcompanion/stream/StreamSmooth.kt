package com.jrees.wowcompanion.stream

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/*
 * STREAM SMOOTHNESS (a tester's report 2026-11-23: "stutters every few seconds" where Artemis on the same Thor is smooth; the user's
 * picks the same day: "Stream smoothness (Recommended)", and "Add it (Recommended)": "Declare WAKE_LOCK; hold the Wi-Fi low-latency
 * lock only while a stream runs"). What moonlight-android / Artemis do and we did not:
 *  - [StreamWifiLock]: the Wi-Fi low-latency lock (WIFI_MODE_FULL_LOW_LATENCY, else HIGH_PERF) from a stream's start to its end, so
 *    Android's Wi-Fi power save and background scans stop making a burst of lost packets every few seconds;
 *  - [RfiPolicy]: reference-frame invalidation, the core's cheap recovery of a lost frame from an older one (HEVC / AV1, the decoder
 *    Moonlight's rule trusts), instead of a whole new picture (a size spike, a visible hitch) for every blip; off by itself on this Thor
 *    after a stream it may have broken ([RfiPolicy.trips], until the next DS Forever build);
 *  - [StreamStats]: one line to the Thor's log every 5 s (frames received / decoded / shown, drops, the bit rate, the round trip, the
 *    Wi-Fi link) - nothing named, no address.
 * The pure parts are objects the tests drive; the Android parts are thin.
 */

/** which Wi-Fi lock mode, in the order tried (pure) */
object WifiLockPlan {
    /** WifiManager.WIFI_MODE_FULL_LOW_LATENCY (API 29) and WIFI_MODE_FULL_HIGH_PERF, as numbers so the tests need no android.jar */
    const val LOW_LATENCY = 4
    const val HIGH_PERF = 3

    /** low latency first where Android has it (10+), then high-perf (the older Androids' best, and the fallback if the first refuses) */
    fun modes(sdk: Int): List<Int> = if (sdk >= 29) listOf(LOW_LATENCY, HIGH_PERF) else listOf(HIGH_PERF)

    fun label(mode: Int): String = when (mode) { LOW_LATENCY -> "low-latency"; HIGH_PERF -> "high-perf"; else -> "mode $mode" }
}

/**
 * The stream's Wi-Fi lock: [acquire] at a stream's start, [release] at its every end (stop, the surface gone with Home or a pause, the
 * screen closing, a lost connection). Not reference counted: one acquire, one release, however often either is called. A crash needs
 * nothing: Android drops a dead process's locks.
 */
class StreamWifiLock(context: Context) {
    private val wifi: WifiManager? = try { context.applicationContext.getSystemService(WifiManager::class.java) } catch (_: Throwable) { null }
    private var lock: WifiManager.WifiLock? = null
    private var mode = 0

    val held: Boolean @Synchronized get() = try { lock?.isHeld == true } catch (_: Throwable) { false }

    /** takes the lock (once); the word for the log */
    @Synchronized fun acquire(): String {
        if (held) return "wifi lock: already held (${WifiLockPlan.label(mode)})"
        val w = wifi ?: return "wifi lock: no Wi-Fi service"
        var why = ""
        for (m in WifiLockPlan.modes(Build.VERSION.SDK_INT)) {
            try {
                @Suppress("DEPRECATION")
                val l = w.createWifiLock(m, TAG_LOCK)
                l.setReferenceCounted(false)
                l.acquire()
                lock = l; mode = m
                return "wifi lock: ${WifiLockPlan.label(m)} held for the stream"
            } catch (t: Throwable) { why = t.javaClass.simpleName }
        }
        return "wifi lock: refused ($why)"
    }

    /** lets it go (any number of times); the word for the log, "" when none was held */
    @Synchronized fun release(why: String): String {
        val l = lock ?: return ""
        lock = null
        try { if (l.isHeld) l.release() } catch (_: Throwable) {}
        return "wifi lock: released ($why)"
    }

    private companion object { const val TAG_LOCK = "DSForever:stream" }
}

/**
 * REFERENCE-FRAME INVALIDATION (moonlight-common-c's RFI: Limelight.h CAPABILITY_REFERENCE_FRAME_INVALIDATION_*). With it, a lost frame
 * is answered by "invalidate frames a..b" and the PC's encoder refers back to a frame the Thor has; without it every loss is a whole
 * new keyframe. Pure. moonlight-android's rule (MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc / Av1): the decoder says
 * FEATURE_LowLatency (Android 11+), or it is one with a known vendor low-latency option (Qualcomm's c2.qti / OMX.qcom: the Thor is a
 * Snapdragon). Never H.264: that needs SPS rewriting (num_ref_frames) we don't do. The core still asks for a keyframe when an RFI
 * request can't be met (its own fallback), and the decoder's DR_NEED_IDR always does.
 */
object RfiPolicy {
    private val VENDOR_LOW_LATENCY = listOf("c2.qti.", "omx.qcom.")

    fun decoderOk(name: String?, lowLatencyFeature: Boolean): Boolean =
        lowLatencyFeature || (name != null && VENDOR_LOW_LATENCY.any { name.lowercase().startsWith(it) })

    /** the video capabilities for [MoonBridge.startConnection]: direct submit, plus RFI for HEVC / AV1 when [on] and its decoder is ok */
    fun capabilities(on: Boolean, hevcOk: Boolean, av1Ok: Boolean): Int {
        var c = MoonBridge.CAPABILITY_DIRECT_SUBMIT
        if (on && hevcOk) c = c or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
        if (on && av1Ok) c = c or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1
        return c
    }

    /**
     * the same for the player's [codec] (review 2026-11-23): only the codecs that stream can run - HEVC for an HEVC or AV1 pick (AV1's
     * fallback), AV1 for an AV1 pick, none for H.264. Without it an H.264 stream was logged "RFI HEVC" and its "pictures stopped" end
     * turned RFI off for the HEVC streams after it
     */
    fun capabilities(codec: StreamQuality.Codec, on: Boolean, hevcOk: Boolean, av1Ok: Boolean): Int =
        capabilities(on, hevcOk && codec != StreamQuality.Codec.H264, av1Ok && codec == StreamQuality.Codec.AV1)

    /** the log's reason when [capabilities] for [codec] has no RFI and it was not tripped off */
    fun offReason(codec: StreamQuality.Codec): String = if (codec == StreamQuality.Codec.H264) "H.264" else "no decoder for it"

    fun has(caps: Int): Boolean = caps and (MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC or
        MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1) != 0

    /**
     * RFI was on for the codec the decoder actually ran ([mime], VideoDecoder.codecMime: the PC may answer an HEVC ask with H.264);
     * null = no decoder set up
     */
    fun inUse(caps: Int, mime: String?): Boolean = when (mime) {
        "video/hevc" -> caps and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC != 0
        "video/av01" -> caps and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1 != 0
        "video/avc" -> caps and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC != 0
        else -> false
    }

    /** the log's words: "RFI HEVC+AV1", "RFI off (...)" */
    fun label(caps: Int, offWhy: String = ""): String {
        val on = listOfNotNull("HEVC".takeIf { caps and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC != 0 },
            "AV1".takeIf { caps and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1 != 0 })
        return if (on.isNotEmpty()) "RFI " + on.joinToString("+") else "RFI off" + if (offWhy.isNotEmpty()) " ($offWhy)" else ""
    }

    /**
     * THE SAFE DEFAULT: an RFI stream that showed pictures and then went wrong in a way RFI could cause - the decoder threw, or the PC
     * "stopped sending pictures" ([MoonBridge.ML_ERROR_NO_VIDEO_FRAME]) - turns RFI off on this Thor for later streams (the old keyframe
     * recovery), until the next DS Forever build tries again. Only when RFI was on for the codec that ran ([mime], [inUse]). The reason,
     * or null = no trip.
     */
    fun trips(caps: Int, error: Int, decoderErrors: Long, shown: Long, mime: String?): String? = when {
        !inUse(caps, mime) || shown <= 0 -> null
        decoderErrors > 0 -> "the decoder failed $decoderErrors times"
        error == MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> "the pictures stopped"
        else -> null
    }

    /** RFI is allowed unless it tripped on this very build ([tripBuild] = the build it tripped on, 0 = never) */
    fun allowed(tripBuild: Long, build: Long): Boolean = tripBuild == 0L || tripBuild != build
}

/** the trip's memory (HostStore's preferences) */
object RfiStore {
    private const val KEY = "rfiOffBuild"
    private const val WHY = "rfiOffWhy"

    fun build(ctx: Context): Long = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val p = pm.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    } catch (_: Throwable) { 0L }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(HostStore.PREFS, Context.MODE_PRIVATE)

    /** null = allowed; else why it is off */
    fun offWhy(ctx: Context): String? {
        val p = prefs(ctx)
        val at = p.getLong(KEY, 0L)
        return if (RfiPolicy.allowed(at, build(ctx))) null else "turned off after a stream on this build: " + p.getString(WHY, "").orEmpty()
    }

    fun trip(ctx: Context, why: String) {
        prefs(ctx).edit().putLong(KEY, build(ctx)).putString(WHY, why).apply()
        Log.i("DSFSTREAM", "smooth: RFI turned off on this Thor until the next build ($why); keyframe recovery as before")
    }
}

/** one reading of the decoder's counters ([VideoDecoder.snap]) */
data class StatsSnap(
    val atMs: Long,
    /** picture frames the core handed over (received whole) */
    val received: Long,
    /** decoded by MediaCodec (its output buffers) */
    val decoded: Long,
    /** shown on the top screen */
    val shown: Long,
    /** decoded but replaced by a newer one before it was shown, plus frames that never reached the decoder */
    val dropped: Long,
    val bytes: Long,
    /** keyframes asked for (DR_NEED_IDR) */
    val keyframeAsks: Long,
)

/** THE STATS LINE every 5 s while streaming (pure: the tests drive it). No names, no addresses */
object StreamStats {
    const val EVERY_MS = 5_000L

    private fun rate(n: Long, ms: Long): String = if (ms <= 0) "0" else String.format(java.util.Locale.ROOT, "%.1f", n * 1000.0 / ms)

    /** [rtt] = the core's (rtt << 32) | variance, -1 = none; [linkMbps] / [rssi] = the Wi-Fi link (-1 / null = unknown) */
    fun line(a: StatsSnap, b: StatsSnap, rtt: Long, linkMbps: Int, rssi: Int?, rfi: String): String {
        val ms = b.atMs - a.atMs
        val mbps = if (ms <= 0) "0" else String.format(java.util.Locale.ROOT, "%.1f", (b.bytes - a.bytes) * 8.0 / 1000.0 / ms)
        val r = if (rtt < 0) "rtt -" else "rtt ${rtt ushr 32}±${rtt and 0xFFFFFFFFL} ms"
        val wifi = when {
            linkMbps <= 0 && rssi == null -> "wifi -"
            else -> "wifi " + (if (linkMbps > 0) "$linkMbps Mbps" else "-") + (rssi?.let { " $it dBm" } ?: "")
        }
        return "stats: fps in ${rate(b.received - a.received, ms)}, decoded ${rate(b.decoded - a.decoded, ms)}, shown ${rate(b.shown - a.shown, ms)}; " +
            "dropped ${b.dropped - a.dropped}, keyframe asks ${b.keyframeAsks - a.keyframeAsks}; $mbps Mbps; $r; $wifi; $rfi"
    }

    /** the Wi-Fi link's speed (Mbps, -1 = unknown) and signal (dBm, null = unknown): no network name, no address */
    fun wifiLink(ctx: Context): Pair<Int, Int?> = try {
        @Suppress("DEPRECATION")
        val info = ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        val speed = info?.linkSpeed ?: -1
        val rssi = info?.rssi?.takeIf { it in -127..0 && it != -127 }
        (if (speed > 0) speed else -1) to rssi
    } catch (_: Throwable) { -1 to null }
}
