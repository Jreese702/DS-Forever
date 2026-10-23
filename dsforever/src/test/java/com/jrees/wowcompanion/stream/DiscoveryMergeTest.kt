package com.jrees.wowcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** stream2 (2026-10-23): the PC answered on its Tailscale 100.x and its LAN 192.168.1.x; the Thor must take the home LAN. Off-device. */
class DiscoveryMergeTest {
    private val desktopTxt = mapOf("proto" to "1", "id" to "h1", "pc" to "DESKTOP-ERNE9KJ", "sunshine" to "52989", "ver" to "0.1")

    @Test fun onePcAtTwoAddressesIsOnePcOnTheLan() {
        val pcs = FoundPcs.merge(listOf(
            Advert("_dsforever._tcp", "DESKTOP-ERNE9KJ", "100.69.36.86", 48210, desktopTxt),
            Advert("_nvstream._tcp", "DESKTOP-ERNE9KJ", "192.168.1.11", 52989),
        ), preferSubnet = "192.168.1")
        val pc = pcs.single()
        assertEquals("192.168.1.11", pc.address)
        assertEquals(52989, pc.sunshinePort); assertEquals(48210, pc.bridgePort); assertEquals("h1", pc.desktopId)
    }

    @Test fun withoutTheLanTheBridgesAddressWins() {
        val pc = FoundPcs.merge(listOf(
            Advert("_nvstream._tcp", "DESKTOP-ERNE9KJ", "100.69.36.86", 52989),
            Advert("_dsforever._tcp", "DESKTOP-ERNE9KJ", "10.0.0.5", 48210, desktopTxt),
        ), preferSubnet = "192.168.1").single()
        assertEquals("10.0.0.5", pc.address)
        assertTrue(pc.hasBridge && pc.hasSunshine)
    }

    @Test fun pickAddressPrefersTheThorsSubnet() {
        assertEquals("192.168.1.11", FoundPcs.pickAddress(listOf("100.69.36.86", "192.168.1.11"), "192.168.1"))
        assertEquals("100.69.36.86", FoundPcs.pickAddress(listOf("100.69.36.86", "192.168.1.11"), ""))
        assertEquals(null, FoundPcs.pickAddress(emptyList(), "192.168.1"))
    }
}
