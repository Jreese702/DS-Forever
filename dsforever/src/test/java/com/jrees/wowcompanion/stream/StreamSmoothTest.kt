package com.jrees.wowcompanion.stream

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * STREAM SMOOTHNESS (a tester's report 2026-11-23; the user's picks: "Stream smoothness", "Up to 150", "Add it" = WAKE_LOCK for the
 * Wi-Fi low-latency lock only while a stream runs): the pure parts of StreamSmooth.kt, the decoder's input size, the bit-rate steps, and
 * the wiring in StreamTop (the lock and the PC search follow the stream's life). Off-device.
 */
class StreamSmoothTest {
    private fun src(rel: String): String =
        listOf("src/main/java/com/jrees/wowcompanion/stream/$rel", "dsforever/src/main/java/com/jrees/wowcompanion/stream/$rel")
            .map { File(it) }.first { it.canRead() }.readText()
    private fun manifest(): String =
        listOf("src/main/AndroidManifest.xml", "dsforever/src/main/AndroidManifest.xml").map { File(it) }.first { it.canRead() }.readText()

    // ---------------------------------------------------------------- the Wi-Fi lock
    @Test fun wifiLockModes() {
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiLockPlan.LOW_LATENCY)
        @Suppress("DEPRECATION") assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WifiLockPlan.HIGH_PERF)
        assertEquals("low latency first, high-perf as its fallback", listOf(4, 3), WifiLockPlan.modes(29))
        assertEquals(listOf(4, 3), WifiLockPlan.modes(35))
        assertEquals("before Android 10: high-perf only", listOf(3), WifiLockPlan.modes(28))
        assertEquals("low-latency", WifiLockPlan.label(4)); assertEquals("high-perf", WifiLockPlan.label(3))
    }

    @Test fun wakeLockIsTheOneNewPermission() {
        val m = manifest()
        assertEquals(1, Regex("""<uses-permission android:name="android\.permission\.WAKE_LOCK" />""").findAll(m).count())
        val perms = Regex("""<uses-permission android:name="android\.permission\.([A-Z_]+)"""").findAll(m).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("INTERNET", "ACCESS_NETWORK_STATE", "REORDER_TASKS", "WAKE_LOCK"), perms)
    }

    @Test fun theLockAndTheSearchFollowTheStream() {
        val top = src("StreamTop.kt")
        val start = top.substringAfter("private fun smoothStart()").substringBefore("private fun smoothStop()")
        val stop = top.substringAfter("private fun smoothStop()").substringBefore("// ----------------------------------------------------------------")
        assertTrue("taken at the start", start.contains("wifiLock.acquire()"))
        assertTrue("the PC search stops for the stream", start.contains("discovery?.stop()") && start.contains("discoveryPaused = true"))
        assertTrue("the stats every 5 s", start.contains("main.postDelayed(statsTick, StreamStats.EVERY_MS)"))
        assertTrue("let go at the end", stop.contains("wifiLock.release("))
        assertTrue("the search back only while the screen is open", stop.contains("if (isOpen) { discovery?.start()"))
        assertTrue(stop.contains("main.removeCallbacks(statsTick)"))
        // every end goes through teardown (stop, Home / the surface gone, a lost connection, the screen closing), which ends it
        val teardown = top.substringAfter("private fun teardown() {").substringBefore("\n    }\n")
        assertTrue(teardown.contains("smoothStop()"))
        val started = top.substringAfter("private fun onStarted(").substringBefore("show(Screen.STREAMING)")
        assertTrue("only once the stream is up", started.contains("smoothStart()"))
        assertFalse("never taken anywhere else", top.substringBefore("private fun smoothStart()").contains("wifiLock.acquire()"))
        // the lock is not reference counted: one release lets it go however often it was asked for
        assertTrue(src("StreamSmooth.kt").contains("setReferenceCounted(false)"))
    }

    // ---------------------------------------------------------------- reference-frame invalidation
    @Test fun rfiDecoders() {
        assertTrue("the Thor's Snapdragon (Codec 2)", RfiPolicy.decoderOk("c2.qti.hevc.decoder", false))
        assertTrue(RfiPolicy.decoderOk("OMX.qcom.video.decoder.hevc", false))
        assertTrue("Android's low-latency feature", RfiPolicy.decoderOk("c2.exynos.hevc.decoder", true))
        assertFalse("a software one", RfiPolicy.decoderOk("c2.android.hevc.decoder", false))
        assertFalse(RfiPolicy.decoderOk(null, false))
    }

    @Test fun rfiCapabilities() {
        val ds = MoonBridge.CAPABILITY_DIRECT_SUBMIT
        assertEquals(ds or 0x4 or 0x40, RfiPolicy.capabilities(on = true, hevcOk = true, av1Ok = true))
        assertEquals(ds or 0x4, RfiPolicy.capabilities(true, hevcOk = true, av1Ok = false))
        assertEquals("off: direct submit alone, as before", ds, RfiPolicy.capabilities(false, true, true))
        assertEquals(ds, RfiPolicy.capabilities(true, false, false))
        for (on in listOf(true, false)) for (h in listOf(true, false)) for (a in listOf(true, false))
            assertEquals("never H.264 (no SPS rewriting)", 0, RfiPolicy.capabilities(on, h, a) and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC)
        assertEquals("RFI HEVC+AV1", RfiPolicy.label(ds or 0x4 or 0x40))
        assertEquals("RFI HEVC", RfiPolicy.label(ds or 0x4))
        assertEquals("RFI off (no decoder for it)", RfiPolicy.label(ds, "no decoder for it"))
        assertTrue(RfiPolicy.has(ds or 0x4)); assertFalse(RfiPolicy.has(ds))
    }

    @Test fun rfiTripsOnlyAfterAPictureAndATrouble() {
        val rfi = MoonBridge.CAPABILITY_DIRECT_SUBMIT or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
        assertNotNull(RfiPolicy.trips(rfi, 0, decoderErrors = 2, shown = 500))
        assertEquals("the pictures stopped", RfiPolicy.trips(rfi, MoonBridge.ML_ERROR_NO_VIDEO_FRAME, 0, 500))
        assertNull("a normal end", RfiPolicy.trips(rfi, MoonBridge.ML_ERROR_GRACEFUL_TERMINATION, 0, 500))
        assertNull("no video at all is the network's (the no-QoS retry's), not RFI's", RfiPolicy.trips(rfi, MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, 0, 0))
        assertNull("never a picture: not RFI's", RfiPolicy.trips(rfi, MoonBridge.ML_ERROR_NO_VIDEO_FRAME, 3, 0))
        assertNull("RFI was off", RfiPolicy.trips(MoonBridge.CAPABILITY_DIRECT_SUBMIT, MoonBridge.ML_ERROR_NO_VIDEO_FRAME, 3, 500))
        // tripped: off on that build, tried again on the next
        assertTrue(RfiPolicy.allowed(0L, 1522L))
        assertFalse(RfiPolicy.allowed(1522L, 1522L))
        assertTrue(RfiPolicy.allowed(1522L, 1523L))
    }

    @Test fun rfiIsLoggedAndPassedToTheCore() {
        val top = src("StreamTop.kt"); val ses = src("StreamSession.kt")
        assertTrue(top.contains("RfiPolicy.capabilities(offWhy == null, VideoDecoder.rfiOk(\"video/hevc\"), VideoDecoder.rfiOk(\"video/av01\"))"))
        assertTrue(top.contains("videoCapabilities = caps, capsLabel = label"))
        assertTrue("the session hands them to the core", ses.contains("LaunchPlan.riIv(riKeyId), videoCapabilities,"))
        assertFalse(ses.contains("LaunchPlan.riIv(riKeyId), MoonBridge.CAPABILITY_DIRECT_SUBMIT,"))
        assertTrue("the stream line says it", ses.contains("\", \$capsLabel\""))
    }

    // ---------------------------------------------------------------- the decoder's input size
    @Test fun inputSizeTakesAWholeFrame() {
        assertEquals("1080p: one raw picture", 1920 * 1080 * 3 / 2, VideoDecoder.maxInputSize(1920, 1080))
        assertEquals("720p: the 2 MB floor", 2 * 1024 * 1024, VideoDecoder.maxInputSize(1280, 720))
        assertEquals("never over 8 MB", 8 * 1024 * 1024, VideoDecoder.maxInputSize(7680, 4320))
        assertEquals(2 * 1024 * 1024, VideoDecoder.maxInputSize(0, 0))
        assertTrue(src("StreamMedia.kt").contains("setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxIn)"))
    }

    // ---------------------------------------------------------------- the stats line
    @Test fun statsLine() {
        val a = StatsSnap(atMs = 10_000, received = 100, decoded = 100, shown = 99, dropped = 1, bytes = 1_000_000, keyframeAsks = 0)
        val b = StatsSnap(atMs = 15_000, received = 400, decoded = 399, shown = 396, dropped = 4, bytes = 13_500_000, keyframeAsks = 1)
        val rtt = (4L shl 32) or 1L
        assertEquals("stats: fps in 60.0, decoded 59.8, shown 59.4; dropped 3, keyframe asks 1; 20.0 Mbps; rtt 4±1 ms; wifi 1201 Mbps -51 dBm; RFI HEVC",
            StreamStats.line(a, b, rtt, 1201, -51, "RFI HEVC"))
        assertEquals("stats: fps in 60.0, decoded 59.8, shown 59.4; dropped 3, keyframe asks 1; 20.0 Mbps; rtt -; wifi -; RFI off",
            StreamStats.line(a, b, -1, -1, null, "RFI off"))
        assertTrue(StreamStats.line(a, a, -1, 866, null, "").contains("fps in 0, decoded 0"))
        assertEquals(5_000L, StreamStats.EVERY_MS)
        // no name and no address in it: only numbers and fixed words
        assertFalse(src("StreamSmooth.kt").substringAfter("fun wifiLink").contains("ssid", ignoreCase = true))
    }

    // ---------------------------------------------------------------- the bit rate up to 150
    @Test fun bitRatesUpTo150() {
        assertEquals(listOf(5_000, 10_000, 15_000, 20_000, 30_000, 40_000, 50_000, 60_000, 80_000, 100_000, 120_000, 150_000), StreamQuality.BITRATES_KBPS)
        assertEquals("the default stays", 20_000, StreamQuality().bitrateKbps)
        assertNull(StreamQuality.wifiNote(StreamQuality(bitrateKbps = 50_000)))
        assertEquals("Needs strong 5/6 GHz Wi-Fi", StreamQuality.wifiNote(StreamQuality(bitrateKbps = 60_000)))
        assertEquals(StreamQuality.NOTE_WIFI, StreamQuality.wifiNote(StreamQuality(bitrateKbps = 150_000)))
        // a stored 150 is kept (it is on the list now); an unknown value still falls back to the default
        assertEquals(150_000, StreamQuality.fromJson(StreamQuality(bitrateKbps = 150_000).toJson()).bitrateKbps)
        assertEquals(20_000, StreamQuality.fromJson(org.json.JSONObject().put("kbps", 77_000)).bitrateKbps)
        assertEquals(60_000, StreamQuality.step(StreamQuality.BITRATES_KBPS, 50_000, 1))
        assertEquals("HEVC 1080p60, 150 Mbps", StreamQuality(bitrateKbps = 150_000).label)
    }

    @Test fun theWifiNoteIsTranslated() {
        for (lang in listOf("es", "fr")) {
            val f = listOf("../companion/app/src/main/assets/companion/i18n/$lang.json", "companion/app/src/main/assets/companion/i18n/$lang.json")
                .map { File(it) }.first { it.canRead() }
            val plain = org.json.JSONObject(f.readText()).getJSONObject("plain")
            assertTrue("$lang has the note", plain.has(StreamQuality.NOTE_WIFI) && plain.getString(StreamQuality.NOTE_WIFI).isNotBlank())
        }
    }
}
