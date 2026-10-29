package com.jrees.wowcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wi-Fi first (the user, 2026-10-28: "Yes, Wi-Fi first"). The user's network that day: the PC 192.168.1.11 on the LAN and
 * 100.69.36.86 on Tailscale, the Thor 192.168.1.25 with its Tailscale off; the stored address was the Tailscale one.
 */
class RouteTest {
    private val pc = PairedHost(name = "DESKTOP-ERNE9KJ", address = "100.69.36.86", serverCertPem = "PEM", bridgePort = 48210,
        bridgeTls = true, bridgeFingerprint = "AB:CD", bridgeToken = "t", desktopId = "d1")
    private val thor = "192.168.1"

    @Test fun homeNetworkBeforeTailscale() {
        // nothing known but the Tailscale address, mDNS sees the LAN one: LAN first
        assertEquals(listOf("192.168.1.11", "100.69.36.86"), Route.candidates(pc, listOf("192.168.1.11"), emptyList(), thor))
        // remembered LAN address, no mDNS answer
        assertEquals(listOf("192.168.1.11", "100.69.36.86"), Route.candidates(pc.copy(lanAddress = "192.168.1.11"), emptyList(), emptyList(), thor))
        // Desktop's welcome reported it
        assertEquals(listOf("192.168.1.11", "100.69.36.86"), Route.candidates(pc, emptyList(), listOf("192.168.1.11"), thor))
        // stored LAN, a Tailscale address remembered: LAN, then Tailscale
        assertEquals(listOf("192.168.1.11", "100.69.36.86"),
            Route.candidates(pc.copy(address = "192.168.1.11", vpnAddress = "100.69.36.86"), emptyList(), emptyList(), thor))
        // a LAN address of another /24 (not this Wi-Fi) goes after Tailscale
        assertEquals(listOf("100.69.36.86", "10.0.0.5"), Route.candidates(pc.copy(lanAddress = "10.0.0.5"), emptyList(), emptyList(), thor))
    }

    @Test fun theFirstThatAnswersWinsAndIsRemembered() {
        val cands = Route.candidates(pc, listOf("192.168.1.11"), emptyList(), thor)
        val tried = ArrayList<String>()
        val ok = Route.pick(cands) { tried += it; it == "192.168.1.11" }
        assertEquals("192.168.1.11", ok)
        assertEquals(listOf("192.168.1.11"), tried)                    // Tailscale not even tried
        val after = Route.after(pc, ok!!, thor)
        assertEquals("192.168.1.11", after.address)
        assertEquals("192.168.1.11", after.lanAddress)
        // the pins never change with the address
        assertEquals(pc.bridgeFingerprint, after.bridgeFingerprint)
        assertEquals(pc.serverCertPem, after.serverCertPem)
        assertEquals(pc.bridgeToken, after.bridgeToken)
        // away from home: the LAN address times out, Tailscale answers and is remembered as the other route
        val away = Route.after(after, Route.pick(Route.candidates(after, emptyList(), emptyList(), "10.1.2")) { it.startsWith("100.") }!!, "10.1.2")
        assertEquals("100.69.36.86", away.address)
        assertEquals("192.168.1.11", away.lanAddress)
        assertEquals("100.69.36.86", away.vpnAddress)
        assertNull(Route.pick(listOf("192.168.1.11", "100.69.36.86")) { false })
    }

    @Test fun kindsAndTheFailureLine() {
        assertEquals(Route.Kind.HOME, Route.kind("192.168.1.11", thor))
        assertEquals(Route.Kind.TAILSCALE, Route.kind("100.69.36.86", thor))
        assertEquals(Route.Kind.HOME, Route.kind("192.168.1.11", ""))       // no Wi-Fi known: a private address counts as home
        assertEquals("home Wi-Fi", Route.Kind.HOME.label)
        assertTrue(Route.unreachable(listOf("100.69.36.86"), vpnUp = false).endsWith("(Tailscale is off)"))
        assertEquals("Can't reach your PC - is DS Forever Desktop running?", Route.unreachable(listOf("192.168.1.11", "100.69.36.86"), vpnUp = false))
        assertEquals("Can't reach your PC - is DS Forever Desktop running?", Route.unreachable(listOf("100.69.36.86"), vpnUp = true))
    }

    @Test fun desktopsReportedAddressAndTheStore() {
        val r = Route.reported(pc, listOf("172.20.0.1", "192.168.1.11"), thor)
        assertEquals("192.168.1.11", r.lanAddress)                     // the one on this Wi-Fi
        val back = PairedHost.fromJson(r.copy(vpnAddress = "100.69.36.86").toJson())!!
        assertEquals("192.168.1.11", back.lanAddress)
        assertEquals("100.69.36.86", back.vpnAddress)
        // an old store without the fields still reads
        val old = PairedHost.fromJson(pc.toJson().apply { remove("lan"); remove("vpn") })!!
        assertEquals("", old.lanAddress)
    }

    @Test fun theWelcomeCarriesTheLanList() {
        val m = BridgeProtocol.parse("""{"t":"welcome","host_id":"d1","pc_name":"PC","lan":["192.168.1.11"],"wow":{"running":false}}""")
        assertEquals(listOf("192.168.1.11"), (m as BridgeMsg.Welcome).lan)
        val older = BridgeProtocol.parse("""{"t":"welcome","host_id":"d1","pc_name":"PC","wow":{"running":false}}""")
        assertEquals(emptyList<String>(), (older as BridgeMsg.Welcome).lan)
    }

    /** "Most people will not be using tailscale": a PC with home-network addresses only pairs and connects, and nothing says Tailscale */
    @Test fun aPcWithoutTailscalePairsAndConnects() {
        val found = PairedHost(name = "PC", address = "192.168.1.11", serverCertPem = "PEM", bridgePort = 48210, bridgeTls = true,
            bridgeFingerprint = "AB", bridgeToken = "t")
        // the pairing (StreamTop): the found address becomes the home route
        val paired = Route.after(found.copy(address = ""), found.address, thor)
        assertEquals("192.168.1.11", paired.address); assertEquals("192.168.1.11", paired.lanAddress); assertEquals("", paired.vpnAddress)
        val cands = Route.candidates(paired, listOf("192.168.1.11"), listOf("192.168.1.11"), thor)
        assertEquals(listOf("192.168.1.11"), cands)
        assertEquals("192.168.1.11", Route.pick(cands) { true })
        assertEquals(Route.Kind.HOME, Route.kind("192.168.1.11", thor))
        // the PC off: the line never mentions Tailscale when no Tailscale route exists
        assertTrue(!Route.unreachable(cands, vpnUp = false).contains("Tailscale"))
        // DHCP moved the PC: mDNS's new LAN address first, the old one after
        assertEquals(listOf("192.168.1.40", "192.168.1.11"), Route.candidates(paired, listOf("192.168.1.40"), emptyList(), thor))
    }
}
