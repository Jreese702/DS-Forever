package com.jrees.wowcompanion.stream

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming away from home (the user, 2026-11-11: streaming away fixes). The user's network: the PC 192.168.1.11 at home and
 * 100.69.36.86 on Tailscale, Desktop on the 52989 base port.
 */
class StreamAwayTest {
    private val pc = PairedHost(name = "DESKTOP-ERNE9KJ", address = "192.168.1.11", serverCertPem = "PEM", bridgePort = 48210,
        bridgeTls = true, bridgeFingerprint = "AB:CD", bridgeToken = "t", desktopId = "d1", lanAddress = "192.168.1.11")
    private val home = "192.168.1"

    @Test fun homeWifiStaysLocalAwayIsRemote() {
        assertEquals(StreamMode.Choice(StreamMode.LOCAL, 1392), StreamMode.choose("192.168.1.11", home, vpnUp = false))
        // at home with Tailscale on, dialling the LAN address: still local
        assertEquals(StreamMode.Choice(StreamMode.LOCAL, 1392), StreamMode.choose("192.168.1.11", home, vpnUp = true))
        // Tailscale, on Wi-Fi or mobile data: remote, 1024 (IPv4) / 1184 (IPv6)
        assertEquals(StreamMode.Choice(StreamMode.REMOTE, 1024), StreamMode.choose("100.69.36.86", home, vpnUp = true))
        assertEquals(StreamMode.Choice(StreamMode.REMOTE, 1024), StreamMode.choose("100.69.36.86", "", vpnUp = true))
        assertEquals(StreamMode.Choice(StreamMode.REMOTE, 1184), StreamMode.choose("fd7a:115c:a1e0::1", "", vpnUp = true))
        // a home address reached off Wi-Fi (a subnet route over the VPN) or through a VPN from another Wi-Fi: remote
        assertEquals(StreamMode.REMOTE, StreamMode.choose("192.168.1.11", "", vpnUp = true).config)
        assertEquals(StreamMode.REMOTE, StreamMode.choose("192.168.1.11", "10.0.0", vpnUp = true).config)
        // anything else on Wi-Fi without a VPN: the core decides by the address
        assertEquals(StreamMode.Choice(StreamMode.AUTO, 1392), StreamMode.choose("10.0.0.7", home, vpnUp = false))
    }

    @Test fun tailscaleRangesBothFamilies() {
        assertTrue(Route.tailscale("100.69.36.86"))
        assertTrue(Route.tailscale("fd7a:115c:a1e0::1234"))
        assertTrue(Route.tailscale("FD7A:115C:A1E0:ab12::1"))
        assertFalse(Route.tailscale("fd7a:115c:a1e1::1"))
        assertFalse(Route.tailscale("fe80::1"))
        assertFalse(Route.tailscale("192.168.1.11"))
    }

    @Test fun theWelcomeCarriesTailscaleAndOlderDesktopsStillParse() {
        val m = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("lan", JSONArray(listOf("192.168.1.11")))
            .put("tailscale", JSONArray(listOf("100.69.36.86", "fd7a:115c:a1e0::5")))
            .put("sunshine", JSONObject().put("http_port", 52989).put("https_port", 52984)).toString()) as BridgeMsg.Welcome
        assertEquals(listOf("100.69.36.86", "fd7a:115c:a1e0::5"), m.tailscale)
        assertEquals(52989, m.httpPort)
        val older = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("lan", JSONArray(listOf("192.168.1.11"))).toString()) as BridgeMsg.Welcome
        assertEquals(emptyList<String>(), older.tailscale)
    }

    @Test fun aThorPairedAtHomeRemembersTheTailscaleAddresses() {
        val n = Route.reported(pc, listOf("192.168.1.11"), home, listOf("fd7a:115c:a1e0::5", "100.69.36.86", "8.8.8.8"))
        assertEquals(listOf("fd7a:115c:a1e0::5", "100.69.36.86"), n.tailnet)            // only Tailscale's ranges
        // stored and read back
        assertEquals(n.tailnet, PairedHost.fromJson(n.toJson())!!.tailnet)
        // an older Desktop (no list) keeps what was remembered; an older store reads as none
        assertEquals(n.tailnet, Route.reported(n, listOf("192.168.1.11"), home).tailnet)
        assertEquals(emptyList<String>(), PairedHost.fromJson(pc.toJson().apply { remove("ts") })!!.tailnet)
        // away (mobile data: no Wi-Fi /24): home first as ever, then Tailscale, IPv4 before IPv6
        assertEquals(listOf("192.168.1.11", "100.69.36.86", "fd7a:115c:a1e0::5"), Route.candidates(n, emptyList(), emptyList(), ""))
        // at home: the LAN, then Tailscale
        assertEquals(listOf("192.168.1.11", "100.69.36.86", "fd7a:115c:a1e0::5"), Route.candidates(n, emptyList(), emptyList(), home))
        // the one that worked away is remembered, the tailnet stays
        val away = Route.after(n, "100.69.36.86", "")
        assertEquals("100.69.36.86", away.vpnAddress)
        assertEquals(n.tailnet, away.tailnet)
        assertEquals("100.69.36.86", Route.candidates(away, emptyList(), emptyList(), "").let { it[it.indexOf("192.168.1.11") + 1] })
    }

    private fun probe(onWifi: Boolean, vpnUp: Boolean, address: String, sun: Diagnosis.Port = Diagnosis.Port.TIMEOUT) =
        Diagnosis.Probe(onWifi = onWifi, thorSubnet = if (onWifi) "10.1.1" else "", pcSubnet = home, sunshine = sun, bridge = sun,
            vpnUp = vpnUp, pcTailscale = Route.tailscale(address))

    @Test fun mobileDataWithTailscaleIsNotNoWifi() {
        // mobile data, no VPN: the old message, worded for both ways in
        assertEquals(Diagnosis.Reason.NO_WIFI, Diagnosis.reason(probe(false, false, "100.69.36.86", Diagnosis.Port.NOT_TRIED)))
        assertTrue(Diagnosis.Reason.NO_WIFI.detail.contains("Tailscale"))
        // mobile data with Tailscale up: measured, not "isn't on Wi-Fi"
        assertEquals(Diagnosis.Reason.VPN_NO_ANSWER, Diagnosis.reason(probe(false, true, "100.69.36.86")))
        assertEquals(Diagnosis.Reason.OK, Diagnosis.reason(probe(false, true, "100.69.36.86", Diagnosis.Port.OPEN)))
        assertEquals(Diagnosis.Reason.DESKTOP_NOT_RUNNING, Diagnosis.reason(probe(false, true, "100.69.36.86", Diagnosis.Port.REFUSED)))
        // another Wi-Fi with Tailscale up, dialling the Tailscale address: over Tailscale, not "another network"
        assertEquals(Diagnosis.Reason.VPN_NO_ANSWER, Diagnosis.reason(probe(true, true, "100.69.36.86")))
        // another Wi-Fi without a VPN: as before
        assertEquals(Diagnosis.Reason.OTHER_NETWORK, Diagnosis.reason(probe(true, false, "192.168.1.11")))
    }

    @Test fun cantReachBacksOffThenOffersRetry() {
        val waits = (1..13).map { ReachBackoff.delayMs(it) / 1000 }
        assertEquals(listOf(3L, 3, 3, 10, 10, 10, 30, 30, 30, 60, 60, 60, 60), waits)
        assertFalse((1..9).any { ReachBackoff.manual(it) })
        assertTrue(ReachBackoff.manual(10))
        assertTrue(ReachBackoff.manual(50))
    }

    @Test fun noVideoNamesDesktopsPorts() {
        assertTrue(StreamSession.terminationText(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, 52989).contains("UDP 52998-53000"))
        assertTrue(StreamSession.terminationText(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, 47989).contains("UDP 47998-48000"))
        assertEquals("UDP 47998-48000", StreamSession.udpPorts(0))                // unknown: Moonlight's default
    }
}
