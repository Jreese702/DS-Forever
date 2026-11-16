package com.jrees.wowcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #414 item 2 (the user's pick, 2026-11-16: "after a 'No video arrived' failure on a home (LOCAL) stream, retry ONCE in the
 * no-QoS mode (qosTrafficType 0), logging it").
 */
class QosRetryTest {
    private val noVideo = MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC

    @Test fun noVideoOnAHomeStreamRetriesOnceWithoutQos() {
        // the retry: REMOTE (qosTrafficType 0) with the LAN packet size, said as such in the log
        val m = StreamMode.retryAfter(StreamMode.HOME, noVideo)
        assertEquals(StreamMode.NO_QOS, m)
        assertEquals(StreamMode.REMOTE, m!!.config)
        assertEquals(StreamSession.PACKET_SIZE, m.packetSize)
        assertEquals("no-QoS 1392", m.label)
        assertEquals("local 1392", StreamMode.HOME.label)
        // once: the retry's own failure gets no other
        assertNull(StreamMode.retryAfter(StreamMode.NO_QOS, noVideo))
        // only "No video arrived", only a home (LOCAL) stream
        assertNull(StreamMode.retryAfter(StreamMode.HOME, MoonBridge.ML_ERROR_NO_VIDEO_FRAME))
        assertNull(StreamMode.retryAfter(StreamMode.HOME, MoonBridge.ML_ERROR_GRACEFUL_TERMINATION))
        assertNull(StreamMode.retryAfter(StreamMode.HOME, -1))
        assertNull(StreamMode.retryAfter(StreamMode.choose("100.69.36.86", "", vpnUp = true), noVideo))     // remote: no QoS already
        assertNull(StreamMode.retryAfter(StreamMode.choose("10.0.0.7", "192.168.1", vpnUp = false), noVideo)) // auto
        assertNull(StreamMode.retryAfter(null, noVideo))
    }

    @Test fun theRetryTakesTheHomeRouteOnly() {
        val home = StreamMode.choose("192.168.1.11", "192.168.1", vpnUp = false)
        assertEquals(StreamMode.NO_QOS, StreamMode.forConnect(home, StreamMode.NO_QOS))
        assertEquals(home, StreamMode.forConnect(home, null))
        // the retry's route went to Tailscale: remote as ever (no QoS there either)
        val away = StreamMode.choose("100.69.36.86", "192.168.1", vpnUp = true)
        assertEquals(away, StreamMode.forConnect(away, StreamMode.NO_QOS))
    }
}
