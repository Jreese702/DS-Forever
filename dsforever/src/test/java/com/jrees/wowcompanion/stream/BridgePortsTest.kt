package com.jrees.wowcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Issue #414 ("Zombie sunshine.exe after a failed session holds the ports ... the Thor keeps dialing the stored ports"): the paired
 * Desktop's bridge is followed to the port it is on now, by mDNS or by asking its /v1/info, and never to anyone else's port.
 */
class BridgePortsTest {
    private val pc = PairedHost(name = "DEN-PC", address = "192.168.1.11", serverCertPem = "PEM", bridgePort = 48210,
        bridgeToken = "t".repeat(43), desktopId = "d1")

    @Test fun theSameDesktopOnAnotherPortIsFollowedAndNothingElse() {
        val seen = FoundPc(name = "DEN-PC", address = "192.168.1.11", bridgePort = 48211, desktopId = "d1")
        val moved = BridgePorts.follow(pc, seen)
        assertEquals(48211, moved.bridgePort)
        // only the port: the token and the pins stay the pairing's
        assertEquals(pc.copy(bridgePort = 48211), moved)
        assertSame(pc, BridgePorts.follow(pc, seen.copy(bridgePort = 48210)))     // same port
        assertSame(pc, BridgePorts.follow(pc, seen.copy(desktopId = "other")))    // another Desktop (same name)
        assertSame(pc, BridgePorts.follow(pc, seen.copy(desktopId = "")))         // a plain Sunshine
        assertSame(pc, BridgePorts.follow(pc, seen.copy(bridgePort = 0)))
        val noId = pc.copy(desktopId = "")
        assertSame(noId, BridgePorts.follow(noId, seen))
    }

    @Test fun theOtherPortsAreDesktopsOwnRange() {
        assertEquals((48211..48219).toList(), BridgePorts.others(48210))
        assertEquals(listOf(48210, 48211, 48212, 48213) + (48215..48219).toList(), BridgePorts.others(48214))
        assertEquals(10, BridgePorts.others(5000).size)
    }

    @Test fun onlyAnInfoNamingThisDesktopCounts() {
        assertTrue(BridgePorts.sameDesktop("{\"t\":\"info\",\"host_id\":\"d1\",\"pc_name\":\"DEN-PC\"}", "d1"))
        assertFalse(BridgePorts.sameDesktop("{\"host_id\":\"d2\"}", "d1"))
        assertFalse(BridgePorts.sameDesktop("<html>a router page</html>", "d1"))
        assertFalse(BridgePorts.sameDesktop(null, "d1"))
        assertFalse(BridgePorts.sameDesktop("{\"host_id\":\"\"}", ""))
    }

    /** a one-shot HTTP answer on a free local port */
    private fun server(body: String, status: String = "200 OK"): ServerSocket {
        val ss = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            try {
                while (true) ss.accept().use { c ->
                    val head = StringBuilder()
                    val inp = c.getInputStream()
                    while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) break; head.append(b.toChar()) }
                    c.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                }
            } catch (_: Throwable) {}
        }
        return ss
    }

    @Test fun theProbeFindsTheBridgeThatNamesThisDesktop() {
        val other = server("{\"host_id\":\"someone-else\"}")
        val notFound = server("nope", "404 Not Found")
        val ours = server("{\"t\":\"info\",\"host_id\":\"d1\"}")
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { it.close() }.localPort
        try {
            val ports = listOf(closed, other.localPort, notFound.localPort, ours.localPort)
            assertEquals(ours.localPort, NetProbe.findBridge("127.0.0.1", ports, "d1", 1_000))
            assertNull(NetProbe.findBridge("127.0.0.1", ports.dropLast(1), "d1", 1_000))
            assertNull(NetProbe.findBridge("127.0.0.1", ports, "", 1_000))
        } finally { other.close(); notFound.close(); ours.close() }
    }
}
